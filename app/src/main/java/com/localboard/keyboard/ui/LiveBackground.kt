package com.localboard.keyboard.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * Animated keyboard background, built to cost almost nothing.
 *
 * THE RULES THIS CLASS FOLLOWS
 * ---------------------------
 * 1. **No allocation on the draw path.** Shaders, matrices, paints and paths
 *    are created once and mutated. The old inline version allocated three
 *    RadialGradients every frame — at 24 fps that is 72 shader objects per
 *    second, all of them garbage, on the thread that also has to service
 *    touch events.
 * 2. **Render small, upscale.** Smooth gradients are indistinguishable when
 *    drawn at 1/3 scale and blown up, so each frame paints 1/9 of the pixels.
 * 3. **Frame-paced, not vsync-chased.** These animations move over tens of
 *    seconds; 12-15 fps is visually identical to 120 and costs an order of
 *    magnitude less. The caller schedules by [frameDelayMs].
 * 4. **Optionally reactive instead of continuous.** In "while typing" mode the
 *    animation runs during a typing burst and then stops completely, so an
 *    idle keyboard draws zero frames.
 *
 * Styles that need real per-pixel work (blobs, aurora, waves) go through the
 * offscreen bitmap. Styles that are a handful of primitives (particles, pulse)
 * draw straight to the canvas, where an offscreen would cost more than it saves.
 */
class LiveBackground {

    enum class Style(val id: String, val label: String) {
        NONE("none", "Off"),
        BLOBS("blobs", "Drifting colour"),
        AURORA("aurora", "Aurora sheets"),
        WAVES("waves", "Slow waves"),
        PARTICLES("particles", "Rising motes"),
        PULSE("pulse", "Reacts to typing");

        companion object {
            fun fromId(id: String): Style = entries.firstOrNull { it.id == id } ?: NONE
        }
    }

    var style: Style = Style.NONE
        set(v) {
            if (field != v) { field = v; needsRepaint = true }
        }

    /** Target frames per second. Clamped to something sane. */
    var fps: Int = 15
        set(v) { field = v.coerceIn(4, 60) }

    /** Animate only during (and just after) a typing burst. */
    var onlyWhileTyping: Boolean = false

    /** Overall strength, 0..1. Scales alpha of everything drawn. */
    var intensity: Float = 1f
        set(v) { field = v.coerceIn(0f, 1f) }

    val frameDelayMs: Long get() = (1000L / fps).coerceAtLeast(8L)

    // --- colours (from the active theme) ---
    private var bgColor = Color.BLACK
    private var c1 = Color.DKGRAY
    private var c2 = Color.GRAY
    private var c3 = Color.LTGRAY

    // --- reusable draw objects ---
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val path = Path()

    private var blobShaders: Array<RadialGradient>? = null
    private var auroraShaders: Array<LinearGradient>? = null
    private var shaderW = 0
    private var shaderH = 0

    // --- offscreen ---
    private var offscreen: Bitmap? = null
    private var offCanvas: Canvas? = null
    private var offW = 0
    private var offH = 0

    // --- animation state ---
    private var startedAt = 0L
    private var lastTypedAt = 0L
    private var needsRepaint = true

    /** Particle state as parallel arrays — no per-mote objects. */
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var pv = FloatArray(0)
    private var pr = FloatArray(0)
    private var particlesSized = 0
    /** Width the particle x-spread was seeded for — a resize must re-seed or
     *  the motes keep their old horizontal distribution. */
    private var particlesW = 0

    /** Pulse energy, 0..1, decays after each keypress. */
    private var pulse = 0f
    private var pulseAt = 0L

    fun setColors(background: Int, accent: Int, trail: Int, functional: Int) {
        if (bgColor == background && c1 == accent && c2 == trail && c3 == functional) return
        bgColor = background
        c1 = accent
        c2 = trail
        c3 = functional
        // Colours are baked into the shaders, so they must be rebuilt.
        blobShaders = null
        auroraShaders = null
        needsRepaint = true
    }

    /** Called on every key press: drives PULSE and the typing-burst window. */
    fun onKeyPress() {
        lastTypedAt = System.currentTimeMillis()
        pulse = 1f
        pulseAt = lastTypedAt
    }

    /** True when this style currently wants to be redrawn at all. */
    fun isActive(): Boolean {
        if (style == Style.NONE) return false
        if (!onlyWhileTyping) return true
        // PULSE is inherently reactive: it is "active" only while decaying.
        val window = if (style == Style.PULSE) PULSE_MS.toLong() else TYPING_WINDOW_MS
        return System.currentTimeMillis() - lastTypedAt < window
    }

