package com.localboard.keyboard.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Latency counts in fixed buckets, kept apart from [PhoneStats] because the
 * Android clock throws in a JVM test and this logic should not need one.
 *
 * Buckets are 1 ms wide to 50, 5 ms to 200, 50 ms to 1000, then one overflow.
 * Recording is an array increment, so it is safe on a key press: percentiles
 * are worked out only when someone reads them.
 */
class LatencyHistogram {

    private val buckets = IntArray(BUCKET_COUNT)

    var count: Int = 0
        private set

    /** A negative time means the clocks disagreed, not that a key was fast. */
    fun record(ms: Long) {
        if (ms < 0L) return
        buckets[bucketOf(ms)]++
        count++
    }

    /**
     * The largest whole millisecond the p-th percentile's bucket can hold, so a
     * coarse bucket never reads faster than it was. [OVERFLOW_MS] means at
     * least that long. -1 when nothing has been recorded.
     */
    fun percentile(p: Int): Int {
        if (count == 0) return -1
        val rank = ((p.toLong() * count + 99L) / 100L).coerceIn(1L, count.toLong())
        var seen = 0L
        for (i in buckets.indices) {
            seen += buckets[i]
            if (seen >= rank) return bucketTop(i)
        }
        return bucketTop(BUCKET_COUNT - 1)
    }

    fun clear() {
        buckets.fill(0)
        count = 0
    }

    companion object {
        /** Readings at or past this share the overflow bucket. */
        const val OVERFLOW_MS = 1000

        private const val FINE_END = 50
        private const val MID_END = 200
        private const val MID_WIDTH = 5
        private const val COARSE_WIDTH = 50
        private const val FINE_BUCKETS = FINE_END
        private const val MID_BUCKETS = (MID_END - FINE_END) / MID_WIDTH
        private const val COARSE_BUCKETS = (OVERFLOW_MS - MID_END) / COARSE_WIDTH
        private const val BUCKET_COUNT = FINE_BUCKETS + MID_BUCKETS + COARSE_BUCKETS + 1

        private fun bucketOf(ms: Long): Int = when {
            ms < FINE_END -> ms.toInt()
            ms < MID_END -> FINE_BUCKETS + (ms.toInt() - FINE_END) / MID_WIDTH
            ms < OVERFLOW_MS -> FINE_BUCKETS + MID_BUCKETS + (ms.toInt() - MID_END) / COARSE_WIDTH
            else -> BUCKET_COUNT - 1
        }

        private fun bucketTop(i: Int): Int = when {
            i < FINE_BUCKETS -> i
            i < FINE_BUCKETS + MID_BUCKETS -> FINE_END + (i - FINE_BUCKETS + 1) * MID_WIDTH - 1
            i < BUCKET_COUNT - 1 -> MID_END + (i - FINE_BUCKETS - MID_BUCKETS + 1) * COARSE_WIDTH - 1
            else -> OVERFLOW_MS
        }
    }
}

/** How the nightly job's last run ended. Closed, so no exception text can reach the file. */
enum class NightlyOutcome {
    STARTED, OFF, NOT_CHARGING, BATTERY_BELOW_90, TOO_FEW_WORDS, TOO_HOT,
    MODEL_REWRITE, MODEL_FALLBACK, NO_MODEL_FALLBACK, FAILED, STOPPED
}

/** What one cold start of the keyboard waits for, in the order they normally end. */
internal enum class ColdStartStage(val label: String) {
    ENGINES_BUILT("engines built"),
    DICTIONARY_IN("dictionary in"),
    FIRST_LOAD_OVER("first load over"),
    INITIALIZE_RETURNED("initialize returned"),
    GLIDE_TABLE_ASKED_FOR("glide table asked for")
}

/**
 * One set of cold-start times per process, measured from the keyboard's own start
 * and not the process's, because Settings or WorkManager may have started the
 * process first. Only the first set counts: a second service start in the same
 * process would otherwise overwrite it with warm figures. Marks come from both the
 * main thread and IO and only a handful per process, so a lock costs nothing here.
 */
