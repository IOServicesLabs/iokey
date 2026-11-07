package com.localboard.keyboard.touch

import com.localboard.keyboard.engine.TouchModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class TouchModelTest {

    private val rows = DeviceLayout.LETTER_ROWS
    private fun model(store: MemStore = MemStore()) = TouchModel(store, rows)
    private fun offsets(m: TouchModel) = ('a'..'z').map { m.offsetXDp(it) to m.offsetYDp(it) }

    /** Both axes of the published estimator, worked by hand on a tiny board. */
    @Test
    fun `the estimator is the published formula`() {
        val m = model()
        repeat(8) { m.observe('k', 3f, -2f) }
        repeat(4) { m.observe('j', 6f, 1f) }
        // Row sum 8*3 + 4*6 = 48 over n 12, so rowMean = 48 / (12 + 30).
        val rowMeanX = 48f / 42f
        assertEquals((24f + 12f * rowMeanX) / 20f, m.offsetXDp('k'), 1e-4f)
        assertEquals((24f + 12f * rowMeanX) / 16f, m.offsetXDp('j'), 1e-4f)
        // A key in that row with no taps takes the shrunk row mean itself.
        assertEquals(rowMeanX, m.offsetXDp('l'), 1e-4f)
        // Row y sum 8*-2 + 4*1 = -12.
        val rowMeanY = -12f / 42f
        assertEquals((-16f + 12f * rowMeanY) / 20f, m.offsetYDp('k'), 1e-4f)
        // Other rows saw nothing.
        assertEquals(0f, m.offsetXDp('q'), 0f)
        assertEquals(0f, m.offsetYDp('z'), 0f)
    }

    @Test
    fun `clear zeroes everything and wipes storage`() {
        val s = MemStore()
        val m = model(s)
        repeat(50) { m.observe('k', 6f, 6f) }
        m.persist()
        assertTrue(s.value.isNotEmpty())
        m.clear()
        assertEquals(0f, m.offsetXDp('k'), 0f)
        assertEquals(0f, m.offsetYDp('k'), 0f)
        assertTrue(s.value.isEmpty())
        m.observe('a', 0f, 0f)
        assertEquals("a reused key does not inherit", 0f, m.offsetXDp('a'), 0f)
        assertEquals(0f, m.offsetXDp('k'), 0f)
    }

    /** The offsets are cached. Reading them first makes sure the cache holds something
     *  for a clear to leave behind. */
    @Test
    fun `clear zeroes offsets that were already computed`() {
        val m = model()
        repeat(50) { m.observe('k', 6f, 6f) }
        assertTrue(m.offsetXDp('k') != 0f)
        assertTrue(m.offsetYDp('k') != 0f)
        m.clear()
        assertEquals(0f, m.offsetXDp('k'), 0f)
        assertEquals(0f, m.offsetYDp('k'), 0f)
        assertEquals(0f, m.largestOffsetDp(), 0f)
        assertEquals(0, m.liveTapCount())
    }

    @Test
    fun `nothing cleared comes back on the next tap`() {
        val m = model()
        repeat(50) { m.observe('k', 6f, 6f) }
        m.setCalibration(calibrationRun(3))
        m.clear()
        m.observe('a', 0f, 0f)
        for ((x, y) in offsets(m)) {
            assertEquals(0f, x, 0f)
            assertEquals(0f, y, 0f)
        }
    }

    @Test
    fun `the cap holds under an extreme stream`() {
        val m = model()
        repeat(5000) { m.observe('h', 40f, -40f) }
        assertEquals(4f, m.offsetXDp('h'), 0f)
        assertEquals(-5f, m.offsetYDp('h'), 0f)
    }

    @Test
    fun `samples are clipped to fifteen dp`() {
        val clipped = model()
        repeat(3) { clipped.observe('h', 200f, -200f) }
        val atLimit = model()
        repeat(3) { atLimit.observe('h', 15f, -15f) }
        assertEquals(atLimit.offsetXDp('h'), clipped.offsetXDp('h'), 0f)
        assertEquals(atLimit.offsetYDp('h'), clipped.offsetYDp('h'), 0f)
    }

    @Test
    fun `two taps on an empty board stay small`() {
        val m = model()
        m.observe('z', 12f, -9f)
        m.observe('z', 12f, -9f)
        assertTrue("got ${m.offsetXDp('z')}", m.offsetXDp('z') / 12f < 0.25f)
    }

    @Test
    fun `two outlier taps in a well populated row stay small`() {
        val m = model()
        repeat(60) {
            m.observe('a', 0f, 0f)
            m.observe('s', 0f, 0f)
            m.observe('d', 0f, 0f)
        }
        m.observe('g', 15f, 15f)
        m.observe('g', 15f, 15f)
        assertTrue("got ${m.offsetXDp('g')}", abs(m.offsetXDp('g')) < 2.5f)
    }

    @Test
    fun `a key is halved at four hundred`() {
        val s = MemStore()
        val m = model(s)
        repeat(399) { m.observe('e', 2f, -1f) }
        m.persist()
        assertEquals("v2\ne:399.00,798.00,-399.00\n", s.value)
        m.observe('e', 2f, -1f)
        m.persist()
        assertEquals("v2\ne:200.00,400.00,-200.00\n", s.value)
    }

    @Test
    fun `a changed habit wins within eight hundred taps`() {
        val m = model()
        repeat(2000) { m.observe('e', 6f, 6f) }
        assertEquals(4f, m.offsetXDp('e'), 0f)
        repeat(800) { m.observe('e', -3f, -3f) }
        assertTrue("got ${m.offsetXDp('e')}", m.offsetXDp('e') < 0f)
    }

    @Test
    fun `letters only and case folded`() {
        val s = MemStore()
        val m = model(s)
        m.observe('1', 9f, 9f)
        m.observe(' ', 9f, 9f)
        m.observe(';', 9f, 9f)
        m.observe('é', 9f, 9f)
        m.persist()
        assertEquals("nothing was recorded", "", s.value)
        assertEquals(0f, m.offsetXDp('1'), 0f)
        m.observe('K', 3f, 3f)
        val lower = model()
        lower.observe('k', 3f, 3f)
        assertEquals(lower.offsetXDp('k'), m.offsetXDp('K'), 0f)
        assertEquals(lower.offsetXDp('k'), m.offsetXDp('k'), 0f)
    }

    /** The trace holds a NaN point for an apostrophe, so one can reach this door. */
    @Test
    fun `a non finite sample cannot poison the model`() {
        val s = MemStore()
        val m = model(s)
        m.observe('k', Float.NaN, 3f)
        m.observe('k', 3f, Float.POSITIVE_INFINITY)
        m.setCalibration(listOf(TouchModel.Sample('j', Float.NaN, 1f)))
        assertEquals(0f, m.offsetXDp('k'), 0f)
        assertEquals(0f, m.offsetYDp('k'), 0f)
        assertEquals(0f, m.offsetXDp('j'), 0f)
        assertEquals(0, m.liveTapCount())
        m.observe('k', 3f, 3f)
        assertTrue(m.offsetXDp('k').isFinite())
        assertTrue(m.offsetXDp('k') > 0f)
    }

    @Test
    fun `the same calibration twice changes nothing`() {
        val samples = calibrationRun(5)
        val m = model()
        m.setCalibration(samples)
        val first = offsets(m)
        repeat(5) { m.setCalibration(samples) }
        assertEquals(first, offsets(m))
    }

    @Test
    fun `a different run replaces and never stacks`() {
        val samples = calibrationRun(5)
        val m = model()
        m.setCalibration(samples)
        val first = offsets(m)
        m.setCalibration(samples.map { TouchModel.Sample(it.letter, -it.dxDp, -it.dyDp) })
        m.setCalibration(samples)
        assertEquals(first, offsets(m))
    }

    @Test
    fun `a calibration alone cannot reach the cap`() {
        val m = model()
        m.setCalibration(calibrationRun(5))
        val largest = offsets(m).maxOf { maxOf(abs(it.first), abs(it.second)) }
        assertTrue("largest offset $largest", largest < 4f)
    }

    /**
     * The worst case for weight 0.25: every tap of the real sentences measures the same
     * bias, with no noise to cancel. The most frequent letter still stops short of the
     * cap, so a single calibration can never decide an offset alone.
     */
    @Test
    fun `the real sentences measuring one bias stop short of the cap`() {
        val letters = com.localboard.keyboard.engine.TouchCheck.SENTENCES
            .joinToString("").filter { it in 'a'..'z' }
        val m = model()
        m.setCalibration(letters.map { TouchModel.Sample(it, 4f, 6f) })
        val xs = offsets(m).map { it.first }
        val ys = offsets(m).map { it.second }
        assertEquals(3.86f, ys.max(), 0.05f)
        assertTrue("x ${xs.max()}", xs.max() < 4f)
        assertTrue("y ${ys.max()}", ys.max() < 5f)
    }

    @Test
    fun `persist then load round trips`() {
        val s = MemStore()
        val m = model(s)
        val rng = Random(6)
        repeat(4000) {
            m.observe(
                'a' + rng.nextInt(26),
                rng.nextGaussian().toFloat() * 9f + 3f,
                rng.nextGaussian().toFloat() * 9f + 4f
            )
        }
        m.setCalibration(calibrationRun(7))
        m.persist()
        val again = model(s)
        for (c in 'a'..'z') {
            assertEquals(m.offsetXDp(c), again.offsetXDp(c), 0.02f)
            assertEquals(m.offsetYDp(c), again.offsetYDp(c), 0.02f)
        }
        assertEquals(m.liveTapCount(), again.liveTapCount())
    }

    /** The check ends and the user may leave at once, so this write does not wait. */
    @Test
    fun `a calibration reaches storage without being asked`() {
        val s = MemStore()
        val first = model(s)
        first.setCalibration(calibrationRun(8))
        val again = model(s)
        for (c in 'a'..'z') assertEquals(first.offsetXDp(c), again.offsetXDp(c), 0.02f)
        assertTrue(offsets(again).any { it.first != 0f })
    }

    @Test
    fun `persist writes only when something changed`() {
        var writes = 0
        val s = object : TouchModel.Store {
            var v = ""
            override fun read() = v
            override fun write(value: String) {
                writes++
                v = value
            }
        }
        val m = TouchModel(s, rows)
        m.persist()
        assertEquals(0, writes)
        m.observe('a', 1f, 1f)
        m.persist()
        m.persist()
        assertEquals(1, writes)
    }

    @Test
    fun `corrupt storage loads as empty`() {
        val corrupt = listOf(
            "garbage;;:::\nx\ny",
            "v2",
            "v2\n",
            "v3\na:1.00,1.00,1.00\n",
            "v2\nzz:1,1,1;a:1;b:x,y,z;c:NaN,1,1;d:1,Infinity,1;e:-4,1,1;\n",
            "\n\n\n\n"
        )
        for (text in corrupt) {
            val m = model(MemStore(text))
            for (c in 'a'..'z') {
                assertEquals("'$text' $c", 0f, m.offsetXDp(c), 0f)
                assertEquals("'$text' $c", 0f, m.offsetYDp(c), 0f)
            }
            assertEquals(0, m.liveTapCount())
        }
    }

    @Test
    fun `the old tap model format is ignored not misread`() {
        val m = model(MemStore("a:12.0,-9.0;b:1.0,1.0"))
        assertEquals(0f, m.offsetXDp('a'), 0f)
        assertEquals(0f, m.offsetYDp('a'), 0f)
    }

    @Test
    fun `a layout with punctuation in its rows works`() {
        val dvorak = TouchModel(MemStore(), listOf("',.pyfgcrl", "aoeuidhtns", ";qjkxbmwvz"))
        repeat(10) { dvorak.observe('p', 5f, 5f) }
        assertTrue(dvorak.offsetXDp('y') > 0f)
        assertEquals(0f, dvorak.offsetXDp('a'), 0f)
    }

    @Test
    fun `status line numbers`() {
        val m = model()
        assertEquals(0, m.liveTapCount())
        assertEquals(0f, m.largestOffsetDp(), 0f)
        repeat(1000) { m.observe('h', 3f, 4f) }
        repeat(30) { m.observe('z', 1f, 1f) }
        // h halves at taps 400, 600, 800 and 1000, so 200 of its taps still count.
        assertEquals(200 + 30, m.liveTapCount())
        assertEquals(5f, m.largestOffsetDp(), 0.1f)
    }

    private fun calibrationRun(seed: Long): List<TouchModel.Sample> {
        val rng = Random(seed)
        return (0 until 180).map {
            TouchModel.Sample(
                'a' + rng.nextInt(26),
                4f + rng.nextGaussian().toFloat() * 7f,
                6f + rng.nextGaussian().toFloat() * 7f
            )
        }
    }
}
