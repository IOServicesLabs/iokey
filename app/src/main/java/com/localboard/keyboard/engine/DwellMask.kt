package com.localboard.keyboard.engine

import kotlin.math.sqrt

/**
 * Which letters a glide paused on, worked out from the raw touch samples: 96 ms in which the
 * finger travelled under a quarter of a key, away from the ends of the stroke, on a key. A pause
 * is how a doubled letter shows in a trace, since the path itself goes over the key only once.
 *
 * Plain arrays and no Android types, so the view, the offline simulator and a plain JVM test all
 * run this one copy. Nothing is allocated, because the view asks for it several times a second
 * while a finger is moving.
 */
object DwellMask {

    private const val WINDOW_MS = 96L
    private const val MIN_WINDOW_MS = WINDOW_MS * 0.8f
    private const val MIN_SAMPLES = 8
    private const val ARC_START = 0.08f
    private const val ARC_END = 0.92f
    private const val STILL_KEYS = 0.25f
    private const val ON_KEY_KEYS = 0.75f

    /**
     * A mask with bit c-'a' set for each letter paused on. [xs], [ys] and [ts] hold [n] samples
     * in time order, [ts] in ms. [keyX] and [keyY] hold 26 key centres, NaN for a letter this
     * board does not have.
     *
     * The window keeps the sample from before a gap. A digitizer can go quiet on a still finger,
     * and a window that dropped everything older than itself would then shrink to the one new
     * sample and never see the pause that caused the gap.
     */
    fun of(
        xs: FloatArray,
        ys: FloatArray,
        ts: LongArray,
        n: Int,
        keyW: Float,
        keyX: FloatArray,
        keyY: FloatArray
    ): Int {
        if (n < MIN_SAMPLES) return 0
        var total = 0f
        for (i in 1 until n) total += step(xs, ys, i)
        if (total <= 0f) return 0
        val still = STILL_KEYS * keyW
        val onKey = (ON_KEY_KEYS * keyW) * (ON_KEY_KEYS * keyW)
        var mask = 0
        var j = 0
        var travelToI = 0f
        var travelToJ = 0f
        for (i in 1 until n) {
            travelToI += step(xs, ys, i)
            while (ts[i] - ts[j + 1] >= WINDOW_MS) {
                j++
                travelToJ += step(xs, ys, j)
            }
            if (ts[i] - ts[j] < MIN_WINDOW_MS) continue
            if (travelToI < total * ARC_START || travelToI > total * ARC_END) continue
            if (travelToI - travelToJ > still) continue
            var best = -1
            var bestD = Float.MAX_VALUE
            for (c in 0 until 26) {
                val dx = keyX[c] - xs[i]
                val dy = keyY[c] - ys[i]
                val d = dx * dx + dy * dy
                // A NaN centre fails this, which is how a missing key stays out.
                if (d < bestD) {
                    bestD = d
                    best = c
                }
            }
            if (best >= 0 && bestD <= onKey) mask = mask or (1 shl best)
        }
        return mask
    }

    private fun step(xs: FloatArray, ys: FloatArray, i: Int): Float {
        val dx = xs[i] - xs[i - 1]
        val dy = ys[i] - ys[i - 1]
        return sqrt(dx * dx + dy * dy)
    }
}
