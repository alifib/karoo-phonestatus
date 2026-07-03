package io.alifib.karoophonestatus

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches whatever "status row" is at the very top of the screen and publishes,
 * via [StatusBarState], whether it is showing and the left edge of its left-most
 * right-hand icon (e.g. wifi, or the battery once wifi drops during a ride).
 *
 * It is deliberately window-agnostic: off-ride the row is the systemui status
 * bar; during a ride Karoo hides that bar and the ride app draws its own top row
 * (battery + clock, no wifi); on the ride-upload/delete screens there is no top
 * row at all. Mirroring "the top row, whatever draws it" matches all of these.
 */
@SuppressLint("AccessibilityPolicy")
class StatusBarMonitorService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    // Nodes count as part of the top status row if they hug the top edge and are
    // short (icon/'99%'/clock sized), not the ride's data fields lower down.
    private val topMaxPx get() = (16 * resources.displayMetrics.density)
    private val rowMaxHeightPx get() = (60 * resources.displayMetrics.density)

    private val rescan = object : Runnable {
        override fun run() {
            scan()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("PhoneStatus", "status-bar monitor connected")
        handler.post(rescan)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = scan()

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(rescan)
        StatusBarState.update(shown = false, anchorLeft = 0, barTop = 0, barHeight = 0, pkg = null)
        super.onDestroy()
    }

    private fun scan() {
        val wins = try {
            windows
        } catch (e: Exception) {
            return
        } ?: return

        var screenRight = resources.displayMetrics.widthPixels
        for (w in wins) {
            val b = Rect().also { w.getBoundsInScreen(it) }
            if (b.right > screenRight) screenRight = b.right
        }
        // The right-hand status cluster is separated from the left side (ride
        // timer / notification icons) by a wide gap, so anything past the first
        // third of the width belongs to the right cluster. (A /2 threshold was
        // too tight: the wifi "no internet" variant sits a little further left.)
        val rightClusterMinX = screenRight / 3

        var anchorLeft = Int.MAX_VALUE
        var rowTop = 0
        var rowBottom = 0
        var anchorPackage: String? = null
        for (w in wins) {
            val root = w.root ?: continue
            // Never anchor to our own overlay (defensive; it's also hidden from
            // accessibility) — that would race the real icon detection.
            if (root.packageName == packageName) continue
            collectLeaves(root) { node, b ->
                val labelled = !node.contentDescription.isNullOrEmpty() || !node.text.isNullOrEmpty()
                val topRow = b.top in 0..topMaxPx.toInt() && b.height() in 1..rowMaxHeightPx.toInt()
                if (labelled && node.isVisibleToUser && topRow && b.left >= rightClusterMinX && b.width() > 0) {
                    if (b.left < anchorLeft) {
                        anchorLeft = b.left
                        rowTop = b.top
                        rowBottom = b.bottom
                        anchorPackage = node.packageName?.toString()
                    }
                }
            }
        }

        if (anchorLeft == Int.MAX_VALUE) {
            StatusBarState.update(shown = false, anchorLeft = 0, barTop = 0, barHeight = 0, pkg = null)
        } else {
            // Report the anchor icon's own vertical box (top + height) so the
            // overlay can center itself on the anchor's midline. Anchoring to
            // [0, bottom] instead made the icon ride too high whenever the
            // left-most node was short and sat lower than the bar top — e.g. a
            // 3-digit "100%" battery label becoming the left-most node.
            val rowHeight = rowBottom - rowTop
            StatusBarState.update(
                shown = true,
                anchorLeft = anchorLeft,
                barTop = rowTop,
                barHeight = if (rowHeight > 0) rowHeight else (26 * resources.displayMetrics.density).toInt(),
                pkg = anchorPackage,
            )
        }
    }

    private fun collectLeaves(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo, Rect) -> Unit) {
        if (node.childCount == 0) {
            val b = Rect().also { node.getBoundsInScreen(it) }
            visit(node, b)
            return
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectLeaves(it, visit) }
        }
    }
}
