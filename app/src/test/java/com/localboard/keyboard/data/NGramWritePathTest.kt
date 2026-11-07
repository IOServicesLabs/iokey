package com.localboard.keyboard.data

import com.localboard.keyboard.data.db.NGramKey
import com.localboard.keyboard.data.db.NGramQueue
import com.localboard.keyboard.data.db.NGramRows
import com.localboard.keyboard.data.db.applyBatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** From what the engine learned to the rows the next start loads, on a real SQLite. */
class NGramWritePathTest {
    private val now = 1_000L

    /** A flush as ContextEngine runs one: the batch in a transaction, put back if it fails. */
    private suspend fun flush(s: V5Store, q: NGramQueue, dao: JdbcNGramDao = JdbcNGramDao(s.db)) {
        val batch = q.drain()
        s.db.autoCommit = false
        try {
            dao.applyBatch(batch, now)
            s.db.commit()
        } catch (t: Throwable) {
            s.db.rollback()
            q.putBack(batch)
            throw t
        } finally {
            s.db.autoCommit = true
        }
    }

    /** A store that will not open can leave the queue filling for days. */
    @Test
    fun aQueueAtItsLimitKeepsTheNewest() {
        val q = NGramQueue(maxRows = 10, maxDeletions = 3)
        repeat(40) { q.addLearned("", "", "word$it") }
        repeat(5) { q.deleteWord("gone$it") }
        val batch = q.drain()
        assertEquals((30 until 40).map { "word$it" }.toSet(), batch.increments.keys.map { it.third }.toSet())
        assertEquals(setOf("gone2", "gone3", "gone4"), batch.deletions)
    }

    @Test
    fun aRowStillBeingLearnedOutlastsOnesThatStopped() {
        val q = NGramQueue(maxRows = 5)
        q.addLearned("", "", "everyday")
        repeat(20) {
            q.addLearned("", "", "word$it")
            q.addLearned("", "", "everyday")
        }
        val queued = q.drain().increments
        assertEquals(21, queued[NGramKey("", "", "everyday")])
    }

    @Test
    fun aBatchPutBackStaysWithinTheLimit() {
        val q = NGramQueue(maxRows = 10)
        repeat(10) { q.addLearned("", "", "old$it") }
        val batch = q.drain()
        repeat(5) { q.addLearned("", "", "new$it") }
        q.putBack(batch)
        assertEquals(10, q.drain().increments.size)
    }

    @Test
    fun rowsForWritesTheOrdersTheEngineCounts() {
        assertEquals(
            listOf(NGramKey("", "", "word")),
            NGramRows.rowsFor("", "", "word"),
        )
        assertEquals(
            listOf(NGramKey("", "", "word"), NGramKey("a", "", "word")),
            NGramRows.rowsFor("a", "", "word"),
        )
        assertEquals(
            listOf(NGramKey("", "", "word"), NGramKey("a", "", "word"), NGramKey("a", "b", "word")),
            NGramRows.rowsFor("a", "b", "word"),
        )
        // FastEngine.learn counts a trigram only with both words, and a bigram from prev1 alone.
        assertEquals(listOf(NGramKey("", "", "word")), NGramRows.rowsFor("", "b", "word"))
    }

    @Test
    fun aWordTypedMidSentenceLoadsInEveryOrder() = runBlocking {
        V5Store().use { s ->
            val q = NGramQueue()
            repeat(4) { q.addLearned("so", "well", "zorblax") }
            flush(s, q)
            repeat(6) { q.addLearned("so", "well", "zorblax") }
            flush(s, q)
            val dao = JdbcNGramDao(s.db)
            assertEquals(listOf("zorblax" to 10), dao.topUnigrams(10).map { it.word to it.count })
            assertEquals(listOf("so" to 10), dao.topBigrams(10).map { it.prev1 to it.count })
            assertEquals(listOf(Triple("so", "well", 10)), dao.topTrigrams(10).map { Triple(it.prev1, it.prev2, it.count) })
            assertEquals(1, dao.wordCount())
            assertEquals(3, dao.count())
        }
    }

