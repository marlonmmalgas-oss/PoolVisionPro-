package com.poolvisionpro

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val requestCode = 5127
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(24),dp(28),dp(24),dp(24)); setBackgroundColor(Color.rgb(13,19,26)) }
        root.addView(TextView(this).apply { text="POOL VISION PRO"; textSize=27f; setTextColor(Color.rgb(38,220,163)) })
        root.addView(TextView(this).apply { text="LIVE BALL DETECTION • AIMING • CUSHION PATHS"; textSize=12f; setTextColor(Color.LTGRAY); setPadding(0,dp(8),0,dp(24)) })
        fun add(label:String, action:()->Unit) { root.addView(Button(this).apply { text=label; setOnClickListener { action() } }, LinearLayout.LayoutParams(-1,dp(54)).apply { bottomMargin=dp(12) }) }
        add("1 • ENABLE FLOATING OVERLAY") { if(!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) else startService(Intent(this,OverlayService::class.java)) }
        add("2 • START LIVE TABLE SCAN") { if(!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) else { val m=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager; startActivityForResult(m.createScreenCaptureIntent(),requestCode) } }
        add("STOP SCAN / OVERLAY") { stopService(Intent(this,CaptureService::class.java)); stopService(Intent(this,OverlayService::class.java)) }
        root.addView(TextView(this).apply { text="Approve screen capture, switch to 8 Ball Pool and keep the full table visible. Drag the PV toolbar; PV toggles drawings, +/- changes guide length, × closes the overlay."; textSize=13f; setTextColor(Color.LTGRAY); setPadding(0,dp(18),0,0) })
        setContentView(root)
    }
    @Deprecated("Legacy result callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==this.requestCode && resultCode==RESULT_OK && data!=null) {
            startService(Intent(this,OverlayService::class.java))
            val i=Intent(this,CaptureService::class.java).putExtra("resultCode",resultCode).putExtra("projectionData",data)
            if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i)
        }
    }
}
