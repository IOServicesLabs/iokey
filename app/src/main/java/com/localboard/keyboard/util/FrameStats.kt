package com.localboard.keyboard.util

/**
 * Counters for the four things that decide how much power the keyboard burns,
 * and the four things that would silently regress.
 *
 * "Nearly zero battery while typing" is not verifiable by feel. Frames are the
 * proxy that matters: every frame is a full pass through measure/draw/composite
 * plus a panel refresh, and the difference between a keyboard that draws 3
 * frames per keystroke and one that draws 40 is invisible on screen and very
 * visible in a battery graph.
 *
 * Deliberately plain counters with no synchronisation: they are written only
 * from the draw path (main thread) and read for display. A torn read shows a
 * slightly stale number on a diagnostics screen, which is not worth a lock on
 * the hottest path in the app.
 */
object FrameStats {

    /** Total onDraw() calls since the last reset. */
    @JvmField var frames: Long = 0

    /** Frames whose only reason to exist was the animated background. */
    @JvmField var backgroundFrames: Long = 0

    /** Frames that redrew the whole view rather than one key's dirty rect. */
    @JvmField var fullRedraws: Long = 0

    /** Key presses dispatched. */
    @JvmField var keystrokes: Long = 0

    /** Glide gestures completed. */
    @JvmField var gestures: Long = 0

    /** Cached-layer rebuilds (layout or theme changes) — should be rare. */
    @JvmField var layerRebuilds: Long = 0

    private var since: Long = System.currentTimeMillis()

    fun reset() {
        frames = 0
        backgroundFrames = 0
        fullRedraws = 0
        keystrokes = 0
        gestures = 0
        layerRebuilds = 0
        since = System.currentTimeMillis()
    }

    fun elapsedMinutes(): Double =
        ((System.currentTimeMillis() - since).coerceAtLeast(1L)) / 60_000.0

    /** The headline number: frames drawn per keystroke. Under ~4 is good;
     *  40 means every keypress is repainting the entire key grid. */
    fun framesPerKeystroke(): Double =
        if (keystrokes == 0L) 0.0 else frames.toDouble() / keystrokes

    fun summary(): String = buildString {
        val mins = elapsedMinutes()
        append("frames: ").append(frames)
        append(String.format(" (%.0f/min)", frames / mins)).append('\n')
        append("  per keystroke: ")
        append(if (keystrokes == 0L) "—" else String.format("%.1f", framesPerKeystroke()))
        append('\n')
        append("  full redraws: ").append(fullRedraws)
        if (frames > 0) append(String.format(" (%.0f%%)", 100.0 * fullRedraws / frames))
        append('\n')
        append("  background-only: ").append(backgroundFrames)
        append(String.format(" (%.0f/min)", backgroundFrames / mins)).append('\n')
        append("keystrokes: ").append(keystrokes)
        append("   gestures: ").append(gestures).append('\n')
        append("layer rebuilds: ").append(layerRebuilds).append('\n')
        append(String.format("window: %.1f min", mins))
    }
}
