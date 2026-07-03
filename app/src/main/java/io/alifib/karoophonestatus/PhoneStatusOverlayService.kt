package io.alifib.karoophonestatus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.RideState
import kotlin.math.roundToInt

/**
 * Draws the phone-connection indicator so it looks and behaves like a native
 * status-bar icon:
 *
 *  - It mirrors the real status bar (via [StatusBarMonitorService]): it is only
 *    visible while the status bar's icon cluster is on screen, and it sits pinned
 *    just to the left of the left-most icon in that cluster. If the wifi icon
 *    disappears (as during a ride) our icon slides right to follow.
 *  - It shows the connection state: white/black when connected (depending on
 *    screen), and amber when disconnected.
 */
class PhoneStatusOverlayService : Service() {

    companion object {
        private const val TAG = "PhoneStatus"
        private const val CHANNEL_ID = "phone_status_overlay"
        private const val NOTIFICATION_ID = 1

        // Gap (dp) between our icon's right edge and the reported left edge of
        // the left-most native icon. The reported edge includes the native
        // icon's own (variable) internal padding, so this is a compromise that
        // keeps a little air on both the wide-padded (wifi) and zero-padded
        // (battery %) anchors.
        private const val GAP_DP = 2f

        // Re-apply placement on this interval so it self-heals if the native
        // icon layout shifts (e.g. wifi appearing/disappearing) between events.
        private const val REPOSITION_MS = 500L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ImageView
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val density get() = resources.displayMetrics.density

    private var phoneServiceClient: PhoneServiceClient? = null
    private var karooSystem: KarooSystemService? = null
    private var rideConsumerId: String? = null

    private var connected = false
    private var inRide = false

    override fun onCreate() {
        super.onCreate()
        this.startForeground(NOTIFICATION_ID, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addOverlayView()

        StatusBarState.setListener { handler.post { render() } }
        handler.post(repositionTick)

        phoneServiceClient = PhoneServiceClient(this) { radioStatus ->
            connected = radioStatus == PhoneServiceClient.RadioStatus.CONNECTED ||
                radioStatus == PhoneServiceClient.RadioStatus.BONDED
            render()
        }.also { it.start() }

        karooSystem = KarooSystemService(this).also { system ->
            system.connect { isConnected ->
                Log.i(TAG, "karoo system connected=$isConnected")
            }
            rideConsumerId = system.addConsumer<RideState> { state ->
                inRide = state is RideState.Recording || state is RideState.Paused
                render()
            }
        }
    }

    // Periodically re-apply placement so wifi appearing/disappearing (which
    // shifts the left-most native icon) is always tracked, even if we missed the
    // triggering event. Cheap: it only touches the window when something moved.
    private val repositionTick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REPOSITION_MS)
        }
    }

    private var lastLog: String? = null

    private fun render() {
        val visible = StatusBarState.barShown
        // If we are recording/paused (Karoo ride state), or if the status bar we
        // are anchored to belongs to the ride app (which uses black icons even
        // when idle/pre-ride), use black. The system status bar is
        // "com.android.systemui" (white icons); the ride app and its variants
        // (io.hammerhead.ride) use black icons.
        val inRideApp = StatusBarState.packageName != null &&
            StatusBarState.packageName != "com.android.systemui"

        val color = if (!connected) {
            Color.parseColor("#FFBF00") // Amber
        } else if (inRide || inRideApp) {
            Color.BLACK
        } else {
            Color.WHITE
        }

        val logLine = "visible=$visible connected=$connected barShown=${StatusBarState.barShown} " +
            "anchorLeft=${StatusBarState.anchorLeftPx} inRide=$inRide inRideApp=$inRideApp pkg=${StatusBarState.packageName} color=$color"
        if (logLine != lastLog) {
            lastLog = logLine
            Log.i(TAG, "render $logLine")
        }

        overlayView.setColorFilter(color)
        if (!visible) {
            if (overlayView.visibility != View.GONE) overlayView.visibility = View.GONE
            return
        }
        if (overlayView.visibility != View.VISIBLE) overlayView.visibility = View.VISIBLE
        applyPosition()
    }

    private fun applyPosition() {
        val gapPx = (GAP_DP * density).roundToInt()
        val iconW = if (overlayView.width > 0) overlayView.width else params.height
        val newX = StatusBarState.anchorLeftPx - gapPx - iconW
        val newY = StatusBarState.barTopPx +
            ((StatusBarState.barHeightPx - params.height) / 2).coerceAtLeast(0)
        if (newX != params.x || newY != params.y) {
            params.x = newX
            params.y = newY
            runCatching { windowManager.updateViewLayout(overlayView, params) }
        }
    }

    private fun addOverlayView() {
        // Match the height of the native status icons (~26px in the 45px bar on
        // Karoo 3). Width tracks the glyph via the tight-viewport drawable.
        val iconHeightPx = (14 * density).roundToInt()

        overlayView = ImageView(this).apply {
            setImageResource(R.drawable.ic_phone)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            // Keep our own overlay out of the accessibility tree, so the status
            // bar monitor can never mistake it for a native icon (which would
            // race the anchor detection).
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            iconHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(overlayView, params)
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(R.drawable.ic_phone)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(repositionTick)
        StatusBarState.setListener(null)
        phoneServiceClient?.stop()
        phoneServiceClient = null
        karooSystem?.let { system ->
            rideConsumerId?.let { system.removeConsumer(it) }
            system.disconnect()
        }
        karooSystem = null
        if (::overlayView.isInitialized && ::windowManager.isInitialized) {
            runCatching { windowManager.removeView(overlayView) }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
