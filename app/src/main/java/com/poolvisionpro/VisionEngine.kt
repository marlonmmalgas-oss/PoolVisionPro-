package com.poolvisionpro

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
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

/** All positions are in the original captured-screen pixel coordinate system. */
data class Ball(val x: Float, val y: Float, val radius: Float, val color: Int)
data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val kind: Int)
data class FrameAnalysis(
    val table: RectF?,
    val balls: List<Ball>,
    val lines: List<Line>,
    val status: String,
    val cueBall: Ball? = null
)

/**
 * Pool-specific OpenCV pipeline tuned against the supplied 8 Ball Pool recording.
 * It uses the blue felt hue range, central table geometry, radius-scaled Hough circles,
 * and whole-disc neutrality for cue-ball classification. Low-confidence frames suppress
 * aiming output instead of drawing arbitrary geometry.
 */
object VisionEngine {
    private const val MAX_ANALYSIS_WIDTH = 900.0
    @Volatile private var openCvReady = false
    @Volatile private var previousCueX = Float.NaN
    @Volatile private var previousCueY = Float.NaN

    @Synchronized private fun ensureOpenCv(): Boolean {
        if (openCvReady) return true
        return try {
            openCvReady = org.opencv.android.OpenCVLoader.initLocal()
            openCvReady
        } catch (_: Throwable) { false }
    }

    fun analyse(bitmap: Bitmap): FrameAnalysis {
        if (!ensureOpenCv()) return FrameAnalysis(null, emptyList(), emptyList(), "OPENCV INITIALIZATION FAILED")
        val source = Mat()
        val small = Mat()
        val hsv = Mat()
        val feltMask = Mat()
        val contours = ArrayList<MatOfPoint>()
        val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        try {
            Utils.bitmapToMat(bitmap, source)
            val scale = min(1.0, MAX_ANALYSIS_WIDTH / source.cols().toDouble())
            if (scale < 1.0) Imgproc.resize(source, small, Size(), scale, scale, Imgproc.INTER_AREA)
            else source.copyTo(small)

            Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)

            // The supplied footage's blue felt samples around OpenCV hue 96.
            Core.inRange(hsv, Scalar(78.0, 45.0, 35.0), Scalar(115.0, 255.0, 255.0), feltMask)
            Imgproc.morphologyEx(feltMask, feltMask, Imgproc.MORPH_CLOSE, closeKernel)
            Imgproc.morphologyEx(feltMask, feltMask, Imgproc.MORPH_OPEN, openKernel)
            Imgproc.findContours(feltMask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val frameArea = small.cols().toDouble() * small.rows().toDouble()
            val tableCandidates = contours.mapNotNull { contour ->
                val area = Imgproc.contourArea(contour)
                if (area < frameArea * 0.08) return@mapNotNull null
                val rect = Imgproc.boundingRect(contour)
                val aspect = rect.width.toDouble() / max(1, rect.height)
                val cx = rect.x + rect.width / 2.0
                val cy = rect.y + rect.height / 2.0
                if (aspect !in 1.65..2.25) return@mapNotNull null
                if (rect.width !in (small.cols() * 0.40).toInt()..(small.cols() * 0.88).toInt()) return@mapNotNull null
                if (rect.height !in (small.rows() * 0.30).toInt()..(small.rows() * 0.85).toInt()) return@mapNotNull null
                if (cx !in (small.cols() * 0.25)..(small.cols() * 0.75)) return@mapNotNull null
                if (cy !in (small.rows() * 0.25)..(small.rows() * 0.75)) return@mapNotNull null
                val aspectPenalty = abs(aspect - 2.0)
                Triple(rect, area, aspectPenalty)
            }

            val tableRect = tableCandidates
                .sortedWith(compareByDescending<Triple<Rect, Double, Double>> { it.second - it.third * frameArea * 0.08 })
                .firstOrNull()?.first
                ?: return FrameAnalysis(null, emptyList(), emptyList(), "TABLE NOT FOUND — KEEP ALL SIX POCKETS VISIBLE")

            // Mask bounds include a narrow blue cushion lip. Inset to the actual playable cloth.
            val insetX = max(2, (tableRect.width * 0.022).toInt())
            val insetY = max(2, (tableRect.height * 0.035).toInt())
            val playable = Rect(
                tableRect.x + insetX,
                tableRect.y + insetY,
                max(1, tableRect.width - insetX * 2),
                max(1, tableRect.height - insetY * 2)
            )
            val table = RectF(
                (playable.x / scale).toFloat(),
                (playable.y / scale).toFloat(),
                ((playable.x + playable.width) / scale).toFloat(),
                ((playable.y + playable.height) / scale).toFloat()
            )

            val roi = small.submat(playable)
            val gray = Mat()
            val blurred = Mat()
            val circles = Mat()
            try {
                Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.GaussianBlur(gray, blurred, Size(7.0, 7.0), 1.5)
                val expectedRadius = playable.width.toDouble() / 46.0
                Imgproc.HoughCircles(
                    blurred,
                    circles,
                    Imgproc.HOUGH_GRADIENT,
                    1.1,
                    expectedRadius * 1.15,
                    90.0,
                    17.0,
                    max(4, (expectedRadius * 0.55).toInt()),
                    max(6, (expectedRadius * 1.50).toInt())
                )

                val detected = ArrayList<Ball>()
                if (!circles.empty()) {
                    for (i in 0 until circles.cols()) {
                        val values = circles.get(0, i) ?: continue
                        if (values.size < 3) continue
                        val xSmall = values[0] + playable.x
                        val ySmall = values[1] + playable.y
                        val radiusSmall = values[2]
                        if (xSmall - radiusSmall < playable.x || xSmall + radiusSmall > playable.x + playable.width ||
                            ySmall - radiusSmall < playable.y || ySmall + radiusSmall > playable.y + playable.height) continue
                        val center = small.get(ySmall.toInt().coerceIn(0, small.rows() - 1), xSmall.toInt().coerceIn(0, small.cols() - 1)) ?: continue
                        if (center.size < 3) continue
                        val color = Color.rgb(center[0].toInt().coerceIn(0, 255), center[1].toInt().coerceIn(0, 255), center[2].toInt().coerceIn(0, 255))
                        val ball = Ball((xSmall / scale).toFloat(), (ySmall / scale).toFloat(), (radiusSmall / scale).toFloat(), color)
                        if (detected.none { hypot((it.x - ball.x).toDouble(), (it.y - ball.y).toDouble()) < ball.radius * 1.05 }) detected.add(ball)
                    }
                }

                // Stable ordering makes diagnostics and tracking easier to compare frame-to-frame.
                val balls = detected.sortedWith(compareBy<Ball> { it.y }.thenBy { it.x })
                val cue = identifyCue(balls, hsv, scale)
                val lines = if (cue != null) buildGuides(cue, balls.filterNot { it === cue }, table) else emptyList()
                val status = when {
                    balls.isEmpty() -> "TABLE LOCKED • NO BALL CIRCLES CONFIRMED"
                    cue == null -> "TABLE LOCKED • ${balls.size} BALL CANDIDATES • CUE NOT CONFIRMED"
                    else -> "TABLE LOCKED • ${balls.size} BALLS • CUE CONFIRMED"
                }
                return FrameAnalysis(table, balls, lines, status, cue)
            } finally {
                roi.release()
                gray.release()
                blurred.release()
                circles.release()
            }
        } catch (error: Throwable) {
            return FrameAnalysis(null, emptyList(), emptyList(), "VISION ERROR: ${error.javaClass.simpleName}")
        } finally {
            contours.forEach { it.release() }
            source.release(); small.release(); hsv.release(); feltMask.release()
            closeKernel.release(); openKernel.release()
        }
    }

