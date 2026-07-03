package io.alifib.karoophonestatus

import android.content.Intent
import android.provider.Settings
import io.hammerhead.karooext.extension.KarooExtension

/**
 * Registers this app as a Karoo extension. The Karoo system binds every
 * registered extension service shortly after boot, so this doubles as the
 * auto-start hook for the overlay: once the user has granted the overlay
 * permission (via MainActivity), the indicator comes back on its own after
 * every reboot — same pattern as karoo-powerbar.
 */
class PhoneStatusExtension : KarooExtension("karoo-phonestatus", BuildConfig.VERSION_NAME) {

    override fun onCreate() {
        super.onCreate()
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, PhoneStatusOverlayService::class.java))
        }
    }
}
