package com.poolvisionpro

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Coordinates in the source screenshot's pixel space. */
data class Ball(val x: Float, val y: Float, val radius: Float, val color: Int)
data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val kind: Int)
data class FrameAnalysis(val table: RectF?, val balls: List<Ball>, val lines: List<Line>, val status: String)

/**
 * OpenCV-based first-stage vision pipeline.
 * It deliberately suppresses all overlays when table confidence is poor instead of
 * drawing plausible-looking but incorrect rectangles and aim rays.
 */
object VisionEngine {
    @Volatile private var initialized = false
    @Synchronized private fun ensureOpenCv(): Boolean {
        if (initialized) return true
        return try {
            initialized = org.opencv.android.OpenCVLoader.initLocal()
            initialized
        } catch (_: Throwable) { false }
    }

    fun analyse(bitmap: Bitmap): FrameAnalysis {
        if (!ensureOpenCv()) return FrameAnalysis(null, emptyList(), emptyList(), "OPENCV NOT AVAILABLE")
        val src = Mat()
        val small = Mat()
        val hsv = Mat()
        val mask = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        val contours = ArrayList<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, src)
            // Work at a fixed maximum width to reduce per-frame CPU cost.
            val scale = min(1.0, 900.0 / src.cols().toDouble())
            Imgproc.resize(src, small, Size(), scale, scale, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)