    /**
     * Paint the background. Returns true when another frame should be
     * scheduled; false means the animation has settled and the caller can
     * stop drawing entirely.
     */
    fun draw(canvas: Canvas, w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (startedAt == 0L) startedAt = System.currentTimeMillis()

        // Base fill always happens: the styles composite on top of it.
        paint.shader = null
        paint.alpha = 255
        paint.color = bgColor
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        if (style == Style.NONE) return false
        if (!isActive()) {
            // Settled: draw one final static frame so the keyboard does not
            // flash back to flat colour, then stop asking for frames. PULSE
            // is the exception — its settled state IS nothing (drawPulse
            // early-returns at pulse <= 0), so the "final frame" would only
            // repaint the base fill that already happened above.
            if (style != Style.PULSE) drawStyle(canvas, w, h, frozen = true)
            return false
        }
        drawStyle(canvas, w, h, frozen = false)
        return true
    }

    private fun drawStyle(canvas: Canvas, w: Int, h: Int, frozen: Boolean) {
        when (style) {
            Style.BLOBS, Style.AURORA, Style.WAVES -> drawViaOffscreen(canvas, w, h)
            Style.PARTICLES -> drawParticles(canvas, w, h, frozen)
            Style.PULSE -> drawPulse(canvas, w, h)
            Style.NONE -> Unit
        }
    }

    // ------------------------------------------------------------ offscreen

    private fun drawViaOffscreen(canvas: Canvas, w: Int, h: Int) {
        val sw = (w / DOWNSCALE).coerceAtLeast(8)
        val sh = (h / DOWNSCALE).coerceAtLeast(8)
        var bmp = offscreen
        if (bmp == null || offW != sw || offH != sh) {
            bmp?.recycle()
            bmp = try {
                Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            } catch (_: Throwable) { null }
            offscreen = bmp
            offCanvas = bmp?.let { Canvas(it) }
            offW = sw
            offH = sh
            blobShaders = null
            auroraShaders = null
        }
        val oc = offCanvas ?: return
        bmp ?: return

        oc.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        val t = (System.currentTimeMillis() - startedAt) / 1000.0
        when (style) {
            Style.BLOBS -> drawBlobs(oc, sw, sh, t)
            Style.AURORA -> drawAurora(oc, sw, sh, t)
            Style.WAVES -> drawWaves(oc, sw, sh, t)
            else -> Unit
        }

        // One filtered upscale blit. The keys are drawn over this afterwards,
        // so softness in the background is invisible where it matters.
        matrix.reset()
        matrix.setScale(w.toFloat() / sw, h.toFloat() / sh)
        canvas.drawBitmap(bmp, matrix, blitPaint)
    }

