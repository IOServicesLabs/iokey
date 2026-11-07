package com.localboard.keyboard.engine.correction

/**
 * Where the word being composed came from. Only freshly TYPED text may be rewritten: a word the
 * user has edited, come back to or taken back from a correction is theirs, and gets a chip at most.
 */
enum class Provenance { TYPED, EDITED, RESUMED, REVERTED }
enum class FieldCorrection { AUTO, SUGGEST_ONLY, OFF }

data class Candidate(
    val word: String,
    val dist: Double,      // weighted edit distance
    val score: Double,     // blended: ln(freq + 1) * 0.25 - dist + context
    val freq: Long,
    val userCount: Int = 0,
)

/** Everything the decision needs. Built on the main thread, read anywhere. */
data class CorrectionInput(
    val typed: String,               // exactly as typed, case preserved
    val provenance: Provenance,
    val field: FieldCorrection,
    val charBefore: Char?,           // null = start of field
    val charAfter: Char?,            // null = end of field
    val separator: Char?,            // null while only previewing for the bar
    val level: Int,                  // 1 conservative, 2 balanced, 3 bold
    val known: Boolean,              // trie or SHORT_WORDS
    val confirmed: Boolean,
    val strongUsage: Boolean,        // user count >= 8
    val rejected: Boolean,           // RejectionMemory protects this word
    val banned: Set<String>,         // fixes the user refused for this word
    val closedFix: String?,          // contraction or misspelling table
    val candidates: List<Candidate>, // best first, at most 3
    val spatialWinner: Candidate?,   // first of spatialCorrect; null = no trace, or raw won
    val hasTrace: Boolean,
    val contextOverride: String?,    // known word or 2-letter override from the engine
    val split: String?,              // splitRunOn, only with bigram evidence
)

sealed interface Verdict {
    data object Keep : Verdict
    data class Hint(val word: String) : Verdict
    data class AutoCommit(val word: String) : Verdict
}

data class LevelParams(
    val minLen: Int,
    val marginShort: Double,       // typed length up to 3
    val marginLong: Double,        // typed length 4 and up
    val firstLetterMargin: Double,
    val vetoSameLengthWithTrace: Boolean,
    val maxDist: Double,
    val allowKnownOverride: Boolean,
    val allowSplit: Boolean,
)

/**
 * The one place that decides what happens to a typed word. Pure: every fact it needs arrives in
 * [CorrectionInput], so the verdict cannot depend on what another thread is doing, and Space gets
 * the same answer the bar was previewing however fast the user types.
 *
 * The caller re-applies case: uppercase the first letter when the first letter was typed uppercase.
 * A cased closed fix like "I'm" is unaffected by that.
 */
object CorrectionPolicy {
    private const val SOLE_CANDIDATE_MARGIN = 9.0

    val LIGHT = LevelParams(4, 1.0, 1.0, Double.POSITIVE_INFINITY, true, 1.0, false, false)
    val BALANCED = LevelParams(2, 0.5, 0.3, 0.75, true, Double.POSITIVE_INFINITY, false, true)
    val BOLD = LevelParams(2, 0.3, 0.1, 0.5, false, Double.POSITIVE_INFINITY, true, true)

    private fun paramsFor(level: Int) = when (level) { 1 -> LIGHT; 3 -> BOLD; else -> BALANCED }

    fun decide(i: CorrectionInput): Verdict {
        if (i.field == FieldCorrection.OFF || i.typed.isEmpty()) return Verdict.Keep
        val v = inner(i)
        return if (v is Verdict.AutoCommit && i.field == FieldCorrection.SUGGEST_ONLY) Verdict.Hint(v.word) else v
    }

