package com.example.karoophonestatus

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Minimal launcher screen.
 *
 * On first launch it requests the "draw over other apps" permission (needed to
 * place the overlay near the clock). Once granted, it starts the foreground
 * overlay service. There is intentionally almost no UI yet — this is a prototype.
 */
class MainActivity : ComponentActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
        renderStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        } else {
            requestOverlayPermission()
        }
        renderStatus()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun startOverlayService() {
        val serviceIntent = Intent(this, PhoneStatusOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun renderStatus() {
        val granted = Settings.canDrawOverlays(this)
        val text = if (granted) {
            "Phone Status overlay is running.\n\nYou can close this screen — the " +
                "indicator will appear on the ride screen."
        } else {
            "Overlay permission was not granted.\n\nRe-open this app and allow " +
                "\"Display over other apps\" to enable the indicator."
        }
        setContentView(TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 16f
            this.text = text
        })
    }
}
