package com.localboard.keyboard.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.localboard.keyboard.llm.AiDiagLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The readings behind the Typing numbers section. It runs on a plain JVM, so the logic has to
 * work with no Android clock and no phone, which is also what keeps the copied report honest:
 * it can only hold what it is handed.
 */
class PhoneStatsTest {

    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    private fun histogramOf(vararg ms: Long) = LatencyHistogram().apply { ms.forEach { record(it) } }

    // ---------------------------------------------------------------- histogram

    @Test
    fun `each reading lands in a bucket whose top is the most that bucket can hold`() {
        val cases = listOf(
            0L to 0, 1L to 1, 49L to 49,
            50L to 54, 54L to 54, 55L to 59, 199L to 199,
            200L to 249, 249L to 249, 250L to 299, 999L to 999,
            1000L to 1000, 123_456L to 1000
        )
        for ((reading, top) in cases) {
            assertEquals("a reading of $reading ms", top, histogramOf(reading).percentile(50))
        }
    }

    @Test
    fun `percentiles are the nearest rank`() {
        val h = LatencyHistogram()
        for (ms in 0L until 100L) h.record(ms)
        assertEquals(100, h.count)
        assertEquals(49, h.percentile(50))
        assertEquals(94, h.percentile(95))
        assertEquals(99, h.percentile(99))
    }

    @Test
    fun `one slow reading among twenty moves p99 and not p50`() {
        val h = LatencyHistogram()
        repeat(19) { h.record(3L) }
        h.record(400L)
        assertEquals(3, h.percentile(50))
        assertEquals(3, h.percentile(95))
        assertEquals(449, h.percentile(99))
    }

    @Test
    fun `a negative time is dropped`() {
        val h = histogramOf(-1L, -5000L)
        assertEquals(0, h.count)
        assertEquals(PhoneNumbers.NOT_MEASURED, PhoneNumbers.latency(h))
    }

    @Test
    fun `empty reads not measured and never 0`() {
        assertEquals(-1, LatencyHistogram().percentile(95))
        assertEquals("not measured", PhoneNumbers.latency(LatencyHistogram()))
        assertEquals("not measured", PhoneNumbers.latency(LatencyHistogram(), withP99 = true))
    }

    @Test
    fun `the percentiles print with n`() {
        val h = histogramOf(3L, 3L, 12L)
        assertEquals("p50 3 ms, p95 12 ms, n 3", PhoneNumbers.latency(h))
        assertEquals("p50 3 ms, p95 12 ms, p99 12 ms, n 3", PhoneNumbers.latency(h, withP99 = true))
    }

    @Test
    fun `the overflow says at least that long`() {
        assertEquals("p50 1000+ ms, p95 1000+ ms, n 1", PhoneNumbers.latency(histogramOf(5000L)))
    }

    @Test
    fun `clear starts it again`() {
        val h = histogramOf(3L)
        h.clear()
        assertEquals(0, h.count)
        h.record(7L)
        assertEquals(7, h.percentile(50))
    }

    // ------------------------------------------------------------ key to commit

    private fun freshKeyToCommit() {
        PhoneStats.noteCommittedAt(0L)
        PhoneStats.keyToCommit.clear()
    }

    @Test
    fun `a commit with no key behind it records nothing and a key counts once`() {
        freshKeyToCommit()
        PhoneStats.noteCommittedAt(2_000L)
        assertEquals(0, PhoneStats.keyToCommit.count)

        PhoneStats.noteKeyDown(2_000L)
        PhoneStats.noteCommittedAt(2_007L)
        assertEquals(1, PhoneStats.keyToCommit.count)
        assertEquals(7, PhoneStats.keyToCommit.percentile(50))

        PhoneStats.noteCommittedAt(2_050L)
        assertEquals("the start was consumed", 1, PhoneStats.keyToCommit.count)
    }

    @Test
    fun `the lift re-stamps so a held key does not count its hold`() {
        freshKeyToCommit()
        PhoneStats.noteKeyDown(1_000L)
        PhoneStats.noteKeyDown(1_500L)
        PhoneStats.noteCommittedAt(1_503L)
        assertEquals(3, PhoneStats.keyToCommit.percentile(95))
    }

