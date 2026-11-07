package com.localboard.keyboard.engine

import kotlin.math.abs
import kotlin.math.ln

/**
 * One key as drawn, in px. [symbol] is 'a'..'z' or ' ' when the scorer may choose
 * this key, and [NOT_SCORABLE] when it may not: functional keys, digits, punctuation.
 */
class HitKey(
    val symbol: Char,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val scorable: Boolean get() = symbol == ' ' || symbol in 'a'..'z'

    companion object {
        const val NOT_SCORABLE = '\u0000'

        /**
         * The symbol for a key of the keyboard model: ' ' for the space bar, the
         * lowercase letter for a key that types one a..z, and [NOT_SCORABLE] for
         * everything else. Comma, period and an accented letter all end up not
         * scorable, so nothing can talk a tap onto or off them.
         */
        fun symbolFor(isSpace: Boolean, isChar: Boolean, output: String): Char {
            if (isSpace) return ' '
            if (!isChar || output.length != 1) return NOT_SCORABLE
            val c = output[0].lowercaseChar()
            return if (c in 'a'..'z') c else NOT_SCORABLE
        }
    }
}

/**
 * The static tessellation of the key grid. Every point between [gridLeft] and
 * [gridRight] belongs to exactly one key: boundaries sit midway between neighbours,
 * and the outermost key of a row or the outermost row owns everything out to the
 * edge. Outside those bounds (the one-handed gutter) there is no key.
 *
 * Pass the view's own edge wherever the side padding runs out at the screen's edge, so
 * that padding belongs to the edge keys: both sides at full width, the docked side in
 * one-handed mode. Pass the grid's own edge on the side facing the gutter.
 */
class HitMap(
    rows: List<List<HitKey>>,
    private val gridLeft: Float,
    private val gridRight: Float
) {
    /** Row order, then left to right: the same order as KeyboardView.keyRects. */
    val keys: Array<HitKey> = rows.flatten().toTypedArray()

    private val rowFirst: IntArray
    private val rowLast: IntArray
    private val rowBelow: FloatArray
    private val rightEdge = FloatArray(keys.size)

    init {
        // A row with no keys contributes nothing to keys, so it must not take part
        // in the boundaries either: one would index past the end of it.
        val filled = rows.filter { it.isNotEmpty() }
        rowFirst = IntArray(filled.size)
        rowLast = IntArray(filled.size)
        rowBelow = FloatArray(filled.size)
        var k = 0
        for ((r, row) in filled.withIndex()) {
            rowFirst[r] = k
            rowLast[r] = k + row.size - 1
            rowBelow[r] =
                if (r + 1 < filled.size) (row[0].bottom + filled[r + 1][0].top) / 2f
                else Float.POSITIVE_INFINITY
            for ((j, key) in row.withIndex()) {
                rightEdge[k] =
                    if (j + 1 < row.size) (key.right + row[j + 1].left) / 2f
                    else Float.POSITIVE_INFINITY
                k++
            }
        }
    }

    /** The index into [keys], or -1 outside the grid. */
    fun keyAt(x: Float, y: Float): Int {
        // Written so that a NaN fails the test and reports no key.
        if (keys.isEmpty() || !(x >= gridLeft && x < gridRight) || y.isNaN()) return -1
        var r = 0
        while (r < rowBelow.size - 1 && y >= rowBelow[r]) r++
        var i = rowFirst[r]
        val last = rowLast[r]
        while (i < last && x >= rightEdge[i]) i++
        return i
    }
}

/**
 * Decides which key a touch-down means.
 *
 *  1. A functional key always wins on its own cell of the [HitMap].
 *  2. The centre of a key, [anchorX] of its width by [anchorY] of its height, always
 *     types that key, whatever the language model thinks.
 *  3. Everything else is scored as a Gaussian touch likelihood around each key's
 *     centre, moved by the learned offset, times the probability of that symbol
 *     coming next. With no prior and no offsets, step 3 is skipped and the tap
 *     behaves exactly like the [HitMap].
 *
 * Offsets only ever act here, on where a tap is judged to have been aimed. Nothing
 * in this class can change where a key is drawn.
 *
 * Allocates nothing per tap, once constructed. Main thread only: the prior buffer
 * is shared between calls.
 */
