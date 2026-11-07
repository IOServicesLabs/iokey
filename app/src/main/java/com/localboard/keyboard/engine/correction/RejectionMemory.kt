package com.localboard.keyboard.engine.correction

/**
 * What the user has told us about their words. Main-thread only. The policy receives copies, so
 * nothing here is read off-main.
 *
 * One explicit keep is enough, because Backspace straight after a correction is unambiguous.
 * Implicit refusals need two to protect a word, because erasing it and retyping it can also be a
 * change of mind. [CorrectionSession] decides when one also bans the fix.
 * Expiry slides for a word kept more than once, so a word in use never loses its protection, while
 * one undo, which can be a slip, ages out even if the same typo keeps being made.
 *
 * Memory is global, not per app: personal vocabulary is about the user, not the app they are in.
 */
class RejectionMemory(
    private val now: () -> Long,
    private val maxEntries: Int = 500,
    private val ttlMs: Long = 90L * 86_400_000L,
    private val recentSize: Int = 8,
    private val retypeWindowMs: Long = 120_000L,
) {
    private class Rec(var kept: Int, var soft: Int, var lastAt: Long, val banned: LinkedHashSet<String>)

    private val entries = object : LinkedHashMap<String, Rec>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Rec>?) = size > maxEntries
    }

    /** True when something changed that has not been persisted. */
    var dirty = false
        private set
    fun markPersisted() { dirty = false }

    /** One automatic correction still within reach in this field. */
    class Recent(val raw: String, val fix: String, val prev1: String, val at: Long) {
        var undone = false    // Backspace or the chip reverted it
        var erased = false    // it left the field some other way
        var settled = false   // it stood long enough to be learned
        var refusalCounted = false  // the local revert counter has seen it
    }
    private val recent = ArrayDeque<Recent>()

    /** Words forgotten before the stored row was read, which must not bring them back. */
    private val forgotten = HashSet<String>()

    private fun live(w: String): Rec? {
        val e = entries[w] ?: return null
        if (now() - e.lastAt > ttlMs) { entries.remove(w); return null }
        return e
    }

    private fun entry(word: String): Rec {
        val w = word.lowercase()
        return live(w) ?: Rec(0, 0, now(), LinkedHashSet()).also { entries[w] = it }
    }

    fun keptCount(word: String): Int = live(word.lowercase())?.kept ?: 0
    fun softCount(word: String): Int = live(word.lowercase())?.soft ?: 0

    /** One explicit keep, or two implicit ones, stop auto-correction of this word. */
    fun isProtected(word: String): Boolean =
        live(word.lowercase())?.let { it.kept >= 1 || it.soft >= 2 } ?: false

    fun bannedFixes(word: String): Set<String> = live(word.lowercase())?.banned?.toSet() ?: emptySet()

    /** Explicit: the user undid a correction and committed text. */
    fun noteKept(word: String, refusedFix: String?) {
        val e = entry(word)
        e.kept++
        e.lastAt = now()
        refusedFix?.let { e.banned += it.lowercase() }
        dirty = true
    }

    /** Implicit: the word was erased some other way and the same text typed again. */
    fun noteSoft(word: String, refusedFix: String?) {
        val e = entry(word)
        e.soft++
        e.lastAt = now()
        refusedFix?.let { e.banned += it.lowercase() }
        dirty = true
    }

    fun banPair(raw: String, fix: String) {
        entry(raw).banned += fix.lowercase()
        dirty = true
    }

    /** The user picked the fix after all. A record with nothing left in it goes too. */
    fun liftBan(raw: String, fix: String): Boolean {
        val w = raw.lowercase()
        val e = live(w) ?: return false
        if (!e.banned.remove(fix.lowercase())) return false
        if (e.kept == 0 && e.soft == 0 && e.banned.isEmpty()) entries.remove(w)
        dirty = true
        return true
    }

    /**
     * Sliding expiry, for a word kept more than once: typed again, it stays remembered. A single keep
     * does not slide, or a slip of Backspace would last as long as the user keeps making the typo.
     */
    fun touch(word: String) {
        live(word.lowercase())?.takeIf { it.kept >= 2 }?.let { it.lastAt = now(); dirty = true }
    }

    /** The user took the word out of what the keyboard knows, so nothing about it may come back. */
    fun forget(word: String) {
        val w = word.lowercase()
        forgotten += w
        if (entries.remove(w) != null) dirty = true
    }

    /** What was known about one word at some moment. It only goes back through [restoreWord]. */
    class WordRecord internal constructor(
        internal val kept: Int,
        internal val soft: Int,
        internal val lastAt: Long,
        internal val banned: Set<String>,
    )

    fun recordOf(word: String): WordRecord? =
        live(word.lowercase())?.let { WordRecord(it.kept, it.soft, it.lastAt, it.banned.toSet()) }

    /** Puts [word] back as [record] had it. No record means nothing was known, so it goes. */
    fun restoreWord(word: String, record: WordRecord?) {
        if (record == null) {
            if (entries.remove(word.lowercase()) != null) dirty = true
        } else {
            entries[word.lowercase()] = Rec(record.kept, record.soft, record.lastAt, LinkedHashSet(record.banned))
            dirty = true
        }
    }

    fun clear() { entries.clear(); recent.clear(); forgotten.clear(); dirty = true }

    // --- Ring of recent corrections. Never persisted, and cleared when the field changes. It is the
    // only state that treats "the same text retyped in the same place" as a refusal, so the same
    // typo somewhere else is still fixed. The fix has to have gone first: a typo made twice after
    // the same word, with the first fix still standing, refuses nothing.
    fun noteAutoCorrected(raw: String, fix: String, prev1: String): Recent {
        val r = Recent(raw.lowercase(), fix.lowercase(), prev1, now())
        recent.addLast(r)
        while (recent.size > recentSize) recent.removeFirst()
        return r
    }

    /** The word being typed equals a recently corrected word whose fix was taken away, in the same place. */
    fun matchRetype(typedLower: String, prev1: String): Recent? =
        recent.lastOrNull {
            (it.undone || it.erased) && it.raw == typedLower && it.prev1 == prev1 && now() - it.at <= retypeWindowMs
        }

    /**
     * A word delete took [wordLower] out of the field, leaving [prev1] before the caret. That erases
     * the fix it belonged to, once it was the fix's first word: a delete takes a split fix last word first.
     */
    fun noteWordErased(wordLower: String, prev1: String): Recent? =
        recent.lastOrNull { it.prev1 == prev1 && WordShape.normalized(it.fix.substringBefore(' ')) == wordLower }
            ?.also { it.erased = true }

    /** A selection holding exactly [fixLower] left the field. A selection says which text went, so the word before it does not have to match. */
    fun noteFixRemoved(fixLower: String): Recent? =
        recent.lastOrNull { WordShape.normalized(it.fix) == fixLower }?.also { it.erased = true }

    /** A re-entered fragment belongs to a recent fix. */
    fun matchFragment(fragmentLower: String, prev1: String): Recent? =
        recent.lastOrNull { it.fix.startsWith(fragmentLower) && it.prev1 == prev1 }

    fun clearRecent() = recent.clear()

    // --- Persistence, stored in style_stats under [STYLE_STATS_KEY].
    // A fix that cannot be written loses only its ban: dropping the whole entry would bring the
    // correction the user refused back after the next restart.
    fun serialize(): String = entries.entries
        .filter { (w, _) -> SAFE.matches(w) }
        .joinToString(",") { (w, e) ->
            "$w=${e.kept}/${e.soft}@${e.lastAt}|" + e.banned.filter { SAFE.matches(it) }.joinToString(";")
        }

    fun restore(text: String) {
        entries.clear()
        readStored(text)
        dirty = false
    }

    /**
     * Reads the stored row into a memory already in use. What happened since the process started
     * is newer than the row, so a word already here keeps what it has, and a word forgotten since
     * stays forgotten, which then has to be written back.
     */
    fun mergeStored(text: String) {
        var rowHoldsForgotten = false
        readStored(text) { word ->
            if (word in forgotten) { rowHoldsForgotten = true; false } else word !in entries
        }
        forgotten.clear()
        if (rowHoldsForgotten) dirty = true
    }

    private fun readStored(text: String, accept: (String) -> Boolean = { true }) {
        for (item in text.split(',')) {
            val eq = item.indexOf('=')
            val slash = item.indexOf('/')
            val at = item.indexOf('@')
            val bar = item.indexOf('|')
            if (eq <= 0 || slash < eq || at < slash || bar < at) continue
            val kept = item.substring(eq + 1, slash).toIntOrNull() ?: continue
            val soft = item.substring(slash + 1, at).toIntOrNull() ?: continue
            val ts = item.substring(at + 1, bar).toLongOrNull() ?: continue
            if (now() - ts > ttlMs) continue
            val word = item.substring(0, eq)
            if (!accept(word)) continue
            val banned = item.substring(bar + 1).split(';').filterTo(LinkedHashSet()) { it.isNotEmpty() }
            entries[word] = Rec(kept, soft, ts, banned)
        }
    }

    companion object {
        /** The style_stats row holding [serialize]. A new key needs no Room schema change. */
        const val STYLE_STATS_KEY = "ac_rejections"

        /** Letters, apostrophes and the space in a run-on fix. No delimiter can occur. */
        private val SAFE = Regex("[\\p{L}'’ ]+")
    }
}
