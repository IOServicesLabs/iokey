package com.localboard.keyboard.data

import com.localboard.keyboard.data.db.NGramRows
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upgrade from version 5 on a real SQLite: the rows a version 5 store really holds, of every
 * kind, go through the statements the app ships. Words here are invented or ordinary, never anyone's
 * typing.
 */
class Room5To6MigrationTest {
    private val t1 = 1_000L
    private val t2 = 2_000L
    private val t3 = 3_000L

    /** Every kind of row version 5 can hold, and rows from the other tables the upgrade must not touch. */
    private fun V5Store.fillWithEveryKindOfOldRow() {
        // The first word of a field: version 5 wrote only a unigram.
        ngram("", "", "hello", 5, t1)
        // The second word of a field: only a bigram.
        ngram("hello", "", "there", 3, t1)
        // Mid-sentence, which is almost everything: only trigrams, across contexts.
        ngram("so", "well", "zorblax", 4, t1)
        ngram("so", "oh", "zorblax", 6, t2)
        ngram("now", "oh", "zorblax", 2, t3)
        // A bigram that already holds more than its trigrams sum to.
        ngram("so", "", "kubectl", 7, t3)
        ngram("so", "oh", "kubectl", 3, t1)
        // A unigram that already holds more than what would be derived.
        ngram("", "", "okayy", 20, t1)
        ngram("so", "oh", "okayy", 2, t2)
        // An import wrote every order, with consistent counts, and must not be doubled.
        ngram("", "", "mibby", 30, t1)
        ngram("so", "", "mibby", 12, t1)
        ngram("oh", "", "mibby", 18, t1)
        ngram("so", "oh", "mibby", 12, t1)
        // Contraction fragments, in every order, which must not be aggregated.
        ngram("", "", "ve", 9, t1)
        ngram("i", "", "ve", 9, t1)
        ngram("so", "i", "ve", 9, t1)
        for (fragment in NGramRows.CONTRACTION_FRAGMENTS - "ve") ngram("so", "i", fragment, 2, t1)
        // Real words that also turn up as pieces of contractions, and an actual contraction.
        for (word in listOf("don", "can", "won", "haven", "don't")) ngram("so", "i", word, 3, t1)
        // Enough "won't" to promote "won", if nothing stopped it.
        ngram("now", "oh", "won", 7, t2)
        // A fragment as context, in each column, as "i've been", "isn't soon", "i've so they" and
        // "don't know what" were stored. Each word after it really was typed.
        ngram("ve", "i", "been", 3, t2)
        ngram("ll", "", "soon", 3, t2)
        ngram("t", "isn", "soon", 3, t2)
        ngram("so", "ve", "they", 3, t2)
        ngram("t", "don", "know", 4, t2)
        ngram("know", "t", "what", 2, t2)
        // Rows old decrements took to nothing, with and without other rows for the word.
        ngram("", "", "gone", 0, t1)
        ngram("", "", "sunk", -1, t1)
        ngram("", "", "undone", 0, t1)
        ngram("a", "b", "undone", 2, t2)
        // The sentence mark is a context like any other.
        ngram(".", "well", "next", 4, t1)
        // A key no code writes, which must not pass for a unigram.
        ngram("", "x", "odd", 3, t1)

        exec("INSERT INTO style_stats VALUES ('profile', '{}'), ('confirmed_words', 'oof'), ('typo_strikes', 'teh=1@5')")
        exec("INSERT INTO app_context VALUES ('some.app', '[]', 1)")
        exec("INSERT INTO usage_stats VALUES ('words_learned_total', 77)")
        exec("INSERT INTO typing_log (ts, appPackage, screenKey, text, origin) VALUES (1, 'some.app', '', 'see you there', 0), (2, 'some.app', '', 'a draft', 1)")
        exec("INSERT INTO distillations (ts, source, notes) VALUES (1, 'night', 'short sentences')")
    }

