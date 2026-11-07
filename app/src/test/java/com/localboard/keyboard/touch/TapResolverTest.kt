package com.localboard.keyboard.touch

import com.localboard.keyboard.engine.HitMap
import com.localboard.keyboard.engine.TapResolver
import com.localboard.keyboard.engine.TouchModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class TapResolverTest {

    private val d = DeviceLayout.DENSITY
    private val width = DeviceLayout.WIDTH_PX.toFloat()
    private val height = DeviceLayout.HEIGHT_PX.toFloat()
    private val rows = DeviceLayout.hitRows()
    private val map = HitMap(rows, 0f, width)
    private val keys = map.keys
    private val res = TapResolver(map, d)

    private fun letter(c: Char) = keys.indexOfFirst { it.symbol == c }
    private fun priorOn(symbol: Int) = TapResolver.Prior { out -> out.fill(0f); out[symbol] = 1f; true }
    private fun symbolIndex(c: Char) = if (c == ' ') 26 else c - 'a'

    /** All the mass on one random symbol, changed every tap. */
    @Test
    fun `an adversarial prior never overrides the anchor or a functional key`() {
        val rng = Random(11)
        val current = intArrayOf(0)
        val prior = TapResolver.Prior { out -> out.fill(0f); out[current[0]] = 1f; true }
        var deepestSideways = 0f
        var deepestVertical = 0f
        repeat(600_000) {
            val x = rng.nextFloat() * width
            val y = rng.nextFloat() * height
            current[0] = rng.nextInt(27)
            val k0 = map.keyAt(x, y)
            val r = res.resolve(x, y, prior)
            val k = keys[k0]
            if (!k.scorable) {
                assertEquals("functional key at ($x,$y)", k0, r)
            } else {
                if (abs(x - k.cx) <= k.width / 4f && abs(y - k.cy) <= k.height * 0.3f) {
                    assertEquals("anchor at ($x,$y)", k0, r)
                }
                assertTrue("only a letter or space can win", keys[r].scorable)
                if (r != k0 && k.symbol != ' ' && x >= k.left && x < k.right && y >= k.top && y < k.bottom) {
                    if (keys[r].top == k.top) {
                        deepestSideways = maxOf(deepestSideways, minOf(x - k.left, k.right - x))
                    } else {
                        deepestVertical = maxOf(deepestVertical, minOf(y - k.top, k.bottom - y))
                    }
                }
            }
        }
        val s = keys[letter('s')]
        assertTrue("a neighbour stole $deepestSideways px sideways", deepestSideways <= s.width / 4f + 0.5f)
        assertTrue("another row stole $deepestVertical px", deepestVertical <= s.height * 0.20f)
    }

    @Test
    fun `no prior and no offsets behaves exactly like the map`() {
        val rng = Random(12)
        repeat(300_000) {
            val x = rng.nextFloat() * width
            val y = rng.nextFloat() * height
            assertEquals(map.keyAt(x, y), res.resolve(x, y, null))
        }
    }

    @Test
    fun `a prior with no opinion behaves exactly like the map`() {
        val noOpinion = TapResolver.Prior { false }
        val rng = Random(13)
        repeat(300_000) {
            val x = rng.nextFloat() * width
            val y = rng.nextFloat() * height
            assertEquals(map.keyAt(x, y), res.resolve(x, y, noOpinion))
        }
    }

    @Test
    fun `outside the grid is minus one whatever the prior says`() {
        val docked = HitMap(rows, 200f, width)
        val resolver = TapResolver(docked, d)
        assertEquals(-1, resolver.resolve(100f, 300f, priorOn(0)))
    }

    @Test
    fun `the language model decides an edge tap and leaves the centre alone`() {
        val q = keys[letter('q')]
        val w = keys[letter('w')]
        // On the q face, just outside its central half, nearly at the boundary.
        val x = q.right - 2f
        val y = q.cy
        assertEquals(letter('q'), res.resolve(x, y, null))
        assertEquals(letter('w'), res.resolve(x, y, priorOn(symbolIndex('w'))))
        assertEquals(letter('q'), res.resolve(q.cx, q.cy, priorOn(symbolIndex('w'))))
        assertEquals(letter('w'), res.resolve(w.cx, w.cy, priorOn(symbolIndex('q'))))
    }

    /** Boundary shift = sigma^2 ln(r) / pitch, so 3.8 dp for a 10 to 1 prior. */
    @Test
    fun `a ten to one prior moves the boundary about four dp`() {
        val s = keys[letter('s')]
        val dd = keys[letter('d')]
        val prior = TapResolver.Prior { out ->
            out.fill(0f)
            out[symbolIndex('s')] = 10f / 11f
            out[symbolIndex('d')] = 1f / 11f
            true
        }
        val mid = (s.right + dd.left) / 2f
        var x = mid - 10f
        while (res.resolve(x, s.cy, prior) != letter('d')) x += 0.1f
        assertEquals(3.8f, (x - mid) / d, 0.25f)
    }

    /** Same formula across rows, at the x where the two keys' x terms cancel. The boundary's
     *  shift scales with the row pitch the centers sit at, so the expectation follows
     *  DeviceLayout's metrics rather than a fixed dp number. */
    @Test
    fun `a ten to one prior moves a row boundary about three dp`() {
        val w = keys[letter('w')]
        val s = keys[letter('s')]
        val prior = TapResolver.Prior { out ->
            out.fill(0f)
            out[symbolIndex('w')] = 10f / 11f
            out[symbolIndex('s')] = 1f / 11f
            true
        }
        val x = (w.cx + s.cx) / 2f
        val mid = (w.cy + s.cy) / 2f
        var y = mid - 10f
        while (res.resolve(x, y, prior) != letter('s')) y += 0.1f
        // ln(10) * sigma^2 / pitch, with the same sigma in dp as the horizontal
        // case above; the pitch is DeviceLayout's keyHeight + rowGap.
        val pitchDp = (DeviceLayout.METRICS.keyHeight + DeviceLayout.METRICS.rowGap) / d
        assertEquals(182.9f / pitchDp, (y - mid) / d, 0.2f)
    }

    /**
     * The edges of the anchor, tested where they decide something. Left alone, a neighbour
     * cannot reach this far in even with all the language behind it, so capped offsets
     * bring its target closer. That leaves the anchor as the only thing protecting the tap.
     */
    @Test
    fun `the anchor is the central half of the width and sixty percent of the height`() {
        val model = TouchModel(MemStore(), DeviceLayout.LETTER_ROWS)
        for (c in 'a'..'z') repeat(200) { model.observe(c, -15f, 0f) }
        val resolver = TapResolver(map, d).also { it.setOffsets(model) }
        val s = keys[letter('s')]
        val prior = priorOn(symbolIndex('d'))
        val halfWidth = s.width * 0.25f
        val halfHeight = s.height * 0.30f
        val insideX = s.cx + halfWidth - 1f
        assertEquals(letter('s'), resolver.resolve(insideX, s.cy, prior))
        assertEquals(letter('d'), resolver.resolve(s.cx + halfWidth + 1f, s.cy, prior))
        for (side in listOf(1f, -1f)) {
            assertEquals(letter('s'), resolver.resolve(insideX, s.cy + side * (halfHeight - 1f), prior))
            assertEquals(letter('d'), resolver.resolve(insideX, s.cy + side * (halfHeight + 1f), prior))
        }
    }

    /**
     * At the real sigmas the likelihood has usually dismissed a far key before the limit
     * is consulted, so this takes the likelihood away. The prior alone would then pick any
     * key on the board, and only the reach stops it.
     */
    @Test
    fun `the reach is one point three widths and one point one heights`() {
        val flat = TapResolver(map, d, sigmaXDp = 100_000f, sigmaYDp = 100_000f)
        val letters = keys.filter { it.symbol in 'a'..'z' }.map { it.width }.sorted()
        val reachX = 1.3f * letters[letters.size / 2]

        // In d's cell but off its anchor, measuring the distance across to f.
        val f = keys[letter('f')]
        val yOffAnchor = f.cy + f.height * 0.35f
        val onF = priorOn(symbolIndex('f'))
        assertEquals(letter('f'), flat.resolve(f.cx - reachX + 2f, yOffAnchor, onF))
        assertNotEquals(letter('f'), flat.resolve(f.cx - reachX - 2f, yOffAnchor, onF))

        // Straight below e, in the gap between s and d, measuring up a row.
        val e = keys[letter('e')]
        val reachY = 1.1f * e.height
        val onE = priorOn(symbolIndex('e'))
        assertEquals(letter('e'), flat.resolve(e.cx, e.cy + reachY - 2f, onE))
        assertNotEquals(letter('e'), flat.resolve(e.cx, e.cy + reachY + 2f, onE))
    }

    /**
     * The bar is judged as one long key, so its far end stays as good as its middle. Scored
     * from its centre alone, that end would be out of reach and x or c would take the tap.
     */
    @Test
    fun `the far end of the space bar is still the space bar`() {
        val bar = rows[3][3]
        val even = TapResolver.Prior { out -> out.fill(1f / TapResolver.PRIOR_SIZE); true }
        val x = bar.left + 2f
        val y = bar.cy - 10f * d
        assertEquals(keys.indexOf(bar), map.keyAt(x, y))
        assertEquals(keys.indexOf(bar), res.resolve(x, y, even))
    }

    /** All the mass on a far key changes nothing once that key is out of reach. */
    @Test
    fun `a key too far away is skipped however likely it is`() {
        val a = keys[letter('a')]
        // On the left margin of the home row: outside the anchor, so it reaches the
        // scorer, and p is the whole board away.
        assertEquals(letter('a'), res.resolve(1f, a.cy, priorOn(symbolIndex('p'))))
        // Space is more than 1.1 key heights below the top row.
        val t = keys[letter('t')]
        assertEquals(letter('t'), res.resolve(t.cx, t.top + 1f, priorOn(26)))
    }

    @Test
    fun `learned offsets move an edge tap with no language at all`() {
        val model = TouchModel(MemStore(), DeviceLayout.LETTER_ROWS)
        // This hand lands well right of s and well right of d.
        repeat(500) { model.observe('s', 15f, 0f) }
        repeat(500) { model.observe('d', 15f, 0f) }
        val resolver = TapResolver(map, d)
        val s = keys[letter('s')]
        val dd = keys[letter('d')]
        val mid = (s.right + dd.left) / 2f
        val x = mid + 1f
        assertEquals("without offsets the tap is on d", letter('d'), resolver.resolve(x, s.cy, null))
        resolver.setOffsets(model)
        assertEquals("with them it was aimed at s", letter('s'), resolver.resolve(x, s.cy, null))
        resolver.setOffsets(null)
        assertEquals("clearing goes back to the map", letter('d'), resolver.resolve(x, s.cy, null))
    }

    @Test
    fun `offsets never reach a key's drawn bounds`() {
        val before = DeviceLayout.hitRows().flatten()
        val model = TouchModel(MemStore(), DeviceLayout.LETTER_ROWS)
        repeat(500) { model.observe('k', 15f, 15f) }
        TapResolver(map, d).setOffsets(model)
        for ((i, key) in keys.withIndex()) {
            assertEquals(before[i].left, key.left)
            assertEquals(before[i].top, key.top)
            assertEquals(before[i].right, key.right)
            assertEquals(before[i].bottom, key.bottom)
        }
    }

    @Test
    fun `a board with no letters still resolves`() {
        val space = rows[3]
        val page = HitMap(listOf(space), 0f, width)
        val resolver = TapResolver(page, d)
        val bar = space[3]
        assertEquals(3, resolver.resolve(bar.left + 2f, bar.cy, priorOn(26)))
        assertEquals(0, resolver.resolve(space[0].cx, space[0].cy, priorOn(26)))
    }
}
