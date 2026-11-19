package com.poolvisionpro

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*

class CaptureService:Service(){
 private var projection:MediaProjection?=null;private var reader:ImageReader?=null;private var display:VirtualDisplay?=null;private var worker:HandlerThread?=null;@Volatile private var last=0L
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("pv","Pool Vision Pro",NotificationManager.IMPORTANCE_LOW))
  startForeground(81,Notification.Builder(this,"pv").setContentTitle("Pool Vision Pro").setContentText("Live table analysis").setSmallIcon(android.R.drawable.ic_menu_search).build())
  val code=intent?.getIntExtra("resultCode",Activity.RESULT_CANCELED)?:Activity.RESULT_CANCELED
  @Suppress("DEPRECATION") val data=intent?.getParcelableExtra<Intent>("projectionData")
  if(data==null){stopSelf();return START_NOT_STICKY}
  projection=(getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code,data)
  if(Build.VERSION.SDK_INT>=34)projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stopSelf()}},Handler(mainLooper))
  val m=resources.displayMetrics;val w=m.widthPixels;val h=m.heightPixels;worker=HandlerThread("PV-frame").also{it.start()};reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
  reader!!.setOnImageAvailableListener({r->val now=System.currentTimeMillis();if(now-last<160){r.acquireLatestImage()?.close();return@setOnImageAvailableListener};last=now;val im=r.acquireLatestImage()?:return@setOnImageAvailableListener
   try{val p=im.planes[0];val pad=p.rowStride-p.pixelStride*im.width;val wide=Bitmap.createBitmap(im.width+pad/p.pixelStride,im.height,Bitmap.Config.ARGB_8888);wide.copyPixelsFromBuffer(p.buffer);val bmp=Bitmap.createBitmap(wide,0,0,im.width,im.height);wide.recycle();OverlayService.publish(VisionEngine.analyse(bmp),w,h);bmp.recycle()}catch(_:Throwable){ }finally{im.close()}
  },Handler(worker!!.looper))
  display=projection?.createVirtualDisplay("PV",w,h,m.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,Handler(worker!!.looper));return START_NOT_STICKY
 }
 override fun onDestroy(){display?.release();reader?.close();projection?.stop();worker?.quitSafely();super.onDestroy()}
 override fun onBind(intent:Intent?)=null
}
