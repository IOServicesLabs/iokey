package com.localboard.keyboard.data.db

/** A row of the ngrams table by its primary key: prev1, prev2, word. */
typealias NGramKey = Triple<String, String, String>

/**
 * How the personal model sits in the ngrams table. Android and Room stay out of this file, so the
 * JVM tests and the offline checks run this same code and SQL on a real SQLite, with no copy of
 * either to drift from what ships.
 *
 * A unigram is ("", "", word), a bigram (prev1, "", word) and a trigram (prev1, prev2, word).
 */
object NGramRows {

    /**
     * The rows one learned occurrence of [word] counts in, which are the same three the engine
     * counts it in. The loader reads each order from its own rows, so an order not written here is
     * an order a restart forgets.
     */
    fun rowsFor(prev1: String, prev2: String, word: String): List<NGramKey> = buildList(3) {
        add(NGramKey("", "", word))
        if (prev1.isNotEmpty()) {
            add(NGramKey(prev1, "", word))
            if (prev2.isNotEmpty()) add(NGramKey(prev1, prev2, word))
        }
    }

    /**
     * Pieces the old tokenizer cut from contractions, which the upgrade must not turn into words.
     * "don", "can", "won" and "haven" also end up in old rows this way, but they are real words.
     */
    val CONTRACTION_FRAGMENTS: List<String> = listOf(
        "isn", "doesn", "didn", "wouldn", "couldn", "shouldn", "wasn", "weren", "hasn", "hadn",
        "aren", "ain", "mustn", "needn", "mightn", "ve", "ll", "re"
    )

    /**
     * Real words that old rows hold almost only as the front of "don't" and "haven't". Their learned
     * rows go, since nothing can tell the few real uses apart, and the dictionary still knows them.
     */
    val FRAGMENT_SHAPED_WORDS: List<String> = listOf("don", "haven")

    /**
     * "won" is typed for itself too often to go the same way, but "won't" must not promote it. This
     * is below the count that can put a word in the dictionary.
     */
    const val WON_COUNT_LIMIT = 3

    /** What follows the apostrophe. Too short to be learned as words, so old rows only hold these as context. */
    private val CONTRACTION_ENDINGS = listOf("t", "s", "m", "d")

    private fun sqlList(words: List<String>) = words.joinToString(", ") { "'$it'" }

    /**
     * Rebuilds what version 5 could not load. That version wrote each word to one row only, the one
     * exact to its context, so a mid-sentence word had a trigram and nothing else, and the unigram
     * and bigram rows the loader reads never existed. Those rows are derived here, bigrams first so
     * that the unigrams can sum them.
     *
     * The fragments go first, or they would be counted into words the dictionary then trusts. MAX
     * rather than addition on a row that already exists, because imports wrote every order and
     * adding would double them; for typed words that undercounts a little, which is the safe way to
     * be wrong. The WHERE clauses also settle SQLite's parsing of INSERT ... SELECT ... ON CONFLICT.
     *
     * Rows with a piece of a contraction as their context stay through the rebuild and go right
     * after. The word in such a row really was typed, and "know" after "didn't" is much of its
     * count, as "been there" in the row for "i've been there" is a real pair. But the new tokenizer
     * keeps a contraction whole, so these contexts can never come up again.
     *
     * Last, rows taken down to nothing by old decrements stop holding space.
     */
    val MIGRATION_5_TO_6: List<String> = listOf(
        "DELETE FROM ngrams WHERE word IN (" + sqlList(CONTRACTION_FRAGMENTS + FRAGMENT_SHAPED_WORDS) + ")",
        "INSERT INTO ngrams (prev1, prev2, word, count, lastUsed) " +
            "SELECT prev1, '', word, SUM(count), MAX(lastUsed) FROM ngrams " +
            "WHERE prev2 != '' AND prev1 != '' AND count > 0 GROUP BY prev1, word " +
            "ON CONFLICT(prev1, prev2, word) DO UPDATE SET " +
            "count = MAX(count, excluded.count), lastUsed = MAX(lastUsed, excluded.lastUsed)",
        "INSERT INTO ngrams (prev1, prev2, word, count, lastUsed) " +
            "SELECT '', '', word, SUM(count), MAX(lastUsed) FROM ngrams " +
            "WHERE prev1 != '' AND prev2 = '' AND count > 0 GROUP BY word " +
            "ON CONFLICT(prev1, prev2, word) DO UPDATE SET " +
            "count = MAX(count, excluded.count), lastUsed = MAX(lastUsed, excluded.lastUsed)",
        (CONTRACTION_FRAGMENTS + CONTRACTION_ENDINGS).let {
            "DELETE FROM ngrams WHERE prev1 IN (${sqlList(it)}) OR prev2 IN (${sqlList(it)})"
        },
        "UPDATE ngrams SET count = $WON_COUNT_LIMIT WHERE word = 'won' AND count > $WON_COUNT_LIMIT",
        "DELETE FROM ngrams WHERE count <= 0"
    )
}