    @Test
    fun `lift decode has its own figures`() {
        PhoneStats.liftDecode.clear()
        PhoneStats.noteLiftDecode(9L)
        assertEquals("p50 9 ms, p95 9 ms, p99 9 ms, n 1", PhoneNumbers.latency(PhoneStats.liftDecode, withP99 = true))
    }

    @Test
    fun `a keyboard show has its own figures`() {
        PhoneStats.fieldShow.clear()
        PhoneStats.noteFieldShow(12L)
        assertEquals("p50 12 ms, p95 12 ms, p99 12 ms, n 1", PhoneNumbers.latency(PhoneStats.fieldShow, withP99 = true))
        PhoneStats.fieldShow.clear()
    }

    @Test
    fun `the show block reads not measured until a show has a value`() {
        fun body(show: LatencyHistogram) = fixture(FieldRing(), show).first { it.title == PhoneNumbers.TITLE_FIELD_SHOW }.body
        assertEquals("not measured", body(LatencyHistogram()))
        assertEquals("p50 1000+ ms, p95 1000+ ms, p99 1000+ ms, n 1", body(histogramOf(2_000L)))
    }

    // ------------------------------------------------------------------ nightly

    /** The persona pass saves before the model's turn, so an outcome that stops there cannot say that nothing was saved. */
    @Test
    fun `the nightly line says what each ending really did`() {
        val meanings = mapOf(
            NightlyOutcome.TOO_HOT to "phone too warm, so it saved no style notes",
            NightlyOutcome.STOPPED to "Android stopped it before it saved its style notes",
            NightlyOutcome.NO_MODEL_FALLBACK to "finished, with no model it may use",
            NightlyOutcome.FAILED to "it could not finish",
        )
        for ((outcome, meaning) in meanings) {
            val line = PhoneNumbers.nightly(now, outcome.name)
            assertTrue(line, line.endsWith("${outcome.name}: $meaning"))
        }
    }

    @Test
    fun `each outcome formats with its name and a meaning of its own`() {
        val lines = NightlyOutcome.entries.map { PhoneNumbers.nightly(now, it.name) }
        NightlyOutcome.entries.forEachIndexed { i, outcome ->
            assertTrue(lines[i], lines[i].contains(outcome.name))
            assertFalse(lines[i], lines[i].contains("not a known outcome"))
        }
        assertEquals("every outcome reads differently", lines.size, lines.toSet().size)
    }

    @Test
    fun `no run yet reads never ran`() {
        assertEquals("never ran", PhoneNumbers.nightly(0L, null))
        assertEquals("never ran", PhoneNumbers.nightly(now, null))
    }

    @Test
    fun `started says no end is recorded because a kill leaves it there`() {
        assertTrue(PhoneNumbers.nightly(now, NightlyOutcome.STARTED.name).contains("no end is recorded"))
    }

    // ------------------------------------------------------------------- deaths

    private fun exit(
        reason: Int = ApplicationExitInfo.REASON_SIGNALED,
        status: Int = 0,
        importance: Int = ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE,
        at: Long = now - day
    ) = ExitRecord(reason, status, importance, at)

    @Test
    fun `signaled shows its status`() {
        val row = PhoneNumbers.deathRow(ApplicationExitInfo.REASON_SIGNALED, 9, 300, now)
        assertTrue(row, row.contains("signaled  status 9"))
    }

    @Test
    fun `low memory reads as low memory`() {
        val row = PhoneNumbers.deathRow(ApplicationExitInfo.REASON_LOW_MEMORY, 0, 400, now)
        assertTrue(row, row.contains("low memory  status 0"))
    }

    @Test
    fun `an unknown reason prints as a number`() {
        val row = PhoneNumbers.deathRow(77, 1, 300, now)
        assertTrue(row, row.contains("reason 77  status 1"))
    }

    @Test
    fun `importance tells in use from the background`() {
        assertTrue(PhoneNumbers.deathRow(2, 9, ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE, now).contains("importance visible (200)"))
        assertTrue(PhoneNumbers.deathRow(2, 9, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED, now).contains("importance cached (400)"))
        assertTrue(PhoneNumbers.deathRow(2, 9, 123, now).contains("importance 123"))
    }