    /** Scores the whole ball disc; striped balls with a white centre are rejected by their coloured rim. */
    private fun identifyCue(balls: List<Ball>, hsv: Mat, scale: Double): Ball? {
        if (balls.isEmpty()) return null
        data class Candidate(val ball: Ball, val score: Double, val neutralRatio: Double, val meanSat: Double, val meanValue: Double)
        val candidates = balls.mapNotNull { ball ->
            val cx = (ball.x * scale).toInt()
            val cy = (ball.y * scale).toInt()
            val r = (ball.radius * scale * 0.72).toInt().coerceAtLeast(2)
            val left = (cx - r).coerceAtLeast(0); val top = (cy - r).coerceAtLeast(0)
            val right = (cx + r).coerceAtMost(hsv.cols() - 1); val bottom = (cy + r).coerceAtMost(hsv.rows() - 1)
            if (right <= left || bottom <= top) return@mapNotNull null
            val patch = hsv.submat(Rect(left, top, right - left + 1, bottom - top + 1))
            try {
                var count = 0; var neutral = 0; var satSum = 0.0; var valueSum = 0.0
                for (yy in 0 until patch.rows() step 2) for (xx in 0 until patch.cols() step 2) {
                    val dx = xx + left - cx; val dy = yy + top - cy
                    if (dx * dx + dy * dy > r * r) continue
                    val pixel = patch.get(yy, xx) ?: continue
                    if (pixel.size < 3) continue
                    count++
                    val saturation = pixel[1]
                    val value = pixel[2]
                    satSum += saturation; valueSum += value
                    if (saturation < 68.0 && value > 150.0) neutral++
                }
                if (count < 8) return@mapNotNull null
                val neutralRatio = neutral.toDouble() / count
                val meanSat = satSum / count
                val meanValue = valueSum / count
                val score = neutralRatio * 100.0 - meanSat * 0.30 + meanValue * 0.04
                Candidate(ball, score, neutralRatio, meanSat, meanValue)
            } finally { patch.release() }
        }.filter { it.neutralRatio >= 0.72 && it.meanSat < 78.0 && it.meanValue > 145.0 }
            .sortedByDescending { it.score }

        if (candidates.isEmpty()) return null
        val previousExists = previousCueX.isFinite() && previousCueY.isFinite()
        val chosen = if (previousExists) {
            val nearby = candidates.minByOrNull { hypot((it.ball.x - previousCueX).toDouble(), (it.ball.y - previousCueY).toDouble()) }
            val maxJump = (hsv.cols() / scale * 0.18).toFloat()
            if (nearby != null && hypot((nearby.ball.x - previousCueX).toDouble(), (nearby.ball.y - previousCueY).toDouble()) < maxJump) nearby else candidates.first()
        } else candidates.first()
        previousCueX = chosen.ball.x; previousCueY = chosen.ball.y
        return chosen.ball
    }

