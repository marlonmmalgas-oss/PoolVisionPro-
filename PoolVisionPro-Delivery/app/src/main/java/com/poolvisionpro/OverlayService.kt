package com.poolvisionpro

import android.app.Service
import android.content.Intent
import android.graphics.*
import android.os.IBinder
import android.os.Build
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

class OverlayService:Service(){
 companion object{@Volatile private var data:FrameAnalysis?=null;@Volatile private var drawing:DrawView?=null;@Volatile private var sw=1;@Volatile private var sh=1;@Volatile private var visible=true;@Volatile private var scale=1f;fun publish(a:FrameAnalysis,w:Int,h:Int){data=a;sw=w;sh=h;drawing?.postInvalidate()}}
 private lateinit var wm:WindowManager;private var canvas:DrawView?=null;private var bar:LinearLayout?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{if(canvas!=null)return START_STICKY;wm=getSystemService(WINDOW_SERVICE) as WindowManager;val type=if(Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
  val v=DrawView();canvas=v;drawing=v;wm.addView(v,WindowManager.LayoutParams(-1,-1,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT))
  val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(6,4,6,4);setBackgroundColor(Color.argb(225,10,22,30))};fun item(s:String,f:()->Unit){controls.addView(TextView(this).apply{text=s;textSize=14f;setTextColor(Color.WHITE);setPadding(12,10,12,10);setOnClickListener{f()}})}
  item("PV"){visible=!visible;v.invalidate()};item("−"){scale=(scale-.15f).coerceAtLeast(.25f);v.invalidate()};item("+"){scale=(scale+.15f).coerceAtMost(2.5f);v.invalidate()};item("×"){stopSelf()};bar=controls
  val lp=WindowManager.LayoutParams(-2,-2,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=12;y=100};wm.addView(controls,lp)
  controls.setOnTouchListener(object:View.OnTouchListener{var x=0f;var y=0f;var ox=0;var oy=0;override fun onTouch(v:View,e:MotionEvent):Boolean{when(e.action){MotionEvent.ACTION_DOWN->{x=e.rawX;y=e.rawY;ox=lp.x;oy=lp.y;return false};MotionEvent.ACTION_MOVE->{lp.x=ox+(e.rawX-x).toInt();lp.y=oy+(e.rawY-y).toInt();wm.updateViewLayout(controls,lp);return true}};return false}});return START_STICKY
 }
 override fun onDestroy(){canvas?.let{wm.removeView(it)};bar?.let{wm.removeView(it)};canvas=null;drawing=null;bar=null;super.onDestroy()};override fun onBind(intent:Intent?)=null
 private inner class DrawView:View(this@OverlayService){val p=Paint(3);override fun onDraw(c:Canvas){super.onDraw(c);if(!visible)return;val a=data?:return;val sx=width.toFloat()/sw.coerceAtLeast(1);val sy=height.toFloat()/sh.coerceAtLeast(1);a.table?.let{p.style=Paint.Style.STROKE;p.color=Color.GREEN;p.strokeWidth=3f;c.drawRect(it.left*sx,it.top*sy,it.right*sx,it.bottom*sy,p)};a.balls.forEachIndexed{i,b->p.style=Paint.Style.STROKE;p.color=Color.WHITE;p.strokeWidth=2f;c.drawCircle(b.x*sx,b.y*sy,b.radius*min(sx,sy),p);p.style=Paint.Style.FILL;p.textSize=18f;c.drawText((i+1).toString(),b.x*sx+5,b.y*sy-5,p)};a.lines.forEach{l->p.style=Paint.Style.STROKE;p.strokeWidth=if(l.kind==0)4f else 2.5f;p.color=when(l.kind){0->Color.YELLOW;1->Color.CYAN;else->Color.MAGENTA};c.drawLine(l.x1*sx,l.y1*sy,(l.x1+(l.x2-l.x1)*scale)*sx,(l.y1+(l.y2-l.y1)*scale)*sy,p)};p.style=Paint.Style.FILL;p.color=Color.WHITE;p.textSize=21f;c.drawText(a.status,22f,34f,p)}}
}
