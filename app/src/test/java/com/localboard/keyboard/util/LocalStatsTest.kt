package com.localboard.keyboard.util

import com.localboard.keyboard.util.LocalStats.Counter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier

class LocalStatsTest {

    private class MapStore : LocalStats.Store {
        val values = HashMap<String, Long>()
        override fun read(key: String) = values[key] ?: 0L
        override fun write(key: String, value: Long) {
            values[key] = value
        }
    }

    /** Detaches whatever a previous test left, since the object lives for the JVM. */
    @Before
    fun fresh() {
        LocalStats.attach(MapStore())
        LocalStats.reset()
    }

    @Test
    fun `a bump counts and persists`() {
        val store = MapStore()
        LocalStats.attach(store)
        LocalStats.bump(Counter.GLIDE_COMMITTED)
        LocalStats.bump(Counter.GLIDE_COMMITTED)
        LocalStats.bump(Counter.GLIDE_WIPED)
        assertEquals(2L, LocalStats[Counter.GLIDE_COMMITTED])
        assertEquals(1L, LocalStats[Counter.GLIDE_WIPED])
        assertEquals(0L, LocalStats[Counter.DRAFT_ACCEPTED])
        assertEquals(2L, store.values["glide_committed"])
        assertEquals(1L, store.values["glide_wiped"])
    }

    @Test
    fun `attach picks up what a previous process stored`() {
        val store = MapStore().apply { values["strip_healed"] = 41L }
        LocalStats.attach(store)
        assertEquals(41L, LocalStats[Counter.STRIP_HEALED])
        LocalStats.bump(Counter.STRIP_HEALED)
        assertEquals(42L, store.values["strip_healed"])
    }

    @Test
    fun `counts from before the attach are kept and folded in`() {
        val broken = object : LocalStats.Store {
            override fun read(key: String): Long = throw IllegalStateException("no store yet")
            override fun write(key: String, value: Long) = throw IllegalStateException("no store yet")
        }
        LocalStats.attach(broken)
        LocalStats.bump(Counter.CORRECTION_FIRED)
        LocalStats.bump(Counter.CORRECTION_FIRED)

        val store = MapStore().apply { values["correction_fired"] = 10L }
        LocalStats.attach(store)
        assertEquals(12L, LocalStats[Counter.CORRECTION_FIRED])
        assertEquals(12L, store.values["correction_fired"])
    }

    @Test
    fun `a store that throws costs only the persisted copy`() {
        val store = object : LocalStats.Store {
            override fun read(key: String) = 0L
            override fun write(key: String, value: Long) = throw IllegalStateException("disk full")
        }
        LocalStats.attach(store)
        LocalStats.bump(Counter.DRAFT_TIMED_OUT)
        LocalStats.bump(Counter.DRAFT_TIMED_OUT)
        assertEquals(2L, LocalStats[Counter.DRAFT_TIMED_OUT])
    }

    @Test
    fun `reset zeroes memory and store`() {
        val store = MapStore()
        LocalStats.attach(store)
        LocalStats.bump(Counter.DRAFT_DISCARDED)
        LocalStats.reset()
        assertEquals(0L, LocalStats[Counter.DRAFT_DISCARDED])
        assertEquals(0L, store.values["draft_discarded"])
    }

    @Test
    fun `the snapshot and the summary list every counter, zeros included`() {
        LocalStats.bump(Counter.CORRECTION_REVERTED)
        val snapshot = LocalStats.snapshot()
        assertEquals(Counter.values().toList(), snapshot.keys.toList())
        assertEquals(1L, snapshot[Counter.CORRECTION_REVERTED])
        val lines = LocalStats.summary().lines()
        assertEquals(Counter.values().size, lines.size)
        assertTrue(lines.contains("correction_reverted: 1"))
        assertTrue(lines.contains("draft_accepted: 0"))
    }

    @Test
    fun `no public call accepts text, so typed text has no way in`() {
        val offenders = LocalStats::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .filter { m -> m.parameterTypes.any { CharSequence::class.java.isAssignableFrom(it) } }
            .map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }
}