internal class ColdStartTimes {

    private val elapsedMs = LongArray(ColdStartStage.entries.size) { -1L }
    private var anchorMs = -1L

    /** True for the first set in this process, and that answer is what [mark] then takes. */
    @Synchronized
    fun begin(nowMs: Long): Boolean {
        if (anchorMs >= 0L) return false
        anchorMs = nowMs
        return true
    }

    @Synchronized
    fun mark(firstSet: Boolean, stage: ColdStartStage, nowMs: Long) {
        if (!firstSet || anchorMs < 0L || elapsedMs[stage.ordinal] >= 0L) return
        elapsedMs[stage.ordinal] = nowMs - anchorMs
    }

    /** Milliseconds from the start to [stage], or -1 if it has not been reached. */
    @Synchronized
    fun elapsed(stage: ColdStartStage): Long = elapsedMs[stage.ordinal]

    @Synchronized
    fun isBegun(): Boolean = anchorMs >= 0L
}

/**
 * The fields shown most recently, in memory only, as a package name and two ints.
 * It takes nothing else, so hint text, labels and extras have no way in. Touched
 * only on the main thread: the keyboard adds, the erase listener removes, and the
 * screen that reads it runs there too.
 */
internal class FieldRing(private val capacity: Int = PhoneNumbers.FIELD_ROWS) {

    private val pkgs = arrayOfNulls<String>(capacity)
    private val inputTypes = IntArray(capacity)
    private val options = IntArray(capacity)
    private var oldest = 0

    var size: Int = 0
        private set

    fun add(pkg: String, inputType: Int, imeOptions: Int) {
        val at = (oldest + size) % capacity
        pkgs[at] = pkg
        inputTypes[at] = inputType
        options[at] = imeOptions
        if (size < capacity) size++ else oldest = (oldest + 1) % capacity
    }

    fun clear() {
        pkgs.fill(null)
        oldest = 0
        size = 0
    }

    /** Takes one app's rows out and keeps the rest in their order. */
    fun erase(pkg: String) {
        val kept = rows().filter { it.pkg != pkg }
        clear()
        for (row in kept) add(row.pkg, row.inputType, row.imeOptions)
    }

    /** Oldest first. Allocates, so it is for reading the section and nothing on a key. */
    fun rows(): List<FieldRow> = List(size) { i ->
        val at = (oldest + i) % capacity
        FieldRow(pkgs[at].orEmpty(), inputTypes[at], options[at])
    }
}

internal class FieldRow(val pkg: String, val inputType: Int, val imeOptions: Int)

/** Only the four numbers of a process's exit: Android's free-text description stays out of it. */
internal class ExitRecord(val reason: Int, val status: Int, val importance: Int, val timestampMs: Long)

/** One titled reading in the section. [text] is what the screen shows and what a copy takes. */
internal class NumbersBlock(val title: String, val body: String, val note: String) {
    val text: String get() = "$title\n$body"
}

/**
 * Turns the readings into words. All of it is pure and takes numbers, so a JVM test
 * can run the whole report with no phone, and so the report has nothing to reach for
 * but what it is handed. The AI diagnostics hold the last prompt verbatim once a draft
 * has run, and they have no route into this file.
 */
internal object PhoneNumbers {

    const val NOT_MEASURED = "not measured"
    const val NEVER_RAN = "never ran"
    const val PRIVATE_FIELD = "private field"
    const val FIELD_ROWS = 30
    const val DEATHS_DAYS = 7

