package com.localboard.keyboard.touch

import com.localboard.keyboard.engine.TouchCheck
import com.localboard.keyboard.engine.TouchModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class TouchCheckTest {

    private val d = DeviceLayout.DENSITY
    private val centre: Map<Char, Pair<Float, Float>> =
        DeviceLayout.hitRows().flatten().filter { it.scorable }.associate { it.symbol to (it.cx to it.cy) }

    /** A tap on the middle of [c], moved by the offset. Separators carry NaN, as they
     *  will in the real session. */
    private fun tap(c: Char, dxDp: Float = 0f, dyDp: Float = 0f): TouchCheck.Tap {
        if (c == ' ') return TouchCheck.Tap(c, Float.NaN, Float.NaN)
        val p = centre.getValue(c.lowercaseChar())
        return TouchCheck.Tap(c, p.first + dxDp * d, p.second + dyDp * d)
    }

    /** A tap aimed at [wanted] that strayed over the boundary and typed [typed]. */
    private fun slip(wanted: Char, typed: Char): TouchCheck.Tap {
        val p = centre.getValue(wanted)
        return TouchCheck.Tap(typed, p.first - 20f * d, p.second)
    }

    private fun analyse(taps: List<TouchCheck.Tap>, target: String) =
        TouchCheck.analyse(taps, target, d) { centre[it] }

    @Test
    fun `a substitution lines up with the letter asked for`() {
        val (map, distance) = TouchCheck.align("thw quick", "the quick")
        assertEquals(1, distance)
        assertEquals(2, map[2])
        assertEquals(8, map[8])
    }

    @Test
    fun `an inserted tap lines up with nothing`() {
        val (map, distance) = TouchCheck.align("thee", "the")
        assertEquals(1, distance)
        assertEquals(1, map.count { it == -1 })
    }

    @Test
    fun `a skipped letter does not shift what follows`() {
        val (map, distance) = TouchCheck.align("te", "the")
        assertEquals(1, distance)
        assertArrayEquals(intArrayOf(0, 2), map)
    }

    @Test
    fun `empty inputs`() {
        assertEquals(3, TouchCheck.align("", "the").second)
        assertEquals(0, TouchCheck.align("", "the").first.size)
        val (map, distance) = TouchCheck.align("the", "")
        assertEquals(3, distance)
        assertArrayEquals(intArrayOf(-1, -1, -1), map)
    }

    @Test
    fun `a mistyped tap is measured against the key the sentence asked for`() {
        val r = analyse(listOf(tap('t'), tap('h'), slip('e', 'w')), "the")
        assertEquals(1, r.errors)
        assertEquals(3, r.samples.size)
        val e = r.samples[2]
        assertEquals('e', e.letter)
        assertEquals(-20f, e.dxDp, 1e-3f)
        assertEquals(0f, e.dyDp, 1e-3f)
    }

    @Test
    fun `taps further than thirty dp are dropped`() {
        val r = analyse(listOf(tap('t', 31f, 0f), tap('h', 29f, 0f), tap('e', 22f, 22f)), "the")
        assertEquals(listOf('h'), r.samples.map { it.letter })
        assertEquals("the letters still typed correctly", 0, r.errors)
    }

    @Test
    fun `an unaligned tap and a separator are not measured`() {
        val r = analyse(listOf(tap('t'), tap('h'), tap('e'), tap(' '), tap('e'), tap('x')), "the e")
        assertEquals("the e".filter { it != ' ' }.length, r.samples.size)
    }

    @Test
    fun `a space lined up with a letter is not measured`() {
        val r = analyse(listOf(tap('t'), tap(' '), tap('e')), "the")
        assertEquals(listOf('t', 'e'), r.samples.map { it.letter })
        assertEquals(1, r.errors)
        assertTrue(r.samples.all { it.dxDp.isFinite() && it.dyDp.isFinite() })
    }

    @Test
    fun `a capital from auto caps is not a mistake`() {
        val r = analyse(listOf(tap('T'), tap('h'), tap('e')), "the")
        assertEquals(0, r.errors)
        assertEquals(1f, r.accuracy, 0f)
        assertEquals(listOf('t', 'h', 'e'), r.samples.map { it.letter })
    }

    @Test
    fun `accuracy`() {
        val r = analyse("thw".map { tap(it) }, "the")
        assertEquals(2f / 3f, r.accuracy, 1e-5f)
        assertEquals("an empty target has no accuracy", 0f, analyse(emptyList(), "").accuracy, 0f)
        val worse = analyse("zzzzzzzzzz".map { tap(it) }, "the")
        assertEquals("never negative", 0f, worse.accuracy, 0f)
    }

    @Test
    fun `confused letters are counted most frequent first`() {
        val taps = "th".map { tap(it) } + slip('e', 'w') + tap(' ') +
            "th".map { tap(it) } + slip('e', 'w') + tap(' ') +
            "th".map { tap(it) } + slip('e', 'r')
        val r = analyse(taps, "the the the")
        assertEquals(listOf(Triple('e', 'w', 2), Triple('e', 'r', 1)), r.confusions.map {
            Triple(it.wanted, it.typed, it.count)
        })
    }

    @Test
    fun `a slip of attention is not a confusion`() {
        val r = analyse(listOf(tap('t'), tap('h'), tap('p')), "the")
        assertEquals(1, r.errors)
        assertEquals(2, r.samples.size)
        assertTrue(r.confusions.isEmpty())
    }

    @Test
    fun `row bias with too few samples says nothing`() {
        val rows = TouchCheck.rowBias(listOf(TouchModel.Sample('q', 5f, 5f)), DeviceLayout.LETTER_ROWS)
        assertEquals(3, rows.size)
        assertEquals(1, rows[0].n)
        assertEquals(0f, rows[0].meanYDp, 0f)
        assertEquals(0f, rows[0].seYDp, 0f)
        assertEquals(0, rows[1].n)
    }

    @Test
    fun `row bias mean and standard error`() {
        val samples = listOf(
            TouchModel.Sample('q', 1f, 2f),
            TouchModel.Sample('w', 3f, 2f),
            TouchModel.Sample('e', 5f, 8f),
            TouchModel.Sample('a', 9f, 9f)
        )
        val top = TouchCheck.rowBias(samples, DeviceLayout.LETTER_ROWS)[0]
        assertEquals(3, top.n)
        assertEquals(3f, top.meanXDp, 1e-4f)
        assertEquals(4f, top.meanYDp, 1e-4f)
        // Sample variance of 1,3,5 is 4, so the standard error is sqrt(4/3).
        assertEquals(1.1547f, top.seXDp, 1e-3f)
    }

    @Test
    fun `the sentences reach every letter and fill each row`() {
        val letters = TouchCheck.SENTENCES.joinToString("").filter { it in 'a'..'z' }
        assertEquals(180, letters.length)
        val counts = letters.groupingBy { it }.eachCount()
        for (c in 'a'..'z') assertTrue("$c appears ${counts[c]} times", (counts[c] ?: 0) >= 2)
        val rowTotals = DeviceLayout.LETTER_ROWS.map { row -> row.sumOf { counts[it] ?: 0 } }
        assertEquals(listOf(87, 61, 32), rowTotals)
    }

    /** What four sentences can promise at sigma 9: a row's bias to about a dp, no finer. */
    @Test
    fun `the check can state a row's bias to about a dp`() {
        val target = TouchCheck.SENTENCES.joinToString(" ")
        val rng = Random(9)
        val trueX = -3f
        val trueY = 4f
        var errY = 0.0
        var count = 0
        repeat(200) {
            val taps = target.map { c ->
                val p = centre.getValue(c)
                TouchCheck.Tap(
                    c,
                    p.first + (trueX + rng.nextGaussian().toFloat() * 9f) * d,
                    p.second + (trueY + rng.nextGaussian().toFloat() * 9f) * d
                )
            }
            val r = analyse(taps, target)
            for (rb in TouchCheck.rowBias(r.samples, DeviceLayout.LETTER_ROWS)) {
                errY += abs(rb.meanYDp - trueY)
                count++
            }
        }
        assertTrue("average error ${errY / count}", errY / count < 1.5)
    }
}