    private fun inner(i: CorrectionInput): Verdict {
        val lower = i.typed.lowercase().replace('’', '\'')
        val allowed = i.candidates.filter { !isBanned(i, it.word) }
        val closed = i.closedFix?.takeIf { !isBanned(i, it) }
        val hint: Verdict = when {
            closed != null -> Verdict.Hint(closed)
            !i.known && allowed.isNotEmpty() -> Verdict.Hint(allowed[0].word)
            else -> Verdict.Keep
        }

        // The user touched this word, or told us it is theirs.
        if (i.provenance != Provenance.TYPED) return hint
        if (i.rejected || i.confirmed || i.strongUsage) return Verdict.Keep

        // Not a whole word, or a shape people type on purpose.
        if (!boundaryOk(i.charBefore, i.charAfter)) return Verdict.Keep
        val tail = if (i.typed.first().isUpperCase()) i.typed.substring(1) else i.typed
        if (tail.any { it.isUpperCase() }) return Verdict.Keep
        if (lower.any { !it.isLetter() && it != '\'' }) return Verdict.Keep

        // Closed tables.
        if (lower == "i") return if (i.typed == "I") Verdict.Keep else Verdict.AutoCommit("I")
        if (closed != null) return Verdict.AutoCommit(closed)

        val p = paramsFor(i.level)
        if (i.known) {
            val o = i.contextOverride
            return if (p.allowKnownOverride && o != null && !isBanned(i, o)) Verdict.AutoCommit(o) else Verdict.Keep
        }
        if (lower.length == 2) {
            val o = i.contextOverride
            return if (o != null && !isBanned(i, o)) Verdict.AutoCommit(o) else hint
        }
        if (lower.length < p.minLen) return hint

        // Elongation: "hiii" is deliberate; a doubled last letter may only collapse.
        val run = trailingRun(lower)
        if (run >= 3) {
            val base = lower.dropLast(run - 1)
            return allowed.firstOrNull { it.word == base }?.let { Verdict.Hint(it.word) } ?: Verdict.Keep
        }
        val collapse = if (run == 2) lower.dropLast(1) else null

        // Tap evidence first: spatialCorrect only returns words that beat the raw string.
        i.spatialWinner?.let { s ->
            if (!isBanned(i, s.word) && passesFloor(s, lower.length) && (collapse == null || s.word == collapse))
                return Verdict.AutoCommit(s.word)
        }

        // A missed space explains the token at least as well as an edit of a whole key or more.
        val best = allowed.firstOrNull()
        if (best == null || best.dist >= 1.0) {
            if (p.allowSplit && (i.separator == null || i.separator == ' ')) {
                i.split?.takeIf { !isBanned(i, it) }?.let { return Verdict.AutoCommit(it) }
            }
            if (best == null) return splitOr(i, p, hint)
        }
        if (p.vetoSameLengthWithTrace && i.hasTrace &&
            best.word.length == lower.length && !isTransposition(lower, best.word)) return splitOr(i, p, hint)
        if (collapse != null && best.word != collapse) return splitOr(i, p, hint)
        if (!passesFloor(best, lower.length)) return splitOr(i, p, hint)
        if (!noShrink(lower, best.word)) return splitOr(i, p, hint)

        val margin = allowed.getOrNull(1)?.let { best.score - it.score } ?: SOLE_CANDIDATE_MARGIN
        if (best.dist > p.maxDist) return splitOr(i, p, hint)
        if (!(best.dist <= 1.0 || margin >= 1.0)) return splitOr(i, p, hint)
        if (firstLetterChanged(lower, best.word) && margin < p.firstLetterMargin) return splitOr(i, p, hint)
        val need = if (lower.length <= 3) p.marginShort else p.marginLong
        if (margin < need) return splitOr(i, p, hint)
        return Verdict.AutoCommit(best.word)
    }

    /** A run-on split is only ever applied by Space. The bar preview passes a null separator. */
    private fun splitOr(i: CorrectionInput, p: LevelParams, hint: Verdict): Verdict {
        val s = i.split?.takeIf { !isBanned(i, it) } ?: return hint
        if (p.allowSplit && (i.separator == null || i.separator == ' ')) return Verdict.AutoCommit(s)
        return if (hint == Verdict.Keep) Verdict.Hint(s) else hint
    }

    /** RejectionMemory holds lowercase; a fix may be cased ("I'm", "I think"). */
    private fun isBanned(i: CorrectionInput, word: String) = word.lowercase() in i.banned

    fun boundaryOk(before: Char?, after: Char?): Boolean {
        if (before != null && (before.isLetterOrDigit() || before in "@#_/\\.:=&+-'’")) return false
        if (after != null && (after.isLetterOrDigit() || after in "_-'’")) return false
        return true
    }

    private fun passesFloor(c: Candidate, typedLen: Int): Boolean {
        val floor = when { typedLen >= 8 -> 100L; typedLen >= 6 -> 600L; else -> 2000L }
        return c.freq >= floor || c.userCount >= 2
    }

    fun trailingRun(t: String): Int {
        var k = 1
        while (k < t.length && t[t.length - 1 - k] == t.last()) k++
        return k
    }

    private fun isDoubledDrop(t: String, c: String): Boolean {
        for (j in 1 until t.length) if (t[j] == t[j - 1] && t.removeRange(j, j + 1) == c) return true
        return false
    }

    fun noShrink(t: String, c: String) = t.length > 4 || c.length >= t.length || isDoubledDrop(t, c)

    fun firstLetterChanged(t: String, c: String): Boolean {
        if (t[0] == c[0]) return false
        return !(t.length > 1 && c.length > 1 && t[0] == c[1] && t[1] == c[0])
    }

    fun isTransposition(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        val d = a.indices.filter { a[it] != b[it] }
        return d.size == 2 && d[1] == d[0] + 1 && a[d[0]] == b[d[1]] && a[d[1]] == b[d[0]]
    }
}