    @Test
    fun aWordPickedFromTheBarCountsWhatTheEngineCounted() = runBlocking {
        V5Store().use { s ->
            val q = NGramQueue()
            q.addLearned("so", "well", "zorblax", unigramDelta = 4)
            flush(s, q)
            assertEquals(4 to now, s.row("", "", "zorblax"))
            assertEquals(1 to now, s.row("so", "", "zorblax"))
            assertEquals(1 to now, s.row("so", "well", "zorblax"))
        }
    }

    @Test
    fun anUndoBeforeTheFlushLeavesNoRow() = runBlocking {
        V5Store().use { s ->
            val q = NGramQueue()
            q.addLearned("so", "well", "zorblax")
            q.takeBack(NGramRows.rowsFor("so", "well", "zorblax"))
            flush(s, q)
            assertEquals(0, s.count("ngrams"))
        }
    }

    @Test
    fun anUndoAfterTheFlushTakesOneFromEveryOrder() = runBlocking {
        V5Store().use { s ->
            val q = NGramQueue()
            repeat(2) { q.addLearned("so", "well", "zorblax") }
            q.addLearned("now", "oh", "zorblax")
            flush(s, q)
            q.takeBack(NGramRows.rowsFor("so", "well", "zorblax"))
            flush(s, q)
            assertEquals(2 to now, s.row("", "", "zorblax"))
            assertEquals(1 to now, s.row("so", "", "zorblax"))
            assertEquals(1 to now, s.row("so", "well", "zorblax"))
            // A row taken to nothing goes, and the other context is left alone.
            q.takeBack(NGramRows.rowsFor("so", "well", "zorblax"))
            flush(s, q)
            assertNull(s.row("so", "", "zorblax"))
            assertNull(s.row("so", "well", "zorblax"))
            assertEquals(1 to now, s.row("", "", "zorblax"))
            assertEquals(1 to now, s.row("now", "", "zorblax"))
        }
    }

    /** All a closed store's memory knows of a word is what this process queued, and the table holds the rest. */
    @Test
    fun forgettingWhatIsQueuedLeavesTheStoredRowsAlone() = runBlocking {
        V5Store().use { s ->
            val q = NGramQueue()
            repeat(5) { q.addLearned("so", "well", "zorblax") }
            flush(s, q)
            q.addLearned("now", "oh", "zorblax")
            q.addLearned("now", "oh", "other")
            q.forgetQueued("zorblax")
            flush(s, q)
            assertEquals(5 to now, s.row("", "", "zorblax"))
            assertEquals(5 to now, s.row("so", "", "zorblax"))
            assertEquals(5 to now, s.row("so", "well", "zorblax"))
            assertNull(s.row("now", "", "zorblax"))
            assertEquals(1 to now, s.row("", "", "other"))
        }
    }

    @Test
    fun aDecrementWithNoRowWritesNothing() = runBlocking {
        V5Store().use { s ->
            val q = NGramQueue()
            q.takeBack(NGramRows.rowsFor("so", "well", "zorblax"))
            flush(s, q)
            assertEquals(0, s.count("ngrams"))
        }
    }

    @Test
    fun aDeletedWordCannotBeBroughtBackByWhatWasQueued() = runBlocking {
        V5Store().use { s ->
            val q = NGramQueue()
            q.addLearned("so", "well", "zorblax")
            flush(s, q)
            q.addLearned("so", "well", "zorblax")
            q.deleteWord("zorblax")
            flush(s, q)
            assertEquals(0, s.count("ngrams"))
        }
    }

    @Test
    fun aWordLearnedAgainAfterItsDeletionStays() = runBlocking {
        V5Store().use { s ->
            val q = NGramQueue()
            q.addLearned("so", "well", "zorblax")
            q.addLearned("so", "oh", "zorblax")
            flush(s, q)
            q.deleteWord("zorblax")
            q.addLearned("now", "oh", "zorblax")
            flush(s, q)
            assertEquals(1 to now, s.row("", "", "zorblax"))
            assertEquals(1 to now, s.row("now", "oh", "zorblax"))
            assertNull(s.row("so", "well", "zorblax"))
            assertNull(s.row("so", "oh", "zorblax"))
            assertNull(s.row("so", "", "zorblax"))
        }
    }

