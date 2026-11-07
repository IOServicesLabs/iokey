package com.localboard.keyboard.touch

import com.localboard.keyboard.engine.HitMap
import com.localboard.keyboard.engine.KeyLayoutMath
import com.localboard.keyboard.engine.TapResolver
import com.localboard.keyboard.engine.TouchModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLayoutMathTest {

    private val d = DeviceLayout.DENSITY
    private fun dp(px: Float) = px / d

    @Test
    fun `device reference numbers`() {
        val m = DeviceLayout.METRICS
        val rows = DeviceLayout.boxes()
        assertEquals(384.0f, DeviceLayout.WIDTH_PX / d, 0.01f)
        assertEquals(40.18f, dp(m.keyHeight), 0.01f)
        assertEquals(4.62f, dp(m.keyGap), 0.01f)
        assertEquals(9.96f, dp(m.rowGap), 0.01f)

        val q = rows[0][0]
        val w = rows[0][1]
        val a = rows[1][0]
        assertEquals("letter face", 33.7f, dp(q.width), 0.05f)
        assertEquals("pitch across", 38.3f, dp(w.left - q.left), 0.05f)
        assertEquals("pitch down", 50.13f, dp(a.top - q.top), 0.05f)
        assertEquals("keyboard height", 206.93f, dp(DeviceLayout.HEIGHT_PX.toFloat()), 0.05f)
    }

    @Test
    fun `the reference numbers on the other rows`() {
        val rows = DeviceLayout.boxes()
        assertEquals("home row inset", 18.9f, dp(rows[1][0].left - rows[0][0].left), 0.05f)
        assertEquals("z row letter face", 34.1f, dp(rows[2][1].width), 0.05f)
        assertEquals("SHIFT", 51.2f, dp(rows[2][0].width), 0.05f)
        assertEquals("DELETE", 51.2f, dp(rows[2].last().width), 0.05f)
        assertEquals("space", 142.1f, dp(rows[3][3].width), 0.05f)
    }

    @Test
    fun `whole pixels on the device`() {
        val m = DeviceLayout.METRICS
        assertEquals(113f, m.keyHeight)
        assertEquals(13f, m.keyGap)
        assertEquals(28f, m.rowGap)
        assertEquals(8f, m.originX)
        assertEquals(1064f, m.usableWidth)
        assertEquals(23f, m.topPadding)
        assertEquals(23f, m.bottomPadding)
        assertEquals(582, DeviceLayout.HEIGHT_PX)
    }

    @Test
    fun `every row is tiled and fills the usable width less its edge padding`() {
        val m = DeviceLayout.METRICS
        val rows = DeviceLayout.boxes()
        for ((r, row) in rows.withIndex()) {
            val pad = m.usableWidth * DeviceLayout.ROWS[r].edgePadding
            assertEquals(m.originX + pad, row.first().left, 0.01f)
            assertEquals(m.originX + m.usableWidth - pad, row.last().right, 0.01f)
            for (i in 1 until row.size) {
                assertEquals("gap in row $r", m.keyGap, row[i].left - row[i - 1].right, 0.01f)
            }
            for (b in row) {
                assertEquals(m.topPadding + r * (m.keyHeight + m.rowGap), b.top, 0.01f)
                assertEquals(m.keyHeight, b.height, 0.01f)
            }
        }
    }

    /**
     * The plain layout must draw what the old code drew when no fit was stored, or
     * removing the fit would itself move keys.
     */
    @Test
    fun `matches the old edge based layout with an empty fit`() {
        val cases = listOf(
            KeyLayoutMath.metrics(d, 1080),
            KeyLayoutMath.metrics(d, 1080, heightScale = 1.2f),
            KeyLayoutMath.metrics(d, 1080, keySpacing = 0.4f),
            KeyLayoutMath.metrics(d, 1080, keySpacing = 1.8f),
            KeyLayoutMath.metrics(d, 1080, oneHandedMode = 1),
            KeyLayoutMath.metrics(d, 1080, oneHandedMode = 2, oneHandedWidth = 0.7f),
            KeyLayoutMath.metrics(3f, 1440),
            KeyLayoutMath.metrics(1f, 320),
            KeyLayoutMath.metrics(2.625f, 1080, formFactor = 0.85f)
        )
        for (m in cases) {
            val new = KeyLayoutMath.layout(DeviceLayout.ROWS, m)
            val old = oldComputeKeyRects(DeviceLayout.ROWS, m)
            for (r in new.indices) {
                for (i in new[r].indices) {
                    assertEquals(old[r][i].left, new[r][i].left, 0.01f)
                    assertEquals(old[r][i].top, new[r][i].top, 0.01f)
                    assertEquals(old[r][i].right, new[r][i].right, 0.01f)
                    assertEquals(old[r][i].bottom, new[r][i].bottom, 0.01f)
                }
            }
        }
    }

    /** KeyboardView.computeKeyRects as it stood before the fit was removed, empty fit. */
    private fun oldComputeKeyRects(
        rows: List<KeyLayoutMath.Row>,
        m: KeyLayoutMath.Metrics
    ): List<List<KeyLayoutMath.Box>> {
        var y = m.topPadding
        return rows.map { row ->
            val n = row.weights.size
            val totalWeight = row.weights.sumOf { it.toDouble() }.toFloat()
            val gaps = m.keyGap * (n - 1)
            val edgePad = m.usableWidth * row.edgePadding
            val keyArea = m.usableWidth - gaps - edgePad * 2
            val widths = FloatArray(n) { keyArea * (row.weights[it] / totalWeight) }
            val edges = FloatArray(n + 1)
            edges[0] = m.originX + edgePad
            for (i in 0 until n) edges[i + 1] = edges[i] + widths[i] + m.keyGap
            val out = (0 until n).map { i ->
                KeyLayoutMath.Box(edges[i], y, edges[i + 1] - m.keyGap, y + m.keyHeight)
            }
            y += m.keyHeight + m.rowGap
            out
        }
    }

    @Test
    fun `one handed mode narrows the grid and docks it`() {
        val whole = 1064f
        val right = KeyLayoutMath.metrics(d, 1080, oneHandedMode = 1, oneHandedWidth = 0.82f)
        assertEquals(whole * 0.82f, right.usableWidth, 0.01f)
        assertEquals(8f + whole * 0.18f, right.originX, 0.01f)
        assertEquals(1072f, right.originX + right.usableWidth, 0.01f)
        val left = KeyLayoutMath.metrics(d, 1080, oneHandedMode = 2, oneHandedWidth = 0.82f)
        assertEquals(whole * 0.82f, left.usableWidth, 0.01f)
        assertEquals(8f, left.originX, 0.01f)
    }

    @Test
    fun `inputs are limited to the ranges the setters allow`() {
        val tight = KeyLayoutMath.metrics(d, 1080, keySpacing = 0.1f)
        assertEquals(KeyLayoutMath.metrics(d, 1080, keySpacing = 0.4f).keyGap, tight.keyGap)
        val airy = KeyLayoutMath.metrics(d, 1080, keySpacing = 9f)
        assertEquals(KeyLayoutMath.metrics(d, 1080, keySpacing = 1.8f).rowGap, airy.rowGap)
        val narrow = KeyLayoutMath.metrics(d, 1080, oneHandedMode = 2, oneHandedWidth = 0.1f)
        assertEquals(1064f * 0.6f, narrow.usableWidth, 0.01f)
    }

    @Test
    fun `key height follows the height scale and the form factor`() {
        assertEquals(135f, KeyLayoutMath.metrics(d, 1080, heightScale = 1.2f).keyHeight)
        val landscape = KeyLayoutMath.formFactor(landscape = true, smallestWidthDp = 384, fontScale = 1f)
        assertEquals(0.85f, landscape, 0f)
        assertEquals(96f, KeyLayoutMath.metrics(d, 1080, formFactor = landscape).keyHeight)
    }

    @Test
    fun `form factor table`() {
        assertEquals(1f, KeyLayoutMath.formFactor(false, 384, 1f), 0f)
        assertEquals(1f, KeyLayoutMath.formFactor(false, 384, 0.85f), 0f)
        assertEquals(1.15f, KeyLayoutMath.formFactor(false, 384, 1.15f), 0f)
        assertEquals("font scale is capped", 1.3f, KeyLayoutMath.formFactor(false, 384, 2f), 0f)
        assertEquals(1.12f, KeyLayoutMath.formFactor(false, 600, 1f), 0f)
        assertEquals(0.85f * 1.12f * 1.3f, KeyLayoutMath.formFactor(true, 720, 1.5f), 1e-6f)
    }

    /**
     * The whole point of the design: a model pushed to its cap, and a resolver actually
     * using it, leave every drawn key exactly where it was.
     */
    @Test
    fun `a populated touch model moves nothing drawn`() {
        fun drawn() = DeviceLayout.boxes().flatten().map { listOf(it.left, it.top, it.right, it.bottom) }
        val before = drawn()
        val model = TouchModel(MemStore(), DeviceLayout.LETTER_ROWS)
        for (c in 'a'..'z') repeat(200) { model.observe(c, 15f, -15f) }
        val map = HitMap(DeviceLayout.hitRows(), 0f, DeviceLayout.WIDTH_PX.toFloat())
        val resolver = TapResolver(map, d)
        resolver.setOffsets(model)
        resolver.resolve(120f, 100f, null)
        assertEquals(before, drawn())
        assertEquals(before, map.keys.map { listOf(it.left, it.top, it.right, it.bottom) })
    }

    @Test
    fun `height of no rows is just the padding`() {
        assertEquals(46, KeyLayoutMath.heightPx(0, DeviceLayout.METRICS))
        assertEquals(159, KeyLayoutMath.heightPx(1, DeviceLayout.METRICS))
    }

    private val floor = 79f   // dp(28) on this density
    private val landscape = KeyLayoutMath.formFactor(landscape = true, smallestWidthDp = 384, fontScale = 1f)

    /** The screen's reservations on this device, worked out in the keyboard's formula. */
    private val portraitCap = 2073
    private val landscapeCap = 753
    private val landscapeCapWithQuickRow = 629

    @Test
    fun `the default portrait board fits and does not change`() {
        val m = DeviceLayout.METRICS
        assertEquals(582, KeyLayoutMath.heightPx(4, m))
        assertSame(m, KeyLayoutMath.capped(m, 4, portraitCap, floor))
    }

    @Test
    fun `the default landscape board fits and does not change`() {
        val m = KeyLayoutMath.metrics(d, 2400, formFactor = landscape)
        assertEquals(514, KeyLayoutMath.heightPx(4, m))
        assertSame(m, KeyLayoutMath.capped(m, 4, landscapeCap, floor))
    }

    @Test
    fun `landscape with the quick row loses a pixel a row`() {
        val m = KeyLayoutMath.metrics(d, 2400, formFactor = landscape)
        val capped = KeyLayoutMath.capped(m, 4, landscapeCapWithQuickRow, floor)
        assertEquals(96f, m.keyHeight)
        assertEquals(96f, capped.keyHeight)
        assertEquals(514, KeyLayoutMath.heightPx(4, capped))
    }

    @Test
    fun `the tallest landscape board fits with every row whole`() {
        val m = KeyLayoutMath.metrics(d, 2400, heightScale = 1.5f, formFactor = landscape)
        assertEquals(143f, m.keyHeight)
        assertEquals(873, KeyLayoutMath.heightPx(5, m))
        val capped = KeyLayoutMath.capped(m, 5, landscapeCap, floor)
        assertEquals(119f, capped.keyHeight)
        assertEquals(753, KeyLayoutMath.heightPx(5, capped))
        val rows = KeyLayoutMath.layout(List(5) { KeyLayoutMath.Row(floatArrayOf(1f, 1f)) }, capped)
        assertEquals("the lowest key ends above the bottom padding", 730f, rows.last()[0].bottom, 0f)
        for (row in rows) assertEquals(119f, row[0].height, 0f)
    }

    @Test
    fun `a capped board keeps everything but the key height`() {
        val m = KeyLayoutMath.metrics(d, 2400, heightScale = 1.5f, formFactor = landscape, keySpacing = 1.4f)
        val capped = KeyLayoutMath.capped(m, 5, landscapeCap, floor)
        assertTrue(capped.keyHeight < m.keyHeight)
        assertEquals(m.originX, capped.originX)
        assertEquals(m.usableWidth, capped.usableWidth)
        assertEquals(m.topPadding, capped.topPadding)
        assertEquals(m.bottomPadding, capped.bottomPadding)
        assertEquals(m.keyGap, capped.keyGap)
        assertEquals(m.rowGap, capped.rowGap)
    }

    @Test
    fun `keys never go under the floor however small the cap`() {
        val m = KeyLayoutMath.metrics(d, 2400, heightScale = 1.5f, formFactor = landscape)
        assertEquals(floor, KeyLayoutMath.capped(m, 5, 200, floor).keyHeight)
        assertEquals(floor, KeyLayoutMath.capped(m, 5, -1, floor).keyHeight)
    }

    @Test
    fun `a board that fits exactly is left alone and one pixel less is not`() {
        val m = KeyLayoutMath.metrics(d, 2400, formFactor = landscape)
        assertSame(m, KeyLayoutMath.capped(m, 4, 514, floor))
        assertEquals(95f, KeyLayoutMath.capped(m, 4, 513, floor).keyHeight)
    }

    @Test
    fun `no cap at all changes nothing`() {
        val m = DeviceLayout.METRICS
        assertSame(m, KeyLayoutMath.capped(m, 4, Int.MAX_VALUE, floor))
        assertSame(m, KeyLayoutMath.capped(m, 0, 10, floor))
    }
}