    private fun buildGuides(cue: Ball, objects: List<Ball>, table: RectF): List<Line> {
        if (objects.isEmpty()) return emptyList()
        val target = objects.minByOrNull { hypot((it.x - cue.x).toDouble(), (it.y - cue.y).toDouble()) } ?: return emptyList()
        val dx = target.x - cue.x; val dy = target.y - cue.y
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val ux = dx / distance; val uy = dy / distance
        val contactX = target.x - ux * (cue.radius + target.radius)
        val contactY = target.y - uy * (cue.radius + target.radius)
        val lines = ArrayList<Line>()
        // Solid cue-to-contact guide and object-ball direction to the nearest pocket.
        lines.add(Line(cue.x, cue.y, contactX, contactY, 0))
        val pockets = listOf(
            table.left to table.top, (table.left + table.right) / 2f to table.top,
            table.right to table.top, table.left to table.bottom,
            (table.left + table.right) / 2f to table.bottom, table.right to table.bottom
        )
        val pocket = pockets.minByOrNull { hypot((it.first - target.x).toDouble(), (it.second - target.y).toDouble()) }!!
        lines.add(Line(target.x, target.y, pocket.first, pocket.second, 1))

        // A centre hit predicts a stun/stop at the contact point; do not invent spin or speed.
        lines.add(Line(contactX, contactY, contactX + ux * cue.radius * 0.65f, contactY + uy * cue.radius * 0.65f, 2))

        // One-cushion bank alternatives are calculated by reflecting each pocket across each rail.
        val reflectedPockets = listOf(
            (2f * table.left - pocket.first) to pocket.second,
            (2f * table.right - pocket.first) to pocket.second,
            pocket.first to (2f * table.top - pocket.second),
            pocket.first to (2f * table.bottom - pocket.second)
        )
        reflectedPockets.forEachIndexed { index, reflected ->
            val hit = segmentToRectangleEdge(target.x, target.y, reflected.first, reflected.second, table) ?: return@forEachIndexed
            // Keep bank lines only when the reflected ray reaches the corresponding cushion before the pocket.
            lines.add(Line(target.x, target.y, hit.first, hit.second, 3 + index))
        }
        return lines
    }

    private fun segmentToRectangleEdge(x: Float, y: Float, tx: Float, ty: Float, r: RectF): Pair<Float, Float>? {
        val dx = tx - x; val dy = ty - y
        if (abs(dx) < 0.001f && abs(dy) < 0.001f) return null
        val candidates = ArrayList<Pair<Float, Pair<Float, Float>>>()
        if (dx > 0f) { val t = (r.right - x) / dx; if (t in 0f..1f) candidates.add(t to (r.right to (y + dy * t))) }
        if (dx < 0f) { val t = (r.left - x) / dx; if (t in 0f..1f) candidates.add(t to (r.left to (y + dy * t))) }
        if (dy > 0f) { val t = (r.bottom - y) / dy; if (t in 0f..1f) candidates.add(t to ((x + dx * t) to r.bottom)) }
        if (dy < 0f) { val t = (r.top - y) / dy; if (t in 0f..1f) candidates.add(t to ((x + dx * t) to r.top)) }
        return candidates.minByOrNull { it.first }?.second
    }
}
