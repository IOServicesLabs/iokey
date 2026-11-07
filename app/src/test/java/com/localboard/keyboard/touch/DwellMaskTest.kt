package com.localboard.keyboard.touch

import com.localboard.keyboard.engine.DwellMask
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pause on a doubled letter, on plain arrays. Ten keys 100 px apart along y = 50, a for the
 * first and j for the last, and the finger moves along them at the speed of an ordinary glide.
 */
class DwellMaskTest {

    private val keyW = 100f
    private val keyX = FloatArray(26) { if (it < 10) 50f + 100f * it else Float.NaN }
    private val keyY = FloatArray(26) { if (it < 10) 50f else Float.NaN }

    private fun bit(c: Char) = 1 shl (c - 'a')

    /** A trace on a 120 Hz clock. */
    private class Trace(x: Float, y: Float) {
        val xs = arrayListOf(x)
        val ys = arrayListOf(y)
        val ts = arrayListOf(0L)
        private var at = 0.0

        private fun add(x: Float, y: Float) {
            xs += x
            ys += y
            ts += at.toLong()
        }

        /** At 100 px every 28 ms, which is about what the simulator's fingers do between letters. */
        fun moveTo(x: Float, y: Float): Trace {
            val fromX = xs.last()
            val fromY = ys.last()
            val dist = kotlin.math.hypot(x - fromX, y - fromY)
            val steps = (dist / 100f * 28f / DT).toInt().coerceAtLeast(1)
            for (s in 1..steps) {
                at += DT
                add(fromX + (x - fromX) * s / steps, fromY + (y - fromY) * s / steps)
            }
            return this
        }

        /** A finger still reporting while it rests, with the jitter of a real one. */
        fun rest(ms: Int): Trace {
            val x = xs.last()
            val y = ys.last()
            repeat((ms / DT).toInt()) { k ->
                at += DT
                add(x + if (k % 2 == 0) 0.6f else -0.6f, y)
            }
            return this
        }

        /** A digitizer gone quiet: the next sample is simply [ms] later, by default on a still finger. */
        fun gap(ms: Int, x: Float = xs.last(), y: Float = ys.last()): Trace {
            at += ms
            add(x, y)
            return this
        }
    }

    private fun Trace.mask(x: FloatArray = keyX, y: FloatArray = keyY): Int =
        DwellMask.of(xs.toFloatArray(), ys.toFloatArray(), ts.toLongArray(), xs.size, keyW, x, y)

    @Test
    fun `a pause on a key sets that bit`() {
        val t = Trace(50f, 50f).moveTo(450f, 50f).rest(140).moveTo(950f, 50f)
        assertEquals(bit('e'), t.mask())
    }

    @Test
    fun `two pauses set two bits`() {
        val t = Trace(50f, 50f).moveTo(350f, 50f).rest(140).moveTo(650f, 50f).rest(140).moveTo(950f, 50f)
        assertEquals(bit('d') or bit('g'), t.mask())
    }

    @Test
    fun `no pause gives nothing`() {
        assertEquals(0, Trace(50f, 50f).moveTo(950f, 50f).mask())
    }

    @Test
    fun `a pause in the first 8 percent of the arc gives nothing`() {
        assertEquals(0, Trace(50f, 50f).moveTo(90f, 50f).rest(140).moveTo(950f, 50f).mask())
    }

    @Test
    fun `a pause in the last 8 percent of the arc gives nothing`() {
        assertEquals(0, Trace(50f, 50f).moveTo(910f, 50f).rest(140).moveTo(950f, 50f).mask())
    }

    @Test
    fun `a pause over three quarters of a key from any key gives nothing`() {
        // One key only, so the distance is plain: 70 px from it counts and 80 px does not.
        val onlyE = FloatArray(26) { if (it == 4) 450f else Float.NaN }
        val onlyEY = FloatArray(26) { if (it == 4) 50f else Float.NaN }
        val near = Trace(50f, 120f).moveTo(450f, 120f).rest(140).moveTo(950f, 120f)
        assertEquals(bit('e'), near.mask(onlyE, onlyEY))
        val far = Trace(50f, 130f).moveTo(450f, 130f).rest(140).moveTo(950f, 130f)
        assertEquals(0, far.mask(onlyE, onlyEY))
    }

    @Test
    fun `a pause with its samples dropped still sets the bit`() {
        val t = Trace(50f, 50f).moveTo(450f, 50f).gap(140).moveTo(950f, 50f)
        assertEquals(bit('e'), t.mask())
    }

    @Test
    fun `a gap with the finger moving across it is not a pause`() {
        val t = Trace(50f, 50f).moveTo(350f, 50f).gap(140, 550f, 50f).moveTo(950f, 50f)
        assertEquals(0, t.mask())
    }

    @Test
    fun `under 8 samples gives nothing`() {
        // What would count as a pause on e with one sample more.
        val t = Trace(50f, 50f).gap(40, 450f, 50f).gap(40).gap(40).gap(40).gap(40, 950f, 50f).gap(40)
        assertEquals(7, t.xs.size)
        assertEquals(0, t.mask())
        assertEquals(bit('e'), t.gap(40).mask())
    }

    @Test
    fun `a finger that never moved gives nothing`() {
        val t = Trace(450f, 50f).gap(40).gap(40).gap(40).gap(40).gap(40).gap(40).gap(40).gap(40)
        assertEquals(0, t.mask())
    }

    private companion object {
        const val DT = 1000.0 / 120.0
    }
}
