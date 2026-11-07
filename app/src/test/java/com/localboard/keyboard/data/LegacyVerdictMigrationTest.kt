package com.localboard.keyboard.data

import com.localboard.keyboard.data.db.LegacyVerdictMigration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-shot on a real SQLite, in the order it runs on a phone: after Room's migration. */
class LegacyVerdictMigrationTest {
    private val ts = 1_700_000_000_000L

    private fun V5Store.styleStat(key: String, value: String) {
        db.prepareStatement("INSERT OR REPLACE INTO style_stats VALUES (?, ?)").use {
            it.setString(1, key)
            it.setString(2, value)
            it.executeUpdate()
        }
    }

    @Test
    fun aStruckWordLosesItsContextRowsTooAfterRoomRebuiltThem() = runBlocking {
        V5Store().use { s ->
            // A real typo the old rules struck, with history only in trigrams, and a word never struck.
            s.ngram("so", "well", "teh", 9, ts)
            s.ngram("now", "well", "teh", 2, ts)
            s.ngram("so", "well", "oof", 12, ts)
            // Struck words in each stored shape.
            s.ngram("so", "well", "thsi", 3, ts)
            s.ngram("so", "well", "wiht", 3, ts)
            s.ngram("so", "well", "me@site.com", 3, ts)
            s.styleStat("typo_strikes", "teh=1@$ts,thsi=1@$ts")
            s.styleStat("typo_suppressions", "wiht,me@site.com@$ts")
            s.styleStat("confirmed_words", "oof")

            s.migrate()
            assertEquals("Room rebuilt the row the old loader kept filtered", 11, s.row("", "", "teh")!!.first)
            val before = s.counts()

            assertTrue(LegacyVerdictMigration.runOnce(JdbcNGramDao(s.db), JdbcStyleStatDao(s.db)))
            println("one-shot, rows before: $before")
            println("one-shot, rows after:  ${s.counts()}")

            for (word in listOf("teh", "thsi", "wiht", "me@site.com")) {
                assertEquals(word, 0, s.count("ngrams", "word = '$word'"))
            }
            // What is not struck keeps everything.
            assertEquals(12, s.row("", "", "oof")!!.first)
            assertEquals(12, s.row("so", "", "oof")!!.first)
            assertEquals(12, s.row("so", "well", "oof")!!.first)
            // And a word that was only part of an email entry was not blamed for it.
            val stats = JdbcStyleStatDao(s.db)
            assertEquals("", stats.get("typo_strikes"))
            assertEquals("", stats.get("typo_suppressions"))
            assertEquals("oof", stats.get("confirmed_words"))
            assertEquals("1", stats.get(LegacyVerdictMigration.DONE_KEY))
            // The list a restore checks an old backup against, in a row that is no verdict.
            assertEquals(
                setOf("teh", "thsi", "wiht", "me@site.com"),
                LegacyVerdictMigration.struckWordsIn(stats.get(LegacyVerdictMigration.STRUCK_WORDS_KEY))
            )
        }
    }

    /** As context a struck word is no use, and what follows it was typed all the same, so that keeps its count. */
    @Test
    fun aStruckWordLeavesTheContextColumnsAndNoOtherWordLosesAnything() = runBlocking {
        V5Store().use { s ->
            s.ngram("mirble", "", "so", 2, ts)
            s.ngram("mirble", "well", "soon", 3, ts)
            s.ngram("then", "mirble", "they", 4, ts)
            s.styleStat("typo_strikes", "mirble=1@$ts")
            s.migrate()

            LegacyVerdictMigration.runOnce(JdbcNGramDao(s.db), JdbcStyleStatDao(s.db))

            assertEquals(0, s.count("ngrams", "word = 'mirble' OR prev1 = 'mirble' OR prev2 = 'mirble'"))
            assertEquals(2, s.row("", "", "so")!!.first)
            assertEquals(3, s.row("", "", "soon")!!.first)
            assertEquals(4, s.row("", "", "they")!!.first)
            assertEquals("a pair the struck word only came before stays", 4, s.row("then", "", "they")!!.first)
        }
    }

