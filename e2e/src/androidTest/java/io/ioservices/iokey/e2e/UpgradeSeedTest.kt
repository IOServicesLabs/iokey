package io.ioservices.iokey.e2e

import android.graphics.Rect
import android.os.SystemClock
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.Random

/**
 * The upgrade from an older build, on real data: tools/e2e/upgrade.ps1 installs the older build, this class makes
 * that build write its real encrypted store and leaves its settings in place, the script installs the new build over
 * it, and UpgradeVerifyTest asks what survived. None of these classes wipes anything, and none is in the suite's
 * list, because each is one step of that script and means nothing alone.
 *
 * Everything typed here is invented.
 */
internal object UpgradeScenario {
    /**
     * In no dictionary, so only the store can make them complete, and each typed as often as its name says. The counts
     * straddle the engine's bands: a word below 4 uses is offered from its own slice, one at 8 or more is promoted into
     * the dictionary, and one in between is neither, which LearningTest.learnedWord_stillCompletesAfterMoreUses is about
     * on a fresh install too. The upgrade rebuilds a word's count from its context rows, so it arrives one or so under
     * what was typed, and the word in the middle is here to show that band apart from the others.
     */
    val LEARNED_OFTEN = listOf("xylqor", "zorblax")
    const val OFTEN_USES = 10
    const val LEARNED_RARELY = "qwibbit"
    const val RARE_USES = 3
    const val LEARNED_MIDDLING = "drumwick"
    const val MIDDLING_USES = 6

    /** The ones that must complete on any build that keeps words at all. */
    val LEARNED = LEARNED_OFTEN + LEARNED_RARELY

    /** Both invented, so a prediction of the second after the first can only come from the store. */
    const val PAIR_LEAD = "the"
    const val PAIR_FIRST = "flumbo"
    const val PAIR_NEXT = "grentix"
    const val PAIR_USES = 6

    const val SENTENCE = "meet me at the velvet harbor tomorrow "
    const val SENTENCE_FIRST = "velvet"
    const val SENTENCE_NEXT = "harbor"
    const val SENTENCE_USES = 5

    /** The corrector takes this for a typo, so the older build strikes it. */
    const val STRUCK_WORD = "mirble"
    const val STRUCK_USES = 3

    /** Typed with the apostrophe, which is what cut the pieces isn, ve, ll and re out of them in the older build. */
    val CONTRACTIONS = listOf("isn't", "we've", "they'll", "you're", "mustn't")
    const val CONTRACTION_USES = 3

    /**
     * Cut the same way, but what they leave behind is a real word, which the upgrade treats apart from the pieces: the
     * rows of don and haven go, and won is held to 3. So they are typed often enough for that limit to have work to do.
     */
    val REAL_WORD_CONTRACTIONS = listOf("don't", "haven't", "won't")
    const val REAL_WORD_CONTRACTION_USES = 6

    /** Learned only after the upgrade, and with no dictionary word close enough for a correction to take the main slot. */
    const val NEW_WORD = "zyxquor"
    const val NEW_PAIR_FIRST = "blorgum"
    const val NEW_PAIR_NEXT = "tizzle"

    /** In no dictionary, typed while the first start may still be running, so only that typing can put them in the store. */
    val TYPED_DURING_FIRST_START = listOf("plimvet", "grondux")

    /** A finger that lands a little low and to the right, in dp, as the typing test stores it. */
    const val KEY_FIT =
        "q:-1.5,3.0;w:-1.0,3.5;e:-0.5,3.0;r:0.0,3.5;t:0.5,4.0;y:1.0,3.5;u:1.5,3.0;i:2.0,3.5;o:2.5,3.0;p:3.0,3.5;" +
            "a:-1.5,4.0;s:-1.0,4.5;d:-0.5,4.0;f:0.0,4.5;g:0.5,4.0;h:1.0,4.5;j:1.5,4.0;k:2.0,4.5;l:2.5,4.0;" +
            "z:-1.0,5.0;x:-0.5,5.5;c:0.0,5.0;v:0.5,5.5;b:1.0,5.0;n:1.5,5.5;m:2.0,5.0"
    const val TAP_MODEL = "a:1.0,2.0;s:0.5,3.1"
    const val OLD_BIAS = "the\t-5"
    const val KEY_OFFSET_X = 5.0f
    const val KEY_OFFSET_Y = 3.0f
    const val NAV_STRIP_OFF = 3