    const val TITLE_KEY_TO_COMMIT = "Key to commit"
    const val TITLE_LIFT_DECODE = "Lift decode"
    const val TITLE_FIELD_SHOW = "Keyboard show"
    const val TITLE_COLD_START = "Cold start"
    const val TITLE_DEATHS = "Keyboard process deaths, last 7 days"
    const val TITLE_NIGHTLY = "Nightly job, last run"
    const val TITLE_FIELDS = "Last 30 fields"
    const val TITLE_COUNTS = "Counts"

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
    private fun dateAndClock(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
    private fun date(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    private fun ms(value: Int): String =
        if (value >= LatencyHistogram.OVERFLOW_MS) "$value+ ms" else "$value ms"

    /** A histogram with nothing in it says so and never reads as 0, which would look fast. */
    fun latency(h: LatencyHistogram, withP99: Boolean = false): String {
        if (h.count == 0) return NOT_MEASURED
        return buildString {
            append("p50 ").append(ms(h.percentile(50)))
            append(", p95 ").append(ms(h.percentile(95)))
            if (withP99) append(", p99 ").append(ms(h.percentile(99)))
            append(", n ").append(h.count)
        }
    }

    /** 'HH:MM', with the date too once the process has lived past today, or the time alone would mislead. */
    fun processStart(startedAtMs: Long, nowMs: Long): String =
        if (date(startedAtMs) == date(nowMs)) clock(startedAtMs) else dateAndClock(startedAtMs)

    fun coldStart(times: ColdStartTimes): String {
        if (!times.isBegun()) return NOT_MEASURED
        return ColdStartStage.entries.joinToString("\n") { stage ->
            val elapsed = times.elapsed(stage)
            stage.label + ": " + if (elapsed < 0L) NOT_MEASURED else "$elapsed ms"
        }
    }

    fun nightly(timeMs: Long, outcomeName: String?): String {
        if (timeMs <= 0L || outcomeName == null) return NEVER_RAN
        return dateAndClock(timeMs) + "  " + outcomeName + ": " + nightlyMeaning(outcomeName)
    }

    private fun nightlyMeaning(outcomeName: String): String = when (outcomeName) {
        NightlyOutcome.STARTED.name -> "it began and no end is recorded, so it is still running or was killed"
        NightlyOutcome.OFF.name -> "switched off in settings"
        NightlyOutcome.NOT_CHARGING.name -> "not charging, so it did nothing"
        NightlyOutcome.BATTERY_BELOW_90.name -> "battery under 90%, so it did nothing"
        NightlyOutcome.TOO_FEW_WORDS.name -> "too little typed lately, so it did nothing"
        NightlyOutcome.TOO_HOT.name -> "phone too warm, so it saved no style notes"
        NightlyOutcome.MODEL_REWRITE.name -> "finished, with the model's rewrite"
        NightlyOutcome.MODEL_FALLBACK.name -> "finished, without the model's rewrite"
        NightlyOutcome.NO_MODEL_FALLBACK.name -> "finished, with no model it may use"
        NightlyOutcome.FAILED.name -> "it could not finish"
        NightlyOutcome.STOPPED.name -> "Android stopped it before it saved its style notes"
        else -> "not a known outcome"
    }

    /** The four numbers and nothing else. Status is beside the reason because a low-memory kill can read as signaled 9. */
    fun deathRow(reason: Int, status: Int, importance: Int, timestampMs: Long): String =
        dateAndClock(timestampMs) + "  " + reasonName(reason) + "  status " + status +
            "  importance " + importanceName(importance)

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited by itself"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package updated"
        else -> "reason $reason"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "top sleeping ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "cannot save state ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached ($importance)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone ($importance)"
        else -> "$importance"
    }

    /**
     * Android keeps only recent records, so a phone killing the keyboard often can show a
     * short list that reaches back only a day or two. It must not read as few deaths, so
     * the list says how far back it goes whenever that is less than the week. [records]
     * is null when Android would not give them up.
     */
    fun deaths(records: List<ExitRecord>?, lowMemoryReported: Boolean, nowMs: Long): String = buildString {
        append("low-memory kills named by this phone: ")
        append(if (lowMemoryReported) "yes" else "no, so one shows as signaled, status 9")
        append('\n')
        if (records == null) {
            append("Android's list could not be read")
            return@buildString
        }
        if (records.isEmpty()) {
            append("none on Android's list")
            return@buildString
        }
        val weekAgo = nowMs - DEATHS_DAYS * DAY_MS
        val inWeek = records.filter { it.timestampMs >= weekAgo }.sortedByDescending { it.timestampMs }
        append(inWeek.size).append(" in the last ").append(DEATHS_DAYS).append(" days")
        for (r in inWeek) {
            append('\n').append(deathRow(r.reason, r.status, r.importance, r.timestampMs))
        }
        val oldest = records.minOf { it.timestampMs }
        if (oldest >= weekAgo) {
            append("\nAndroid's list only reaches back to ").append(date(oldest))
            append(", so there may have been more before that")
        }
    }

    /** Works the flags out from the two ints only when someone reads them. */
    fun fieldRow(pkg: String, inputType: Int, imeOptions: Int): String = buildString {
        append(if (pkg.isEmpty()) PRIVATE_FIELD else pkg)
        append("  class ").append(inputClass(inputType))
        append(", variation ").append(variation(inputType))
        append(", flags ").append(inputFlags(inputType))
        append(", action ").append(action(imeOptions))
        val extra = optionFlags(imeOptions)
        if (extra.isNotEmpty()) append(", options ").append(extra)
    }

    private fun hex(value: Int) = "0x" + Integer.toHexString(value)

    private fun inputClass(inputType: Int): String = when (val c = inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_NULL -> "none"
        InputType.TYPE_CLASS_TEXT -> "text"
        InputType.TYPE_CLASS_NUMBER -> "number"
        InputType.TYPE_CLASS_PHONE -> "phone"
        InputType.TYPE_CLASS_DATETIME -> "datetime"
        else -> hex(c)
    }

    private fun variation(inputType: Int): String {
        val v = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> when (v) {
                InputType.TYPE_TEXT_VARIATION_NORMAL -> "normal"
                InputType.TYPE_TEXT_VARIATION_URI -> "uri"
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> "email"
                InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> "email-subject"
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> "short-message"
                InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> "long-message"
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> "person-name"
                InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> "postal-address"
                InputType.TYPE_TEXT_VARIATION_PASSWORD -> "password"
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> "visible-password"
                InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> "web-edit"
                InputType.TYPE_TEXT_VARIATION_FILTER -> "filter"
                InputType.TYPE_TEXT_VARIATION_PHONETIC -> "phonetic"
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "web-email"
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> "web-password"
                else -> hex(v)
            }
            InputType.TYPE_CLASS_NUMBER -> when (v) {
                InputType.TYPE_NUMBER_VARIATION_NORMAL -> "normal"
                InputType.TYPE_NUMBER_VARIATION_PASSWORD -> "password"
                else -> hex(v)
            }
            InputType.TYPE_CLASS_DATETIME -> when (v) {
                InputType.TYPE_DATETIME_VARIATION_NORMAL -> "normal"
                InputType.TYPE_DATETIME_VARIATION_DATE -> "date"
                InputType.TYPE_DATETIME_VARIATION_TIME -> "time"
                else -> hex(v)
            }
            else -> if (v == 0) "normal" else hex(v)
        }
    }

