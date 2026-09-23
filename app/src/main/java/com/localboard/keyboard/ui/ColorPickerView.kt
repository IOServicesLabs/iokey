package com.localboard.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * A compact HSV colour picker: a hue strip, a saturation strip and a
 * lightness strip, each a live gradient of what the *other two* currently
 * are. Three drags instead of typing `#3E2E23` into a text box.
 *
 * Deliberately three 1-D strips rather than the usual hue-bar-plus-SV-square:
 * strips are thumb-friendly on a phone, every axis stays reachable one-handed,
 * and each one previews its own outcome.
 */
class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    /** Fired continuously while dragging. */
    var onColorChanged: ((Int) -> Unit)? = null

    private val hsl = floatArrayOf(200f, 0.5f, 0.5f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val rect = RectF()
    private var activeStrip = -1

    /** Cached gradients — rebuilding a LinearGradient per frame while
     *  dragging is the one thing that makes a picker feel cheap. */
    private var hueShader: Shader? = null
    private var satShader: Shader? = null
    private var litShader: Shader? = null
    private var shaderW = 0
    private var shaderHue = -1f
    private var shaderSat = -1f
    private var shaderLit = -1f

    var color: Int
        get() = ThemeSpec.fromHsl(hsl[0], hsl[1], hsl[2])
        set(v) {
            val c = ThemeSpec.toHsl(v)
            // A pure grey has no meaningful hue; keep the last one so the
            // strip doesn't snap to red when the user drags saturation to 0.
            if (c[1] > 0.001f) hsl[0] = c[0]
            hsl[1] = c[1]
            hsl[2] = c[2]
            invalidateShaders()
            invalidate()
        }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun invalidateShaders() {
        shaderHue = -1f; shaderSat = -1f; shaderLit = -1f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (STRIP_H * 3 + GAP * 2).let { dp(it).roundToInt() })
    }

    private fun stripRect(i: Int): RectF {
        val h = dp(STRIP_H)
        val top = i * (h + dp(GAP))
        rect.set(dp(2f), top, width - dp(2f), top + h)
        return rect
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        if (w <= 0) return
        val r = dp(STRIP_H) / 2f

        if (shaderW != w) { invalidateShaders(); shaderW = w }

        // Hue: the full wheel at the current S/L, so it previews truthfully.
        if (shaderHue != hsl[1] * 1000 + hsl[2]) {
            val stops = IntArray(13) { ThemeSpec.fromHsl(it * 30f, hsl[1].coerceAtLeast(0.25f), hsl[2].coerceIn(0.3f, 0.7f)) }
            hueShader = LinearGradient(0f, 0f, w.toFloat(), 0f, stops, null, Shader.TileMode.CLAMP)
            shaderHue = hsl[1] * 1000 + hsl[2]
        }
        if (shaderSat != hsl[0] * 1000 + hsl[2]) {
            satShader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                ThemeSpec.fromHsl(hsl[0], 0f, hsl[2]),
                ThemeSpec.fromHsl(hsl[0], 1f, hsl[2]),
                Shader.TileMode.CLAMP
            )
            shaderSat = hsl[0] * 1000 + hsl[2]
        }
        if (shaderLit != hsl[0] * 1000 + hsl[1]) {
            litShader = LinearGradient(
                0f, 0f, w.toFloat(), 0f,
                intArrayOf(
                    Color.BLACK,
                    ThemeSpec.fromHsl(hsl[0], hsl[1], 0.5f),
                    Color.WHITE
                ),
                null, Shader.TileMode.CLAMP
            )
            shaderLit = hsl[0] * 1000 + hsl[1]
        }

        drawStrip(canvas, 0, hueShader, hsl[0] / 360f, r)
        drawStrip(canvas, 1, satShader, hsl[1], r)
        drawStrip(canvas, 2, litShader, hsl[2], r)
    }

    private fun drawStrip(canvas: Canvas, i: Int, shader: Shader?, pos: Float, radius: Float) {
        val rr = stripRect(i)
        paint.shader = shader
        canvas.drawRoundRect(rr, radius, radius, paint)
        paint.shader = null

        // Thumb: a ring, so it reads on any underlying colour.
        val cx = rr.left + pos.coerceIn(0f, 1f) * rr.width()
        val cy = rr.centerY()
        strokePaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, radius * 0.72f, strokePaint)
        strokePaint.color = 0x66000000
        canvas.drawCircle(cx, cy, radius * 0.72f + dp(2f), strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeStrip = (event.y / (dp(STRIP_H) + dp(GAP)))
                    .toInt().coerceIn(0, 2)
                parent?.requestDisallowInterceptTouchEvent(true)
                apply(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeStrip >= 0) { apply(event.x); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeStrip = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun apply(x: Float) {
        val rr = stripRect(activeStrip)
        val t = ((x - rr.left) / rr.width()).coerceIn(0f, 1f)
        when (activeStrip) {
            0 -> hsl[0] = t * 360f
            1 -> hsl[1] = t
            else -> hsl[2] = t
        }
        invalidateShaders()
        invalidate()
        onColorChanged?.invoke(color)
    }

    private companion object {
        const val STRIP_H = 34f
        const val GAP = 10f
    }
}
