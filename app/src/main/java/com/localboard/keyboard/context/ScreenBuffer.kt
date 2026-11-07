package com.localboard.keyboard.context

import com.localboard.keyboard.memory.Speaker
import com.localboard.keyboard.memory.TranscriptMerger
import com.localboard.keyboard.memory.Turn

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
    private const val FORGET_HOLD_MS = 10_000L

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

    private class Entry(val ts: Long, val key: String, val turns: List<Turn>) {
        val text: String = render(turns)
    }

    private fun lineOf(t: Turn): String = when (t.speaker) {
        Speaker.ME -> "You: " + t.text
        Speaker.THEM -> "Them: " + t.text
        Speaker.UNKNOWN -> t.text
    }

    private fun render(turns: List<Turn>): String = turns.joinToString("\n") { lineOf(it) }

    /** The newest whole lines that fit: a cut line would lose its speaker. */
    private fun capToNewest(turns: List<Turn>): List<Turn> {
        var chars = 0
        var from = turns.size
        while (from > 0) {
            chars += lineOf(turns[from - 1]).length + 1
            if (chars > MAX_CHARS && from < turns.size) break
            from--
        }
        return turns.subList(from, turns.size).toList()
    }

    private val map = LinkedHashMap<String, Entry>(MAX_APPS, 0.75f, true)
    private val byScreen = LinkedHashMap<String, Entry>(MAX_SCREENS, 0.75f, true)
    private val refusedUntil = HashMap<String, Long>()

    /** What a refusal is timed by: a wall clock set back would stretch it. A function so that the
     *  rules can be tested off the device. */
    internal var elapsedMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }

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

    /**
     * Takes one capture of the visible chat, oldest line first. [atBottom] is false while
     * the list can still scroll forward, that is, while the user is reading history.
     *
     * Captures of one conversation accumulate, so scrolling a chat with the keyboard up
     * builds it up instead of only ever holding the last visible slice. They line up by
     * position, so a fresh "ok" is kept even when "ok" was said before.
     */
    @Synchronized
    fun put(pkg: String, capture: List<Turn>, threadKey: String = "", atBottom: Boolean = true) {
        if (pkg in noScreenPackages) return
        refusedUntil[pkg]?.let { until ->
            if (elapsedMs() < until) return
            refusedUntil.remove(pkg)
        }
        // Redact BEFORE storing, never on read: anything that reaches the map
        // is one bug away from reaching a prompt, a log or the AI diagnostic.
        val clean = capture
            .map { if (redactEnabled) it.copy(text = redact(it.text)) else it }
            .filter { it.text.isNotBlank() }
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        // A named conversation only ever merges with itself: one line in common is not
        // enough to pour two people's chats together. What has gone stale is not brought
        // back to life by a merge.
        val prev = (if (threadKey.isBlank()) map[pkg] else byScreen["$pkg|$threadKey"])
            ?.takeIf { now - it.ts <= FRESH_MS }
        val merge = prev?.let { TranscriptMerger.merge(it.turns, clean, atBottom) }
        val turns = when {
            prev == null || merge == null -> clean
            // Scrolled far up in a chat we can name: what we hold is still its newest
            // lines. With no name this could be any other screen, and showing a draft the
            // wrong person's chat is worse than an older part of the right one.
            merge.disjoint -> if (atBottom || threadKey.isBlank()) clean else prev.turns
            else -> merge.older + prev.turns + merge.newer
        }
        val capped = capToNewest(turns)
        // The stable thread key (toolbar title / window title) is the
        // conversation identity. A capture with no title keeps the name of the
        // chat it lines up with, and otherwise has no name: its first line is a
        // message now, and readers of this key write it to disk.
        val key = threadKey.ifBlank { prev?.takeIf { merge?.disjoint == false }?.key.orEmpty() }
        val e = Entry(now, key, capped)
        map[pkg] = e
        if (key.isNotEmpty()) byScreen["$pkg|$key"] = e
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

    /** Which conversation the app's freshest capture is of, by its title.
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

    @Synchronized
    fun clear(pkg: String) {
        map.remove(pkg)
        val it = byScreen.entries.iterator()
        while (it.hasNext()) {
            if (it.next().key.startsWith("$pkg|")) it.remove()
        }
    }

    /**
     * Drops everything held for an app, its title included, and refuses captures of it for a
     * moment: a walk of the screen already under way cannot be called back, and would otherwise
     * put the same text straight back when it lands.
     */
    @Synchronized
    fun forget(pkg: String) {
        clear(pkg)
        refusedUntil[pkg] = elapsedMs() + FORGET_HOLD_MS
    }

    /**
     * A tap has asked for a new read of this app, and that outranks the refusal left by the last
     * [forget]: a retry would otherwise draft with no chat at all, and each retry would start the
     * refusal over. Queued walks went with that forget. One still running is cleared by the forget
     * of the draft asking now, in a field where that matters.
     */
    @Synchronized
    fun allowRead(pkg: String) {
        refusedUntil.remove(pkg)
    }

    @Synchronized
    fun clearAll() {
        map.clear()
        byScreen.clear()
    }
}
