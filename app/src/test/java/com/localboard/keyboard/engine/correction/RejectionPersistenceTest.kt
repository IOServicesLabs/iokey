package com.localboard.keyboard.engine.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

class RejectionPersistenceTest {
    private var now = 5_000_000_000L
    private val clock = { now }

    @Test
    fun whatHappenedBeforeTheStoredRowArrivedWins() {
        val stored = RejectionMemory(clock).also {
            it.noteKept("xray", "array")
            it.noteKept("older", "elder")
        }.serialize()
        val m = RejectionMemory(clock)
        m.noteSoft("xray", "gray")
        m.mergeStored(stored)
        assertEquals(setOf("gray"), m.bannedFixes("xray"))
        assertEquals(0, m.keptCount("xray"))
        assertTrue(m.isProtected("older"))
        assertTrue("the soft note still has to be written", m.dirty)
    }

    @Test
    fun readingTheStoredRowIntoAnEmptyMemoryLeavesNothingToWrite() {
        val stored = RejectionMemory(clock).also { it.noteKept("xray", "array") }.serialize()
        val m = RejectionMemory(clock)
        m.mergeStored(stored)
        assertTrue(m.isProtected("xray"))
        assertFalse(m.dirty)
    }

    /** The stored row arrives late, and it still holds the word. */
    @Test
    fun aWordForgottenBeforeTheStoredRowArrivesStaysForgotten() {
        val stored = RejectionMemory(clock).also {
            it.noteKept("xray", "array")
            it.noteKept("older", "elder")
        }.serialize()
        val m = RejectionMemory(clock)
        m.forget("XRAY")
        assertFalse("nothing was held yet, so nothing has changed", m.dirty)
        m.mergeStored(stored)
        assertFalse(m.isProtected("xray"))
        assertTrue(m.isProtected("older"))
        assertTrue("the row still holds the word, so it has to be written", m.dirty)
        assertFalse(m.serialize().contains("xray"))
    }

    @Test
    fun aStoredRowWithoutTheForgottenWordLeavesNothingToWrite() {
        val stored = RejectionMemory(clock).also { it.noteKept("older", "elder") }.serialize()
        val m = RejectionMemory(clock)
        m.forget("xray")
        m.mergeStored(stored)
        assertTrue(m.isProtected("older"))
        assertFalse(m.dirty)
    }

    /** A slip on a chip puts a word back as it was, and that is not the user removing the word. */
    @Test
    fun undoingASlipDoesNotKeepTheStoredRowOut() {
        val stored = RejectionMemory(clock).also { it.noteKept("xray", "array") }.serialize()
        val m = RejectionMemory(clock)
        val before = m.recordOf("xray")
        m.noteKept("xray", null)
        m.restoreWord("xray", before)
        m.mergeStored(stored)
        assertTrue(m.isProtected("xray"))
    }

    @Test
    fun liftingABanLeavesTheRestOfTheRecord() {
        val m = RejectionMemory(clock)
        m.noteKept("xray", "array")
        m.noteKept("xray", "gray")
        m.markPersisted()
        assertFalse(m.liftBan("xray", "other"))
        assertFalse(m.dirty)
        assertTrue(m.liftBan("XRAY", "ARRAY"))
        assertTrue(m.dirty)
        assertEquals(setOf("gray"), m.bannedFixes("xray"))
        assertTrue(m.isProtected("xray"))
    }

    @Test
    fun liftingTheLastBanOfAnEmptyRecordRemovesIt() {
        val m = RejectionMemory(clock)
        m.banPair("xray", "array")
        assertTrue(m.liftBan("xray", "array"))
        assertEquals("", m.serialize())
    }

    @Test
    fun aFixThatCannotBeWrittenLosesOnlyItsBan() {
        val m = RejectionMemory(clock)
        m.noteKept("xray", "x-ray")
        m.noteKept("xray", "array")
        val back = RejectionMemory(clock).also { it.restore(m.serialize()) }
        assertTrue(back.isProtected("xray"))
        assertEquals(setOf("array"), back.bannedFixes("xray"))
    }

    @Test
    fun aWordThatCannotBeWrittenIsLeftOut() {
        val m = RejectionMemory(clock)
        m.noteKept("a,b", null)
        m.noteKept("fine", null)
        val back = RejectionMemory(clock).also { it.restore(m.serialize()) }
        assertFalse(back.isProtected("a,b"))
        assertTrue(back.isProtected("fine"))
    }

    @Test
    fun anEmptyStoreRestoresToNothing() {
        val m = RejectionMemory(clock)
        m.restore("")
        assertEquals("", m.serialize())
        assertFalse(m.dirty)
    }

    /**
     * Through a real SQLite table with the columns StyleStatEntity declares. The JDBC driver sits
     * on the test classpath so the SQL behind the personal model can be run without a device, and
     * this is the proof that it loads on the build's JVM.
     */
    @Test
    fun theSerializedTextSurvivesAStyleStatsRow() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use {
                it.execute("CREATE TABLE style_stats (`key` TEXT NOT NULL PRIMARY KEY, `value` TEXT NOT NULL)")
            }
            val m = RejectionMemory(clock)
            m.noteKept("woulda", "would a")
            m.noteKept("don’t", "don't")
            m.noteSoft("café", "cafe")
            put(db, RejectionMemory.STYLE_STATS_KEY, m.serialize())

            val back = RejectionMemory(clock).also { it.restore(get(db, RejectionMemory.STYLE_STATS_KEY)) }
            assertEquals(setOf("would a"), back.bannedFixes("woulda"))
            assertTrue(back.isProtected("woulda"))
            assertEquals(setOf("don't"), back.bannedFixes("don’t"))
            assertEquals(1, back.softCount("café"))
        }
    }

    private fun put(db: Connection, key: String, value: String) {
        db.prepareStatement("INSERT OR REPLACE INTO style_stats (`key`, `value`) VALUES (?, ?)").use {
            it.setString(1, key)
            it.setString(2, value)
            it.executeUpdate()
        }
    }

    private fun get(db: Connection, key: String): String =
        db.prepareStatement("SELECT `value` FROM style_stats WHERE `key` = ?").use { s ->
            s.setString(1, key)
            s.executeQuery().use { r ->
                assertTrue(r.next())
                r.getString(1)
            }
        }
}
