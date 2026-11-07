package io.ioservices.iokey.e2e

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Phase 2, on the new build installed over what UpgradeSeedTest left. Each test asks one thing, and one going red
 * does not stop the next, because what failed and what held are both the result. They run in name order: the first
 * show comes first, what the old store holds is asked before anything new is typed, and the restarts come after.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UpgradeVerifyTest : UpgradeTestBase() {

    private fun stored(): String = ime.readPrefsXml() ?: throw AssertionError("The keyboard has no preferences file")

    private fun holds(xml: String, name: String) = xml.contains("name=\"$name\"")

    private fun crashLog(): String = runAs("cat $CRASH_LOG 2>/dev/null").trim()

    /** The harness empties the crash log after each test and keeps only what the keyboard lived through, so read it first. */
    @Before
    fun crashLogIsReadBeforeTheHarnessEmptiesIt() {
        atEnd { crashLog().takeIf { it.isNotEmpty() }?.let { record("crash-log-at-end", it.take(MAX_NOTE)) } }
    }

    private fun assertCrashLogEmpty() {
        val log = crashLog()
        assertEquals("The crash log must be empty, but holds:\n$log", "", log)
    }

    private fun Problems.settingsRepaired(xml: String) {
        for (name in RETIRED) expect(!holds(xml, name)) { "$name must be gone" }
        expect(xml.contains("<int name=\"geometry_schema\" value=\"2\" />")) { "The stamp must be on disk" }
        expect(xml.contains("<int name=\"nav_strip_mode\" value=\"0\" />")) { "nav_strip_mode must be 0" }
        expect(!holds(xml, "llm_suggestions_reset_v2")) { "The old reset flag must be gone" }
        expect(xml.contains("<boolean name=\"llm_suggestions\" value=\"true\" />")) { "This person's llm_suggestions choice must stay" }
    }

    @Test
    fun a_theFirstShow_fitsWithTheKeysAtNominal() {
        // Not showReady: it would repair a short window, and here that is what is being asked.
        kb.show(chat)
        ime.waitIdle()
        val problems = Problems()
        problems.windowFits()
        problems.keysAtNominal()
        problems.expect(crashLog().isEmpty()) { "The crash log must be empty, but holds:\n${crashLog()}" }
        kb.hideCompletely()
        kb.show(chat)
        problems.windowFits()
        problems.assertNone("The first show after the upgrade")
    }

    @Test
    fun b_theSettingsFile_isRepaired() {
        showReady(chat)
        val xml = stored()
        record("prefs-after-upgrade", xml)
        val problems = Problems()
        problems.settingsRepaired(xml)
        problems.expect(!holds(xml, "swipe_bias_data")) { "The old glide bias must be gone" }
        problems.expect(xml.contains("<boolean name=\"keys_reset_notice\" value=\"true\" />")) { "Keys moved back, so the notice must be due" }
        problems.assertNone("The settings file:\n$xml\n")
    }

    @Test
    fun c_oldWords_stillComplete() {
        showReady(chat)
        val problems = Problems()
        for (word in UpgradeScenario.LEARNED) {
            val added = completionOf(word)
            record("completion-of-$word", added)
            // Whether the word is lost or only outranked in the main slot.
            if (added != "$word ") record("right-slot-of-$word", completionOf(word, Keyboard.Slot.RIGHT))
            problems.expect(added == "$word ") { "'${word.dropLast(1)}' must complete to '$word', but gave '$added'" }
        }
        problems.assertNone("Words learned on the older build")
    }

    /** Apart from the others, because this band has a known gap of its own that has nothing to do with the upgrade. */
    @Test
    fun c2_anOldWordInTheMiddleBand_stillCompletes() {
        showReady(chat)
        val word = UpgradeScenario.LEARNED_MIDDLING
        val added = completionOf(word)
        record("completion-of-$word", added)
        if (added != "$word ") record("right-slot-of-$word", completionOf(word, Keyboard.Slot.RIGHT))
        assertEquals("'${word.dropLast(1)}' must complete to '$word'", "$word ", added)
    }

    @Test
    fun d_oldPairs_stillPredict() {
        showReady(chat)
        val invented = predictionAfter("see", UpgradeScenario.PAIR_FIRST)
        record("prediction-after-${UpgradeScenario.PAIR_FIRST}", invented)
        val ordinary = predictionAfter("see", UpgradeScenario.SENTENCE_FIRST)
        record("prediction-after-${UpgradeScenario.SENTENCE_FIRST}", ordinary)
        val problems = Problems()
        problems.expect(invented == UpgradeScenario.PAIR_NEXT) { "After '${UpgradeScenario.PAIR_FIRST}' the bar must offer '${UpgradeScenario.PAIR_NEXT}', but gave '$invented'" }
        problems.expect(ordinary == UpgradeScenario.SENTENCE_NEXT) { "After '${UpgradeScenario.SENTENCE_FIRST}' the bar must offer '${UpgradeScenario.SENTENCE_NEXT}', but gave '$ordinary'" }
        problems.assertNone("Pairs learned on the older build")
    }

    /** Whether a piece is gone is read from the store by the script. Here, what a person sees of it. */
    @Test
    fun e_fragmentsGone_andContractionsStillType() {
        showReady(chat)
        val problems = Problems()
        for (contraction in UpgradeScenario.CONTRACTIONS + UpgradeScenario.REAL_WORD_CONTRACTIONS) {
            val before = host.text(chat)
            typeWithApostrophes("so $contraction ")
            Wait.until(CORRECTION_MS, 40) { false }
            val added = host.text(chat).substring(before.length).lowercase()
            record("typed-$contraction", added)
            problems.expect(added == "so $contraction ") { "Typing '$contraction' must give it, but the field gained '$added'" }
        }
        // Typed without the apostrophe, the piece decides: a store that still holds it keeps it, as a known word.
        val before = host.text(chat)
        kb.type("so isn ")
        Wait.until(CORRECTION_MS, 40) { false }
        record("typed-isn-with-a-space", host.text(chat).substring(before.length).lowercase())
        val completion = completionOf("isn'")
        record("completion-of-is", completion)
        problems.assertNone("Contractions")
    }

    private fun find(node: AccessibilityNodeInfo, tappable: Boolean, matches: (String) -> Boolean): AccessibilityNodeInfo? {
        if (node.text?.toString()?.let(matches) == true && (node.isClickable || !tappable)) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { c -> find(c, tappable, matches)?.let { return it } }
        }
        return null
    }

    private fun findOnScreen(tappable: Boolean = false, matches: (String) -> Boolean): AccessibilityNodeInfo? {
        E2eDevice.ua.clearCache()
        val root = E2eDevice.ua.rootInActiveWindow?.takeIf { it.packageName?.toString() == ime.pkg } ?: return null
        return find(root, tappable, matches)
    }

    private fun tapText(what: String, matches: (String) -> Boolean) {
        var found: AccessibilityNodeInfo? = null
        check(Wait.until(SCREEN_MS, 200) { found = findOnScreen(tappable = true, matches = matches); found != null }) { "Nothing on screen reads $what" }
        val r = Rect()
        found!!.getBoundsInScreen(r)
        touch.tap(r.exactCenterX(), r.exactCenterY())
    }

    private fun openSettings() {
        shell.exec("am start -n ${ime.pkg}/$ONBOARDING")
        tapText("'$OPEN_SETTINGS_LABEL'") { it.equals(OPEN_SETTINGS_LABEL, ignoreCase = true) }
    }

    @Test
    fun f_settingsScreen_saysItOnce_andDiagnosticsAreClean() {
        showReady(chat)
        kb.hideWithBack()

        openSettings()
        val noticeShown = Wait.until(SCREEN_MS, 200) { findOnScreen { NOTICE_TEXT in it } != null }
        record("notice-shown-on-first-visit", noticeShown)
        val noticeUsedUp = Wait.until(SCREEN_MS, 200) { !holds(stored(), "keys_reset_notice") }
        if (noticeShown) tapText("'Done'") { it.equals("Done", ignoreCase = true) }

        var report = ""
        check(Wait.until(SCREEN_MS, 200) { report = findOnScreen { DIAGNOSTICS_LAST_LINE in it }?.text?.toString().orEmpty(); report.isNotEmpty() }) {
            "The settings screen shows no diagnostics report"
        }
        record("diagnostics", report.take(MAX_NOTE))

        // A new process builds the screen again and asks again, which is the only way the notice could come back.
        ime.forceStop()
        openSettings()
        check(Wait.until(SCREEN_MS, 200) { findOnScreen { SETTINGS_SUBTITLE in it } != null }) { "The settings screen did not come back" }
        val noticeAgain = Wait.until(NO_NOTICE_MS, 200) { findOnScreen { NOTICE_TEXT in it } != null }
        record("notice-shown-on-second-visit", noticeAgain)
        host.bringToFront()

        val problems = Problems()
        problems.expect(noticeShown) { "The notice must say the keys were put back" }
        problems.expect(noticeUsedUp) { "Showing the notice must use it up" }
        problems.expect(!noticeAgain) { "The notice must not come back" }
        problems.expect(STORE_PROBLEMS.none { it in report }) { "The diagnostics must not report the store, but read:\n$report" }
        problems.assertNone("The settings screen")
    }

    /** A process kill stands for anything that ends the process. Nothing here may have depended on that first process. */
    @Test
    fun g_afterAKill_everythingStillHolds() {
        showReady(chat)
        val before = stored()
        letItReachTheDisk()
        val pid = ime.pid()
        ime.killProcess()
        assertFalse("The process must be a new one", pid == ime.pid())
        kb.show(chat)
        ime.waitIdle()

        val problems = Problems()
        problems.windowFits()
        problems.keysAtNominal()
        val xml = stored()
        record("prefs-after-a-kill", xml)
        problems.settingsRepaired(xml)
        problems.expect(!holds(xml, "keys_reset_notice")) { "The notice must not be due again" }
        problems.expect(xml.length >= before.length - MAX_PREFS_SHRINK) { "The settings shrank over a kill:\n$before\nto\n$xml" }

        for (word in UpgradeScenario.LEARNED) {
            val added = completionOf(word)
            record("completion-after-a-kill-of-$word", added)
            problems.expect(added == "$word ") { "After a kill, '${word.dropLast(1)}' must complete to '$word', but gave '$added'" }
        }
        val next = predictionAfter("see", UpgradeScenario.PAIR_FIRST)
        record("prediction-after-a-kill", next)
        problems.expect(next == UpgradeScenario.PAIR_NEXT) { "After a kill, '${UpgradeScenario.PAIR_FIRST}' must predict '${UpgradeScenario.PAIR_NEXT}', but gave '$next'" }
        problems.expect(crashLog().isEmpty()) { "The crash log must be empty, but holds:\n${crashLog()}" }
        problems.assertNone("After a process kill")
    }

    /** A glide warmed up by one before it, because what a process's first gesture does is SwipeTest's. */
    @Test
    fun h_aGlide_findsAnOldWord() {
        showReady(chat)
        kb.glide(WARM_UP_GLIDE)
        Wait.until(GLIDE_SETTLE_MS, 150) { host.text(chat).isNotBlank() }
        Wait.untilStable(GLIDE_SETTLE_MS, 150) { host.text(chat) }
        kb.type(" ")
        val before = host.text(chat)
        val word = UpgradeScenario.LEARNED.first()
        kb.glide(word)
        Wait.until(GLIDE_SETTLE_MS, 150) { host.text(chat).length > before.length }
        val added = Wait.untilStable(GLIDE_SETTLE_MS, 150) { host.text(chat) }.substring(before.length).trim().lowercase()
        record("glide-of-$word", added)
        assertEquals("A glide through '$word' must find it", word, added)
    }

    @Test
    fun i_newTyping_learns_andSurvivesAKill() {
        showReady(chat)
        record("corrections-of-${UpgradeScenario.NEW_WORD}", useWord(UpgradeScenario.NEW_WORD, NEW_USES))
        repeat(NEW_USES) {
            kb.type("see ")
            typeKeeping(UpgradeScenario.NEW_PAIR_FIRST)
            typeKeeping(UpgradeScenario.NEW_PAIR_NEXT)
        }
        letItReachTheDisk()
        ime.killProcess()
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()

        val added = completionOf(UpgradeScenario.NEW_WORD)
        record("completion-of-${UpgradeScenario.NEW_WORD}", added)
        val next = predictionAfter("see", UpgradeScenario.NEW_PAIR_FIRST)
        record("prediction-after-${UpgradeScenario.NEW_PAIR_FIRST}", next)
        val problems = Problems()
        problems.expect(added == "${UpgradeScenario.NEW_WORD} ") { "'${UpgradeScenario.NEW_WORD}' learned after the upgrade must complete after a kill, but gave '$added'" }
        problems.expect(next == UpgradeScenario.NEW_PAIR_NEXT) { "After '${UpgradeScenario.NEW_PAIR_FIRST}' the bar must offer '${UpgradeScenario.NEW_PAIR_NEXT}' after a kill, but gave '$next'" }
        problems.assertNone("New typing")
    }

    /**
     * The one-time step cleared the words the older build struck. Run again it would take this word again, once it
     * has been learned since. Whether its rows stay is read from the store by the script, and here is what a person sees.
     */
    @Test
    fun j_aStruckWordLearnedAgain_staysAfterAKill() {
        showReady(chat)
        val word = UpgradeScenario.STRUCK_WORD
        record("corrections-of-$word-after-the-upgrade", useWord(word, NEW_USES))
        letItReachTheDisk()
        ime.killProcess()
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()
        kb.type("so ")
        val before = host.text(chat)
        kb.type("$word ")
        SystemClock.sleep(CORRECTION_MS)
        val added = host.text(chat).substring(before.length).lowercase()
        record("typed-$word-after-a-kill", added)
        assertEquals("A word taken back on this build must stay as typed after a kill", "$word ", added)
    }

    /** Only the stamp keeps the bias reset from running at every start, so bias of the old form shows if it did. */
    @Test
    fun k_theOneTimeReset_doesNotRunAgain() {
        showReady(chat)
        kb.hideWithBack()
        ime.forceStop()
        ime.seedStringPrefs(mapOf("swipe_bias_data" to UNSTAMPED_BIAS))
        ime.select()
        showReady(chat)
        val xml = stored()
        record("prefs-after-a-seeded-restart", xml)
        val problems = Problems()
        problems.expect(holds(xml, "swipe_bias_data")) { "A second start must not reset the glide bias:\n$xml" }
        problems.expect(!holds(xml, "keys_reset_notice")) { "A second start must not raise the notice again:\n$xml" }
        problems.assertNone("The one-time steps")
        assertCrashLogEmpty()
    }

    private companion object {
        val RETIRED = listOf("key_layout_fit", "tap_model_data", "key_offset_x", "key_offset_y")
        const val CRASH_LOG = "files/last_crash.txt"
        const val ONBOARDING = "com.localboard.keyboard.onboarding.OnboardingActivity"
        const val OPEN_SETTINGS_LABEL = "Open Settings"
        const val DIAGNOSTICS_LAST_LINE = "crash log:"

        /** Every way the store's trouble reads in the diagnostics: not opened, not read at the start, failing since, or set aside. */
        val STORE_PROBLEMS = listOf(
            "Saved words could not be opened",
            "Saved words could not be read",
            "Reading or saving words failed",
            "moved them aside",
        )
        const val NOTICE_TEXT = "back in their standard positions"
        const val SETTINGS_SUBTITLE = "Make IOKey yours"
        const val SCREEN_MS = 20_000L
        const val NO_NOTICE_MS = 3_000L
        const val GLIDE_SETTLE_MS = 3_000L
        const val WARM_UP_GLIDE = "hello"
        const val NEW_USES = 3
        const val UNSTAMPED_BIAS = "you\t-1"
        const val MAX_NOTE = 1_800
        const val MAX_PREFS_SHRINK = 100
    }
}

