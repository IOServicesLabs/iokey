package com.localboard.keyboard.context

/**
 * Shared between both distributions ON PURPOSE.
 *
 * This is a passive, RAM-only store. The thing that FILLS it — the
 * accessibility service — exists only in the full build, because Google
 * Play does not permit an AccessibilityService to be used for reading
 * screen content to feed a language model.
 *
 * In the Play build nothing ever writes here, so every reader simply sees
 * an empty buffer and the AI falls back to the editor's own text. That
 * path already existed (see ContextEngine.recentTypedContext), so no
 * caller needs to know which build it is running in.
 */
/**
 * The rolling, RAM-only store of recently-seen screen text, per app.
 * Read by the keyboard when assembling AI context.
 */
object ScreenBuffer {
    const val MAX_CHARS = 2000
    private const val MAX_APPS = 6
    private const val MAX_SCREENS = 12
    private const val FRESH_MS = 10 * 60_000L

    @Volatile
    var serviceRunning = false

    /** Set by the IME while the keyboard is on screen — the only time this
     *  buffer is read, and therefore the only time capturing is allowed. */
    @Volatile
    var imeVisible = false

    /** Mirror of Prefs.redactScreenText, pushed by the IME. When on (default),
     *  one-time codes, card numbers and key-shaped tokens are stripped from
     *  captured screen text before it is stored. */
    @Volatile
    var redactEnabled = true

    /** Packages the user marked "never read the screen". Mirrored from prefs
     *  by the IME. Enforced at [put], so a blocked app's text never enters
     *  the map at all rather than being filtered on the way out. */
    @Volatile
    var noScreenPackages: Set<String> = emptySet()