    /** More struck words than one statement can bind, over a store the size of months of typing. Prints the time. */
    @Test
    fun aLongListOverALargeStoreRuns() = runBlocking {
        V5Store().use { s ->
            s.inTransaction {
                s.db.prepareStatement("INSERT INTO ngrams VALUES (?, ?, ?, 2, 1)").use {
                    for (i in 0 until 100_000) {
                        it.setString(1, "p${i % 700}")
                        it.setString(2, "q${i % 11}")
                        it.setString(3, "w$i")
                        it.executeUpdate()
                    }
                }
            }
            val struck = (0 until 700).map { "p$it" }
            s.styleStat("typo_strikes", struck.joinToString(",") { "$it=1@$ts" })

            val started = System.nanoTime()
            LegacyVerdictMigration.runOnce(JdbcNGramDao(s.db), JdbcStyleStatDao(s.db))
            val ms = (System.nanoTime() - started) / 1_000_000
            println("one-shot on plain SQLite: ${struck.size} struck words over 100000 rows in $ms ms")

            assertEquals(0, s.count("ngrams"))
        }
    }

    /** What the keyboard would offer next is read from these rows, so none may name the word. */
    @Test
    fun nothingLoadedAfterwardsCanOfferAStruckWord() = runBlocking {
        V5Store().use { s ->
            s.ngram("so", "well", "teh", 9, ts)
            s.styleStat("typo_strikes", "teh=1@$ts")
            s.migrate()
            val ngrams = JdbcNGramDao(s.db)
            assertEquals("teh", ngrams.topBigrams(10).single().word)
            LegacyVerdictMigration.runOnce(ngrams, JdbcStyleStatDao(s.db))
            assertTrue(ngrams.topUnigrams(10).isEmpty())
            assertTrue(ngrams.topBigrams(10).isEmpty())
            assertTrue(ngrams.topTrigrams(10).isEmpty())
        }
    }

    @Test
    fun itRunsOnceSoLaterVerdictsAreLeftAlone() = runBlocking {
        V5Store().use { s ->
            val ngrams = JdbcNGramDao(s.db)
            val stats = JdbcStyleStatDao(s.db)
            assertTrue(LegacyVerdictMigration.runOnce(ngrams, stats))

            s.ngram("so", "well", "wrod", 2, ts)
            s.styleStat("typo_strikes", "wrod=1@$ts")
            assertFalse(LegacyVerdictMigration.runOnce(ngrams, stats))
            assertEquals(2 to ts, s.row("so", "well", "wrod"))
            assertEquals("wrod=1@$ts", stats.get("typo_strikes"))
        }
    }

    @Test
    fun anEraseKeepsTheMarkerSoItDoesNotRunAgain() = runBlocking {
        V5Store().use { s ->
            val ngrams = JdbcNGramDao(s.db)
            val stats = JdbcStyleStatDao(s.db)
            LegacyVerdictMigration.runOnce(ngrams, stats)
            // As ContextEngine wipes the table.
            stats.clear()
            stats.putAll(listOf(LegacyVerdictMigration.doneRow()))

            s.styleStat("typo_strikes", "wrod=1@$ts")
            assertFalse(LegacyVerdictMigration.runOnce(ngrams, stats))
            assertEquals("wrod=1@$ts", stats.get("typo_strikes"))
        }
    }

    @Test
    fun aNewInstallJustGetsTheMarker() = runBlocking {
        V5Store().use { s ->
            val stats = JdbcStyleStatDao(s.db)
            assertNull(stats.get(LegacyVerdictMigration.DONE_KEY))
            assertTrue(LegacyVerdictMigration.runOnce(JdbcNGramDao(s.db), stats))
            assertEquals("1", stats.get(LegacyVerdictMigration.DONE_KEY))
            assertEquals(0, s.count("ngrams"))
        }
    }
}
