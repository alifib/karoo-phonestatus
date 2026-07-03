package io.alifib.karoophonestatus

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri

/**
 * Minimal launcher/setup screen. Needed once after install to grant two
 * permissions, then it starts the overlay service (which the Karoo system also
 * auto-starts on boot via [PhoneStatusExtension]):
 *
 *  1. "Draw over other apps" — to place the indicator in the status bar.
 *  2. Accessibility ("Phone Status") — so the indicator can watch the real
 *     status bar and mirror it (position next to the other icons, and hide when
 *     the status bar is hidden). It only observes the status bar; no actions.
 *
 * No Bluetooth permission is needed: the phone connection state comes from the
 * system phoneservice (see [PhoneServiceClient]), not the Android BT stack.
 */
class MainActivity : ComponentActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onReturn() }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onReturn() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderStatus()
        // Delay permission request slightly to ensure activity is visible
        window.decorView.post {
            requestNextMissingPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun onReturn() {
        if (canDrawOverlays()) startOverlayService()
        renderStatus()
        requestNextMissingPermission()
    }

    private fun requestNextMissingPermission() {
        when {
            !canDrawOverlays() -> {
                AlertDialog.Builder(this)
                    .setTitle("Overlay Permission Required")
                    .setMessage("This app needs permission to display over other apps so the phone status indicator can appear in the status bar.")
                    .setPositiveButton("OK") { _, _ ->
                        overlayPermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:$packageName".toUri(),
                            ),
                        )
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        finish()
                    }
                    .setCancelable(false)
                    .show()
            }
            !isAccessibilityEnabled() -> {
                AlertDialog.Builder(this)
                    .setTitle("Accessibility Permission Required")
                    .setMessage("One more step: enable the \"Phone Status\" accessibility service.\n\nIt lets the indicator sit next to the other status icons and hide when the status bar hides. It only reads the status bar — it performs no actions.")
                    .setPositiveButton("OK") { _, _ ->
                        accessibilityLauncher.launch(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                        )
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        finish()
                    }
                    .setCancelable(false)
                    .show()
            }
            else -> startOverlayService()
        }
    }

    private fun canDrawOverlays() = Settings.canDrawOverlays(this)

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/$packageName.StatusBarMonitorService"
        val am = getSystemService(AccessibilityManager::class.java)
        val enabled = am?.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        )?.any { it.id == expected } == true
        if (enabled) return true
        // Fallback to the raw setting (some ROMs report the list late).
        val setting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(setting) }
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }

    private fun startOverlayService() {
        startForegroundService(Intent(this, PhoneStatusOverlayService::class.java))
    }

    private fun renderStatus() {
        val text = when {
            !canDrawOverlays() ->
                "Overlay permission needed.\n\nAllow \"Display over other apps\" " +
                    "so the indicator can appear in the status bar."
            !isAccessibilityEnabled() ->
                "One more step: enable the \"Phone Status\" accessibility service.\n\n" +
                    "It lets the indicator sit next to the other status icons and " +
                    "hide when the status bar hides. It only reads the status bar — " +
                    "it performs no actions."
            else ->
                "Phone Status is set up.\n\nThe indicator appears in the status bar, " +
                    "next to the other icons, whenever your phone is connected and " +
                    "the status bar is showing.\n\nYou can close this screen; it " +
                    "restarts automatically after a reboot."
        }
        setContentView(
            TextView(this).apply {
                setPadding(48, 48, 48, 48)
                textSize = 16f
                this.text = text
            },
        )
    }
}
