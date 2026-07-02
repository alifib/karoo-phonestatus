package com.example.karoophonestatus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.SavedDevices

/**
 * Draws a small phone-connection indicator as a system overlay, positioned near
 * the top of the screen (next to the Karoo clock), and keeps it updated by
 * listening to the Karoo system.
 *
 * PROTOTYPE NOTES
 * ---------------
 * karoo-ext exposes phone/sensor connection state in two related ways:
 *   - SavedDevices (a KarooEvent): the list of paired devices and whether each
 *     is enabled. Reliable to consume via addConsumer<SavedDevices>.
 *   - OnConnectionStatus (a DeviceEvent, enum CONNECTED / SEARCHING /
 *     DISCONNECTED / DISABLED): the *live* per-device connection state.
 *
 * The exact consumer signature for per-device DeviceEvents is not fully
 * documented, so this prototype:
 *   1. Connects to KarooSystemService.
 *   2. Logs every saved device (tag "PhoneStatus") so you can read the phone's
 *      real id / connectionType / name on YOUR device — fill in PHONE_MATCH
 *      below once you see them in logcat.
 *   3. Drives the overlay from whatever signal is available.
 *
 * Next step (see README "Wiring live status"): subscribe to the phone device's
 * OnConnectionStatus stream and call updateOverlay() from there. The
 * karoo-powerbar and Ki2 repos are good references for the device consumer API.
 */
class PhoneStatusOverlayService : Service() {

    companion object {
        private const val TAG = "PhoneStatus"
        private const val CHANNEL_ID = "phone_status_overlay"
        private const val NOTIFICATION_ID = 1

        // Substrings to identify the phone / companion connection in the saved
        // device list. Adjust after inspecting logcat on your Karoo.
        private val PHONE_MATCH = listOf("phone", "companion", "bluetooth")
    }

    private lateinit var karooSystem: KarooSystemService
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: TextView

    private var savedDevicesConsumerId: String? = null

    // Overlay state model, independent of the exact SDK signal source.
    enum class PhoneState { CONNECTED, SEARCHING, DISCONNECTED, UNKNOWN }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlayView()
        updateOverlay(PhoneState.UNKNOWN)

        karooSystem = KarooSystemService(this)
        // NOTE: depending on karoo-ext version the connect lambda may receive a
        // Boolean (connected) or take no argument. If this line fails to compile,
        // change it to `karooSystem.connect { startListening() }`.
        karooSystem.connect { connected ->
            Log.d(TAG, "Karoo system connected = $connected")
            if (connected) startListening()
        }
    }

    private fun startListening() {
        savedDevicesConsumerId = karooSystem.addConsumer { devices: SavedDevices ->
            // Log everything once so you can discover the phone's identifiers.
            devices.devices.forEach { d ->
                Log.d(
                    TAG,
                    "device id=${d.id} type=${d.connectionType} " +
                        "name=${d.name} enabled=${d.enabled}"
                )
            }

            val phone = devices.devices.firstOrNull { d ->
                val hay = "${d.connectionType} ${d.name}".lowercase()
                PHONE_MATCH.any { hay.contains(it) }
            }

            val state = when {
                phone == null -> PhoneState.DISCONNECTED
                phone.enabled -> PhoneState.CONNECTED
                else -> PhoneState.SEARCHING
            }
            updateOverlay(state)
        }
    }

    private fun addOverlayView() {
        overlayView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 0f, Color.BLACK) // legibility over any background
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Top-right, near the clock. Tune x/y on the actual Karoo screen.
            gravity = Gravity.TOP or Gravity.END
            x = 90
            y = 6
        }

        windowManager.addView(overlayView, params)
    }

    private fun updateOverlay(state: PhoneState) {
        val (label, color) = when (state) {
            PhoneState.CONNECTED    -> "\uD83D\uDCF1" to Color.GREEN   // 📱
            PhoneState.SEARCHING    -> "\uD83D\uDD0D" to Color.YELLOW  // 🔍
            PhoneState.DISCONNECTED -> "\uD83D\uDCF5" to Color.RED     // 📵
            PhoneState.UNKNOWN      -> "\u2026" to Color.GRAY          // …
        }
        overlayView.post {
            overlayView.text = label
            overlayView.setTextColor(color)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }

    override fun onDestroy() {
        savedDevicesConsumerId?.let { karooSystem.removeConsumer(it) }
        if (::karooSystem.isInitialized) karooSystem.disconnect()
        if (::overlayView.isInitialized && ::windowManager.isInitialized) {
            runCatching { windowManager.removeView(overlayView) }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
