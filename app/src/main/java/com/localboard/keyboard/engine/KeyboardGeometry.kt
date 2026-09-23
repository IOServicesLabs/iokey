package com.localboard.keyboard.engine

import android.graphics.PointF
import android.graphics.RectF

/**
 * Spatial model of the currently visible keyboard: where each character
 * key lives on screen. Shared by the swipe decoder and touch dispatch.
 *
 * PUBLISHED, NOT MUTATED. The maps a reader sees are never the maps a layout
 * pass is writing. Only the UI thread lays out, but three other places read:
 * the swipe table builder on its own daemon thread, the live glide preview on
 * the IO dispatcher, and the spatial corrector on the same dispatcher. The
 * previous shape — one HashMap, cleared and refilled in place — meant any of
 * them could observe the board half-erased. For the table builder that was not
 * a transient glitch: a letter missing at that instant drops EVERY word
 * containing it from the glide dictionary, and the result is then cached.
 *
 * [register] therefore writes to a staging map and [finishLayout] publishes it
 * in one volatile assignment, so a reader sees either the whole old board or
 * the whole new one.
 */
class KeyboardGeometry {

    @Volatile private var centers: Map<Char, PointF> = emptyMap()
    @Volatile private var bounds: Map<Char, RectF> = emptyMap()

    /** char -> center point. Read-only: replaced wholesale by [finishLayout]. */
    val keyCenters: Map<Char, PointF> get() = centers

    /** char -> full bounds. Read-only: replaced wholesale by [finishLayout]. */
    val keyBounds: Map<Char, RectF> get() = bounds

    /** Written only between [clear] and [finishLayout], only by the layout pass. */
    private var stagingCenters = HashMap<Char, PointF>(32)
    private var stagingBounds = HashMap<Char, RectF>(32)

    @Volatile
    var averageKeyWidth = 0f
        private set

    @Volatile
    var keyHeight = 0f
        private set

    /**
     * Bumped whenever the letter positions actually move.
     *
     * The swipe decoder builds a table of every dictionary word's ideal curve,
     * expressed in key-width units against the centres it read at build time,
     * and that table is far too expensive to rebuild per gesture. It therefore
     * has to know when the board underneath it has changed — and it cannot
     * find out by holding a reference, because this object is the same object
     * across every layout. This counter is that signal.
     *
     * It moves only on a REAL change. [finishLayout] runs on every layout
     * pass, including the many that reproduce byte-identical centres (a
     * symbols/letters toggle, an inset pass, re-applying the same prefs on
     * each field focus), and a bare increment there would schedule a walk over
     * the whole dictionary several times per field focus for nothing.
     */
    @Volatile
    var layoutVersion = 0
        private set

    /** Content hash of the letter centres behind [layoutVersion]. */
    private var layoutHash = 0

    fun clear() {
        stagingCenters = HashMap(32)
        stagingBounds = HashMap(32)
    }

    fun register(char: Char, bounds: RectF) {
        stagingBounds[char] = RectF(bounds)
        stagingCenters[char] = PointF(bounds.centerX(), bounds.centerY())
    }

    fun finishLayout() {
        val newCenters = stagingCenters
        val newBounds = stagingBounds
        val avgW = if (newBounds.isEmpty()) 0f
        else newBounds.values.sumOf { it.width().toDouble() }.toFloat() / newBounds.size

        // Metrics before the maps: a reader that has already picked up the new
        // centres must not still be dividing by the old key width.
        averageKeyWidth = avgW
        keyHeight = newBounds.values.firstOrNull()?.height() ?: 0f
        centers = newCenters
        bounds = newBounds

        // A layout with no letters at all — the number pad, the symbols page —
        // is not a new board to decode against, it is the absence of one.
        // Bumping here would invalidate a perfectly good table and ask for a
        // replacement that cannot be built, every time the user presses ?123.
        if (newCenters.isEmpty()) return
        var h = avgW.toRawBits()
        for ((c, p) in newCenters) {
            // Order-independent, so the map's iteration order cannot pass for
            // a layout change.
            h = h xor (c.code * 31 + p.x.toRawBits() * 7 + p.y.toRawBits() * 13)
        }
        if (h != layoutHash) {
            layoutHash = h
            layoutVersion++
        }
    }

    fun charAt(x: Float, y: Float): Char? {
        for ((c, r) in bounds) {
            if (r.contains(x, y)) return c
        }
        return null
    }

    fun nearestChar(x: Float, y: Float): Char? {
        var best: Char? = null
        var bestDist = Float.MAX_VALUE
        for ((c, p) in centers) {
            val d = (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y)
            if (d < bestDist) {
                bestDist = d
                best = c
            }
        }
        return best
    }

    fun squaredDistance(c: Char, x: Float, y: Float): Float {
        val p = centers[c] ?: return Float.MAX_VALUE
        val dx = p.x - x
        val dy = p.y - y
        return dx * dx + dy * dy
    }

    fun hasLetters(): Boolean = centers.isNotEmpty()
}
