package com.poolvisionpro

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val captureRequest = 5127
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(Color.rgb(10, 17, 25))
        }
        root.addView(TextView(this).apply {
            text = "POOL VISION PRO"
            textSize = 28f
            setTextColor(Color.rgb(37, 225, 163))
        })
        root.addView(TextView(this).apply {
            text = "TABLE CALIBRATION  •  BALL TRACKING  •  AIM GUIDES"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(26))
        })

        fun action(label: String, callback: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { callback() }
            }, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(12) })
        }

        action("ENABLE FLOATING OVERLAY") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else startService(Intent(this, OverlayService::class.java))
        }
        action("START LIVE TABLE SCAN") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        }
        action("STOP SCAN AND OVERLAY") {
            stopService(Intent(this, CaptureService::class.java))
            stopService(Intent(this, OverlayService::class.java))
        }
        root.addView(TextView(this).apply {
            text = "Grant overlay permission, start live scan and approve screen capture. Switch to 8 Ball Pool with the complete table visible. The PV toolbar can be dragged; PV toggles guides, +/- changes guide length and × closes the overlay."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(18), 0, 0)
        })
        setContentView(root)
    }

    @Deprecated("Legacy activity result API is retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequest || resultCode != RESULT_OK || data == null) return
        startService(Intent(this, OverlayService::class.java))
        val service = Intent(this, CaptureService::class.java)
            .putExtra("resultCode", resultCode)
            .putExtra("projectionData", data)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
    }
}
