package com.localboard.keyboard.util

/**
 * Repairs stored preferences that older builds left behind, so an existing install
 * heals itself on update with no action from the user. Everything lands in one
 * atomic write: a process killed part way cannot leave half a migration.
 *
 * Runs at every process start, before the keyboard reads a preference, and again
 * after a backup restore. The restore is why most rules test the stored value rather
 * than a done flag: an old backup can bring an old value back long after any flag
 * was set. Only the glide bias reset is truly once-only, and the schema stamp exists
 * for it.
 *
 * Safe if it never runs at all: the code that read the retired keys is gone, so a
 * failure here leaves stale bytes and not a broken keyboard.
 *
 * No Android in this file, so all of it can be checked on a plain JVM. [Store] is one
 * map read and one write.
 */
object PrefsMigrations {

    const val GEOMETRY_SCHEMA = 2
    const val KEY_GEOMETRY_SCHEMA = "geometry_schema"

    /** Set when the update put visibly moved keys back. Settings shows the notice
     *  once and removes the flag. */
    const val KEY_KEYS_RESET_NOTICE = "keys_reset_notice"

    /** Pref names as written by the old typing test and grid game, which baked
     *  offsets into the drawn layout. Their contents are not converted: they were
     *  measured in a mix of frames, so nothing trustworthy can be recovered. */
    val RETIRED: Set<String> =
        setOf("key_layout_fit", "tap_model_data", "key_offset_x", "key_offset_y")

    /** The retired keys that visibly changed the board. The tap model only nudged
     *  misses, so a user who only ever had that saw no keys move. */
    private val VISIBLE: Set<String> = setOf("key_layout_fit", "key_offset_x", "key_offset_y")

    private const val KEY_NAV_STRIP_MODE = "nav_strip_mode"

    /** "Off", which let the keys sit under the gesture bar. The option is gone. */
    private const val NAV_STRIP_UNDER_BAR = 3
    private const val NAV_STRIP_MATCH_KEYBOARD = 0

    private const val KEY_SWIPE_BIAS = "swipe_bias_data"

    /** The old getter-that-writes left this behind. It is only a leftover flag: the
     *  user's own llm_suggestions choice is not touched. */
    private const val KEY_LLM_SUGGESTIONS_RESET = "llm_suggestions_reset_v2"

    private const val PREFIX_TOUCH_MODEL = "touch_model_"
    private const val PREFIX_TOUCH_CHECK = "touch_check_"

    /** One atomic change to the stored preferences. */
    class Edit(
        val remove: Set<String>,
        val putInts: Map<String, Int>,
        val putBooleans: Map<String, Boolean>
    )

    interface Store {
        /** Everything stored, in the shape SharedPreferences.getAll() returns it. */
        fun snapshot(): Map<String, Any?>

        /** Applies the whole edit in one synchronous write: Editor.commit() rather
         *  than apply(), so the repair is on disk before anything else runs. */
        fun commit(edit: Edit): Boolean
    }

    /**
     * Does whatever is still needed, and returns true when that changed something.
     * Running it again finds nothing to do and does not touch the disk, so it is cheap
     * enough to call on every start.
     *
     * Pass [afterRestore] when a backup was just written back. Restored glide bias
     * from before the day stamp existed is then dropped as well, because it may sit at
     * the old floor for words the user never rejected on purpose.
     */
    fun run(store: Store, afterRestore: Boolean = false): Boolean {
        val stored = store.snapshot()
        val remove = LinkedHashSet<String>()
        val ints = LinkedHashMap<String, Int>()
        val booleans = LinkedHashMap<String, Boolean>()

        val firstRun = ((stored[KEY_GEOMETRY_SCHEMA] as? Number)?.toInt() ?: 0) < GEOMETRY_SCHEMA

        for (key in RETIRED) if (stored.containsKey(key)) remove += key
        if (firstRun) ints[KEY_GEOMETRY_SCHEMA] = GEOMETRY_SCHEMA
        // A fit that arrives with a restore was never applied in this build, so no
        // key moved back and there is nothing to tell the user.
        if (firstRun && !afterRestore && VISIBLE.any { holdsValue(stored[it]) }) {
            booleans[KEY_KEYS_RESET_NOTICE] = true
        }

        if ((stored[KEY_NAV_STRIP_MODE] as? Number)?.toInt() == NAV_STRIP_UNDER_BAR) {
            ints[KEY_NAV_STRIP_MODE] = NAV_STRIP_MATCH_KEYBOARD
        }

        if ((firstRun || afterRestore) && holdsUnstampedBias(stored[KEY_SWIPE_BIAS])) {
            remove += KEY_SWIPE_BIAS
        }

        if (stored.containsKey(KEY_LLM_SUGGESTIONS_RESET)) remove += KEY_LLM_SUGGESTIONS_RESET

        if (remove.isEmpty() && ints.isEmpty() && booleans.isEmpty()) return false
        return store.commit(Edit(remove, ints, booleans))
    }

    /**
     * True for a key that must not leave this device in a backup, nor arrive on it
     * from one. The stamp and flags describe this install, not the user's choices: a
     * restored stamp would make a repair skip a device that still needs it. Touch
     * learning describes a hand on one screen, and starts empty anywhere else.
     */
    fun staysOnDevice(key: String): Boolean =
        key in RETIRED ||
            key == KEY_GEOMETRY_SCHEMA ||
            key == KEY_KEYS_RESET_NOTICE ||
            key == KEY_LLM_SUGGESTIONS_RESET ||
            key.startsWith(PREFIX_TOUCH_MODEL) ||
            key.startsWith(PREFIX_TOUCH_CHECK)

    /** True for a non-blank string or a non-zero number. */
    private fun holdsValue(value: Any?): Boolean = when (value) {
        is String -> value.isNotBlank()
        is Number -> value.toFloat() != 0f
        else -> false
    }

    /**
     * Stamped bias lines are word, tab, value, tab, the day they were written, so they
     * can fade with age. A line without the stamp cannot fade, and comes from the
     * scoring that let common words collect penalties for nothing.
     */
    private fun holdsUnstampedBias(value: Any?): Boolean {
        if (value == null) return false
        if (value !is String) return true
        return value.split('\n').any { line -> line.isNotBlank() && line.count { it == '\t' } < 2 }
    }
}
