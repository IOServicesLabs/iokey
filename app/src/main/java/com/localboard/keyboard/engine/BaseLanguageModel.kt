package com.localboard.keyboard.engine

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.ln

/**
 * The bundled English bigram model: P(word | previous word) for the ~16k
 * most common previous words, ~160k continuations in all.
 *
 * Before this class existed the keyboard had NO general-English notion of
 * what follows what. Next-word prediction was the user's own learned
 * bigrams plus a twelve-word list of sentence starters, so a fresh install
 * — and every context the user had never typed before — got "the", "I",
 * "a" after every space. Completion ranking, autocorrect and the glide
 * decoder were context-blind for the same reason: they scored a candidate
 * by its bare corpus frequency, so "the" beat "think" after "I t" and a
 * glide that could be "here" or "hete" leaned on shape alone.
 *
 * Storage is struct-of-arrays keyed by a compact word id, not a
 * Map<String, Map<String, Int>>: 160k boxed entries and their strings
 * would be ~15 MB of heap in a process that shares the phone with the
 * foreground app. Here it is ~35k strings for the vocabulary and two
 * 160k-int arrays — a couple of MB.
 *
 * Every read is on immutable arrays published once by [load], so this is
 * safe from any thread with no locking.
 *
 * Asset format (`base_bigrams.txt`), one line per previous word:
 *
 *     prev<TAB>next count<TAB>next count ...
 *
 * entries sorted by count descending. Counts are corpus counts / 1000.
 */
