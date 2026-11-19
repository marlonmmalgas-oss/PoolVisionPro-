package com.poolvisionpro

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

class OverlayService : Service() {
    companion object {
        @Volatile private var latest: FrameAnalysis? = null
        @Volatile private var drawing: DrawView? = null
        @Volatile private var sourceWidth = 1
        @Volatile private var sourceHeight = 1
        @Volatile private var enabled = true
        @Volatile private var guideScale = 1f

        fun publish(analysis: FrameAnalysis, width: Int, height: Int) {
            latest = analysis
            sourceWidth = width.coerceAtLeast(1)
            sourceHeight = height.coerceAtLeast(1)
            drawing?.postInvalidate()
        }
    }

    private lateinit var windowManager: WindowManager
    private var drawingView: DrawView? = null
    private var toolbar: LinearLayout? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (drawingView != null) return START_STICKY
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val windowType = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val drawing = DrawView()
        drawingView = drawing
        OverlayService.drawing = drawing
        val drawingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(drawing, drawingParams)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6, 3, 6, 3)
            setBackgroundColor(Color.argb(225, 8, 18, 28))
        }
        fun control(label: String, action: () -> Unit) {
            bar.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(12, 9, 12, 9)
                setOnClickListener { action() }
            })
        }
        control("PV") { enabled = !enabled; drawing.invalidate() }
        control("−") { guideScale = (guideScale - 0.15f).coerceAtLeast(0.25f); drawing.invalidate() }
        control("+") { guideScale = (guideScale + 0.15f).coerceAtMost(2.5f); drawing.invalidate() }
        control("×") { stopSelf() }
        toolbar = bar
        val barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 12; y = 100 }
        windowManager.addView(bar, barParams)
        bar.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f; private var downY = 0f; private var originX = 0; private var originY = 0
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = barParams.x; originY = barParams.y; return false }
                    MotionEvent.ACTION_MOVE -> {
                        barParams.x = originX + (event.rawX - downX).toInt()
                        barParams.y = originY + (event.rawY - downY).toInt()
                        windowManager.updateViewLayout(bar, barParams)
                        return true
                    }
                }
                return false
            }
        })
        return START_STICKY
    }

    override fun onDestroy() {
        drawingView?.let { windowManager.removeView(it) }
        toolbar?.let { windowManager.removeView(it) }
        drawingView = null; toolbar = null; drawing = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private inner class DrawView : View(this@OverlayService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!enabled) return
            val analysis = latest ?: return
            val sx = width.toFloat() / sourceWidth.coerceAtLeast(1)
            val sy = height.toFloat() / sourceHeight.coerceAtLeast(1)
            val radiusScale = min(sx, sy)

            analysis.table?.let { table ->
                paint.style = Paint.Style.STROKE; paint.color = Color.rgb(35, 245, 110); paint.strokeWidth = 3f
                canvas.drawRect(table.left * sx, table.top * sy, table.right * sx, table.bottom * sy, paint)
            }
            analysis.balls.forEachIndexed { index, ball ->
                paint.style = Paint.Style.STROKE
                paint.color = if (ball === analysis.cueBall) Color.rgb(255, 235, 40) else Color.WHITE
                paint.strokeWidth = if (ball === analysis.cueBall) 3.5f else 1.8f
                canvas.drawCircle(ball.x * sx, ball.y * sy, ball.radius * radiusScale, paint)
                paint.style = Paint.Style.FILL
                paint.textSize = 15f * radiusScale.coerceAtLeast(0.7f)
                canvas.drawText((index + 1).toString(), ball.x * sx + ball.radius * radiusScale, ball.y * sy - ball.radius * radiusScale, paint)
            }
            analysis.lines.forEach { line ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = when (line.kind) { 0 -> 4.2f; 1 -> 3.0f; 2 -> 2.5f; else -> 1.8f }
                paint.color = when (line.kind) {
                    0 -> Color.WHITE
                    1 -> Color.rgb(255, 220, 35)
                    2 -> Color.rgb(50, 245, 255)
                    else -> Color.MAGENTA
                }
                val x2 = line.x1 + (line.x2 - line.x1) * guideScale
                val y2 = line.y1 + (line.y2 - line.y1) * guideScale
                canvas.drawLine(line.x1 * sx, line.y1 * sy, x2 * sx, y2 * sy, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 18f
            canvas.drawText(analysis.status, 18f, 28f, paint)
        }
    }
}