    /**
     * Remove things that should never sit in a context buffer or reach an LLM
     * prompt, even one that runs on-device: OTPs, card numbers, API keys.
     *
     * Deliberately more conservative than [ClipHistory.looksSensitive] on the
     * bare-digits rule. The clipboard treats any 4-8 digit run as an OTP,
     * which is right for a thing the user explicitly copied; applying that to
     * ambient screen text would redact years, prices, times and street
     * numbers out of every conversation and starve the AI of context. Five
     * digits and up, skipping plausible years, keeps real codes covered
     * without gutting normal chat.
     */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        return text.lineSequence().joinToString("\n") { line ->
            line.split(' ').joinToString(" ") { tok ->
                val core = tok.trim('(', ')', '[', ']', '{', '}', ',', '.', ':', ';', '"', '\'', '!', '?')
                if (core.length < 5) return@joinToString tok
                val digitsOnly = core.all { it.isDigit() }
                val year = digitsOnly && core.length == 4
                val plainSmallNumber = digitsOnly && core.length < 5
                val sensitive = when {
                    year || plainSmallNumber -> false
                    digitsOnly && core.length in 5..8 -> true
                    else -> com.localboard.keyboard.util.ClipHistory.looksSensitive(core)
                }
                if (sensitive) "[redacted]" else tok
            }
        }
    }

    private data class Entry(val ts: Long, val key: String, val text: String)

    private val map = LinkedHashMap<String, Entry>(MAX_APPS, 0.75f, true)
    private val byScreen = LinkedHashMap<String, Entry>(MAX_SCREENS, 0.75f, true)

    /** Number of apps currently buffered (diagnostics). */
    @Synchronized
    fun bufferedApps(): Int = map.size

    /** Age in ms of the freshest capture, or -1 when the buffer is empty
     *  (diagnostics). */
    @Synchronized
    fun freshestAgeMs(): Long {
        val newest = map.values.maxByOrNull { it.ts } ?: return -1L
        return System.currentTimeMillis() - newest.ts
    }

    @Synchronized
    fun put(pkg: String, rawText: String, threadKey: String = "") {
        if (pkg in noScreenPackages) return
        // Redact BEFORE storing, never on read: anything that reaches the map
        // is one bug away from reaching a prompt, a log or the AI diagnostic.
        val text = if (redactEnabled) redact(rawText) else rawText
        val now = System.currentTimeMillis()
        val prev = map[pkg]
        val capped: String
        if (prev == null) {
            capped = text.takeLast(MAX_CHARS)
        } else {
            val prevLines = prev.text.lineSequence()
                .map { it.trim() }.filter { it.isNotBlank() }.toSet()
            val newLines = text.lineSequence()
                .map { it.trim() }.filter { it.isNotBlank() }.toList()
            val shared = newLines.count { it in prevLines }
            capped = if (shared == 0 && newLines.isNotEmpty() && prevLines.isNotEmpty()) {
                // A completely different screen (switched conversations):
                // REPLACE, don't merge two chats into mush.
                text.takeLast(MAX_CHARS)
            } else {
                // Same conversation, overlapping captures: ACCUMULATE with
                // positional anchoring. One capture per screen used to
                // REPLACE the buffer, so scrolling a chat with the keyboard
                // up never built a conversation — the AI only ever saw the
                // last visible slice. Anchor on the first new line that
                // already exists: unseen lines above it scrolled in from the
                // top (prepend), unseen lines below it are new (append).
                val prevList = prev.text.lineSequence()
                    .map { it.trim() }.filter { it.isNotBlank() }.toList()
                val anchor = newLines.indexOfFirst { it in prevLines }
                val merged = ArrayList<String>(prevList.size + newLines.size + 8)
                if (anchor > 0) merged.addAll(newLines.subList(0, anchor))
                merged.addAll(prevList)
                val tailStart = if (anchor >= 0) anchor + 1 else 0
                val prevSet = prevLines
                for (i in tailStart until newLines.size) {
                    val l = newLines[i]
                    if (l !in prevSet) merged.add(l)
                }
                merged.joinToString("\n").takeLast(MAX_CHARS)
            }
        }
        // B2: the stable thread key (toolbar title / window title) is the
        // conversation identity; the first-line fallback only fires when a
        // capture carried no title at all.
        val e = Entry(now, threadKey.ifBlank { screenKeyOf(capped) }, capped)
        map[pkg] = e
        byScreen["$pkg|" + e.key] = e
        while (map.size > MAX_APPS) {
            val eldest = map.keys.firstOrNull() ?: break
            map.remove(eldest)
        }
        while (byScreen.size > MAX_SCREENS) {
            val eldest = byScreen.keys.firstOrNull() ?: break
            byScreen.remove(eldest)
        }
    }

    @Synchronized
    fun get(pkg: String, maxChars: Int = MAX_CHARS): String {
        val e = map[pkg] ?: return ""
        if (System.currentTimeMillis() - e.ts > FRESH_MS) return ""
        return e.text.takeLast(maxChars)
    }

    /** The first meaningful line of the app's freshest capture — a stand-in
     *  for "which screen/conversation this is" (chat titles sit at the top).
     *  "" when unknown or stale. */
    @Synchronized
    fun currentScreenKey(pkg: String): String {
        val e = map[pkg] ?: return ""
        if (System.currentTimeMillis() - e.ts > FRESH_MS) return ""
        return e.key
    }

    /** Text from the last time this app showed THIS screen — per-conversation
     *  recall instead of per-app mush. */
    @Synchronized
    fun getForScreen(pkg: String, screenKey: String, maxChars: Int = MAX_CHARS): String {
        if (screenKey.isEmpty()) return ""
        val e = byScreen["$pkg|$screenKey"] ?: return ""
        if (System.currentTimeMillis() - e.ts > FRESH_MS) return ""
        return e.text.takeLast(maxChars)
    }

    private fun screenKeyOf(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(48) ?: ""

    @Synchronized
    fun clear(pkg: String) {
        map.remove(pkg)
        val it = byScreen.entries.iterator()
        while (it.hasNext()) {
            if (it.next().key.startsWith("$pkg|")) it.remove()
        }
    }

    @Synchronized
    fun clearAll() {
        map.clear()
        byScreen.clear()
    }
}