    @Test
    fun everyKindOfOldRowEndsUpWhereItBelongs() {
        V5Store().use { s ->
            s.fillWithEveryKindOfOldRow()
            val before = s.counts()
            s.migrate()
            val after = s.counts()
            println("Room 5 to 6, rows before: $before")
            println("Room 5 to 6, rows after:  $after")

            // A first-in-field word stands as it was.
            assertEquals(5 to t1, s.row("", "", "hello"))

            // A second-in-field word gains the unigram it lacked.
            assertEquals(3 to t1, s.row("hello", "", "there"))
            assertEquals(3 to t1, s.row("", "", "there"))

            // A mid-sentence word is what this is for: it gets back every order.
            assertEquals(10 to t2, s.row("so", "", "zorblax"))
            assertEquals(2 to t3, s.row("now", "", "zorblax"))
            assertEquals(12 to t3, s.row("", "", "zorblax"))
            assertEquals(4 to t1, s.row("so", "well", "zorblax"))
            assertEquals(6 to t2, s.row("so", "oh", "zorblax"))

            // An existing row keeps the larger count.
            assertEquals(7 to t3, s.row("so", "", "kubectl"))
            assertEquals(7 to t3, s.row("", "", "kubectl"))
            assertEquals(20 to t2, s.row("", "", "okayy"))
            assertEquals(2 to t2, s.row("so", "", "okayy"))

            // Imported rows are not doubled.
            assertEquals(30 to t1, s.row("", "", "mibby"))
            assertEquals(12 to t1, s.row("so", "", "mibby"))
            assertEquals(18 to t1, s.row("oh", "", "mibby"))

            // Every fragment has gone, in every order.
            val list = NGramRows.CONTRACTION_FRAGMENTS.joinToString { "'$it'" }
            assertEquals(0, s.count("ngrams", "word IN ($list)"))

            // Old rows hold these almost only as the front of a contraction, so they go, in every order.
            for (word in listOf("don", "haven")) assertEquals(word, 0, s.count("ngrams", "word = '$word'"))

            // A real word and a contraction survive and are aggregated like any other.
            for (word in listOf("can", "don't")) {
                assertEquals(word, 3 to t1, s.row("so", "i", word))
                assertEquals(word, 3 to t1, s.row("so", "", word))
                assertEquals(word, 3 to t1, s.row("", "", word))
            }

            // "won" stays, at no more than could leave it short of the dictionary, in every order.
            assertEquals(3 to t1, s.row("so", "i", "won"))
            assertEquals(3 to t2, s.row("now", "oh", "won"))
            assertEquals(3 to t1, s.row("so", "", "won"))
            assertEquals(3 to t2, s.row("now", "", "won"))
            assertEquals(3 to t2, s.row("", "", "won"))

            // No fragment is left as a context, in either column, and neither is what follows an apostrophe.
            val contexts = (NGramRows.CONTRACTION_FRAGMENTS + listOf("t", "s", "m", "d")).joinToString { "'$it'" }
            assertEquals(0, s.count("ngrams", "prev1 IN ($contexts) OR prev2 IN ($contexts)"))

            // The word after one really was typed, so it keeps all it counted for.
            assertNull(s.row("ve", "i", "been"))
            assertNull(s.row("ve", "", "been"))
            assertEquals(3 to t2, s.row("", "", "been"))
            assertNull(s.row("ll", "", "soon"))
            assertNull(s.row("t", "isn", "soon"))
            assertNull(s.row("t", "", "soon"))
            assertEquals("counted from both", 6 to t2, s.row("", "", "soon"))
            assertNull(s.row("t", "don", "know"))
            assertNull(s.row("t", "", "know"))
            assertEquals(4 to t2, s.row("", "", "know"))

            // And a real pair that a fragment only came before stays a pair.
            assertNull(s.row("so", "ve", "they"))
            assertEquals(3 to t2, s.row("so", "", "they"))
            assertEquals(3 to t2, s.row("", "", "they"))
            assertNull(s.row("know", "t", "what"))
            assertEquals(2 to t2, s.row("know", "", "what"))
            assertEquals(2 to t2, s.row("", "", "what"))

            // Emptied rows go, unless the word has counts elsewhere.
            assertNull(s.row("", "", "gone"))
            assertNull(s.row("", "", "sunk"))
            assertEquals(2 to t2, s.row("", "", "undone"))
            assertEquals(4 to t1, s.row(".", "", "next"))
            assertEquals(4 to t1, s.row("", "", "next"))

            // A malformed key is left alone and never passes for a unigram.
            assertEquals(3 to t1, s.row("", "x", "odd"))
            assertNull(s.row("", "", "odd"))
            assertEquals(1, after["malformed rows"])

            // Nothing outside the table moved.
            for (table in listOf("style_stats", "app_context", "usage_stats", "typing_log", "distillations")) {
                assertEquals(table, before[table], after[table])
            }
            // The exact totals, so any row that appears from nowhere fails here. Unigrams: 7, less
            // the fragment, plus 12 derived, less the 2 emptied. Bigrams: 6, less the fragment, plus
            // 14 derived, less the 4 with a fragment as context. Trigrams: 37, less the 18 fragments
            // and the 2 words that go with them, less the 5 with a fragment as context.
            assertEquals(mapOf("unigram" to 16, "bigram" to 15, "trigram" to 12), mapOf(
                "unigram" to after.getValue("unigram rows"),
                "bigram" to after.getValue("bigram rows"),
                "trigram" to after.getValue("trigram rows"),
            ))
        }
    }