/**
 * A person types right after an update, whether or not the first start has finished, and nothing else in these classes
 * types while a slow migration runs. So this asks what they would meet: the letters must land at once, and the words must
 * still be learned. The second half is read from the store by the script, once the first start has gone quiet, because a
 * word typed before the first load is held until that load ends.
 *
 * It records the main file as the last word lands, so the script can say whether a first start was still running: until
 * that start's commit is folded in, the file stays as the update left it. The log says less, because it can stay still for
 * half a minute while a migration scans the rows, so whether it moved is only a note.
 *
 * Each key's time is recorded too, because a tap waits for the keyboard to finish with it, so a key held up by the first
 * start shows there and not in whether its letter landed. The script sets those times against UpgradeTypingAtRestTest's.
 */
class UpgradeFirstStartTypingTest : UpgradeTestBase() {

    private fun stat(file: String) = runAs("stat -c %s:%y databases/$file 2>&1").trim()

    @Test
    fun theLettersLandWhileTheFirstStartRuns() {
        // Not showReady, and no wait for idle: its whole point is not to wait for the keyboard's start-up to end.
        kb.show(chat)
        // Where nothing has shown the keyboard yet, this is the first show after the update, under the migration's load, and
        // no other test asks that. What it finds waits for the end, so that a poor fit cannot hide what the typing found.
        val problems = Problems()
        runCatching {
            problems.windowFits()
            problems.keysAtNominal()
        }.onFailure { problems.expect(false) { "This show could not be read: ${it.message}" } }
        val logBefore = stat("localboard.db-wal")
        for (word in UpgradeScenario.TYPED_DURING_FIRST_START) {
            val keyMs = typeTimingEachKey("$word ")
            val tapped = SystemClock.uptimeMillis()
            val landed = Wait.until(WORD_MS, 20) { host.text(chat).lowercase().endsWith("$word ") }
            record("$word-tapping-ms", keyMs.sum())
            record("$word-key-ms", keyMs.joinToString(","))
            record("$word-in-the-field-ms-after", SystemClock.uptimeMillis() - tapped)
            problems.expect(landed) { "'$word' is not at the end of the field ${WORD_MS} ms after its last tap, which holds '${host.text(chat).takeLast(40)}'" }
        }
        record("main-file-after-typing", stat("localboard.db"))
        record("store-moving-during-typing", stat("localboard.db-wal") != logBefore)
        letItReachTheDisk()
        problems.assertNone("Typing during the first start")
    }

    private companion object {
        const val WORD_MS = 1_000L
    }
}

/**
 * The same keys on a quiet store and under the same limit on the emulator's CPUs. A time means little alone, because the
 * harness and the emulator take most of it, and this is what the time during a first start is set against.
 */
class UpgradeTypingAtRestTest : UpgradeTestBase() {

    @Test
    fun theSameKeysWithNothingElseGoingOn() {
        kb.show(chat)
        ime.waitIdle()
        for (word in UpgradeScenario.TYPED_DURING_FIRST_START) {
            val keyMs = typeTimingEachKey("$word ")
            record("$word-tapping-ms", keyMs.sum())
            record("$word-key-ms", keyMs.joinToString(","))
        }
        letItReachTheDisk()
    }
}
