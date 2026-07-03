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

    // The settings screens return by simply resuming this activity, so the
    // re-check lives entirely in onResume(); these launchers just need to bring
    // us back.
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    // The permission prompt currently on screen, and which permission it targets,
    // so onResume() can avoid re-stacking an identical dialog.
    private var permissionDialog: AlertDialog? = null
    private var dialogKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        // onResume is the single re-check point: it runs on launch, when the app
        // is brought back to the foreground, and when returning from the settings
        // screens. That means a permission revoked while we were backgrounded
        // (which does not always kill the process) is caught and re-prompted on
        // the next launch, instead of only updating the status text.
        renderStatus()
        requestNextMissingPermission()
    }

    override fun onDestroy() {
        dismissPermissionDialog()
        super.onDestroy()
    }

    private fun requestNextMissingPermission() {
        when {
            !canDrawOverlays() -> showPermissionDialog(
                key = "overlay",
                title = "Overlay Permission Required",
                message = "This app needs permission to display over other apps so the phone status indicator can appear in the status bar.",
            ) {
                overlayPermissionLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:$packageName".toUri(),
                    ),
                )
            }
            !isAccessibilityEnabled() -> showPermissionDialog(
                key = "accessibility",
                title = "Accessibility Permission Required",
                message = "One more step: enable the \"Phone Status\" accessibility service.\n\nIt lets the indicator sit next to the other status icons and hide when the status bar hides. It only reads the status bar — it performs no actions.",
            ) {
                accessibilityLauncher.launch(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                )
            }
            else -> {
                dismissPermissionDialog()
                startOverlayService()
            }
        }
    }

    private fun showPermissionDialog(
        key: String,
        title: String,
        message: String,
        onOk: () -> Unit,
    ) {
        // Already prompting for this exact permission — don't rebuild it on every
        // resume (that would reset/flicker the dialog).
        if (permissionDialog?.isShowing == true && dialogKey == key) return
        dismissPermissionDialog()
        dialogKey = key
        permissionDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> onOk() }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun dismissPermissionDialog() {
        permissionDialog?.dismiss()
        permissionDialog = null
        dialogKey = null
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