class TapResolver(
    private val map: HitMap,
    private val density: Float,
    sigmaXDp: Float = 8f,
    sigmaYDp: Float = 9f,
    private val anchorX: Float = 0.50f,
    private val anchorY: Float = 0.60f,
    private val eps: Float = 0.10f
) {
    /** Fills out[0..25] for a..z and out[26] for space. False means no opinion. */
    fun interface Prior {
        fun fill(out: FloatArray): Boolean
    }

    private val twoSx2 = 2f * (sigmaXDp * density) * (sigmaXDp * density)
    private val twoSy2 = 2f * (sigmaYDp * density) * (sigmaYDp * density)
    private val scorable: IntArray =
        map.keys.indices.filter { map.keys[it].scorable }.toIntArray()
    private val symbolIndex = IntArray(map.keys.size) {
        val s = map.keys[it].symbol
        if (s == ' ') 26 else s - 'a'
    }
    private val standardWidth: Float = run {
        val w = map.keys.filter { it.symbol in 'a'..'z' }.map { it.width }.sorted()
        if (w.isEmpty()) 0f else w[w.size / 2]
    }
    /** Half-length of the flat top along x: nearly zero for a letter, wide for space. */
    private val flat = FloatArray(map.keys.size) {
        ((map.keys[it].width - standardWidth) / 2f).coerceAtLeast(0f)
    }
    private val offX = FloatArray(map.keys.size)
    private val offY = FloatArray(map.keys.size)
    private var hasOffsets = false
    private val buf = FloatArray(PRIOR_SIZE)

    /** Call after the model changes and after the layout changes. Null clears. */
    fun setOffsets(model: TouchModel?) {
        hasOffsets = false
        for (i in map.keys.indices) {
            val s = map.keys[i].symbol
            if (model != null && s in 'a'..'z') {
                offX[i] = model.offsetXDp(s) * density
                offY[i] = model.offsetYDp(s) * density
                if (offX[i] != 0f || offY[i] != 0f) hasOffsets = true
            } else {
                offX[i] = 0f
                offY[i] = 0f
            }
        }
    }

    /** The key index, or -1 when the touch is outside the grid. */
    fun resolve(x: Float, y: Float, prior: Prior?): Int {
        val k0 = map.keyAt(x, y)
        if (k0 < 0) return -1
        val key = map.keys[k0]
        if (!key.scorable) return k0
        if (abs(x - key.cx) <= key.width / 2f * anchorX &&
            abs(y - key.cy) <= key.height / 2f * anchorY
        ) return k0
        val havePrior = prior != null && prior.fill(buf)
        if (!havePrior && !hasOffsets) return k0

        val maxDx = 1.3f * standardWidth
        var best = k0
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in scorable) {
            val c = map.keys[i]
            val ax = (abs(x - c.cx - offX[i]) - flat[i]).coerceAtLeast(0f)
            val ay = y - c.cy - offY[i]
            if (ax > maxDx || abs(ay) > 1.1f * c.height) continue
            var s = -(ax * ax) / twoSx2 - (ay * ay) / twoSy2
            if (havePrior) s += ln((1f - eps) * buf[symbolIndex[i]] + eps / 27f)
            if (s > bestScore) {
                bestScore = s
                best = i
            }
        }
        return best
    }

    companion object {
        const val PRIOR_SIZE = 27
    }
}

/** The same reading of the trie as FastEngine.nextCharPrior, over a trie handed in, so
 *  the prior can be exercised on a plain JVM. */
object NextCharPrior {
    /**
     * P(next symbol | the letters typed so far in this word), from the subtree sums.
     * False when the prefix is unknown: an out-of-dictionary word gets the plain
     * geometric decision for the rest of the word.
     */
    fun fill(trie: Trie, prefix: String, out: FloatArray): Boolean {
        val node = trie.node(prefix) ?: return false
        val total = node.subtree
        if (total <= 0L) return false
        out.fill(0f)
        var kept = 0L
        // Another thread can insert while this iterates. A caller whose trie is shared
        // has to catch ConcurrentModificationException and report no opinion, as the
        // engine does.
        for ((c, child) in node.children) {
            val i = c - 'a'
            if (i in 0..25) {
                out[i] = child.subtree.toFloat()
                kept += child.subtree
            }
        }
        out[26] = node.frequency.toFloat()
        kept += node.frequency
        if (kept <= 0L) return false
        val scale = 1f / kept.toFloat()
        for (i in out.indices) out[i] *= scale
        return true
    }
}
