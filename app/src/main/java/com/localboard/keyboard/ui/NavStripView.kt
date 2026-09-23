package com.localboard.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View

/**
 * The strip behind the system navigation bar.
 *
 * From API 35 the IME window is laid out edge to edge and extends behind the
 * navigation bar. The keys are padded up out of that area (see
 * LLMKeyboardService.onCreateInputView) so they are neither hidden by the bar
 * nor stealing its touches — but that leaves the strip itself empty, and an
 * empty strip in a transparent window shows the app underneath. The keyboard
 * then looks like it has a hole in the bottom of it.
 *
 * This view fills that strip. It is purely decorative: the touchable region
 * excludes it, so nothing here can ever receive a tap.
 *
 * Three modes, because "just make it opaque" and "make it interesting" are
 * both reasonable things to want:
 *
 *   MATCH   the keyboard's own background colour. The default, because the
 *           correct fix for a hole is for the hole to stop existing, and a
 *           strip that matches the keys reads as part of the keyboard rather
 *           than as a band under it.
 *   SOLID   any colour, for people who want the bar delineated.
 *   ANIMATED  a [LiveBackground] style, sharing the same renderer as the
 *           keyboard's own animated background.
 *
 * The animated mode is the only one that costs frames, and it costs them only
 * while it is on: [draw] returns whether it wants another frame, exactly as it
 * does for the keyboard, and a settled animation stops scheduling entirely.
 */
/*
 * The @JvmOverloads constructor is load-bearing, not boilerplate. This view is
 * named in keyboard_root.xml, and LayoutInflater instantiates by reflection on
 * the (Context, AttributeSet) constructor specifically. A Kotlin class written
 * as `NavStripView(context: Context)` compiles and looks fine, then throws
 * NoSuchMethodException inside InflateException the first time the keyboard is
 * shown — which is every time, so the keyboard never appears at all.
 */
class NavStripView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { MATCH, SOLID, ANIMATED }

    private val liveBg = LiveBackground()
    private val tick = Runnable { invalidate() }

    var mode: Mode = Mode.MATCH
        set(v) {
            if (field != v) {
                field = v
                // A tick posted by ANIMATED must not fire into the new mode —
                // it would repaint (and in ANIMATED's onDraw branch, repost)
                // forever after the animation was switched off.
                removeCallbacks(tick)
                invalidate()
            }
        }

    /** Used by [Mode.SOLID]. Not named solidColor: View already has a
     *  getSolidColor(), and the accessor would collide with it. */
    var fillColor: Int = Color.BLACK
        set(v) { if (field != v) { field = v; if (mode == Mode.SOLID) invalidate() } }

    /** The keyboard background, tracked so [Mode.MATCH] stays in step with the
     *  active theme without this view having to know what a theme is. */
    var matchColor: Int = Color.BLACK
        set(v) { if (field != v) { field = v; if (mode == Mode.MATCH) invalidate() } }

    var style: LiveBackground.Style
        get() = liveBg.style
        set(v) { if (liveBg.style != v) { liveBg.style = v; invalidate() } }

    fun setAnimationColors(background: Int, accent: Int, trail: Int, functional: Int) {
        liveBg.setColors(background, accent, trail, functional)
    }

    fun setAnimationTuning(fps: Int, intensity: Float, onlyWhileTyping: Boolean) {
        liveBg.fps = fps
        liveBg.intensity = intensity
        liveBg.onlyWhileTyping = onlyWhileTyping
    }

    /** Forwarded from the keyboard so PULSE and the typing-burst window work
     *  here too — otherwise a reactive style in the strip would never react. */
    fun onKeyPress() {
        if (mode == Mode.ANIMATED && liveBg.style != LiveBackground.Style.NONE) {
            liveBg.onKeyPress()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        when (mode) {
            Mode.MATCH -> canvas.drawColor(matchColor)
            Mode.SOLID -> canvas.drawColor(fillColor)
            Mode.ANIMATED -> {
                // Paint the base colour first: the styles composite onto a
                // background rather than covering every pixel, and without this
                // the gaps between blobs would still be see-through.
                canvas.drawColor(matchColor)
                val wantsAnother = liveBg.draw(canvas, width, height)
                if (wantsAnother && isShown) {
                    removeCallbacks(tick)
                    postDelayed(tick, liveBg.frameDelayMs)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A pending frame on a detached view is a wake-up for nothing.
        removeCallbacks(tick)
    }
}
