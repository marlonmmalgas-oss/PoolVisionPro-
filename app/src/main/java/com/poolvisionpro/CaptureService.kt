package com.poolvisionpro

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import java.nio.ByteBuffer

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: HandlerThread? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY
        val code = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("projectionData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra("projectionData")
        }
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code, data)
        if (projection == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val channelId = "pool_vision_live"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Pool Vision Pro", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
                .setContentTitle("Pool Vision Pro")
                .setContentText("Live table and ball analysis")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .build()
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
                .setContentTitle("Pool Vision Pro")
                .setContentText("Live table and ball analysis")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(81, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(81, notification)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        worker = HandlerThread("PoolVision-Frames").also { it.start() }
        val handler = Handler(worker!!.looper)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader!!.setOnImageAvailableListener({ imageReader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < 85L) {
                imageReader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            lastFrameAt = now
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val paddedWidth = rowStride / pixelStride
                val buffer = plane.buffer.duplicate()
                val requiredBytes = paddedWidth * image.height * 4
                val bytes = ByteArray(requiredBytes)
                val count = minOf(buffer.remaining(), requiredBytes)
                buffer.get(bytes, 0, count)

                // ImageReader is RGBA; Bitmap's native ARGB_8888 byte order on Android is BGRA.
                var offset = 0
                while (offset + 3 < count) {
                    val red = bytes[offset]
                    bytes[offset] = bytes[offset + 2]
                    bytes[offset + 2] = red
                    offset += 4
                }
                val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                val frame = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                padded.recycle()
                try {
                    OverlayService.publish(VisionEngine.analyse(frame), width, height)
                } finally {
                    frame.recycle()
                }
            } catch (_: Throwable) {
                // Drop an invalid frame; the next ImageReader frame will be analysed.
            } finally {
                image.close()
            }
        }, handler)

        if (Build.VERSION.SDK_INT >= 34) {
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
        }
        display = projection!!.createVirtualDisplay(
            "PoolVisionPro-Capture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
        started = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        display?.release(); display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close(); reader = null
        projection?.stop(); projection = null
        worker?.quitSafely(); worker = null
        started = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
