package com.localboard.keyboard.util

/**
 * Text expansion: short triggers that become long text on commit.
 *
 * Stored as plain prefs (one "shortcut\texpansion" per line) rather than in
 * the encrypted DB — a snippet is something the user typed in deliberately to
 * be reused, not something the keyboard silently observed. The encrypted store
 * is for learned data.
 *
 * Triggers are matched case-insensitively and only on a whole committed word,
 * so ";addr" expands but "address" never does.
 */
class Snippets(private val prefs: Prefs) {

    private var cache: Map<String, String> = emptyMap()
    private var cacheSource: String? = null

    private fun map(): Map<String, String> {
        val raw = prefs.snippetData
        // Cache validity hinges on String REFERENCE identity (===), which
        // works only because SharedPreferences.getString returns the same
        // cached String instance until the value actually changes. If Prefs
        // ever copies the string on read, this must become content equality.
        if (raw === cacheSource) return cache
        val m = LinkedHashMap<String, String>()
        for (line in raw.split('\n')) {
            val t = line.indexOf('\t')
            if (t <= 0) continue
            val k = line.substring(0, t).trim().lowercase()
            val v = line.substring(t + 1)
            if (k.isNotEmpty() && v.isNotEmpty()) m[k] = v
        }
        cache = m
        cacheSource = raw
        return m
    }

    /** The expansion for a committed word, or null. */
    fun expand(word: String): String? {
        if (!prefs.snippetsEnabled) return null
        val w = word.trim()
        if (w.isEmpty() || w.length > 32) return null
        return map()[w.lowercase()]
    }

    fun all(): List<Pair<String, String>> = map().entries.map { it.key to it.value }

    fun put(shortcut: String, expansion: String) {
        val k = shortcut.trim().lowercase()
        if (k.isEmpty() || expansion.isBlank()) return
        val m = LinkedHashMap(map())
        // Newlines and tabs are the record separators — keep them out of values.
        m[k] = expansion.replace('\t', ' ').replace("\n", " ").trim()
        write(m)
    }

    fun remove(shortcut: String) {
        val m = LinkedHashMap(map())
        m.remove(shortcut.trim().lowercase())
        write(m)
    }

    private fun write(m: Map<String, String>) {
        prefs.snippetData = m.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        cacheSource = null
    }

    /** A couple of examples on first run, so the feature is discoverable
     *  rather than an empty list the user has to imagine a use for. */
    fun seedIfEmpty() {
        if (prefs.snippetData.isNotBlank()) return
        write(
            linkedMapOf(
                ";email" to "you@example.com",
                ";omw" to "On my way, be there shortly!"
            )
        )
    }
}