    private val TEXT_FLAGS = listOf(
        InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS to "cap-characters",
        InputType.TYPE_TEXT_FLAG_CAP_WORDS to "cap-words",
        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES to "cap-sentences",
        InputType.TYPE_TEXT_FLAG_AUTO_CORRECT to "auto-correct",
        InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE to "auto-complete",
        InputType.TYPE_TEXT_FLAG_MULTI_LINE to "multi-line",
        InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE to "ime-multi-line",
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS to "no-suggestions"
    )

    private val NUMBER_FLAGS = listOf(
        InputType.TYPE_NUMBER_FLAG_SIGNED to "signed",
        InputType.TYPE_NUMBER_FLAG_DECIMAL to "decimal"
    )

    private val OPTION_FLAGS = listOf(
        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING to "no-personalized-learning",
        EditorInfo.IME_FLAG_NO_FULLSCREEN to "no-fullscreen",
        EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS to "navigate-previous",
        EditorInfo.IME_FLAG_NAVIGATE_NEXT to "navigate-next",
        EditorInfo.IME_FLAG_NO_EXTRACT_UI to "no-extract-ui",
        EditorInfo.IME_FLAG_NO_ACCESSORY_ACTION to "no-accessory-action",
        EditorInfo.IME_FLAG_NO_ENTER_ACTION to "no-enter-action",
        EditorInfo.IME_FLAG_FORCE_ASCII to "force-ascii"
    )