    @Test
    fun `a list reaching back 2 days says so`() {
        val text = PhoneNumbers.deaths(listOf(exit(at = now - 2 * day)), true, now)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now - 2 * day))
        assertTrue(text, text.contains("only reaches back to $date"))
        assertTrue(text, text.contains("1 in the last 7 days"))
    }

    @Test
    fun `a list reaching past the week leaves out what is older and does not hedge`() {
        val text = PhoneNumbers.deaths(listOf(exit(at = now - day), exit(at = now - 8 * day)), true, now)
        assertTrue(text, text.contains("1 in the last 7 days"))
        assertFalse(text, text.contains("reaches back"))
        assertEquals(text, 1, text.lines().count { it.contains("signaled  status") })
    }

    @Test
    fun `the newest death comes first`() {
        val text = PhoneNumbers.deaths(
            listOf(exit(status = 1, at = now - 3 * day), exit(status = 2, at = now - day)),
            true,
            now
        )
        assertTrue(text, text.indexOf("status 2") < text.indexOf("status 1"))
    }

    @Test
    fun `an empty list says that and claims no count for the week`() {
        val text = PhoneNumbers.deaths(emptyList(), true, now)
        assertTrue(text, text.contains("none on Android's list"))
        assertFalse(text, text.contains("in the last 7 days"))
        assertFalse(text, text.contains("reaches back"))
    }

    @Test
    fun `a list Android would not give up says so`() {
        assertTrue(PhoneNumbers.deaths(null, true, now).contains("could not be read"))
    }

    @Test
    fun `it says whether this phone names low-memory kills`() {
        assertTrue(PhoneNumbers.deaths(emptyList(), true, now).contains("named by this phone: yes"))
        assertTrue(PhoneNumbers.deaths(emptyList(), false, now).contains("named by this phone: no, so one shows as signaled, status 9"))
    }

    // --------------------------------------------------------------- cold start

    @Test
    fun `nothing yet reads not measured`() {
        assertEquals("not measured", PhoneNumbers.coldStart(ColdStartTimes()))
    }

    @Test
    fun `a second set is ignored`() {
        val times = ColdStartTimes()
        val first = times.begin(1_000L)
        assertTrue(first)
        times.mark(first, ColdStartStage.ENGINES_BUILT, 1_010L)

        val second = times.begin(5_000L)
        assertFalse(second)
        times.mark(second, ColdStartStage.ENGINES_BUILT, 5_001L)
        times.mark(second, ColdStartStage.DICTIONARY_IN, 5_002L)

        assertEquals(10L, times.elapsed(ColdStartStage.ENGINES_BUILT))
        assertEquals("a warm figure must not fill a gap", -1L, times.elapsed(ColdStartStage.DICTIONARY_IN))

        times.mark(first, ColdStartStage.DICTIONARY_IN, 1_200L)
        assertEquals(200L, times.elapsed(ColdStartStage.DICTIONARY_IN))
    }

    @Test
    fun `a stage is set once`() {
        val times = ColdStartTimes()
        val first = times.begin(0L)
        times.mark(first, ColdStartStage.ENGINES_BUILT, 10L)
        times.mark(first, ColdStartStage.ENGINES_BUILT, 99L)
        assertEquals(10L, times.elapsed(ColdStartStage.ENGINES_BUILT))
    }

    @Test
    fun `a stage not reached reads not measured beside those that were`() {
        val times = ColdStartTimes()
        val first = times.begin(0L)
        times.mark(first, ColdStartStage.ENGINES_BUILT, 10L)
        val text = PhoneNumbers.coldStart(times)
        assertTrue(text, text.contains("engines built: 10 ms"))
        assertTrue(text, text.contains("first load over: not measured"))
        assertEquals(ColdStartStage.entries.size, text.lines().size)
    }

    // ------------------------------------------------------------------- fields

    @Test
    fun `the ring holds 30 and the oldest goes`() {
        val ring = FieldRing()
        for (i in 1..31) ring.add("app$i", 0, 0)
        assertEquals(30, ring.size)
        val rows = ring.rows()
        assertEquals(30, rows.size)
        assertEquals("app2", rows.first().pkg)
        assertEquals("app31", rows.last().pkg)
    }

    @Test
    fun `erasing a package takes its rows and keeps the rest in order, also after a wrap`() {
        val ring = FieldRing(capacity = 4)
        for (pkg in listOf("gone", "a", "gone", "b", "c", "gone")) ring.add(pkg, 0, 0)
        ring.erase("gone")
        assertEquals(listOf("b", "c"), ring.rows().map { it.pkg })
        ring.add("d", 0, 0)
        assertEquals(listOf("b", "c", "d"), ring.rows().map { it.pkg })
    }

    @Test
    fun `erasing everything empties it, and it works again after`() {
        val ring = FieldRing()
        ring.add("a", 1, 2)
        ring.clear()
        assertEquals(0, ring.size)
        assertEquals("not measured", PhoneNumbers.fields(ring))
        ring.add("b", 1, 2)
        assertEquals(listOf("b"), ring.rows().map { it.pkg })
    }

    @Test
    fun `the erase listener's entry takes a package or everything`() {
        PhoneStats.eraseFields(null)
        PhoneStats.noteField("kept.app", 0, 0)
        PhoneStats.noteField("gone.app", 0, 0)
        PhoneStats.eraseFields("gone.app")
        assertEquals(listOf("kept.app"), PhoneStats.fields.rows().map { it.pkg })
        PhoneStats.eraseFields(null)
        assertEquals(0, PhoneStats.fields.size)
    }

    @Test
    fun `rows from before a per-app rule go when the section reads them, and a private field's stay`() {
        PhoneStats.eraseFields(null)
        for (pkg in listOf("ruled.out", "", "kept.app", "ruled.out")) PhoneStats.noteField(pkg, 0, 0)
        PhoneStats.eraseFieldsOfAppsRuledOut { it == "kept.app" }
        assertEquals(listOf("", "kept.app"), PhoneStats.fields.rows().map { it.pkg })
        PhoneStats.eraseFields(null)
    }

    @Test
    fun `a row is worked out from the two ints`() {
        val row = PhoneNumbers.fieldRow(
            "some.app",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        )
        assertEquals(
            "some.app  class text, variation short-message, flags cap-sentences multi-line, " +
                "action send, options no-enter-action",
            row
        )
    }

    @Test
    fun `a number pad row shows what hiding keys on it would depend on`() {
        val row = PhoneNumbers.fieldRow(
            "some.app",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            EditorInfo.IME_ACTION_DONE
        )
        assertEquals("some.app  class number, variation normal, flags decimal, action done", row)
    }

    @Test
    fun `a private field shows no package`() {
        val row = PhoneNumbers.fieldRow("", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NONE)
        assertTrue(row, row.startsWith("private field  class text"))
    }

    @Test
    fun `bits with no name stay visible as numbers`() {
        val row = PhoneNumbers.fieldRow(
            "some.app",
            InputType.TYPE_CLASS_TEXT or 0x400000,
            0x77
        )
        assertTrue(row, row.contains("flags 0x400000"))
        assertTrue(row, row.contains("action 119"))
    }

    // ------------------------------------------------------------------- report

    @Test
    fun `the process start reads as a time today and with its date before that`() {
        val clock = SimpleDateFormat("HH:mm", Locale.US)
        assertEquals(clock.format(Date(now)), PhoneNumbers.processStart(now, now))
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now - 2 * day))
        assertTrue(PhoneNumbers.processStart(now - 2 * day, now).startsWith(date))
    }

    private fun fixture(fields: FieldRing, fieldShow: LatencyHistogram = LatencyHistogram()): List<NumbersBlock> = PhoneNumbers.blocks(
        buildType = "debug",
        processStartedAtMs = now - 60_000L,
        keyToCommit = histogramOf(3L, 12L),
        liftDecode = LatencyHistogram(),
        fieldShow = fieldShow,
        coldStart = ColdStartTimes(),
        nightlyTimeMs = 0L,
        nightlyOutcomeName = null,
        deaths = listOf(exit(status = 9)),
        lowMemoryReported = false,
        fields = fields,
        counts = LocalStats.summary(),
        nowMs = now
    )

    @Test
    fun `every block is in the report with the build type and the counts`() {
        val blocks = fixture(FieldRing().apply { add("some.app", InputType.TYPE_CLASS_TEXT, 0) })
        val report = PhoneNumbers.report(blocks)
        for (title in listOf(
            PhoneNumbers.TITLE_KEY_TO_COMMIT, PhoneNumbers.TITLE_LIFT_DECODE, PhoneNumbers.TITLE_FIELD_SHOW,
            PhoneNumbers.TITLE_COLD_START, PhoneNumbers.TITLE_DEATHS, PhoneNumbers.TITLE_NIGHTLY, PhoneNumbers.TITLE_FIELDS,
            PhoneNumbers.TITLE_COUNTS
        )) {
            assertTrue(title, report.contains("$title\n"))
        }
        assertTrue(report, report.contains("build: debug"))
        assertTrue(report, report.contains("p50 3 ms, p95 12 ms, n 2"))
        assertTrue(report, report.contains("since process started"))
        assertTrue(report, report.contains("correction_fired: "))
        assertTrue(report, report.contains("draft_accepted: "))
        assertTrue(report, report.contains("glide_committed: "))
        assertTrue(report, report.contains("glide_wiped: "))
        assertTrue(report, report.contains("some.app"))
        assertTrue(report, report.contains("never ran"))
        val keyToCommitNote = blocks.first { it.title == PhoneNumbers.TITLE_KEY_TO_COMMIT }.note
        assertTrue(keyToCommitNote, keyToCommitNote.contains("handed the text over"))
        assertTrue(keyToCommitNote, keyToCommitNote.contains("debug build"))
    }

    @Test
    fun `the report holds none of the AI diagnostics, even with a prompt in them`() {
        val prompt = "an invented prompt about a plum"
        val headings = listOf("screen ctx last draft", "typed ctx", "field ctx", "last prompt", "load: ", "quick lane: ")
        try {
            AiDiagLog.recordPrompt("draft", prompt)
            AiDiagLog.lastLoadEvent = "an invented load event"
            AiDiagLog.quickRuns = 1
            AiDiagLog.quickTotalMs = 100L
            val diagnostics = AiDiagLog.report("model line", "screen line")
            for (heading in headings) {
                assertTrue("'$heading' must still be what the diagnostics print", diagnostics.contains(heading))
            }
            assertTrue(diagnostics.contains(prompt))

            val report = PhoneNumbers.report(fixture(FieldRing()))
            for (heading in headings + prompt + "an invented load event" + "model line" + "screen line" + "crash log") {
                assertFalse("'$heading' must not be in the report:\n$report", report.contains(heading))
            }
        } finally {
            AiDiagLog.lastPrompt = ""
            AiDiagLog.lastPromptAt = 0L
            AiDiagLog.lastPromptPurpose = ""
            AiDiagLog.lastLoadEvent = ""
            AiDiagLog.quickRuns = 0
            AiDiagLog.quickTotalMs = 0L
        }
    }

    /** About what is absent, so it reads the source: nothing else can catch a later line added beside the rest. */
    @Test
    fun `the section's code has no route to the AI diagnostics, the crash log or free text`() {
        val root = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        val stats = File(root, "java/com/localboard/keyboard/util/PhoneStats.kt").readText()
        val motion = File(root, "java/com/localboard/keyboard/settings/MotionActivity.kt").readText()
        for (name in listOf("AiDiagLog", "CRASH_FILE", "last_crash")) {
            assertFalse("PhoneStats must not name $name", stats.contains(name))
            assertFalse("MotionActivity must not name $name", motion.contains(name))
        }
        for (name in listOf(".description", ".traceInputStream", ".processStateSummary", ".hintText", ".extras")) {
            assertFalse("PhoneStats must not read $name", stats.contains(name))
        }
    }
}