class BaseLanguageModel private constructor(
    private val wordId: HashMap<String, Int>,
    private val words: Array<String>,
    /** Per previous-word id: first index into [nextId]/[count], or -1 when
     *  the word has no row. */
    private val rowStart: IntArray,
    /** Per previous-word id: one past the last index of its row. */
    private val rowEnd: IntArray,
    /** Continuation word ids, grouped by row, sorted ASCENDING BY ID within
     *  the row so a single continuation is a binary search. */
    private val nextId: IntArray,
    private val count: IntArray,
    private val rowTotal: LongArray,
    /** Per row: continuation ids in descending count order, capped at
     *  [TOP_PER_ROW]. The next-word bar only ever needs the head. */
    private val topIds: Array<IntArray?>
) {

    val vocabularySize: Int get() = words.size
    val entryCount: Int get() = nextId.size

    private fun idOf(w: String): Int = wordId[w] ?: -1

    /** True when the model has any continuations for [prevLower]. */
    fun hasRow(prevLower: String): Boolean {
        val id = idOf(prevLower)
        return id >= 0 && rowStart[id] >= 0
    }

    /** Number of distinct continuations recorded for [prevLower]. */
    fun rowSize(prevLower: String): Int {
        val id = idOf(prevLower)
        if (id < 0 || rowStart[id] < 0) return 0
        return rowEnd[id] - rowStart[id]
    }

    /** P(word | prev) from the table, 0.0 when the pair is unknown. Both
     *  arguments must already be lowercase. */
    fun prob(prevLower: String, wordLower: String): Double {
        val p = idOf(prevLower)
        if (p < 0) return 0.0
        val start = rowStart[p]
        if (start < 0) return 0.0
        val w = idOf(wordLower)
        if (w < 0) return 0.0
        val c = countAt(start, rowEnd[p], w)
        if (c <= 0) return 0.0
        return c.toDouble() / rowTotal[p].toDouble()
    }

    /**
     * Context score for ranking: `(ln P(word|prev) + LOG_OFFSET) * weight`,
     * floored at zero, so a continuation seen at one in eight thousand
     * scores nothing and the most predictable continuations ("thank" →
     * "you", "want" → "to") score around 8 × weight. 0.0 when the pair is
     * unknown — callers that want backoff to unigram frequency use
     * [effectiveFrequency] instead.
     */
    fun contextScore(prevLower: String, wordLower: String, weight: Double): Double {
        val p = prob(prevLower, wordLower)
        if (p <= 0.0) return 0.0
        val s = ln(p) + LOG_OFFSET
        return if (s > 0.0) s * weight else 0.0
    }

    /**
     * Context-aware "equivalent unigram frequency" of [wordLower] after
     * [prevLower], on the SAME scale as the base dictionary's counts so it
     * can replace a bare `trie.frequency(word)` in any existing formula.
     *
     * When the previous word has a row: `max(backoff × unigramFreq,
     * P(word|prev) × TOTAL)`. The backoff weight is small for a well
     * attested row (most of what plausibly follows "the" is IN the table, so
     * an unlisted continuation really is unlikely) and larger for a thin one.
     * Without a row the unigram frequency is returned untouched — no
     * information is not negative information.
     */
    fun effectiveFrequency(prevLower: String, wordLower: String, unigramFreq: Long): Long {
        val p = idOf(prevLower)
        if (p < 0) return unigramFreq
        val start = rowStart[p]
        if (start < 0) return unigramFreq
        val end = rowEnd[p]
        val backed = (unigramFreq * backoffFor(end - start)).toLong()
        val w = idOf(wordLower)
        if (w < 0) return backed
        val c = countAt(start, end, w)
        if (c <= 0) return backed
        val ctx = (c.toDouble() / rowTotal[p].toDouble() * DICTIONARY_TOTAL).toLong()
        return if (ctx > backed) ctx else backed
    }

    /** The backoff weight [effectiveFrequency] applies after [prevLower]:
     *  1.0 when the word has no row. Lets a caller scale a baseline it
     *  compares candidates against by the same factor. */
    fun backoffWeight(prevLower: String): Double {
        val p = idOf(prevLower)
        if (p < 0) return 1.0
        val start = rowStart[p]
        if (start < 0) return 1.0
        return backoffFor(rowEnd[p] - start)
    }

    private fun backoffFor(rowSize: Int): Double = when {
        rowSize >= 60 -> BACKOFF_WIDE
        rowSize >= 12 -> BACKOFF_MID
        else -> BACKOFF_THIN
    }

    /** Most likely continuations of [prevLower], best first, with their
     *  conditional probabilities. Empty when the word has no row. */
    fun topContinuations(prevLower: String, limit: Int): List<Pair<String, Double>> {
        val p = idOf(prevLower)
        if (p < 0) return emptyList()
        val top = topIds[p] ?: return emptyList()
        val start = rowStart[p]
        val end = rowEnd[p]
        val total = rowTotal[p].toDouble()
        val n = minOf(limit, top.size)
        val out = ArrayList<Pair<String, Double>>(n)
        for (i in 0 until n) {
            val id = top[i]
            out += words[id] to countAt(start, end, id) / total
        }
        return out
    }

    private fun countAt(start: Int, end: Int, id: Int): Int {
        var lo = start
        var hi = end - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = nextId[mid]
            if (v < id) lo = mid + 1
            else if (v > id) hi = mid - 1
            else return count[mid]
        }
        return 0
    }

    companion object {
        /** Sum of the base dictionary's counts — what P(word|prev) is scaled
         *  by to land on the dictionary's frequency scale. */
        const val DICTIONARY_TOTAL = 375_000_000.0
        /** ln(P) offset: P = e^-9 ≈ 1/8000 scores zero. */
        const val LOG_OFFSET = 9.0
        private const val BACKOFF_WIDE = 0.05
        private const val BACKOFF_MID = 0.15
        private const val BACKOFF_THIN = 0.35
        private const val TOP_PER_ROW = 40

        /** Parse the asset. Runs once at startup on an IO thread; ~160k
         *  entries parse in a few hundred milliseconds on a phone. */
        fun load(stream: InputStream): BaseLanguageModel {
            val wordId = HashMap<String, Int>(40_000)
            val words = ArrayList<String>(40_000)
            fun intern(w: String): Int {
                val existing = wordId[w]
                if (existing != null) return existing
                val id = words.size
                words += w
                wordId[w] = id
                return id
            }
            // Collected first, laid out second: continuation ids are only
            // final once every word has been interned.
            val rowPrev = ArrayList<Int>(17_000)
            val rowEntries = ArrayList<IntArray>(17_000)   // [id0,c0,id1,c1,...] count-descending
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 1 shl 16).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty()) continue
                    val parts = line.split('\t')
                    if (parts.size < 2) continue
                    val prev = intern(parts[0])
                    val flat = IntArray((parts.size - 1) * 2)
                    var n = 0
                    for (k in 1 until parts.size) {
                        val e = parts[k]
                        val sp = e.indexOf(' ')
                        if (sp <= 0) continue
                        val c = e.substring(sp + 1).toIntOrNull() ?: continue
                        if (c <= 0) continue
                        flat[n++] = intern(e.substring(0, sp))
                        flat[n++] = c
                    }
                    if (n == 0) continue
                    rowPrev += prev
                    rowEntries += if (n == flat.size) flat else flat.copyOf(n)
                }
            }
            val vocab = words.size
            val rowStart = IntArray(vocab) { -1 }
            val rowEnd = IntArray(vocab)
            val rowTotal = LongArray(vocab)
            val topIds = arrayOfNulls<IntArray>(vocab)
            var total = 0
            for (e in rowEntries) total += e.size / 2
            val nextId = IntArray(total)
            val count = IntArray(total)
            var cursor = 0
            for (ri in rowEntries.indices) {
                val prev = rowPrev[ri]
                val e = rowEntries[ri]
                val n = e.size / 2
                if (rowStart[prev] >= 0) continue   // duplicate row: keep the first
                rowStart[prev] = cursor
                // Top-K by count: entries arrive count-descending.
                val k = minOf(TOP_PER_ROW, n)
                topIds[prev] = IntArray(k) { e[it * 2] }
                // Row body sorted by continuation id for binary search.
                val idx = (0 until n).sortedBy { e[it * 2] }
                var sum = 0L
                for (i in 0 until n) {
                    val src = idx[i]
                    nextId[cursor + i] = e[src * 2]
                    count[cursor + i] = e[src * 2 + 1]
                    sum += e[src * 2 + 1]
                }
                rowTotal[prev] = sum
                cursor += n
                rowEnd[prev] = cursor
            }
            return BaseLanguageModel(
                wordId, words.toTypedArray(), rowStart, rowEnd,
                if (cursor == total) nextId else nextId.copyOf(cursor),
                if (cursor == total) count else count.copyOf(cursor),
                rowTotal, topIds
            )
        }
    }
}