    /** Named bits first. Any bit with no name stays visible as a number instead of vanishing. */
    private fun named(bits: Int, names: List<Pair<Int, String>>): String {
        var rest = bits
        val out = ArrayList<String>()
        for ((bit, name) in names) {
            if (bits and bit != 0) {
                out += name
                rest = rest and bit.inv()
            }
        }
        if (rest != 0) out += hex(rest)
        return out.joinToString(" ")
    }

    private fun inputFlags(inputType: Int): String {
        val bits = inputType and InputType.TYPE_MASK_FLAGS
        val text = when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> named(bits, TEXT_FLAGS)
            InputType.TYPE_CLASS_NUMBER -> named(bits, NUMBER_FLAGS)
            else -> if (bits == 0) "" else hex(bits)
        }
        return text.ifEmpty { "none" }
    }

    private fun action(imeOptions: Int): String = when (val a = imeOptions and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_UNSPECIFIED -> "unspecified"
        EditorInfo.IME_ACTION_NONE -> "none"
        EditorInfo.IME_ACTION_GO -> "go"
        EditorInfo.IME_ACTION_SEARCH -> "search"
        EditorInfo.IME_ACTION_SEND -> "send"
        EditorInfo.IME_ACTION_NEXT -> "next"
        EditorInfo.IME_ACTION_DONE -> "done"
        EditorInfo.IME_ACTION_PREVIOUS -> "previous"
        else -> "$a"
    }

    private fun optionFlags(imeOptions: Int): String =
        named(imeOptions and EditorInfo.IME_MASK_ACTION.inv(), OPTION_FLAGS)

    fun fields(ring: FieldRing): String {
        val rows = ring.rows()
        if (rows.isEmpty()) return NOT_MEASURED
        return rows.joinToString("\n") { fieldRow(it.pkg, it.inputType, it.imeOptions) }
    }

    fun blocks(
        buildType: String,
        processStartedAtMs: Long,
        keyToCommit: LatencyHistogram,
        liftDecode: LatencyHistogram,
        fieldShow: LatencyHistogram,
        coldStart: ColdStartTimes,
        nightlyTimeMs: Long,
        nightlyOutcomeName: String?,
        deaths: List<ExitRecord>?,
        lowMemoryReported: Boolean,
        fields: FieldRing,
        counts: String,
        nowMs: Long
    ): List<NumbersBlock> = listOf(
        NumbersBlock(
            TITLE_KEY_TO_COMMIT,
            "build: " + buildType + "\n" + latency(keyToCommit) +
                "\nsince process started " + processStart(processStartedAtMs, nowMs),
            "Time until the keyboard handed the text over, counted from the touch event that set the " +
                "key off, so a key held down does not add its hold. Keys that type text, Space and " +
                "Backspace, but not Enter, shift or the mode keys. It starts again when the keyboard's " +
                "process restarts. Times from a debug build say nothing about how fast a release is."
        ),
        NumbersBlock(
            TITLE_LIFT_DECODE,
            latency(liftDecode, withP99 = true),
            "How long working out a glide's word took when the finger lifted, on the main thread. " +
                "It starts again when the keyboard's process restarts, and a debug build's times say " +
                "nothing about a release here either."
        ),
        NumbersBlock(
            TITLE_FIELD_SHOW,
            latency(fieldShow, withP99 = true),
            "How long the keyboard's own work took each time it was handed a text box, on the main thread, " +
                "when an app restarts a box too. That work includes asking the app what is already in the box, " +
                "so a busy app shows here, and the keys can come up later by this much. It starts again when " +
                "the keyboard's process restarts, and a debug build's times say nothing about a release here either."
        ),
        NumbersBlock(
            TITLE_COLD_START,
            coldStart(coldStart),
            "Times from the keyboard's first start in this process, one set per process. " +
                "The last line is when the glide table was asked for, not when it was finished."
        ),
        NumbersBlock(
            TITLE_DEATHS,
            deaths(deaths, lowMemoryReported, nowMs),
            "Android's own record of how this process ended. Importance is how Android rated the process " +
                "at that moment, from foreground, in use, down to cached, idle in the background."
        ),
        NumbersBlock(
            TITLE_NIGHTLY,
            nightly(nightlyTimeMs, nightlyOutcomeName),
            "When the overnight job last ran and how that run ended. The time and the outcome are kept " +
                "in a small file on this phone."
        ),
        NumbersBlock(
            TITLE_FIELDS,
            fields(fields),
            "The kinds of text box the keyboard came up in, newest last: the app's package name, and how " +
                "the box describes itself. Never what was typed. A box that asked not to be remembered, " +
                "or one in an app the keyboard is told not to learn from, shows no package. The list " +
                "stays in memory, is gone when the keyboard's process ends, and Erase all data empties it."
        ),
        NumbersBlock(
            TITLE_COUNTS,
            counts,
            "How often each of these happened on this phone. Kept in a small file, so they outlast a restart."
        )
    )

    /** Titles and numbers only, so the words about them stay on the screen. */
    fun report(blocks: List<NumbersBlock>): String =
        blocks.joinToString("\n\n", prefix = "IOKey typing numbers\n\n") { it.text }
}