    /** Longer than the older build's delay on writing what it learned, and than its checkpoint on hide. */
    const val PERSIST_MS = 3_500L

    /**
     * One text expansion per shot. Each is a dictionary word no upgrade test types, so the corrector leaves it alone, and
     * these lines are gone from the settings before anything else types.
     */
    val GROW_TRIGGERS = listOf("lantern", "walnut", "teapot", "pebble", "thimble", "saddle", "kettle", "pumpkin", "blanket")

    /** Small to large, because the first run of them is also the trial of how much one expansion can carry. */
    private val GROW_CHARS = listOf(20_000, 40_000, 60_000, 90_000, 90_000, 90_000, 90_000, 90_000, 90_000)

    /** Below the older build's load limit on single words, so that what the seed typed still gets loaded. */
    private const val GROW_VOCABULARY = 9_000
    private const val GROW_SEED = 58_213L

    /** How often a slot holds what the older build cuts out of a contraction, so that the upgrade has rows of those to delete. */
    private const val GROW_CUT_EVERY = 100
    private val GROW_CUTS = listOf("isn t", "we ve", "don t")

    /** Letters only, which is all the older build learns, and far from every real word and every word the seed types. */
    private fun growWord(index: Int) = "qj" + ('a' + index / 676) + ('a' + index / 26 % 26) + ('a' + index % 26)

    /** The same text on every run, with most word triples new, which is what makes nearly every row a count of 1. */
    fun growText(shot: Int): String {
        val random = Random(GROW_SEED + shot)
        val limit = GROW_CHARS[shot]
        val text = StringBuilder(limit + 16)
        var slot = 0
        while (text.length < limit) {
            if (slot > 0) text.append(' ')
            slot++
            text.append(if (slot % GROW_CUT_EVERY == 0) GROW_CUTS[random.nextInt(GROW_CUTS.size)] else growWord(random.nextInt(GROW_VOCABULARY)))
        }
        return text.toString()
    }

    /** As the older build stores its expansions: one trigger, a tab and the text per line. */
    fun growSnippets() = GROW_TRIGGERS.indices.joinToString("\n") { "${GROW_TRIGGERS[it]}\t${growText(it)}" }

    private const val NOMINAL_FILE = "upgrade-nominal-keys.txt"

    private fun nominalFile() = File(E2eDevice.instrumentation.targetContext.getExternalFilesDir(null), NOMINAL_FILE)

    /** The shift key announces its state in its label, and the state is not what is being compared. */
    private fun keyName(label: String) = label.substringBefore(',')

    private fun Rect.line() = "$left,$top,$right,$bottom"

    private fun rectOf(line: String): Rect = line.split(',').map { it.toInt() }.let { Rect(it[0], it[1], it[2], it[3]) }

    fun saveNominal(layout: Keyboard.Layout, frame: Rect) {
        nominalFile().writeText(
            buildString {
                appendLine("window\t${frame.line()}")
                for (face in layout.faces) appendLine("key\t${keyName(face.label)}\t${face.rect.line()}")
            },
        )
    }

    class Nominal(val frame: Rect, val keys: Map<String, Rect>)

    fun loadNominal(): Nominal {
        val lines = nominalFile().readLines().map { it.split('\t') }
        val frame = lines.first { it[0] == "window" }.let { rectOf(it[1]) }
        return Nominal(frame, lines.filter { it[0] == "key" }.associate { it[1] to rectOf(it[2]) })
    }

    /** Each key that is not where the nominal layout puts it, as text, or nothing when all of them are. */
    fun movedKeys(nominal: Nominal, now: Keyboard.Layout): List<String> {
        val got = now.faces.associate { keyName(it.label) to it.rect }
        val moved = ArrayList<String>()
        for ((name, rect) in nominal.keys) {
            val at = got[name]
            if (at == null) moved += "$name is missing" else if (at != rect) moved += "$name ${rect.toShortString()} to ${at.toShortString()}"
        }
        for (name in got.keys - nominal.keys.keys) moved += "$name is new"
        return moved
    }
}

/** What both phases do at the keyboard: type as a person who means their words would, and read what came of it. */
abstract class UpgradeTestBase : E2eTestBase() {

    protected val chat = HostActivity.CHAT
    protected val shell get() = E2eDevice.shell

    protected fun runAs(script: String) = shell.sh("run-as ${ime.pkg} sh -c '$script'")

