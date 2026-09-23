package com.localboard.keyboard.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.SystemClock

/**
 * Draws the fading glow trail that follows the finger during glide typing.
 * Points age out after ~260 ms, giving the classic comet-tail look.
 */
class GestureTrailRenderer(private val color: Int) {

    /** Fade-speed multiplier driven by the animation-level preference:
     *  1f = full, 1.4f = reduced, 2f = off-feel (near-instant fade). */
    var fadeSpeed = 1f
    private val lifetimeMs: Long get() = (LIFETIME_MS / fadeSpeed).toLong().coerceAtLeast(60L)

    private data class TrailPoint(val x: Float, val y: Float, val t: Long)

    private val points = ArrayList<TrailPoint>(64)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    var isActive = false
        private set

    fun start(x: Float, y: Float) {
        points.clear()
        points += TrailPoint(x, y, SystemClock.uptimeMillis())
        isActive = true
    }

    fun add(x: Float, y: Float) {
        if (!isActive) return
        val last = points.lastOrNull()
        // dist() is SQUARED distance, so the 4px thinning threshold is 16 here
        // — comparing against 4f thinned at 2px, twice as dense as intended.
        if (last == null || dist(last, x, y) > 16f) {
            points += TrailPoint(x, y, SystemClock.uptimeMillis())
            if (points.size > 128) points.removeAt(0)
        }
    }

    fun finish() {
        isActive = false
    }

    fun clear() {
        points.clear()
        isActive = false
    }

    /**
     * Bounding box of the live trail, or false when there is nothing to draw.
     *
     * Lets the view repaint the trail's own region instead of the whole
     * keyboard on every animation frame. Callers must union this with the
     * PREVIOUS frame's box — the tail fades away outside the new bounds, and
     * without the union it would be left on screen.
     */
    fun bounds(out: android.graphics.RectF): Boolean {
        if (points.isEmpty()) return false
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        out.set(minX, minY, maxX, maxY)
        return true
    }

    /** @return true while there is something visible (caller should keep invalidating). */
    fun draw(canvas: Canvas, width: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        while (points.isNotEmpty() && now - points.first().t > lifetimeMs) {
            points.removeAt(0)
        }
        if (points.size < 2) return isActive

        path.reset()
        path.moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            val midX = (prev.x + cur.x) / 2f
            val midY = (prev.y + cur.y) / 2f
            path.quadTo(prev.x, prev.y, midX, midY)
        }
        val last = points.last()
        path.lineTo(last.x, last.y)

        val age = (now - last.t).coerceIn(0, lifetimeMs)
        val alphaScale = 1f - age.toFloat() / lifetimeMs
        paint.color = withAlpha(color, (BASE_ALPHA * alphaScale).toInt())
        paint.strokeWidth = width
        canvas.drawPath(path, paint)

        if (isActive) {
            headPaint.color = withAlpha(color, (BASE_ALPHA * 0.8f * alphaScale).toInt())
            canvas.drawCircle(last.x, last.y, width * 0.9f, headPaint)
        }
        return true
    }

    private fun withAlpha(c: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun dist(p: TrailPoint, x: Float, y: Float): Float {
        val dx = p.x - x
        val dy = p.y - y
        return dx * dx + dy * dy
    }

    companion object {
        private const val LIFETIME_MS = 260L
        private const val BASE_ALPHA = 180
    }
}