    @Test
    fun theLoaderSeesEachOrderOnceAndNothingSpent() = runBlocking {
        V5Store().use { s ->
            s.fillWithEveryKindOfOldRow()
            s.migrate()
            val dao = JdbcNGramDao(s.db)
            val unigrams = dao.topUnigrams(10_000)
            val bigrams = dao.topBigrams(32_000)
            val trigrams = dao.topTrigrams(24_000)
            assertTrue(unigrams.all { it.prev1.isEmpty() && it.prev2.isEmpty() && it.count > 0 })
            assertTrue(bigrams.all { it.prev1.isNotEmpty() && it.prev2.isEmpty() && it.count > 0 })
            assertTrue(trigrams.all { it.prev1.isNotEmpty() && it.prev2.isNotEmpty() && it.count > 0 })
            assertEquals(s.count("ngrams") - 1, unigrams.size + bigrams.size + trigrams.size)
            assertEquals("the largest count comes first", "mibby", unigrams.first().word)
            assertTrue("zorblax" in unigrams.map { it.word })
        }
    }

    @Test
    fun aMigrationThatFailsLeavesVersion5AsItWas() {
        V5Store().use { s ->
            s.fillWithEveryKindOfOldRow()
            val before = s.counts()
            assertThrows(java.sql.SQLException::class.java) {
                // Fails after the last real statement, with all of them applied inside the transaction.
                s.migrate(NGramRows.MIGRATION_5_TO_6 + "INSERT INTO no_such_table VALUES (1)")
            }
            assertEquals(before, s.counts())
            assertEquals("fragments are still there to try again", 9 to t1, s.row("", "", "ve"))
            assertNull("nothing was derived", s.row("", "", "zorblax"))

            // The next open simply succeeds.
            s.migrate()
            assertEquals(12 to t3, s.row("", "", "zorblax"))
        }
    }

    @Test
    fun anEmptyStoreMigrates() {
        V5Store().use { s ->
            s.migrate()
            assertEquals(0, s.count("ngrams"))
        }
    }

    @Test
    fun theFragmentListHoldsNoRealWord() {
        for (word in listOf("don", "can", "won", "haven")) {
            assertTrue(word, word !in NGramRows.CONTRACTION_FRAGMENTS)
        }
    }

    /**
     * A store with months of history. This asserts no time, which would only fail on a busy machine. It prints
     * the times at two sizes, because what would hurt on a phone is a statement that grows faster than the rows
     * do. The words are invented, and some of them are contraction pieces, so that the deletes have rows to find.
     */
    @Test
    fun aLargeStoreMigrates() {
        for (target in listOf(100_000, 200_000)) {
            V5Store().use { s ->
                s.fillWithInventedHistory(target)
                val rows = s.count("ngrams")
                val started = System.nanoTime()
                s.migrate()
                val ms = (System.nanoTime() - started) / 1_000_000
                println("Room 5 to 6 on plain SQLite: $rows rows in $ms ms, ${s.count("ngrams")} rows after")
                assertEquals(0, s.count("ngrams", "word IN ('ve', 'don')"))
                assertTrue(s.count("ngrams", "prev1 = '' AND prev2 = ''") > 0)
            }
        }
    }

    private fun V5Store.fillWithInventedHistory(target: Int) {
        val random = java.util.Random(7)
        val vocabulary = List(30_000) { "w$it" } + NGramRows.CONTRACTION_FRAGMENTS + listOf("t", "s", "m", "d", "don")
        // Common words far more often than rare ones, as in anything typed.
        fun pick() = vocabulary[(vocabulary.size * Math.pow(random.nextDouble(), 3.0)).toInt()]
        inTransaction {
            db.prepareStatement("INSERT OR REPLACE INTO ngrams VALUES (?, ?, ?, ?, ?)").use {
                var written = 0
                while (written < target) {
                    it.setString(1, pick())
                    it.setString(2, pick())
                    it.setString(3, pick())
                    it.setInt(4, 1 + random.nextInt(6))
                    it.setLong(5, t1)
                    it.executeUpdate()
                    written++
                }
            }
        }
    }
}