    /** Fails once, part way through the batch, after some rows were written. */
    private class ThrowsOnce(s: V5Store) : JdbcNGramDao(s.db) {
        private var calls = 0
        var thrown = false
        override suspend fun increment(prev1: String, prev2: String, word: String, delta: Int, ts: Long): Int {
            if (!thrown && ++calls == 3) {
                thrown = true
                throw java.sql.SQLException("database is locked")
            }
            return super.increment(prev1, prev2, word, delta, ts)
        }
    }

    @Test
    fun aWriteThatFailsLosesNothingAndCountsNothingTwice() = runBlocking {
        V5Store().use { s ->
            val q = NGramQueue()
            repeat(2) { q.addLearned("so", "well", "zorblax") }
            flush(s, q)
            repeat(3) { q.addLearned("so", "well", "zorblax") }
            q.addLearned("so", "well", "kubectl")
            q.deleteWord("oldword")
            s.ngram("", "", "oldword", 5, now)

            val failing = ThrowsOnce(s)
            assertThrows(java.sql.SQLException::class.java) { runBlocking { flush(s, q, failing) } }
            assertEquals("the failure did happen", true, failing.thrown)
            assertEquals("rolled back", 2 to now, s.row("", "", "zorblax"))
            assertEquals("rolled back", 5 to now, s.row("", "", "oldword"))
            assertNull("rolled back", s.row("", "", "kubectl"))

            // More is typed before the retry, and all of it lands.
            q.addLearned("so", "well", "zorblax")
            flush(s, q, failing)
            assertEquals(6 to now, s.row("", "", "zorblax"))
            assertEquals(6 to now, s.row("so", "", "zorblax"))
            assertEquals(6 to now, s.row("so", "well", "zorblax"))
            assertEquals(1 to now, s.row("", "", "kubectl"))
            assertNull(s.row("", "", "oldword"))
        }
    }

    @Test
    fun aDeletionNewerThanAFailedBatchWinsOverIt() {
        val q = NGramQueue()
        q.addLearned("so", "well", "zorblax")
        val failed = q.drain()
        q.deleteWord("zorblax")
        q.putBack(failed)
        val retry = q.drain()
        assertEquals(setOf("zorblax"), retry.deletions)
        assertEquals(emptyMap<NGramKey, Int>(), retry.increments)
    }

    @Test
    fun anIncrementNewerThanADeletionInAFailedBatchSurvivesIt() {
        val q = NGramQueue()
        q.deleteWord("zorblax")
        q.addLearned("", "", "zorblax")
        val failed = q.drain()
        q.putBack(failed)
        val retry = q.drain()
        assertEquals(setOf("zorblax"), retry.deletions)
        assertEquals(mapOf(NGramKey("", "", "zorblax") to 1), retry.increments)
    }

    @Test
    fun clearDropsEverything() {
        val q = NGramQueue()
        q.addLearned("so", "well", "zorblax")
        q.deleteWord("other")
        q.clear()
        val batch = q.drain()
        assertEquals(emptySet<String>(), batch.deletions)
        assertEquals(emptyMap<NGramKey, Int>(), batch.increments)
    }

    @Test
    fun pruningLeavesRowsThatEarnedTheirPlace() = runBlocking {
        V5Store().use { s ->
            s.ngram("", "", "oneoff", 1, 10)
            s.ngram("", "", "recent", 1, 5_000)
            s.ngram("", "", "kept", 2, 10)
            JdbcNGramDao(s.db).prune(1_000)
            assertNull(s.row("", "", "oneoff"))
            assertEquals(1 to 5_000L, s.row("", "", "recent"))
            assertEquals(2 to 10L, s.row("", "", "kept"))
        }
    }
}