    /** Types [word] and a space. Takes a correction back, as a person who meant the word does. True if it had to. */
    protected fun typeKeeping(word: String): Boolean {
        fun kept() = host.text(chat).lowercase().let { it == "$word " || it.endsWith(" $word ") }
        kb.type("$word ")
        Wait.until(CORRECTION_MS, 40) { !kept() }
        if (kept()) return false
        kb.backspace()
        check(Wait.until(CORRECTION_MS, 40) { host.text(chat).lowercase().endsWith(word) }) {
            "Backspace did not take the correction back: '${host.text(chat)}'"
        }
        kb.type(" ")
        check(kept()) { "'$word' is not in the field: '${host.text(chat)}'" }
        return true
    }

    /** "so word", [times] over. The first word is there so that the word has a word before it, as most words do. */
    protected fun useWord(word: String, times: Int): Int {
        var corrected = 0
        repeat(times) {
            kb.type("so ")
            if (typeKeeping(word)) corrected++
        }
        return corrected
    }

    /** Taps the keys of [text], going by way of the symbols page for each apostrophe. */
    protected fun typeWithApostrophes(text: String) {
        for (c in text) {
            if (c == '\'') kb.typeSymbol("'") else kb.type(c.toString())
        }
    }

    /** What [slot] of the bar, by default the main one, adds to the field after all but the last letter of [word]. */
    protected fun completionOf(word: String, slot: Keyboard.Slot = Keyboard.Slot.CENTRE): String {
        val prefix = word.dropLast(1)
        kb.type("so ")
        val before = host.text(chat)
        kb.type(prefix)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(slot)
        Wait.until(CHIP_MS, 40) { host.text(chat).length > before.length + prefix.length }
        val added = host.text(chat).substring(before.length)
        if (!added.endsWith(" ")) kb.type(" ")
        return added.lowercase()
    }

    /** What the bar's main slot adds to the field right after [lead] and [first]. Nothing typed yet of the next word. */
    protected fun predictionAfter(lead: String, first: String): String {
        kb.type("$lead ")
        typeKeeping(first)
        SystemClock.sleep(BAR_SETTLE_MS)
        val before = host.text(chat)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        Wait.until(CHIP_MS, 40) { host.text(chat).length > before.length }
        val added = host.text(chat).substring(before.length)
        if (added.isNotEmpty() && !added.endsWith(" ")) kb.type(" ")
        return added.trim().lowercase()
    }

    /** Hiding is what makes the keyboard write and checkpoint, and either build's timed write is shorter than this. */
    protected fun letItReachTheDisk() {
        kb.hideWithBack()
        SystemClock.sleep(UpgradeScenario.PERSIST_MS)
    }

    /** Taps the keys of [text] and says how long each took, in order. A tap waits for the keyboard to finish with it. */
    protected fun typeTimingEachKey(text: String): List<Long> = text.map { key ->
        val started = SystemClock.uptimeMillis()
        kb.type(key.toString())
        SystemClock.uptimeMillis() - started
    }

    /** All that was wrong at once, because a first start that happens only once deserves more than the first complaint. */
    protected class Problems {
        val list = ArrayList<String>()
        fun expect(ok: Boolean, what: () -> String) {
            if (!ok) list += what()
        }
        fun assertNone(what: String) = assertTrue("$what:\n${list.joinToString("\n")}", list.isEmpty())
    }

    protected fun Problems.windowFits() {
        kb.assertIokeyShowing()
        val state = kb.windowState()
        val layout = kb.readLayout() ?: throw AssertionError("The keys cannot be read")
        val (_, screen) = host.windowSizePx()
        val nav = host.navigationBarBottomPx()
        val clear = maxOf(nav, kb.systemButtonsHeight(screen))
        val facts = "window ${state.frame.toShortString()}, requested ${state.requestedHeight}, key grid ${layout.view.toShortString()}, " +
            "lowest key bottom ${layout.lowestKeyBottom}, area to keep clear $clear, navigation area $nav, screen $screen"
        expect(state.frame.bottom == screen) { "The window must reach the bottom of the screen. $facts" }
        expect(layout.lowestKeyBottom <= screen - clear) { "The keys must end above the gesture area. $facts" }
        expect(state.frame.height() == state.requestedHeight) { "The requested height must match the frame. $facts" }
        expect(state.touchable.bottom >= layout.lowestKeyBottom) { "The touchable area must cover every key. $facts" }
        expect(!kb.isShort()) { "The window is short. $facts" }
        record("window-${System.currentTimeMillis() % 100000}", facts)
    }