/** The queries behind [NGramDao], here so that the JVM tests run the text Room ships. */
object NGramSql {
    const val INCREMENT = "UPDATE ngrams SET count = count + :delta, lastUsed = :ts " +
        "WHERE prev1 = :prev1 AND prev2 = :prev2 AND word = :word"
    const val TOP_UNIGRAMS = "SELECT * FROM ngrams WHERE prev1 = '' AND prev2 = '' AND count > 0 " +
        "ORDER BY count DESC LIMIT :limit"
    const val TOP_BIGRAMS = "SELECT * FROM ngrams WHERE prev2 = '' AND prev1 != '' AND count > 0 " +
        "ORDER BY count DESC LIMIT :limit"
    const val TOP_TRIGRAMS = "SELECT * FROM ngrams WHERE prev2 != '' AND prev1 != '' AND count > 0 " +
        "ORDER BY count DESC LIMIT :limit"
    const val WORD_COUNT = "SELECT COUNT(*) FROM ngrams WHERE prev1 = '' AND prev2 = '' AND count > 0"
    const val DELETE_WORD = "DELETE FROM ngrams WHERE word = :word"
    const val DELETE_CONTEXTS = "DELETE FROM ngrams WHERE prev1 IN (:words) OR prev2 IN (:words)"
    const val DELETE_SPENT = "DELETE FROM ngrams WHERE count <= 0"
    const val ADD_COUNT = "INSERT INTO ngrams (prev1, prev2, word, count, lastUsed) " +
        "VALUES (:prev1, :prev2, :word, :delta, :ts) " +
        "ON CONFLICT(prev1, prev2, word) DO UPDATE SET " +
        "count = count + excluded.count, lastUsed = MAX(lastUsed, excluded.lastUsed)"
    const val PRUNE = "DELETE FROM ngrams WHERE count <= 1 AND lastUsed < :olderThan"
}

/**
 * What is on its way to the ngrams table. A failed write puts its batch back here, so that nothing
 * is lost to a full disk or a busy database, and the next flush tries again. Safe from any thread.
 *
 * A store that will not open can leave this filling for days, so it has a limit, past which what was
 * touched longest ago goes first.
 */
class NGramQueue(private val maxRows: Int = MAX_ROWS, private val maxDeletions: Int = MAX_DELETIONS) {

    /** What one flush writes. Deletions apply first, since every increment still here for a
     *  deleted word was learned after that deletion. */
    class Batch internal constructor(
        val deletions: Set<String>,
        val increments: Map<NGramKey, Int>
    )

    private val increments = object : LinkedHashMap<NGramKey, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NGramKey, Int>?) = size > maxRows
    }
    private val deletions: MutableSet<String> = java.util.Collections.newSetFromMap(
        object : LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > maxDeletions
        }
    )

    /** One learned occurrence of [word]. [unigramDelta] is what the engine added to its count,
     *  which for a word picked from the bar is more than one. */
    @Synchronized
    fun addLearned(prev1: String, prev2: String, word: String, unigramDelta: Int = 1) {
        for (key in NGramRows.rowsFor(prev1, prev2, word)) {
            increments.merge(key, if (key.first.isEmpty()) unigramDelta else 1, Int::plus)
        }
    }

    /** One occurrence taken back from each of [keys]. It nets against an increment still queued
     *  and otherwise lands as a decrement on the stored row. */
    @Synchronized
    fun takeBack(keys: Collection<NGramKey>) {
        for (key in keys) increments.merge(key, -1, Int::plus)
    }

    /** What is on its way for [word] goes, and any rows the table holds for it stay. */
    @Synchronized
    fun forgetQueued(word: String) {
        increments.keys.removeAll { it.third == word }
    }

    /** Every row ending in [word] goes, along with whatever is queued for them. */
    @Synchronized
    fun deleteWord(word: String) {
        increments.keys.removeAll { it.third == word }
        deletions.add(word)
    }

    @Synchronized
    fun drain(): Batch {
        val batch = Batch(HashSet(deletions), HashMap(increments))
        deletions.clear()
        increments.clear()
        return batch
    }

    /** A write of [batch] failed. A deletion queued since then is newer than anything in the batch,
     *  so it wins over the batch's increments for that word. */
    @Synchronized
    fun putBack(batch: Batch) {
        for ((key, delta) in batch.increments) {
            if (key.third !in deletions) increments.merge(key, delta, Int::plus)
        }
        deletions.addAll(batch.deletions)
    }

    @Synchronized
    fun clear() {
        increments.clear()
        deletions.clear()
    }

    companion object {
        /** A word typed is up to three rows, so this is some thousands of words. */
        const val MAX_ROWS = 20_000
        const val MAX_DELETIONS = 2_000
    }
}
