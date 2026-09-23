package com.localboard.keyboard.util

/**
 * Short, expiring history of copied text.
 *
 * Privacy shape matters here: clipboards routinely carry passwords, 2FA codes
 * and addresses. So this is deliberately NOT the encrypted learning store and
 * NOT permanent — unpinned clips expire on a user-set timer (default one
 * hour), the list is capped, and anything that looks like a password-manager
 * payload is never recorded at all. Pinning is the explicit "keep this"
 * gesture; everything else evaporates.
 */
class ClipHistory(private val prefs: Prefs) {

    data class Clip(val text: String, val ts: Long, val pinned: Boolean)

    private fun parse(): MutableList<Clip> {
        val out = ArrayList<Clip>(MAX)
        for (line in prefs.clipboardEntries.split('\n')) {
            if (line.isBlank()) continue
            val a = line.indexOf('\t')
            if (a <= 0) continue
            val b = line.indexOf('\t', a + 1)
            if (b <= a) continue
            val pinned = line.substring(0, a) == "1"
            val ts = line.substring(a + 1, b).toLongOrNull() ?: continue
            val text = line.substring(b + 1).replace("", "\n")
            if (text.isNotBlank()) out += Clip(text, ts, pinned)
        }
        return out
    }

    private fun write(list: List<Clip>) {
        prefs.clipboardEntries = list.take(MAX).joinToString("\n") {
            "${if (it.pinned) 1 else 0}\t${it.ts}\t${it.text.replace("\n", "")}"
        }
    }

    /** Live clips, newest first, with expired unpinned entries dropped. */
    fun entries(): List<Clip> {
        if (!prefs.clipboardHistory) return emptyList()
        val mins = prefs.clipboardExpiryMinutes
        val now = System.currentTimeMillis()
        val kept = parse().filter {
            it.pinned || mins <= 0 || now - it.ts <= mins * 60_000L
        }
        return kept.sortedWith(
            compareByDescending<Clip> { it.pinned }.thenByDescending { it.ts }
        )
    }

    /** Record a newly-copied string. Returns false when it was rejected. */
    fun record(text: String): Boolean {
        if (!prefs.clipboardHistory) return false
        val t = text.trim()
        if (t.length !in 1..MAX_LEN) return false
        if (looksSensitive(t)) return false
        val list = parse()
        // Same text again just refreshes its timestamp — copying twice should
        // not produce two rows.
        val existing = list.indexOfFirst { it.text == t }
        val pinned = if (existing >= 0) list[existing].pinned else false
        if (existing >= 0) list.removeAt(existing)
        list.add(0, Clip(t, System.currentTimeMillis(), pinned))
        write(list)
        return true
    }

    fun setPinned(text: String, pinned: Boolean) {
        val list = parse()
        val i = list.indexOfFirst { it.text == text }
        if (i < 0) return
        list[i] = list[i].copy(pinned = pinned)
        write(list)
    }

    fun remove(text: String) = write(parse().filterNot { it.text == text })

    fun clear() { prefs.clipboardEntries = "" }

    /** Not private: ScreenBuffer reuses [looksSensitive] so screen capture and
     *  clipboard history apply one definition of "don't keep this", instead of
     *  drifting into two. */
    companion object {
        private const val MAX = 24
        private const val MAX_LEN = 5000

        /**
         * Heuristics for "don't keep a copy of this". Not a security boundary
         * — the system clipboard is readable by the foreground app anyway —
         * but it stops the obvious footguns: OTP codes and password-manager
         * fills sitting in a history the user forgot exists.
         */
        fun looksSensitive(t: String): Boolean {
            // Bare 4-8 digit codes: almost always an OTP.
            if (t.length in 4..8 && t.all { it.isDigit() }) return true
            // Long card-shaped digit runs.
            val digits = t.count { it.isDigit() }
            if (digits >= 13 && t.count { !it.isDigit() && !it.isWhitespace() && it != '-' } == 0) return true
            // High-entropy single tokens that look generated rather than typed.
            if (!t.contains(' ') && t.length in 12..64) {
                val hasUpper = t.any { it.isUpperCase() }
                val hasLower = t.any { it.isLowerCase() }
                val hasDigit = t.any { it.isDigit() }
                val hasSym = t.any { !it.isLetterOrDigit() }
                if (hasUpper && hasLower && hasDigit && hasSym) return true
            }
            return false
        }
    }
}