            // 8 Ball Pool's blue/cyan felt. OpenCV hue is 0..179.
            // Broad enough for lighting variation; contour geometry rejects HUD panels.
            Core.inRange(hsv, Scalar(78.0, 48.0, 48.0), Scalar(112.0, 255.0, 255.0), mask)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN,
                Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)))
            Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val frameArea = small.cols().toDouble() * small.rows().toDouble()
            val candidates = contours.mapNotNull { contour ->
                val area = Imgproc.contourArea(contour)
                if (area < frameArea * 0.12) return@mapNotNull null
                val r = Imgproc.boundingRect(contour)
                val aspect = r.width.toDouble() / max(1, r.height)
                val centerX = r.x + r.width / 2.0
                val centerY = r.y + r.height / 2.0
                // Pool table felt is a large, near-2:1 central surface; reject HUD rectangles.
                if (aspect !in 1.45..2.25 || r.width < small.cols() * 0.42 || r.height < small.rows() * 0.18) return@mapNotNull null
                if (centerX !in (small.cols() * 0.25)..(small.cols() * 0.80) || centerY !in (small.rows() * 0.18)..(small.rows() * 0.88)) return@mapNotNull null
                Triple(r, area, abs(aspect - 2.0))
            }.sortedWith(compareByDescending<Triple<Rect, Double, Double>> { it.second }.thenBy { it.third })

            val tableRect = candidates.firstOrNull()?.first
                ?: return FrameAnalysis(null, emptyList(), emptyList(), "TABLE NOT LOCKED — KEEP FULL TABLE VISIBLE")

            // Exclude cushion rails by shrinking the felt contour slightly.
            val insetX = max(2, (tableRect.width * 0.012).toInt())
            val insetY = max(2, (tableRect.height * 0.018).toInt())
            val inner = Rect(
                tableRect.x + insetX,
                tableRect.y + insetY,
                max(1, tableRect.width - 2 * insetX),
                max(1, tableRect.height - 2 * insetY)
            )
            val gray = Mat()
            val blur = Mat()
            val circles = Mat()
            try {
                val roi = small.submat(inner)
                Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.GaussianBlur(gray, blur, Size(7.0, 7.0), 1.6)
                val expectedRadius = inner.width.toDouble() / 72.0
                Imgproc.HoughCircles(
                    blur, circles, Imgproc.HOUGH_GRADIENT,
                    1.25, expectedRadius * 1.65,
                    105.0, 27.0,
                    max(5, (expectedRadius * 0.68).toInt()),
                    max(7, (expectedRadius * 1.38).toInt())
                )
                val detected = ArrayList<Ball>()
                if (!circles.empty()) {
                    for (i in 0 until circles.cols()) {
                        val v = circles.get(0, i) ?: continue
                        if (v.size < 3) continue
                        val x = v[0] + inner.x
                        val y = v[1] + inner.y
                        val radius = v[2]
                        if (x < inner.x + radius || x > inner.x + inner.width - radius ||
                            y < inner.y + radius || y > inner.y + inner.height - radius) continue
                        val px = small.get(min(small.rows() - 1, y.toInt()), min(small.cols() - 1, x.toInt())) ?: continue
                        if (px.size < 3) continue
                        val color = Color.rgb(px[0].toInt().coerceIn(0,255), px[1].toInt().coerceIn(0,255), px[2].toInt().coerceIn(0,255))
                        // Deduplicate near-identical Hough responses.
                        if (detected.none { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) < radius * 0.8 }) {
                            detected.add(Ball((x / scale).toFloat(), (y / scale).toFloat(), (radius / scale).toFloat(), color))
                        }
                    }
                }

                val table = RectF(
                    (inner.x / scale).toFloat(), (inner.y / scale).toFloat(),
                    ((inner.x + inner.width) / scale).toFloat(), ((inner.y + inner.height) / scale).toFloat()
                )
                val fullBalls = detected
                val cue = identifyCue(fullBalls)
                val lines = ArrayList<Line>()
                // Only produce a provisional centre-to-centre guide when cue and target
                // are both credible. This is not claimed to infer the player's chosen aim.
                if (cue != null && fullBalls.size >= 2) {
                    val target = fullBalls.filter { it !== cue }.minByOrNull {
                        hypot((it.x - cue.x).toDouble(), (it.y - cue.y).toDouble())
                    }
                    if (target != null) {
                        val dx = target.x - cue.x
                        val dy = target.y - cue.y
                        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
                        val ux = dx / length
                        val uy = dy / length
                        val contactX = target.x - ux * (cue.radius + target.radius)
                        val contactY = target.y - uy * (cue.radius + target.radius)
                        lines.add(Line(cue.x, cue.y, contactX, contactY, 0))
                        val end = rayToTable(target.x, target.y, ux, uy, table)
                        lines.add(Line(target.x, target.y, end.first, end.second, 1))
                    }
                }
                val status = when {
                    fullBalls.isEmpty() -> "TABLE LOCKED • NO BALLS CONFIRMED"
                    cue == null -> "TABLE LOCKED • CUE BALL NOT CONFIRMED • ${fullBalls.size} BALLS"
                    else -> "TABLE LOCKED • ${fullBalls.size} BALLS • CUE CONFIRMED"
                }
                return FrameAnalysis(table, fullBalls, lines, status)
            } finally {
                gray.release(); blur.release(); circles.release()
            }
        } catch (t: Throwable) {
            return FrameAnalysis(null, emptyList(), emptyList(), "VISION ERROR: ${t.javaClass.simpleName}")
        } finally {
            contours.forEach { it.release() }
            src.release(); small.release(); hsv.release(); mask.release(); kernel.release()
        }
    }

    private fun identifyCue(balls: List<Ball>): Ball? {
        if (balls.size < 2) return null
        // Cue candidate must be bright and nearly neutral; require a clear score gap.
        val ranked = balls.map { b ->
            val r = Color.red(b.color).toDouble(); val g = Color.green(b.color).toDouble(); val bl = Color.blue(b.color).toDouble()
            val brightness = (r + g + bl) / 3.0
            val spread = max(r, max(g, bl)) - min(r, min(g, bl))
            b to (brightness - spread * 1.35)
        }.sortedByDescending { it.second }
        val first = ranked.first()
        val second = ranked.getOrNull(1)?.second ?: 0.0
        return if (first.second > 145.0 && first.second - second > 8.0) first.first else null
    }

    private fun rayToTable(x: Float, y: Float, dx: Float, dy: Float, r: RectF): Pair<Float, Float> {
        val ts = ArrayList<Float>(2)
        if (dx > 0f) ts.add((r.right - x) / dx) else if (dx < 0f) ts.add((r.left - x) / dx)
        if (dy > 0f) ts.add((r.bottom - y) / dy) else if (dy < 0f) ts.add((r.top - y) / dy)
        val t = ts.filter { it > 0f }.minOrNull() ?: 0f
        return Pair(x + dx * t, y + dy * t)
    }
}
