package com.localboard.keyboard.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeometryLogTest {

    private fun sample(window: Int) = GeometryLog.Geometry(
        window = window, root = window, rootWanted = window, rootTooSmall = false,
        stripLayout = 68, stripTop = window - 68, stripHeight = 68,
        navigationBar = 68, captionBar = 0, tappable = 0, gestures = 90,
        display = 68, dispatch = GeometryLog.UNKNOWN, lastGood = 68
    )

    @Before
    @After
    fun empty() = GeometryLog.clear()

    @Test
    fun `an empty log says nothing`() {
        assertEquals("", GeometryLog.summary())
        assertTrue(GeometryLog.lines().isEmpty())
    }

    @Test
    fun `a line names each height it holds`() {
        val line = GeometryLog.describe(GeometryLog.Event.SHOW, sample(921))
        for (part in listOf("show", "window=921", "root=921", "wants=921", "strip=68", "top=853", "nav=68", "display=68", "dispatch=-1")) {
            assertTrue("$part is in: $line", line.contains(part))
        }
        assertFalse("a root that fits is not called cut", line.contains("cut"))
    }

    @Test
    fun `a root the window clamped says so`() {
        val line = GeometryLog.describe(GeometryLog.Event.LAYOUT, sample(853).copy(rootWanted = 921, rootTooSmall = true))
        assertTrue(line, line.contains("wants=921 cut"))
    }

    @Test
    fun `it keeps the newest measurements and drops the oldest`() {
        val extra = 5
        for (i in 0 until GeometryLog.CAPACITY + extra) {
            GeometryLog.record(GeometryLog.Event.LAYOUT, sample(i), atMs = 0L)
        }
        val lines = GeometryLog.lines()
        assertEquals(GeometryLog.CAPACITY, lines.size)
        assertTrue(lines.first(), lines.first().contains("window=$extra "))
        assertTrue(lines.last(), lines.last().contains("window=${GeometryLog.CAPACITY + extra - 1} "))
    }

    @Test
    fun `clearing empties it`() {
        GeometryLog.record(GeometryLog.Event.SHOW, sample(921))
        GeometryLog.clear()
        assertEquals("", GeometryLog.summary())
    }

    /** Typed text cannot reach the log if nothing on the way in can carry it. */
    @Test
    fun `a measurement can hold only numbers and flags`() {
        val allowed = setOf(Int::class.java, Boolean::class.java)
        for (constructor in GeometryLog.Geometry::class.java.constructors) {
            for (type in constructor.parameterTypes) {
                assertTrue("a field of type $type", type in allowed)
            }
        }
    }

    @Test
    fun `recording takes no text`() {
        val record = GeometryLog::class.java.methods.filter { it.name.startsWith("record") }
        assertTrue(record.isNotEmpty())
        for (method in record) {
            assertFalse(method.toString(), method.parameterTypes.any { CharSequence::class.java.isAssignableFrom(it) })
        }
    }
}