    private fun ensureBlobShaders(w: Int, h: Int) {
        if (blobShaders != null && shaderW == w && shaderH == h) return
        val r = maxOf(w, h) * 0.75f
        // Built at the origin; every frame only moves them with a matrix.
        blobShaders = arrayOf(
            RadialGradient(0f, 0f, r, c1, Color.TRANSPARENT, Shader.TileMode.CLAMP),
            RadialGradient(0f, 0f, r, c2, Color.TRANSPARENT, Shader.TileMode.CLAMP),
            RadialGradient(0f, 0f, r, c3, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        )
        shaderW = w
        shaderH = h
    }

    private fun drawBlobs(canvas: Canvas, w: Int, h: Int, t: Double) {
        ensureBlobShaders(w, h)
        val shaders = blobShaders ?: return
        val fw = w.toFloat()
        val fh = h.toFloat()
        for (i in shaders.indices) {
            val period = 40.0 + i * 14.0
            val ang = t / period * 2 * Math.PI
            val cx = fw * (0.5 + 0.38 * kotlin.math.cos(ang)).toFloat()
            val cy = fh * (0.5 + 0.42 * kotlin.math.sin(ang * 0.8 + i)).toFloat()
            matrix.reset()
            matrix.setTranslate(cx, cy)
            shaders[i].setLocalMatrix(matrix)
            paint.shader = shaders[i]
            paint.alpha = (96 * intensity).toInt()
            canvas.drawRect(0f, 0f, fw, fh, paint)
        }
        paint.shader = null
        paint.alpha = 255
    }

    private fun ensureAuroraShaders(h: Int) {
        if (auroraShaders != null && shaderH == h) return
        fun sheet(color: Int) = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, color, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        auroraShaders = arrayOf(sheet(c1), sheet(c2), sheet(c3))
        shaderH = h
    }

    private fun drawAurora(canvas: Canvas, w: Int, h: Int, t: Double) {
        ensureAuroraShaders(h)
        val sheets = auroraShaders ?: return
        val fw = w.toFloat()
        val fh = h.toFloat()
        for (i in sheets.indices) {
            val period = 26.0 + i * 11.0
            val phase = ((t / period) % 1.0).toFloat()
            // Sheets slide horizontally and breathe vertically a little.
            val dx = (phase * (fw + fw) - fw)
            val squash = 0.75f + 0.25f * kotlin.math.sin((t / 9.0 + i).toFloat())
            matrix.reset()
            matrix.setScale(1f, squash)
            matrix.postTranslate(0f, fh * (1f - squash) * 0.5f)
            sheets[i].setLocalMatrix(matrix)
            paint.shader = sheets[i]
            paint.alpha = (104 * intensity).toInt()
            val bandW = fw * 0.55f
            canvas.drawRect(dx, 0f, dx + bandW, fh, paint)
        }
        paint.shader = null
        paint.alpha = 255
    }

    private fun drawWaves(canvas: Canvas, w: Int, h: Int, t: Double) {
        val fw = w.toFloat()
        val fh = h.toFloat()
        val colors = intArrayOf(c1, c2, c3)
        paint.shader = null
        paint.style = Paint.Style.FILL
        for (i in 0 until 3) {
            val amp = fh * (0.05f + 0.02f * i)
            val yBase = fh * (0.55f + 0.14f * i)
            val speed = 0.18 + 0.07 * i
            val phase = (t * speed).toFloat()
            path.rewind()
            path.moveTo(0f, fh)
            path.lineTo(0f, yBase)
            // Eight segments is plenty at this scale and keeps the path cheap.
            val steps = 8
            for (s in 0..steps) {
                val x = fw * s / steps
                val y = yBase + amp * kotlin.math.sin(phase + s * 0.9f)
                path.lineTo(x, y)
            }
            path.lineTo(fw, fh)
            path.close()
            paint.color = colors[i]
            paint.alpha = (84 * intensity).toInt()
            canvas.drawPath(path, paint)
        }
        paint.alpha = 255
    }

    // ------------------------------------------------------------ direct

    private fun drawParticles(canvas: Canvas, w: Int, h: Int, frozen: Boolean) {
        val fw = w.toFloat()
        val fh = h.toFloat()
        if (particlesSized != PARTICLES || particlesW != w) {
            px = FloatArray(PARTICLES)
            py = FloatArray(PARTICLES)
            pv = FloatArray(PARTICLES)
            pr = FloatArray(PARTICLES)
            // Deterministic spread — no Random object, no per-frame RNG.
            for (i in 0 until PARTICLES) {
                val f = i.toFloat() / PARTICLES
                px[i] = fw * ((f * 7.13f) % 1f)
                py[i] = fh * ((f * 3.71f) % 1f)
                pv[i] = 0.10f + 0.22f * ((f * 5.31f) % 1f)
                pr[i] = 1.2f + 2.6f * ((f * 2.17f) % 1f)
            }
            particlesSized = PARTICLES
            particlesW = w
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = c1
        for (i in 0 until PARTICLES) {
            // frozen = the settled final frame: draw, but do not advance the
            // motes, so a stray repaint after settling is not a hidden step.
            if (!frozen) {
                py[i] -= pv[i]
                if (py[i] < -4f) {
                    py[i] = fh + 4f
                    // Nudge x on respawn so columns do not form.
                    px[i] = (px[i] + 37.7f) % fw
                }
            }
            // Fade at the top and bottom edges so motes appear and vanish
            // rather than popping.
            val edge = kotlin.math.min(py[i], fh - py[i]) / (fh * 0.25f)
            paint.alpha = (150 * intensity * edge.coerceIn(0f, 1f)).toInt()
            canvas.drawCircle(px[i], py[i], pr[i], paint)
        }
        paint.alpha = 255
    }

    private fun drawPulse(canvas: Canvas, w: Int, h: Int) {
        val now = System.currentTimeMillis()
        val age = (now - pulseAt).toFloat()
        pulse = (1f - age / PULSE_MS).coerceIn(0f, 1f)
        if (pulse <= 0f) return
        ensureBlobShaders(w, h)
        val shaders = blobShaders ?: return
        // A single accent bloom from the middle of the keyboard, easing out.
        val eased = pulse * pulse
        matrix.reset()
        matrix.setTranslate(w * 0.5f, h.toFloat())
        shaders[0].setLocalMatrix(matrix)
        paint.shader = shaders[0]
        paint.alpha = (120 * intensity * eased).toInt()
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.alpha = 255
    }

    fun release() {
        offscreen?.recycle()
        offscreen = null
        offCanvas = null
        offW = 0
        offH = 0
        blobShaders = null
        auroraShaders = null
    }

    private companion object {
        /** Offscreen render scale. 3 keeps waves clean; blobs would tolerate 6. */
        const val DOWNSCALE = 3
        const val PARTICLES = 22
        const val TYPING_WINDOW_MS = 1_600L
        const val PULSE_MS = 900f
    }
}