/**
 * Readings the owner can see on the phone without tools. Counts and times only:
 * every entry point takes primitives, so nothing typed can get in later.
 *
 * Like [FrameStats] these are plain fields with no lock. The key-side writes all
 * happen on the main thread and so does the screen that reads them, and a key
 * press must never wait on a diagnostics screen. They live in the process and
 * die with it.
 */
object PhoneStats {

    private const val NIGHTLY_FILE = "phone_stats"
    private const val KEY_NIGHTLY_TIME = "nightly_time"
    private const val KEY_NIGHTLY_OUTCOME = "nightly_outcome"

    private var pendingStartMs = -1L
    internal val keyToCommit = LatencyHistogram()
    internal val liftDecode = LatencyHistogram()
    internal val fieldShow = LatencyHistogram()
    private val coldStart = ColdStartTimes()
    internal val fields = FieldRing()

    /**
     * Stamps the start of a key from the touch event's own time. Called for every
     * event but a move, so the up re-stamps and a held key does not count its hold.
     * Main thread, no allocation, no lock.
     */
    fun noteKeyDown(eventTimeMs: Long) {
        pendingStartMs = eventTimeMs
    }

    /**
     * The keyboard has handed the text over. Consumes the start, so a commit with
     * no key behind it records nothing. Main thread, no allocation, no lock.
     */
    fun noteCommitted() {
        noteCommittedAt(SystemClock.uptimeMillis())
    }

    internal fun noteCommittedAt(nowMs: Long) {
        val start = pendingStartMs
        if (start < 0L) return
        pendingStartMs = -1L
        keyToCommit.record(nowMs - start)
    }

    /** One glide lift's full decode, on the main thread. No allocation, no lock. */
    fun noteLiftDecode(durationMs: Long) {
        liftDecode.record(durationMs)
    }

