package com.localboard.keyboard.util

/**
 * How often the things that decide whether this keyboard is any good happened on
 * this device, so that "did that fix help?" can be read off the phone instead of
 * guessed at from a simulator.
 *
 * Counters and nothing else, by construction. The set is closed and named in code,
 * and nothing here accepts a string or a word, so typed text has no way in, not
 * even as a key. Nothing here sends anything anywhere either: the numbers are for
 * the diagnostics screen.
 *
 * Every counter is a low-rate event (a correction, a glide, a draft), never a
 * keystroke, so a lock and a write per bump cost nothing noticeable and spare the
 * callers from having to pick a thread. Counting must never be what takes the
 * keyboard down, so a store that throws only costs the persisted copy.
 */
object LocalStats {

    enum class Counter(val key: String) {
        CORRECTION_FIRED("correction_fired"),
        CORRECTION_REVERTED("correction_reverted"),
        GLIDE_COMMITTED("glide_committed"),
        GLIDE_WIPED("glide_wiped"),
        STRIP_HEALED("strip_healed"),
        DRAFT_ACCEPTED("draft_accepted"),
        DRAFT_DISCARDED("draft_discarded"),
        DRAFT_TIMED_OUT("draft_timed_out")
    }

    /** Where counts outlive the process, which the system kills freely. Kept
     *  behind an interface so this file stays free of Android. */
    interface Store {
        /** The stored value of [key], or 0 when it has none. */
        fun read(key: String): Long
        fun write(key: String, value: Long)
    }

    private val counters = Counter.values()
    private val counts = LongArray(counters.size)

    /** What was counted before a store was attached. Bumps can arrive first:
     *  the process may be started for the keyboard and count straight away. */
    private val unsaved = LongArray(counters.size)
    private var store: Store? = null

    /** Connects the store and folds in what it holds. Counts stay in memory only
     *  until this is called, or if the store fails here. */
    @Synchronized
    fun attach(newStore: Store) {
        store = null
        try {
            for (c in counters) {
                val i = c.ordinal
                counts[i] = newStore.read(c.key) + unsaved[i]
                if (unsaved[i] != 0L) newStore.write(c.key, counts[i])
                unsaved[i] = 0L
            }
            store = newStore
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun bump(counter: Counter) {
        val i = counter.ordinal
        counts[i]++
        val target = store
        if (target == null) {
            unsaved[i]++
            return
        }
        try {
            target.write(counter.key, counts[i])
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    operator fun get(counter: Counter): Long = counts[counter.ordinal]

    /** Every counter, zeros included, in declaration order. */
    @Synchronized
    fun snapshot(): Map<Counter, Long> {
        val out = LinkedHashMap<Counter, Long>(counters.size)
        for (c in counters) out[c] = counts[c.ordinal]
        return out
    }

    /** Back to zero everywhere, for the erase path and for starting a fresh
     *  measurement window. */
    @Synchronized
    fun reset() {
        val target = store
        for (c in counters) {
            val i = c.ordinal
            counts[i] = 0L
            unsaved[i] = 0L
            try {
                target?.write(c.key, 0L)
            } catch (_: Throwable) {
            }
        }
    }

    /** One line per counter, for the diagnostics screen. */
    fun summary(): String =
        snapshot().entries.joinToString("\n") { (c, n) -> "${c.key}: $n" }
}
