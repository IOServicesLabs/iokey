package com.localboard.keyboard.touch

import com.localboard.keyboard.engine.HitKey
import com.localboard.keyboard.engine.HitMap
import com.localboard.keyboard.engine.KeyLayoutMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitMapTest {

    private val rows = DeviceLayout.hitRows()
    private val width = DeviceLayout.WIDTH_PX.toFloat()
    private val map = HitMap(rows, 0f, width)
    private val keys = map.keys

    @Test
    fun `every pixel of the keyboard belongs to a key`() {
        for (y in 0 until DeviceLayout.HEIGHT_PX) {
            for (x in 0 until DeviceLayout.WIDTH_PX) {
                assertTrue("($x,$y)", map.keyAt(x.toFloat(), y.toFloat()) >= 0)
            }
        }
    }

    @Test
    fun `every pixel inside a drawn key belongs to that key`() {
        var inside = 0
        for (y in 0 until DeviceLayout.HEIGHT_PX) {
            for (x in 0 until DeviceLayout.WIDTH_PX) {
                val fx = x.toFloat()
                val fy = y.toFloat()
                val k = map.keyAt(fx, fy)
                for ((i, key) in keys.withIndex()) {
                    if (fx >= key.left && fx < key.right && fy >= key.top && fy < key.bottom) {
                        inside++
                        assertEquals("($x,$y)", i, k)
                    }
                }
            }
        }
        // The floor is the drawn face area itself, recomputed from the layout the
        // test iterates, not a literal: it moved with the Gboard-parity metrics
        // retune and would move again on the next one.
        val faceArea = rows.sumOf { row -> row.sumOf { ((it.right - it.left) * (it.bottom - it.top)).toDouble() } }
        assertTrue("the faces cover most of the view, got $inside of $faceArea", inside > faceArea * 0.95)
    }

    @Test
    fun `home row margins belong to a and l never q p SHIFT or DELETE`() {
        val a = rows[1][0]
        val l = rows[1].last()
        var y = a.top
        while (y < a.bottom) {
            for (x in 0 until a.left.toInt()) {
                assertEquals('a', keys[map.keyAt(x.toFloat(), y)].symbol)
            }
            for (x in l.right.toInt() + 1 until DeviceLayout.WIDTH_PX) {
                assertEquals('l', keys[map.keyAt(x.toFloat(), y)].symbol)
            }
            y += 1f
        }
    }

    @Test
    fun `boundaries sit midway between neighbours`() {
        val q = rows[0][0]
        val w = rows[0][1]
        val midX = (q.right + w.left) / 2f
        val y = q.cy
        assertEquals('q', keys[map.keyAt(midX - 0.01f, y)].symbol)
        assertEquals('w', keys[map.keyAt(midX, y)].symbol)

        val a = rows[1][0]
        val midY = (q.bottom + a.top) / 2f
        assertEquals('q', keys[map.keyAt(q.cx, midY - 0.01f)].symbol)
        assertEquals('a', keys[map.keyAt(a.cx, midY)].symbol)
    }

    @Test
    fun `the outermost rows own everything above and below them`() {
        val q = rows[0][0]
        val space = rows[3][3]
        assertEquals('q', keys[map.keyAt(q.cx, -500f)].symbol)
        assertEquals(' ', keys[map.keyAt(space.cx, 50_000f)].symbol)
    }

    @Test
    fun `the outermost keys own the side padding at full width`() {
        for (row in rows) {
            val first = map.keyAt(0f, row[0].cy)
            val last = map.keyAt(width - 0.5f, row[0].cy)
            assertEquals(keys.indexOf(row.first()), first)
            assertEquals(keys.indexOf(row.last()), last)
        }
    }

    @Test
    fun `the one handed gutter has no key`() {
        val m = KeyLayoutMath.metrics(
            DeviceLayout.DENSITY, DeviceLayout.WIDTH_PX, oneHandedMode = 1, oneHandedWidth = 0.82f
        )
        val docked = HitMap(DeviceLayout.hitRows(m), m.originX, width)
        val y = m.topPadding + m.keyHeight / 2f
        assertEquals(-1, docked.keyAt(m.originX - 1f, y))
        assertEquals(-1, docked.keyAt(0f, y))
        assertEquals('q', docked.keys[docked.keyAt(m.originX, y)].symbol)
        assertEquals('p', docked.keys[docked.keyAt(m.originX + m.usableWidth - 1f, y)].symbol)
    }

    @Test
    fun `docked right the strip beside the screen edge belongs to the outer keys`() {
        val m = KeyLayoutMath.metrics(
            DeviceLayout.DENSITY, DeviceLayout.WIDTH_PX, oneHandedMode = 1, oneHandedWidth = 0.82f
        )
        val docked = HitMap(DeviceLayout.hitRows(m), m.originX, width)
        val topRow = m.topPadding + m.keyHeight / 2f
        val secondRow = topRow + m.keyHeight + m.rowGap
        for (x in listOf(m.originX + m.usableWidth, width - 0.5f)) {
            assertEquals('p', docked.keys[docked.keyAt(x, topRow)].symbol)
            assertEquals('l', docked.keys[docked.keyAt(x, secondRow)].symbol)
        }
        assertEquals(-1, docked.keyAt(width, topRow))
    }

    @Test
    fun `docked left the strip beside the screen edge belongs to the outer keys and the gutter has no key`() {
        val m = KeyLayoutMath.metrics(
            DeviceLayout.DENSITY, DeviceLayout.WIDTH_PX, oneHandedMode = 2, oneHandedWidth = 0.82f
        )
        val gridRight = m.originX + m.usableWidth
        val docked = HitMap(DeviceLayout.hitRows(m), 0f, gridRight)
        val topRow = m.topPadding + m.keyHeight / 2f
        val secondRow = topRow + m.keyHeight + m.rowGap
        for (x in listOf(0f, m.originX - 1f)) {
            assertEquals('q', docked.keys[docked.keyAt(x, topRow)].symbol)
            assertEquals('a', docked.keys[docked.keyAt(x, secondRow)].symbol)
        }
        assertEquals('p', docked.keys[docked.keyAt(gridRight - 1f, topRow)].symbol)
        assertEquals(-1, docked.keyAt(gridRight, topRow))
        assertEquals(-1, docked.keyAt(width - 0.5f, topRow))
    }

    @Test
    fun `outside the grid is minus one`() {
        assertEquals(-1, map.keyAt(-0.5f, 100f))
        assertEquals(-1, map.keyAt(width, 100f))
        assertEquals(-1, map.keyAt(Float.NaN, 100f))
        assertEquals(-1, map.keyAt(100f, Float.NaN))
    }

    @Test
    fun `keys keep row order and left to right order`() {
        assertEquals(
            rows.flatten().map { it.left to it.top },
            keys.map { it.left to it.top }
        )
        assertEquals("qwertyuiop", keys.take(10).map { it.symbol }.joinToString(""))
    }

    @Test
    fun `a map with no keys reports no key`() {
        assertEquals(-1, HitMap(emptyList(), 0f, width).keyAt(10f, 10f))
        assertEquals(-1, HitMap(listOf(emptyList()), 0f, width).keyAt(10f, 10f))
    }

    @Test
    fun `an empty row is skipped without shifting the indices`() {
        val withGap = listOf(rows[0], emptyList(), rows[1])
        val m = HitMap(withGap, 0f, width)
        assertEquals(19, m.keys.size)
        assertEquals('q', m.keys[m.keyAt(rows[0][0].cx, rows[0][0].cy)].symbol)
        assertEquals('a', m.keys[m.keyAt(rows[1][0].cx, rows[1][0].cy)].symbol)
    }

    @Test
    fun `key symbols follow the model`() {
        assertEquals(' ', HitKey.symbolFor(isSpace = true, isChar = false, output = " "))
        assertEquals('q', HitKey.symbolFor(isSpace = false, isChar = true, output = "q"))
        assertEquals('q', HitKey.symbolFor(isSpace = false, isChar = true, output = "Q"))
        val none = HitKey.NOT_SCORABLE
        assertEquals(none, HitKey.symbolFor(isSpace = false, isChar = true, output = "1"))
        assertEquals(none, HitKey.symbolFor(isSpace = false, isChar = true, output = "'"))
        assertEquals(none, HitKey.symbolFor(isSpace = false, isChar = true, output = "é"))
        assertEquals(none, HitKey.symbolFor(isSpace = false, isChar = true, output = "ab"))
        assertEquals(none, HitKey.symbolFor(isSpace = false, isChar = true, output = ""))
        assertEquals(none, HitKey.symbolFor(isSpace = false, isChar = false, output = ""))
        assertEquals(none, HitKey.symbolFor(isSpace = false, isChar = false, output = "."))
        // A functional key whose output happens to be one letter is still functional.
        assertEquals(none, HitKey.symbolFor(isSpace = false, isChar = false, output = "a"))
    }
}
