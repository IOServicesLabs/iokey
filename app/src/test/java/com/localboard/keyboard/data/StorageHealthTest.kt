package com.localboard.keyboard.data

import com.localboard.keyboard.context.StorageHealth
import com.localboard.keyboard.context.StoreAdvice
import com.localboard.keyboard.context.StoreFailureMemory
import com.localboard.keyboard.context.StoreFailureRecord
import com.localboard.keyboard.context.StoredRowGuard
import com.localboard.keyboard.data.db.NGramRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StorageHealthTest {
    private var now = 1_000_000L
    private val health = StorageHealth({ now })

    @Test
    fun healthyHasNothingToSay() {
        assertNull(health.problem())
        assertEquals(0L, health.msUntilRetry())
    }

    /** The real exception a migration throws on a real SQLite, wrapped the way Room's open wraps it. */
    private fun whatAFailedMigrationThrows(): Throwable {
        V5Store().use { s ->
            try {
                s.migrate(NGramRows.MIGRATION_5_TO_6 + "INSERT INTO no_such_table VALUES (1)")
            } catch (t: Throwable) {
                return IllegalStateException("open failed", t)
            }
        }
        fail("the migration was supposed to fail")
        throw AssertionError()
    }

    @Test
    fun aFailedMigrationReachesTheDiagnosticsInPlainWords() {
        health.openFailed(whatAFailedMigrationThrows())
        val problem = health.problem()
        assertNotNull("a store that would not open must not stay silent", problem)
        println("diagnostics after a failed migration: $problem")
        assertTrue(problem, "no such table" in problem!!)
        assertTrue(problem, "could not be opened" in problem)
        assertTrue("says what is lost and what is not: $problem", "Nothing was deleted" in problem)
    }

    @Test
    fun aStoreThatWouldNotOpenStaysReportedAfterALaterWrite() {
        health.openFailed(whatAFailedMigrationThrows())
        health.writeWorked()
        assertNotNull(health.problem())
    }

    @Test
    fun aFailedWriteIsReportedAndClearsWhenAWriteWorks() {
        health.writeFailed(java.sql.SQLException("database or disk is full"))
        assertEquals(1, health.failures)
        val problem = health.problem()!!
        assertTrue(problem, "database or disk is full" in problem)
        assertTrue(problem, "tries again" in problem)
        health.writeWorked()
        assertNull(health.problem())
        assertEquals(0, health.failures)
        assertEquals(0L, health.msUntilRetry())
    }

    @Test
    fun aModelThatFailedToLoadStaysReportedUntilTheNextStart() {
        health.loadFailed(java.sql.SQLException("disk I/O error"))
        health.writeWorked()
        val problem = health.problem()!!
        assertTrue(problem, "disk I/O error" in problem)
        assertTrue(problem, "until it restarts" in problem)
    }

    @Test
    fun theRetryBacksOffAndCapsAtTenMinutes() {
        val waits = (1..10).map {
            health.writeFailed(RuntimeException("busy"))
            health.msUntilRetry()
        }
        assertEquals(10_000L, waits[0])
        assertEquals(20_000L, waits[1])
        assertEquals(40_000L, waits[2])
        assertEquals(600_000L, waits.last())
        assertTrue(waits.zipWithNext().all { (a, b) -> b >= a })

        now += 600_000L
        assertEquals("the wait passes", 0L, health.msUntilRetry())
    }

    @Test
    fun aKeyThatFailsIsSaidPlainlyAndNamesTheWayOut() {
        health.openFailed(RuntimeException("open failed", javax.crypto.AEADBadTagException()))
        val problem = health.problem()!!
        println("diagnostics after a key failure: $problem")
        assertTrue(problem, "could not be opened" in problem)
        assertTrue(problem, "the key that unlocks them" in problem)
        assertTrue(problem, "Nothing was deleted" in problem)
        assertTrue(problem, "Start fresh" in problem)
    }

    @Test
    fun aFailureThatHasNotLastedOffersNoStartFreshInTheReport() {
        health.openFailed(whatAFailedMigrationThrows())
        assertTrue(health.problem()!!, "Start fresh" !in health.problem()!!)
    }

    /** One [StorageHealth] is one process. This is what carries over from each to the next. */
    private val memory = StoreFailureMemory.ForThisProcess()

    private fun aStartThatFails(
        t: Throwable = whatAFailedMigrationThrows(),
        storageShort: Boolean = false,
        libraryMissing: Boolean = false
    ) = StorageHealth({ now }, memory).also { it.startFailed(t, storageShort, libraryMissing) }

    @Test
    fun theOfferComesWithTheThirdStartThatFails() {
        assertEquals(StoreAdvice.WAIT, aStartThatFails().advice())
        assertEquals(StoreAdvice.WAIT, aStartThatFails().advice())
        val third = aStartThatFails()
        assertEquals(StoreAdvice.START_FRESH, third.advice())
        assertTrue(third.problem()!!, "Start fresh" in third.problem()!!)
    }

    @Test
    fun theOfferComesAfterADayOfFailingHoweverFewStarts() {
        val only = aStartThatFails()
        now += StoreFailureRecord.OFFER_AFTER_FAILING_MS - 1
        assertEquals(StoreAdvice.WAIT, only.advice())
        now += 1
        assertEquals(StoreAdvice.START_FRESH, only.advice())
    }

    @Test
    fun theDayCountsFromTheFirstFailureNotTheLatest() {
        aStartThatFails()
        now += StoreFailureRecord.OFFER_AFTER_FAILING_MS - 1
        val later = aStartThatFails()
        now += 1
        assertEquals(StoreAdvice.START_FRESH, later.advice())
    }

    @Test
    fun anyKindOfFailureComesToTheOfferOnceItHasLasted() {
        val failures = listOf(
            RuntimeException("open failed", javax.crypto.AEADBadTagException()),
            java.io.IOException("disk I/O error"),
            whatAFailedMigrationThrows()
        )
        for (failure in failures) {
            val mem = StoreFailureMemory.ForThisProcess()
            val third = (1..3).map { StorageHealth({ now }, mem).also { h -> h.startFailed(failure) } }.last()
            assertEquals(failure.toString(), StoreAdvice.START_FRESH, third.advice())
        }
    }

    @Test
    fun aFullDiskNeverComesToTheOfferHoweverLongItLasts() {
        val said = java.sql.SQLException("database or disk is full")
        repeat(5) { aStartThatFails(said) }
        now += 2 * StoreFailureRecord.OFFER_AFTER_FAILING_MS
        val last = aStartThatFails(said)
        assertEquals(StoreAdvice.FREE_SPACE, last.advice())
        assertTrue(last.problem()!!, "Start fresh" !in last.problem()!!)
    }

    @Test
    fun aPhoneShortOfSpaceCountsAsFullWhateverTheFailureSays() {
        repeat(5) { aStartThatFails(java.io.IOException("disk I/O error"), storageShort = true) }
        assertEquals(StoreAdvice.FREE_SPACE, aStartThatFails(java.io.IOException("disk I/O error"), storageShort = true).advice())
    }

    /** Free space would not cure it, and telling them to clear their phone for nothing is worse than the offer. */
    @Test
    fun aKeyThatFailsOnAPhoneShortOfSpaceIsStillAboutTheKey() {
        val key = RuntimeException("open failed", javax.crypto.AEADBadTagException())
        repeat(2) { aStartThatFails(key, storageShort = true) }
        assertEquals(StoreAdvice.START_FRESH, aStartThatFails(key, storageShort = true).advice())
    }

    @Test
    fun aMissingLibraryNeverComesToTheOfferOrToARetry() {
        val said = UnsatisfiedLinkError("no sqlcipher")
        repeat(5) { aStartThatFails(said, libraryMissing = true) }
        now += 2 * StoreFailureRecord.OFFER_AFTER_FAILING_MS
        val last = aStartThatFails(said, libraryMissing = true)
        assertEquals(StoreAdvice.UPDATE_APP, last.advice())
        now += 10 * 60 * 60 * 1000L
        assertFalse("no try can work in this build", last.takeOpenRetry())
    }

    @Test
    fun aStoreThatOpensStartsTheCountAgain() {
        aStartThatFails()
        val second = aStartThatFails()
        second.storeOpened()
        assertEquals(StoreAdvice.NONE, second.advice())
        assertEquals(StoreFailureRecord(), memory.read())

        assertEquals(StoreAdvice.WAIT, aStartThatFails().advice())
        assertEquals(StoreAdvice.WAIT, aStartThatFails().advice())
    }

    /** The first install freezes what an upgrade writes, and this must add nothing on the healthy path. */
    @Test
    fun aStoreThatJustOpensWritesNothingToRemember() {
        var writes = 0
        val counting = object : StoreFailureMemory {
            override fun read() = StoreFailureRecord()
            override fun write(record: StoreFailureRecord) {
                writes++
            }
        }
        StorageHealth({ now }, counting).storeOpened()
        assertEquals(0, writes)
    }

    @Test
    fun retriesComeAfterOneFiveThirtyAndThenEverySixtyMinutes() {
        val failed = aStartThatFails()
        fun minutes(n: Long) = n * 60_000L
        assertFalse("not at once", failed.takeOpenRetry())
        for (gap in listOf(1L, 5L, 30L, 60L, 60L, 60L)) {
            now += minutes(gap) - 1
            assertFalse("not before the $gap minutes are up", failed.takeOpenRetry())
            now += 1
            assertTrue("after $gap minutes", failed.takeOpenRetry())
            assertFalse("and each try counts once", failed.takeOpenRetry())
        }
    }

    @Test
    fun aHealthyStoreHasNoRetryToTake() {
        now += 100 * 60 * 60 * 1000L
        assertFalse(StorageHealth({ now }, memory).takeOpenRetry())
    }

    @Test
    fun aStoreThatFailedAgainOnARetryKeepsItsPlaceInTheGaps() {
        val failed = aStartThatFails()
        now += 60_000L
        assertTrue(failed.takeOpenRetry())
        failed.openFailed(java.io.IOException("still no"))
        now += 60_000L
        assertFalse("five minutes this time", failed.takeOpenRetry())
        now += 4 * 60_000L
        assertTrue(failed.takeOpenRetry())
        assertEquals("a retry is not another start", 1, memory.read().failedStarts)
    }

    @Test
    fun noNoticeUntilItHasLastedAndThenOnce() {
        assertNull(aStartThatFails().noticeDue())
        assertNull(aStartThatFails().noticeDue())
        val third = aStartThatFails()
        assertEquals(StoreAdvice.START_FRESH, third.noticeDue())
        third.noticeShown()
        assertNull(third.noticeDue())
        assertNull("nor again at the next start", aStartThatFails().noticeDue())
    }

    @Test
    fun aNoticeThatWasNeverShownIsStillDueAfterARestart() {
        repeat(3) { aStartThatFails() }
        assertEquals("still due after a process that never got to show it", StoreAdvice.START_FRESH, aStartThatFails().noticeDue())
    }

    @Test
    fun aNewFailureAfterTheStoreCameBackNoticesAgain() {
        repeat(2) { aStartThatFails() }
        val third = aStartThatFails()
        third.noticeShown()
        third.storeOpened()
        assertNull(third.noticeDue())

        repeat(2) { aStartThatFails() }
        assertEquals(StoreAdvice.START_FRESH, aStartThatFails().noticeDue())
    }

    @Test
    fun aFullDiskGetsItsOwnNoticeOnceItHasLasted() {
        val said = java.sql.SQLException("database or disk is full")
        repeat(2) { aStartThatFails(said) }
        assertEquals(StoreAdvice.FREE_SPACE, aStartThatFails(said).noticeDue())
    }

    @Test
    fun aHealthyStoreHasNoNotice() {
        assertNull(health.noticeDue())
    }

    @Test
    fun aNewStoreClearsEverythingThatWasWrongWithTheOldOne() {
        health.openFailed(javax.crypto.AEADBadTagException())
        health.loadFailed(java.sql.SQLException("disk I/O error"))
        health.writeFailed(java.sql.SQLException("database or disk is full"))
        health.storeReplaced()
        assertNull(health.problem())
        assertEquals(0L, health.msUntilRetry())
    }

    @Test
    fun aRowSavedWholeWaitsForTheLoadThatReadsIt() {
        val guard = StoredRowGuard(setOf("confirmed_words"))
        assertFalse("the load has not read it yet", guard.maySave("confirmed_words"))
        assertTrue("a row no load reads is up to whoever asks for it", guard.maySave("other"))
        guard.loadRead()
        assertTrue(guard.maySave("confirmed_words"))
    }

    /** A load that failed never gets to [StoredRowGuard.loadRead], so saves stay refused to the end of the process. */
    @Test
    fun aRowThatCouldNotBeReadIsNotOverwritten() {
        val guard = StoredRowGuard(setOf("confirmed_words"))
        guard.loadRead()
        assertTrue(guard.maySave("ac_rejections"))
        guard.readFailed("ac_rejections")
        assertFalse(guard.maySave("ac_rejections"))
        assertTrue("other rows are not affected", guard.maySave("confirmed_words"))
        guard.readWorked("ac_rejections")
        assertTrue(guard.maySave("ac_rejections"))
    }

    @Test
    fun anEmptiedStoreHasNothingASaveCouldLose() {
        val guard = StoredRowGuard(setOf("confirmed_words"))
        guard.readFailed("ac_rejections")
        guard.storeEmptied()
        assertTrue(guard.maySave("confirmed_words"))
        assertTrue(guard.maySave("ac_rejections"))
    }

    @Test
    fun theRootCauseIsWhatGetsReported() {
        val wrapped = RuntimeException("outer", IllegalStateException("middle", java.sql.SQLException("SQLITE_FULL")))
        assertEquals("SQLException: SQLITE_FULL", StorageHealth.describe(wrapped))
        assertEquals("IllegalStateException", StorageHealth.describe(IllegalStateException()))
    }

    @Test
    fun aMessageOfManyLinesIsReportedOnOne() {
        val keystore = RuntimeException("verification failed: finish\n\nCaused by:\n  0: bad tag")
        assertEquals("RuntimeException: verification failed: finish Caused by: 0: bad tag", StorageHealth.describe(keystore))
    }
}