    /** One pass of the keyboard's work when it is handed a field, on the main thread. No allocation, no lock. */
    fun noteFieldShow(durationMs: Long) {
        fieldShow.record(durationMs)
    }

    /** A field was shown. Main thread, primitives and a package name only. An empty package is a private field. */
    fun noteField(pkg: String, inputType: Int, imeOptions: Int) {
        fields.add(pkg, inputType, imeOptions)
    }

    /** Null empties the whole list, and a package takes out that app's rows. Main thread, from the erase listener. */
    internal fun eraseFields(packageName: String?) {
        if (packageName == null) fields.clear() else fields.erase(packageName)
    }

    /**
     * A rule set on an app does not reach the rows already here, and the section says such an app shows
     * no package. A private field's row names nobody, so it stays. Allocates, so it is for reading the section.
     */
    internal fun eraseFieldsOfAppsRuledOut(mayLearnIn: (String) -> Boolean) {
        for (row in fields.rows()) if (row.pkg.isNotEmpty() && !mayLearnIn(row.pkg)) fields.erase(row.pkg)
    }

    /**
     * Worker thread, and it never throws: a failed write must not change the job's result.
     * Written with commit, because a kill during the model load is what the STARTED record is
     * there to catch, and it would otherwise leave last night's good result showing.
     */
    fun noteNightlyRun(context: Context, outcome: NightlyOutcome) {
        try {
            context.getSharedPreferences(NIGHTLY_FILE, Context.MODE_PRIVATE).edit()
                .putLong(KEY_NIGHTLY_TIME, System.currentTimeMillis())
                .putString(KEY_NIGHTLY_OUTCOME, outcome.name)
                .commit()
        } catch (_: Throwable) {
            // The file is only there to be read on a screen. The job's result is what matters.
        }
    }

    /**
     * 'Erase all data' says it all went, and this file outlasts the process, so it goes too. It holds
     * a time and an outcome and nothing personal, so a failure here is not the erase failing.
     */
    internal fun eraseNightly(context: Context) {
        try {
            context.getSharedPreferences(NIGHTLY_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        } catch (_: Throwable) {
            // Only ever read on a screen, as with the write above.
        }
    }

    /** The anchor for this start's times. True only for the first start in this process. */
    internal fun coldStartBegin(): Boolean = coldStart.begin(SystemClock.elapsedRealtime())

    internal fun coldStartMark(firstSet: Boolean, stage: ColdStartStage) {
        coldStart.mark(firstSet, stage, SystemClock.elapsedRealtime())
    }

    /** Everything the section shows, read when it opens. */
    internal fun blocks(context: Context, buildType: String): List<NumbersBlock> {
        val nowMs = System.currentTimeMillis()
        eraseFieldsOfAppsRuledOut(Prefs(context)::mayLearnIn)
        val nightly = try {
            context.getSharedPreferences(NIGHTLY_FILE, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
        return PhoneNumbers.blocks(
            buildType = buildType,
            processStartedAtMs = nowMs - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()),
            keyToCommit = keyToCommit,
            liftDecode = liftDecode,
            fieldShow = fieldShow,
            coldStart = coldStart,
            nightlyTimeMs = nightly?.getLong(KEY_NIGHTLY_TIME, 0L) ?: 0L,
            nightlyOutcomeName = nightly?.getString(KEY_NIGHTLY_OUTCOME, null),
            deaths = readExits(context),
            lowMemoryReported = ActivityManager.isLowMemoryKillReportSupported(),
            fields = fields,
            counts = LocalStats.summary(),
            nowMs = nowMs
        )
    }

    /** Copies out the four numbers at the door, so nothing past here can touch the rest of a record. */
    private fun readExits(context: Context): List<ExitRecord>? = try {
        context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, 0)
            .map { ExitRecord(it.reason, it.status, it.importance, it.timestamp) }
    } catch (_: Throwable) {
        null
    }
}