    protected fun Problems.keysAtNominal() {
        val nominal = UpgradeScenario.loadNominal()
        val layout = kb.readLayout() ?: throw AssertionError("The keys cannot be read")
        val moved = UpgradeScenario.movedKeys(nominal, layout)
        expect(moved.isEmpty()) { "${moved.size} keys are not where the nominal layout puts them: ${moved.take(MAX_LISTED)}" }
        val frame = kb.windowState().frame
        expect(frame == nominal.frame) { "The window is ${frame.toShortString()}, and with nothing stored it is ${nominal.frame.toShortString()}" }
    }

    protected companion object {
        const val CORRECTION_MS = 500L
        const val BAR_SETTLE_MS = 800L
        const val CHIP_MS = 1_500L
        const val MAX_LISTED = 14

        /** What a person who has finished setting up holds, so that a field brings up the keyboard and not a first-run screen. */
        val FIRST_RUN_OVER = mapOf("onboarding_done" to true, "tutorial_seen" to true, "show_advanced" to true, "settings_simple" to false)
    }
}

/** The keys as this build draws them with nothing stored, which is what "nominal" means in the later steps. */
class UpgradeNominalKeysTest : UpgradeTestBase() {

    @Test
    fun recordTheNominalKeys() {
        showReady(chat)
        val layout = kb.readLayout() ?: throw AssertionError("The keys cannot be read")
        val frame = kb.windowState().frame
        UpgradeScenario.saveNominal(layout, frame)
        record("window", frame.toShortString())
        record("keys", layout.faces.size)
    }
}

/**
 * Phase 1, on the older build from a cleared install. Each test leaves what it typed on disk, and runs in name order
 * because the settings are seeded last and the first test ends the first-run screens.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UpgradeSeedTest : UpgradeTestBase() {

    /** A word typed before the keyboard is ready is dropped by the older build, so this waits, as a person's first word does. */
    private fun firstRunIsOver() {
        ime.forceStop()
        ime.seedBooleanPrefs(FIRST_RUN_OVER)
        ime.select()
        showReady(chat)
    }

    @Test
    fun seed1_wordsLearnedByUse() {
        firstRunIsOver()
        for (word in UpgradeScenario.LEARNED_OFTEN) {
            record("corrections-of-$word", useWord(word, UpgradeScenario.OFTEN_USES))
        }
        record("corrections-of-${UpgradeScenario.LEARNED_RARELY}", useWord(UpgradeScenario.LEARNED_RARELY, UpgradeScenario.RARE_USES))
        record("corrections-of-${UpgradeScenario.LEARNED_MIDDLING}", useWord(UpgradeScenario.LEARNED_MIDDLING, UpgradeScenario.MIDDLING_USES))
        letItReachTheDisk()
    }

    @Test
    fun seed2_pairsAndOrdinarySentences() {
        showReady(chat)
        var corrected = 0
        repeat(UpgradeScenario.PAIR_USES) {
            kb.type("${UpgradeScenario.PAIR_LEAD} ")
            if (typeKeeping(UpgradeScenario.PAIR_FIRST)) corrected++
            if (typeKeeping(UpgradeScenario.PAIR_NEXT)) corrected++
        }
        record("corrections-of-the-pair", corrected)
        repeat(UpgradeScenario.SENTENCE_USES) { kb.type(UpgradeScenario.SENTENCE) }
        record("sentence-field", host.text(chat).takeLast(UpgradeScenario.SENTENCE.length))
        letItReachTheDisk()
    }

    @Test
    fun seed3_contractions() {
        showReady(chat)
        repeat(UpgradeScenario.CONTRACTION_USES) {
            for (contraction in UpgradeScenario.CONTRACTIONS) typeWithApostrophes("so $contraction ")
        }
        val typed = host.text(chat).lowercase()
        record("contractions-field", typed.takeLast(40))
        for (contraction in UpgradeScenario.CONTRACTIONS) {
            assertTrue("'$contraction' must be in the field, which holds '$typed'", typed.contains(contraction))
        }
        letItReachTheDisk()
    }

    /** Its own test, because typing by way of the symbols page is slow and each test has a time limit. */
    @Test
    fun seed3b_contractionsThatLeaveARealWord() {
        showReady(chat)
        repeat(UpgradeScenario.REAL_WORD_CONTRACTION_USES) {
            for (contraction in UpgradeScenario.REAL_WORD_CONTRACTIONS) typeWithApostrophes("so $contraction ")
        }
        val typed = host.text(chat).lowercase()
        record("real-word-contractions-field", typed.takeLast(40))
        for (contraction in UpgradeScenario.REAL_WORD_CONTRACTIONS) {
            assertTrue("'$contraction' must be in the field, which holds '$typed'", typed.contains(contraction))
        }
        letItReachTheDisk()
    }

    @Test
    fun seed4_aWordTheOlderBuildStrikes() {
        showReady(chat)
        record("corrections-of-${UpgradeScenario.STRUCK_WORD}", useWord(UpgradeScenario.STRUCK_WORD, UpgradeScenario.STRUCK_USES))
        letItReachTheDisk()
    }

    /** The settings go in with the process stopped, then the older build runs over them once, so what it does to them counts. */
    @Test
    fun seed5_settings() {
        ime.forceStop()
        ime.seedStringPrefs(
            mapOf(
                "key_layout_fit" to UpgradeScenario.KEY_FIT,
                "tap_model_data" to UpgradeScenario.TAP_MODEL,
                "swipe_bias_data" to UpgradeScenario.OLD_BIAS,
            ),
        )
        ime.seedFloatPrefs(mapOf("key_offset_x" to UpgradeScenario.KEY_OFFSET_X, "key_offset_y" to UpgradeScenario.KEY_OFFSET_Y))
        // The reset flag is what a build that has run once holds, and the value is this person turning the feature back on.
        ime.seedBooleanPrefs(mapOf("llm_suggestions" to true, "llm_suggestions_reset_v2" to true))
        val xml = ime.readPrefsXml() ?: throw AssertionError("The keyboard has no preferences file")
        ime.writePrefsXml(
            xml.replace(Regex("""\s*<int name="nav_strip_mode" value="[^"]*" />"""), "")
                .replace("</map>", "    <int name=\"nav_strip_mode\" value=\"${UpgradeScenario.NAV_STRIP_OFF}\" />\n</map>"),
        )
        ime.select()

        // Not showReady: the strip is off here, so a short window is the state being set up and not a thing to repair.
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()
        val layout = kb.readLayout() ?: throw AssertionError("The keys cannot be read")
        val moved = UpgradeScenario.movedKeys(UpgradeScenario.loadNominal(), layout)
        record("older-build-keys-away-from-nominal", moved.size)
        record("older-build-keys-away-sample", moved.take(4))
        record("older-build-window", kb.windowState().frame.toShortString())
        record("older-build-lowest-key-bottom", layout.lowestKeyBottom)
        record("older-build-window-short", kb.isShort())
        assertTrue("The stored fit must move keys on the older build, or the seed proves nothing", moved.isNotEmpty())

        kb.type("see you ")
        letItReachTheDisk()
    }
}

