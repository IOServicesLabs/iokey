package com.localboard.keyboard.memory

import kotlin.math.exp
import kotlin.math.ln

data class MemoryDoc(
    val id: Long,
    val text: String,
    val ts: Long,
    val speaker: Speaker = Speaker.UNKNOWN,
    val sender: String = ""
)

data class Scored(val doc: MemoryDoc, val score: Double)

/** Keyword retrieval over one conversation's older messages. No embeddings: small,
 *  explainable, and it never needs a second model in memory. */
object Bm25 {
    /** Marks count as part of a word: many scripts write their vowels with them. */
    private val TOKEN = Regex("[\\p{L}\\p{M}\\p{N}']+")
    private val STOP = setOf(
        "the", "a", "an", "and", "or", "but", "to", "of", "in", "on", "at", "for", "is", "are",
        "was", "were", "be", "been", "it", "its", "it's", "i", "i'm", "you", "we", "they", "he",
        "she", "me", "my", "your", "our", "that", "this", "do", "did", "does", "so", "if", "not",
        "no", "yes", "ok", "okay", "yeah", "yep", "lol", "just", "with", "have", "has", "had",
        "will", "would", "can", "could", "what", "when", "where", "who", "how", "why", "there",
        "then", "than", "about", "am", "up", "out", "get", "got"
    )

    fun tokens(s: String): List<String> =
        TOKEN.findAll(s.lowercase()).map { it.value.trim('\'') }
            .filter { it.length >= 2 && it !in STOP }.toList()

    /** One conversation only, at most a couple of thousand rows, run off the main thread. */
    fun search(
        query: String,
        docs: List<MemoryDoc>,
        topK: Int,
        nowMs: Long,
        minScore: Double = 2.5,
        halfLifeDays: Double = 60.0,
        k1: Double = 1.2,
        b: Double = 0.75
    ): List<Scored> {
        val q = tokens(query).toSet()
        if (q.isEmpty() || docs.isEmpty() || topK <= 0) return emptyList()
        val tok = docs.map { tokens(it.text) }
        val avg = (tok.sumOf { it.size }.toDouble() / docs.size).coerceAtLeast(1.0)
        val df = HashMap<String, Int>()
        for (t in tok) for (w in t.toSet()) if (w in q) df[w] = (df[w] ?: 0) + 1
        val n = docs.size.toDouble()
        val out = ArrayList<Scored>()
        for ((i, doc) in docs.withIndex()) {
            val t = tok[i]
            if (t.isEmpty()) continue
            var s = 0.0
            for (w in q) {
                val tf = t.count { it == w }
                if (tf == 0) continue
                val d = df[w] ?: continue
                val idf = ln(1.0 + (n - d + 0.5) / (d + 0.5))
                s += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * t.size / avg))
            }
            if (s < minScore) continue
            val ageDays = ((nowMs - doc.ts).coerceAtLeast(0L)) / 86_400_000.0
            out += Scored(doc, s * (0.7 + 0.3 * exp(-ageDays / halfLifeDays)))
        }
        return out.sortedByDescending { it.score }.take(topK)
    }
}
