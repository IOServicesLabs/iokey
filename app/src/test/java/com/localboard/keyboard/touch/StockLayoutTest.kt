package com.localboard.keyboard.touch

import com.localboard.keyboard.engine.HitKey
import com.localboard.keyboard.ui.KeyType
import com.localboard.keyboard.ui.KeyboardLayouts
import com.localboard.keyboard.ui.KeyboardModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the simulators' board to the real one. Every touch number in this code base was
 * worked out on [DeviceLayout], so if the real layout moves on, this should fail before
 * those numbers quietly stop describing it.
 */
class StockLayoutTest {

    private fun symbolOf(model: KeyboardModel, r: Int, i: Int): Char {
        val key = model.rows[r].keys[i]
        return HitKey.symbolFor(key.type == KeyType.SPACE, key.type == KeyType.CHAR, key.output)
    }

    @Test
    fun `the fixture has the weights and padding of the real stock layout`() {
        val real = KeyboardLayouts.qwerty()
        assertEquals(DeviceLayout.ROWS.size, real.rows.size)
        for ((r, row) in real.rows.withIndex()) {
            val weights = FloatArray(row.keys.size) { row.keys[it].weight }
            assertArrayEquals("row $r weights", DeviceLayout.ROWS[r].weights, weights, 0f)
            assertEquals("row $r padding", DeviceLayout.ROWS[r].edgePadding, row.edgePadding, 0f)
        }
    }

    @Test
    fun `the fixture has the symbols of the real stock layout`() {
        val real = KeyboardLayouts.qwerty()
        val fixture = DeviceLayout.hitRows()
        for ((r, row) in real.rows.withIndex()) {
            for (i in row.keys.indices) {
                assertEquals("row $r key $i", fixture[r][i].symbol, symbolOf(real, r, i))
            }
        }
    }

    @Test
    fun `no functional key is scorable on any page`() {
        val pages = listOf(
            KeyboardLayouts.qwerty(),
            KeyboardLayouts.numberPad(),
            KeyboardLayouts.selectionPad()
        )
        for (page in pages) {
            for ((r, row) in page.rows.withIndex()) {
                for ((i, key) in row.keys.withIndex()) {
                    val symbol = symbolOf(page, r, i)
                    if (key.type != KeyType.CHAR && key.type != KeyType.SPACE) {
                        assertEquals(key.type.name, HitKey.NOT_SCORABLE, symbol)
                    }
                }
            }
        }
    }

    @Test
    fun `the number pad offers the scorer nothing`() {
        val pad = KeyboardLayouts.numberPad()
        for ((r, row) in pad.rows.withIndex()) {
            for (i in row.keys.indices) {
                assertEquals(HitKey.NOT_SCORABLE, symbolOf(pad, r, i))
            }
        }
    }

    @Test
    fun `every layout reaches all twenty six letters and only those`() {
        for ((id, _) in KeyboardLayouts.LAYOUT_CHOICES) {
            val model = KeyboardLayouts.qwerty(
                com.localboard.keyboard.ui.LayoutOptions(layoutId = id)
            )
            val symbols = ArrayList<Char>()
            for ((r, row) in model.rows.withIndex()) {
                for (i in row.keys.indices) {
                    val s = symbolOf(model, r, i)
                    if (s in 'a'..'z') symbols += s
                }
            }
            assertEquals(id, 26, symbols.size)
            assertEquals(id, ('a'..'z').toList(), symbols.sorted())
            assertTrue(id, model.rows.flatMap { it.keys }.count { it.type == KeyType.SPACE } == 1)
        }
    }
}