/**
 * Grows the store on the older build, one text expansion at a time, as a step of the script before or around the seed.
 * The older build must write it or it is not the real encrypted format, and its expansion writes the row a typed word
 * writes: a word with the two before it. So nearly every row is a three-word row, as a store built by typing is, and the
 * upgrade has its doubling to do. Its corpus import would be quicker, and would write all three orders at once, which
 * leaves the upgrade nothing to derive.
 *
 * A failed shot does not stop the rest. The script's floor on the rows it reads back is what says how far this got, because
 * a store write that fails in the older build drops every later write without a word.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UpgradeGrowTest : UpgradeTestBase() {

    /** Not showReady: once the seed's settings are in the strip is off, and its repair of a short window would fail here. */
    private fun showForAShot() {
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()
    }

    @Test
    fun a_prepare() {
        ime.forceStop()
        ime.seedBooleanPrefs(FIRST_RUN_OVER)
        ime.seedStringPrefs(mapOf(SNIPPETS to UpgradeScenario.growSnippets()))
        ime.select()
        showForAShot()
    }

    /**
     * Hiding is what gets it all written. A timed write takes what is queued after 1.5 s, and in the older build words
     * queued after that wait for the next word learned, which here would never come.
     */
    private fun shot(number: Int) {
        val index = number - 1
        showForAShot()
        val expected = UpgradeScenario.growText(index)
        val started = SystemClock.uptimeMillis()
        kb.type("${UpgradeScenario.GROW_TRIGGERS[index]} ")
        val arrived = Wait.until(EXPANSION_MS, 200) { host.text(chat).trim() == expected }
        val expandedAt = SystemClock.uptimeMillis()
        val held = host.text(chat).length
        record("shot$number-chars", expected.length)
        record("shot$number-field-chars", held)
        record("shot$number-expansion-ms", expandedAt - started)
        // The lengths and never the text, or one failure would flood the results.
        assertTrue("Shot $number: the field holds $held characters, and the expansion has ${expected.length}", arrived)
        // It learns the whole text on its main thread, and a Back it cannot answer for seconds would read as a failure to hide.
        ime.waitIdle(IDLE_MS)
        val learnedAt = SystemClock.uptimeMillis()
        record("shot$number-learning-ms", learnedAt - expandedAt)
        kb.hideWithBack()
        ime.waitIdle(IDLE_MS)
        record("shot$number-hide-to-idle-ms", SystemClock.uptimeMillis() - learnedAt)
        val settled = waitForTheStoreFilesToHold()
        record("shot$number-store-files-held-after-ms", settled)
        record("shot$number-store-files", storeFiles())
    }

    private fun storeFiles() = runAs("stat -c %s:%y $STORE_FILE $STORE_FILE-wal 2>&1").trim()

    /** Milliseconds until the store's files stop changing. Fails past the limit, because the next step would cut that write short. */
    private fun waitForTheStoreFilesToHold(): Long {
        val started = SystemClock.uptimeMillis()
        var last = storeFiles()
        var since = started
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - since >= FILES_HOLD_MS) return since - started
            check(now - started < FILES_LIMIT_MS) { "The store's files were still changing after ${FILES_LIMIT_MS / 1000} s" }
            SystemClock.sleep(FILES_POLL_MS)
            val files = storeFiles()
            if (files != last) {
                last = files
                since = SystemClock.uptimeMillis()
            }
        }
    }

    @Test fun shot01() = shot(1)

    @Test fun shot02() = shot(2)

    @Test fun shot03() = shot(3)

    @Test fun shot04() = shot(4)

    @Test fun shot05() = shot(5)

    @Test fun shot06() = shot(6)

    @Test fun shot07() = shot(7)

    @Test fun shot08() = shot(8)

    @Test fun shot09() = shot(9)

    /** The triggers must not stay: a later test typing one would get the expansion, and the settings go in the script's report. */
    @Test
    fun z_blank() {
        showForAShot()
        letItReachTheDisk()
        ime.forceStop()
        ime.seedStringPrefs(mapOf(SNIPPETS to ""))
    }

    private companion object {
        const val SNIPPETS = "snippet_data"
        const val STORE_FILE = "databases/localboard.db"
        const val EXPANSION_MS = 60_000L
        const val IDLE_MS = 100_000L
        const val FILES_HOLD_MS = 5_000L
        const val FILES_LIMIT_MS = 70_000L
        const val FILES_POLL_MS = 500L
    }
}

/**
 * Not a step of the upgrade: the same words typed on cleared data with no upgrade in the way, on whichever build is
 * installed, so that a word that does not complete after the upgrade can be told from one that would not have anyway.
 * It records and asserts nothing, because the comparison is the result.
 *
 * Tapping a slot teaches what the slot holds, so each way of asking gets its own cleared data. What a test taps must
 * not be what the next one reads.
 */
class UpgradeControlTest : UpgradeTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val uses = listOf(
        UpgradeScenario.LEARNED_RARELY to UpgradeScenario.RARE_USES,
        UpgradeScenario.LEARNED_MIDDLING to UpgradeScenario.MIDDLING_USES,
    )

    private fun typeTheWords() {
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf("onboarding_done" to true, "tutorial_seen" to true))
        ime.select()
        showReady(chat)
        for ((word, times) in uses) record("corrections-of-$word", useWord(word, times))
    }

    @Test
    fun theSameWords_inTheSameSession() {
        typeTheWords()
        for ((word, _) in uses) {
            record("completion-in-the-same-session-of-$word", completionOf(word))
            record("right-slot-in-the-same-session-of-$word", completionOf(word, Keyboard.Slot.RIGHT))
        }
    }

    /** The one to set beside the upgrade: the words come from the store, into a process that has not typed them. */
    @Test
    fun theSameWords_afterAKill() {
        typeTheWords()
        letItReachTheDisk()
        ime.killProcess()
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()
        for ((word, _) in uses) {
            record("completion-after-a-kill-of-$word", completionOf(word))
            record("right-slot-after-a-kill-of-$word", completionOf(word, Keyboard.Slot.RIGHT))
        }
    }
}
