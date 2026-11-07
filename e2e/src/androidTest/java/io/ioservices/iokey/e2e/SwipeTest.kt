package io.ioservices.iokey.e2e

import android.os.SystemClock
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Touch
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A glide must land the word that was traced, in its place in the sentence, and use the words around it to
 * settle what the finger alone cannot. The fingers here are clean: a stroke through the centres of the keys,
 * with no noise, is the easiest input the keyboard will ever get.
 */
class SwipeTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT

    /** Glides [word], waits for the field to stop changing and returns what the glide added. */
    private fun glideAndRead(word: String): String {
        val before = host.text(chat)
        kb.glide(word)
        return settledAddition(before)
    }

    /** The field after a glide: its word can come well after the lift, so wait for that first, then for all to go quiet. */
    private fun settledText(before: String): String {
        Wait.until(GLIDE_COMMIT_CEILING_MS) { host.text(chat) != before }
        return Wait.untilStable(GLIDE_SETTLE_MS, 150) { host.text(chat) }
    }

    private fun settledAddition(before: String): String {
        val now = settledText(before)
        return if (now.startsWith(before)) now.substring(before.length) else "<replaced>'$now'"
    }

    /**
     * The first gesture of a new process is a test of its own. Everything else starts after one, in another
     * field so that it leaves nothing behind in the field under test.
     */
    private fun warmUpGlide() {
        kb.show(HostActivity.SINGLE)
        ime.waitIdle()
        kb.glide("hello")
        SystemClock.sleep(GLIDE_PAUSE_MS)
    }

    private fun centre(label: String): Touch.Point {
        val r = kb.layout().require(label).rect
        return Touch.Point(r.exactCenterX(), r.exactCenterY())
    }

    private fun between(a: String, b: String): Touch.Point {
        val first = centre(a)
        val second = centre(b)
        return Touch.Point((first.x + second.x) / 2f, (first.y + second.y) / 2f)
    }

    /** A stroke that starts between i and u, then goes to s: "is" or "us" is for the sentence to decide. */
    private fun glideIsOrUs(): String {
        val before = host.text(chat)
        touch.glide(listOf(between("i", "u"), centre("s")), AMBIGUOUS_GLIDE_MS, dwellEndMs = Keyboard.GLIDE_END_REST_MS)
        return settledAddition(before)
    }

    /** A stroke that starts between f and g, through o, to d: "food" or "good". */
    private fun glideFoodOrGood(): String {
        val before = host.text(chat)
        touch.glide(listOf(between("f", "g"), centre("o"), centre("d")), AMBIGUOUS_GLIDE_MS, dwellEndMs = Keyboard.GLIDE_END_REST_MS)
        return settledAddition(before)
    }

    // ---------------------------------------------------------------------------------------------
    // The word itself
    // ---------------------------------------------------------------------------------------------

    /**
     * In the order of ordinary text, so each word is scored as it would be in use. The count goes in the results,
     * because a drop from 39 to 30 is red to red, or green to green, and only the number shows it. The bar allows for
     * two misses: "sorry" has come out as "sort" in some runs, and any one word can jitter from run to run. Raise it to
     * 39 once "sorry" lands every time.
     */
    @Test
    fun commonWords_traceAndLand() {
        warmUpGlide()
        kb.show(chat)
        val landed = COMMON_WORDS.map { word ->
            val got = glideAndRead(word)
            SystemClock.sleep(GLIDE_PAUSE_MS)
            word to got
        }
        val misses = landed.filter { (word, got) -> got.lowercase() != "$word " }.map { (word, got) -> "$word gave '$got'" }
        val right = landed.size - misses.size
        record("right", right)
        record("of", landed.size)
        record("misses", misses.joinToString(", ").ifEmpty { "none" })
        assertTrue(
            "At least $COMMON_WORDS_NEEDED of ${landed.size} common words must land, and $right did. Misses: $misses",
            right >= COMMON_WORDS_NEEDED,
        )
    }

    /** Puts each attempt's result in the results, passing or not, and requires every one to be [word]. */
    private fun assertEveryAttemptLands(word: String, attempts: Int, why: String, attempt: () -> String) {
        val landed = List(attempts) {
            val got = attempt()
            SystemClock.sleep(GLIDE_PAUSE_MS)
            got.lowercase()
        }
        record("right", landed.count { it == "$word " })
        record("attempts", landed.joinToString(" | ") { it.trim() })
        assertEquals(why, List(attempts) { "$word " }, landed)
    }

    /** The finger reaches the last letter, and the word that was traced is the one that lands. */
    private fun assertWordLandsEveryTime(word: String) {
        warmUpGlide()
        kb.show(chat)
        assertEveryAttemptLands(word, ATTEMPTS, "the last letters of a glide must count") { glideAndRead(word) }
    }

    @Test
    fun lastLetterCounts_there() = assertWordLandsEveryTime("there")

    @Test
    fun lastLetterCounts_these() = assertWordLandsEveryTime("these")

    @Test
    fun lastLetterCounts_later() = assertWordLandsEveryTime("later")

    /**
     * The tests above rest on the last key for longer than the keyboard may trust a reading it took while the finger
     * moved, so they never meet what a lift does to that reading. Here the finger leaves as soon as it arrives.
     */
    private fun assertWordLandsAfterAQuickLift(word: String) {
        warmUpGlide()
        kb.show(chat)
        assertEveryAttemptLands(word, ATTEMPTS, "the last letters of a glide must count without a rest on the last key") {
            val before = host.text(chat)
            kb.glide(word, endRestMs = QUICK_LIFT_MS)
            settledAddition(before)
        }
    }

    @Test
    fun lastLetterCounts_thereQuickLift() = assertWordLandsAfterAQuickLift("there")

    @Test
    fun lastLetterCounts_theseQuickLift() = assertWordLandsAfterAQuickLift("these")

    /**
     * A sure hand: it rests on the next-to-last key, hops onto the last one and stays there for [endRestMs]. The hop
     * ends well inside the last key's face, just short of its centre, where a hop ends.
     */
    private fun glideWithLastHop(word: String, endRestMs: Long): String {
        val before = host.text(chat)
        val keys = word.map { centre(it.toString()) }
        val resting = keys[keys.lastIndex - 1]
        val lastKey = keys.last()
        fun hopped(fraction: Float) = Touch.Point(
            resting.x + (lastKey.x - resting.x) * fraction,
            resting.y + (lastKey.y - resting.y) * fraction,
        )
        touch.glide(
            listOf(
                Touch.Stroke(keys.dropLast(1), Keyboard.GLIDE_MS_PER_KEY * (word.length - 1)),
                Touch.Stroke(listOf(resting), REST_BEFORE_HOP_MS),
                Touch.Stroke(listOf(hopped(HOP_FIRST_SAMPLE), hopped(HOP_END)), HOP_MS),
            ),
            dwellEndMs = endRestMs,
        )
        return settledAddition(before)
    }

    /** After a glide in another field, because what a process's first gesture does has a test of its own. */
    private fun assertLastHopLands(word: String, endRestMs: Long) {
        warmUpGlide()
        kb.show(chat)
        assertEveryAttemptLands(word, HOP_ATTEMPTS, "the finger ended on the last key, so it counts") { glideWithLastHop(word, endRestMs) }
    }

    /**
     * The controls for the tests below: the same stroke, resting at the end, spells the word. They hold only while a
     * reading taken during the stroke lands quickly, because the keyboard's trust in a reading runs from when it landed.
     * So Lift red with Rest green is the keyboard stopping early, and both red decides nothing.
     */
    @Test
    fun lastHopThenRest_later() = assertLastHopLands("later", Keyboard.GLIDE_END_REST_MS)

    @Test
    fun lastHopThenRest_start() = assertLastHopLands("start", Keyboard.GLIDE_END_REST_MS)

    @Test
    fun lastHopThenRest_here() = assertLastHopLands("here", Keyboard.GLIDE_END_REST_MS)

    /** The finger does not wait for the keyboard, and the keyboard has no right to stop reading before the lift. */
    @Test
    fun lastHopThenLift_later() = assertLastHopLands("later", QUICK_LIFT_MS)

    @Test
    fun lastHopThenLift_start() = assertLastHopLands("start", QUICK_LIFT_MS)

    @Test
    fun lastHopThenLift_here() = assertLastHopLands("here", QUICK_LIFT_MS)

    /**
     * The first gesture of a new process must not be swallowed while its tables are still being built. One show and no
     * more: a show starts building them if the dictionary is ready by then, so the second show that repairs a short
     * window would make this pass.
     */
    @Test
    fun firstGlideOfAProcess_isNotLost() {
        kb.show(chat)
        ime.waitIdle()
        kb.type("what ")
        assertText("What ")
        val before = host.text(chat)
        touch.glide(listOf(centre("i"), centre("f")), FIRST_GLIDE_MS)
        assertEquals("the first glide must land a word", "if ", settledAddition(before).lowercase())
    }

    /**
     * Force-stopped, a field opened and a glide straight away. The test above lets the start-up work end first, while
     * nothing waits here, so this is where the keyboard has to hold on to a stroke it cannot read yet.
     */
    @Test
    fun glideRightAfterAFreshStart_isNotLost() {
        ime.forceStop()
        ime.select()
        kb.show(chat)
        assertEquals("a glide right after a fresh start must land its word and nothing else", "$QUICK_WORD ", glideQuickWord().lowercase())
    }

    /**
     * As above with the shortest stroke, whose control is [laterGlideOfAProcess_sameFlickLands]. A long word can take long
     * enough for the keyboard to catch up during the stroke itself, and a flick is over before that.
     */
    @Test
    fun flickRightAfterAFreshStart_isNotLost() {
        ime.forceStop()
        ime.select()
        kb.show(chat)
        kb.type("what ")
        assertText("What ")
        val before = host.text(chat)
        touch.glide(listOf(centre("i"), centre("f")), FIRST_GLIDE_MS)
        assertEquals("a flick right after a fresh start must land its word", "if ", settledAddition(before).lowercase())
    }

    /** The control for the long word above: the same stroke works once the process has seen a gesture. */
    @Test
    fun laterGlideOfAProcess_sameQuickWordLands() {
        warmUpGlide()
        kb.show(chat)
        assertEquals("the same glide, after another", "$QUICK_WORD ", glideQuickWord().lowercase())
    }

    /** A long word, to show that all of the stroke was read, and traced fast enough to be over before a table its first move started is ready. */
    private fun glideQuickWord(): String {
        val before = host.text(chat)
        kb.glide(QUICK_WORD, msPerKey = QUICK_WORD_MS_PER_KEY, endRestMs = QUICK_WORD_END_REST_MS)
        return settledAddition(before)
    }

    /** The control for the first-glide test: the same stroke works once the process has seen a gesture. */
    @Test
    fun laterGlideOfAProcess_sameFlickLands() {
        warmUpGlide()
        kb.show(chat)
        kb.type("what ")
        assertText("What ")
        val before = host.text(chat)
        touch.glide(listOf(centre("i"), centre("f")), FIRST_GLIDE_MS)
        assertEquals("the same glide, after another", "if ", settledAddition(before).lowercase())
    }

    // ---------------------------------------------------------------------------------------------
    // Its place in the sentence
    // ---------------------------------------------------------------------------------------------

    @Test
    fun firstWordOfAField_isCapitalised() {
        showReady(chat)
        kb.glide("hello")
        assertTextStays("Hello ", message = "a sentence starts with a capital however it was entered")
    }

    @Test
    fun consecutiveGlides_areSeparatedByOneSpace() {
        warmUpGlide()
        kb.show(chat)
        kb.glide("hello")
        SystemClock.sleep(GLIDE_PAUSE_MS)
        kb.glide("world")
        assertTextStaysIgnoringCase("hello world ")
    }

    /** Tap the first word and glide the next, without a space: the tapped word must stay. */
    @Test
    fun rightAfterATappedWord_keepsThatWord() {
        showReady(chat)
        kb.type("hi")
        assertText("Hi")
        kb.glide("there")
        val text = host.text(chat)
        SystemClock.sleep(GLIDE_PAUSE_MS)
        assertTrue("the tapped word must not be overwritten. Text: '$text'", host.text(chat).lowercase().startsWith("hi "))
    }

    /** A word still composing is the word before the glide, though nothing has finished it yet. */
    @Test
    fun wordStillComposing_decidesAnAmbiguousGlide() {
        warmUpGlide()
        kb.show(chat)
        kb.type("let")
        assertText("Let")
        assertEquals("the word being typed says 'let', so the stroke is 'us'", " us ", glideIsOrUs().lowercase())
    }

    /**
     * A thumb that slides half a key on the way to a letter, on the most sensitive setting, where that is enough to
     * count as a glide and too short to read. The letter must join the word being typed, and the word that comes of
     * it, not the part typed before the slip, is what the next word follows.
     */
    @Test
    fun aSlipMidWord_staysOneWord_andTheNextWordFollowsThatWord() {
        ime.forceStop()
        ime.seedFloatPrefs(mapOf("swipe_sensitivity" to 0.6f))
        ime.select()
        showReady(chat)
        kb.type("tha")
        assertText("Tha")
        val key = centre("n")
        val halfAKey = kb.layout().require("n").rect.width() / 2f
        touch.glide(listOf(key, Touch.Point(key.x + halfAKey, key.y)), SLIP_MS)
        assertText("Than", message = "a slip that cannot be read as a glide is a tap on the key it began on")
        assertComposing("Than", message = "and the letter is part of the word being typed")
        kb.type("k ")
        assertText("Thank ")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertTextStays("Thank you ", message = "the next word follows the whole word, not the part before the slip")
    }

    /** A word typed before a glide may be a slip that nobody has said is finished, so it stays out of what is learned. */
    @Test
    fun tappedUnknownWordThenAGlide_isNotLearned() {
        warmUpGlide()
        kb.show(chat)
        repeat(UNKNOWN_WORD_USES) {
            kb.type("so $UNKNOWN_WORD")
            glideAndRead("hello")
            SystemClock.sleep(GLIDE_PAUSE_MS)
        }
        val before = host.text(chat)
        val prefix = UNKNOWN_WORD.dropLast(1)
        kb.type(prefix)
        assertText(before + prefix)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        val added = settledText(before + prefix).substring(before.length).lowercase()
        record("offered", added.trim())
        assertTrue("'$UNKNOWN_WORD' was never finished by the user, so the bar must not lead with it, but gave '$added'", !added.startsWith(UNKNOWN_WORD))
    }

    /** A slide from the keys up onto the bar's main word, lifted there. */
    private fun slideUpOntoTheMainWord(from: String = "r", ms: Long = SLIDE_UP_MS) {
        val grid = kb.layout().view
        val barY = (kb.windowState().frame.top + grid.top) / 2f
        touch.glide(listOf(centre(from), Touch.Point(grid.width() * MAIN_SLOT_X, barY)), ms, dwellEndMs = SLIDE_UP_REST_MS)
    }

    /** From the middle row at a person's speed there is time for a preview of the stroke, and the word aimed at must still be there. */
    @Test
    fun slideUpOntoTheBar_atAnOrdinarySpeedStillCompletesTheWord() {
        warmUpGlide()
        kb.show(chat)
        kb.type("so tomorr")
        assertText("So tomorr")
        SystemClock.sleep(BAR_SETTLE_MS)
        slideUpOntoTheMainWord(from = "l", ms = ORDINARY_SLIDE_UP_MS)
        settledText("So tomorr")
        assertTextStays("So tomorrow ", message = "the word under the finger replaces the word being typed")
    }

    /** Only one word can be on the chip for "tomorr", and it takes the place of what is typed, with a space. */
    @Test
    fun slideUpOntoTheBar_completesTheWordBeingTyped() {
        warmUpGlide()
        kb.show(chat)
        kb.type("so tomorr")
        assertText("So tomorr")
        SystemClock.sleep(BAR_SETTLE_MS)
        slideUpOntoTheMainWord()
        settledText("So tomorr")
        assertTextStays("So tomorrow ", message = "the word under the finger replaces the word being typed")
    }

    /** "hel" can show more than one word on that chip, and the bar cannot be read. Whichever it is, nothing stays of "hel". */
    @Test
    fun slideUpOntoTheBar_leavesNothingOfTheWordBeingTyped() {
        warmUpGlide()
        kb.show(chat)
        kb.type("so hel")
        assertText("So hel")
        SystemClock.sleep(BAR_SETTLE_MS)
        slideUpOntoTheMainWord()
        val text = settledText("So hel")
        record("field", text)
        assertTrue("one word and a space must take the place of 'hel', but the field holds '$text'", SLID_UP_WORD.matches(text))
    }

    private fun dp(v: Int): Float = v * host.onMain { it.resources.displayMetrics.density }

    /** A slide up onto the chip that takes a correction back means what a tap on it means. Added after the fix, it would read 'the teh '. */
    @Test
    fun slideUpOntoTheRevertChip_undoesTheFix() {
        warmUpGlide()
        kb.show(chat)
        kb.type("for teh ")
        assertText("For the ")
        Wait.until(BAR_SETTLE_MS, 100) { kb.barNodes().any { it.label == "teh" } }
        val chip = kb.barNodes().firstOrNull { it.label == "teh" }
            ?: throw AssertionError("the bar must offer the word as typed. It offers: ${kb.barNodes()}")
        touch.glide(listOf(centre("r"), Touch.Point(chip.rect.exactCenterX(), chip.rect.exactCenterY())), SLIDE_UP_MS, dwellEndMs = CHIP_STAY_REST_MS)
        settledText("For the ")
        assertTextStays("For teh ", message = "the chip restores the word and its space")
    }

    /**
     * A stroke that overshoots the top edge and lifts over that chip at once is a glide that ran long. It lands what it traced,
     * and the fix stands, so nothing was refused and the same slip is fixed the next time too.
     */
    @Test
    fun overshootLiftedOverTheRevertChip_landsTheTracedWord() {
        warmUpGlide()
        kb.show(chat)
        kb.type("for teh ")
        assertText("For the ")
        Wait.until(BAR_SETTLE_MS, 100) { kb.barNodes().any { it.label == "teh" } }
        val chip = kb.barNodes().firstOrNull { it.label == "teh" }
            ?: throw AssertionError("the bar must offer the word as typed. It offers: ${kb.barNodes()}")
        val u = centre("u")
        val margin = chip.rect.width() / 8f
        val above = Touch.Point(u.x.coerceIn(chip.rect.left + margin, chip.rect.right - margin), kb.layout().view.top - dp(OVERSHOOT_DP))
        touch.glide(listOf(centre("y"), centre("o"), u, above), OVERSHOOT_GLIDE_MS, dwellEndMs = 0)
        settledText("For the ")
        assertTextStays("For the you ", message = "the word traced lands, and the correction is not taken back")
        kb.type("teh ")
        assertTextStays("For the you the ", message = "no undo happened, so nothing was refused")
    }

    /** The word a glide left on the bar as its way back, reached by a slide. It takes that glide's place, and nothing is added after it. */
    @Test
    fun slideUpOntoTheAlternate_replacesTheGlide() {
        warmUpGlide()
        kb.show(chat)
        val landed = glideIsOrUs()
        record("landed", landed)
        SystemClock.sleep(BAR_SETTLE_MS)
        val grid = kb.layout().view
        val barY = (kb.windowState().frame.top + grid.top) / 2f
        touch.glide(listOf(centre("r"), Touch.Point(grid.width() * LEFT_SLOT_X, barY)), SLIDE_UP_MS, dwellEndMs = CHIP_STAY_REST_MS)
        val text = settledText(landed)
        record("replacedWith", text)
        assertTrue("one word and a space must remain, but the field holds '$text'", ONE_WORD_AND_A_SPACE.matches(text))
        assertTrue("the left chip must be a different word from '$landed'", !text.equals(landed, ignoreCase = true))
    }

    /** The same overshoot over the alternate. The glide before it stands, and the word traced goes after it. */
    @Test
    fun overshootLiftedOverTheAlternate_landsTheTracedWord() {
        warmUpGlide()
        kb.show(chat)
        val landed = glideIsOrUs()
        record("landed", landed)
        SystemClock.sleep(BAR_SETTLE_MS)
        val w = centre("w")
        val above = Touch.Point(w.x, kb.layout().view.top - dp(OVERSHOOT_DP))
        touch.glide(listOf(centre("h"), centre("o"), w, above), OVERSHOOT_GLIDE_MS, dwellEndMs = 0)
        settledText(landed)
        assertTextStays("${landed}how ", message = "the word traced lands, and the glide before it is not replaced")
    }

    /** Up onto the bar and down again far off comes to nothing, so the left chip still does what it did before that stroke. */
    @Test
    fun afterAStrokeThatCameToNothing_theLeftChipStillReplacesTheGlide() {
        warmUpGlide()
        kb.show(chat)
        val landed = glideIsOrUs()
        record("landed", landed)
        SystemClock.sleep(BAR_SETTLE_MS)
        val grid = kb.layout().view
        val barY = (kb.windowState().frame.top + grid.top) / 2f
        val q = centre("q")
        val w = centre("w")
        val farX = w.x + (w.x - q.x) * FAR_BACK_KEYS
        val path = listOf(q, w, Touch.Point(w.x, barY), Touch.Point(farX, barY), Touch.Point(farX, centre("a").y))
        touch.glide(path, FAR_BACK_MS, dwellEndMs = FAR_BACK_REST_MS)
        assertTextStays(landed, message = "nothing lands from a stroke that comes to nothing")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.LEFT)
        val text = settledText(landed)
        record("replacedWith", text)
        assertTrue("one word and a space must remain, but the field holds '$text'", ONE_WORD_AND_A_SPACE.matches(text))
        assertTrue("the left chip must be a different word from '$landed'", !text.equals(landed, ignoreCase = true))
    }

    /** A bar word after a typed full stop opens the sentence the same as a glide does in afterAFullStopWithNoSpace_getsASpaceAndACapital. */
    @Test
    fun aBarWordAfterAFullStopWithNoSpace_getsASpaceAndACapital() {
        warmUpGlide()
        kb.show(chat)
        kb.type("ok.")
        assertText("Ok.")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        val text = settledText("Ok.")
        record("field", text)
        assertTrue("a space and a capital go in, but the field holds '$text'", OPENS_A_SENTENCE.matches(text))
    }

    private fun assertGlideJoinsWhatIsTyped(field: String, typed: String, glided: String) {
        val before = host.text(field)
        assertTrue("'$typed' must be in the field, but it holds '$before'", before.equals(typed, ignoreCase = true))
        kb.glide(glided)
        Wait.until(GLIDE_COMMIT_CEILING_MS) { host.text(field) != before }
        val text = Wait.untilStable(GLIDE_SETTLE_MS, 150) { host.text(field) }.lowercase()
        record("field", text)
        assertTrue("the glide must land a word. Field: '$text'", text.length > typed.length)
        assertTrue(
            "no space goes between '$typed' and the glide's word here. Field: '$text'",
            text.startsWith(typed) && text[typed.length] != ' ',
        )
    }

    @Test
    fun glideInAUrlField_addsNoSpaceInsideTheAddress() {
        warmUpGlide()
        kb.show(HostActivity.URL)
        kb.type("exam")
        assertGlideJoinsWhatIsTyped(HostActivity.URL, "exam", "people")
    }

    /** Glides [word] into the URL field and waits for it to land. */
    private fun glideIntoTheUrlField(word: String) {
        val url = HostActivity.URL
        val before = host.text(url)
        kb.glide(word)
        Wait.until(GLIDE_COMMIT_CEILING_MS) { host.text(url) != before }
        Wait.untilStable(GLIDE_SETTLE_MS, 150) { host.text(url) }
    }

    /** A browser's bar is a URL field that takes searches too, and a search has words in it. */
    @Test
    fun twoGlidesInAUrlField_stayApart() {
        warmUpGlide()
        kb.show(HostActivity.URL)
        glideIntoTheUrlField("weather")
        SystemClock.sleep(GLIDE_PAUSE_MS)
        glideIntoTheUrlField("tomorrow")
        assertTextStays("weather tomorrow ", field = HostActivity.URL, message = "two words in a browser's bar stay two words")
    }

    @Test
    fun aGlideThenTypedLettersInAUrlField_stayApart() {
        warmUpGlide()
        kb.show(HostActivity.URL)
        glideIntoTheUrlField("weather")
        SystemClock.sleep(GLIDE_PAUSE_MS)
        kb.type("to")
        assertTextStays("weather to", field = HostActivity.URL, message = "letters typed after a glide begin a word of their own")
    }

    /** A dot in the run says this is an address after all, and nothing goes inside an address. */
    @Test
    fun aGlideAfterADotInAUrlField_staysGlued() {
        warmUpGlide()
        kb.show(HostActivity.URL)
        kb.type("example.")
        assertTextStaysIgnoringCase("example.", field = HostActivity.URL)
        glideIntoTheUrlField("people")
        val text = host.text(HostActivity.URL)
        record("field", text)
        assertTrue("no space goes in or after the word in an address, but the field holds '$text'", GLUED_TO_THE_DOT.matches(text))
    }

    /** Half an address is not an address, so it must not come back as a completion. */
    @Test
    fun glideInAnEmailField_addsNoSpaceAndTeachesNoHalfAddress() {
        val email = HostActivity.EMAIL
        warmUpGlide()
        kb.show(email)
        kb.type("john")
        kb.typeSymbol("@")
        kb.type("gm")
        assertTextStaysIgnoringCase("john@gm", field = email)
        assertGlideJoinsWhatIsTyped(email, "john@gm", "mail")
        kb.hideWithBack()
        host.setText(email, "")
        kb.show(email)
        kb.type("john")
        kb.typeSymbol("@")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        val text = Wait.untilStable(GLIDE_SETTLE_MS, 150) { host.text(email) }.lowercase()
        record("offered", text)
        assertTrue("the half address must not be offered back, but the bar gave '$text'", !text.startsWith("john@gm"))
    }

    /** Auto-space off means no automatic space, however the words got there. */
    @Test
    fun tappedWordThenAGlide_withAutoSpaceOff_getsNoSpace() {
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf("auto_space" to false))
        ime.select()
        warmUpGlide()
        kb.show(chat)
        kb.type("so")
        assertText("So")
        assertGlideJoinsWhatIsTyped(chat, "so", "there")
    }

    @Test
    fun rightAfterPunctuation_getsASpaceBefore() {
        showReady(chat)
        kb.type("hello,")
        assertText("Hello,")
        kb.glide("world")
        assertTextStaysIgnoringCase("hello, world ")
    }

    /** The first word of a sentence. firstWordOfAField covers only an empty field. */
    @Test
    fun afterAFullStopAndASpace_isCapitalised() {
        warmUpGlide()
        kb.show(chat)
        kb.type("ok. ")
        assertText("Ok. ")
        kb.glide("hello")
        settledText("Ok. ")
        assertTextStays("Ok. Hello ", message = "after a full stop and a space a new sentence starts")
    }

    /** The check behind Shift cannot see this one: a sentence end with no space after it looks like the middle of a word. */
    @Test
    fun afterAFullStopWithNoSpace_getsASpaceAndACapital() {
        warmUpGlide()
        kb.show(chat)
        kb.type("ok.")
        assertText("Ok.")
        kb.glide("hello")
        settledText("Ok.")
        assertTextStays("Ok. Hello ", message = "a space goes in, and it opens a new sentence")
    }

    @Test
    fun capsLock_holdsForTwoGlides() {
        warmUpGlide()
        kb.show(chat)
        kb.type("ok ")
        assertText("Ok ")
        // This key's label says whether it is on, and it was on when the field opened, so the keys are read again here.
        // Found once, because the second tap has to come within the double tap's time.
        kb.forgetLayout()
        val shift = kb.layout().require(Keyboard.SHIFT).rect
        repeat(2) { touch.tap(shift.exactCenterX(), shift.exactCenterY()) }
        kb.glide("hello")
        settledText("Ok ")
        SystemClock.sleep(GLIDE_PAUSE_MS)
        kb.glide("there")
        settledText("Ok HELLO ")
        assertTextStays("Ok HELLO THERE ", message = "caps lock lasts until it is turned off, and a glide does not turn it off")
    }

    /** With no space to end the word, Shift would stay on for one capital and give 'HelloWorld'. */
    @Test
    fun withAutoSpaceOff_twoGlidesTakeOneCapital() {
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf("auto_space" to false))
        ime.select()
        warmUpGlide()
        kb.show(chat)
        kb.glide("hello")
        settledText("")
        SystemClock.sleep(GLIDE_PAUSE_MS)
        kb.glide("world")
        settledText("Hello")
        assertTextStays("Helloworld", message = "only the first word of the field takes the capital")
    }

    @Test
    fun firstGlideInAnEmailField_staysLowerCase() {
        val email = HostActivity.EMAIL
        warmUpGlide()
        kb.show(email)
        kb.glide("hello")
        Wait.until(GLIDE_COMMIT_CEILING_MS) { host.text(email).isNotEmpty() }
        assertTextStays("hello", field = email, message = "an address takes no capital and no space from a glide")
    }

    /** Stays red until a typed lone i becomes a capital when a glide finishes it. */
    @Test
    fun tappedLoneI_thenAGlide_givesACapitalI() {
        warmUpGlide()
        kb.show(chat)
        kb.type("so i")
        assertText("So i")
        kb.glide("love")
        settledText("So i")
        assertTextStays("So I love ", message = "the glide finishes the lone i, which is a word like any other")
    }

    // ---------------------------------------------------------------------------------------------
    // The bar after a glide
    // ---------------------------------------------------------------------------------------------

    /** A thumb tapping the middle for the next word must never replace the glide. The bar is tapped blind on purpose. */
    @Test
    fun afterAGlide_theCentreChipIsTheNextWord() {
        warmUpGlide()
        kb.show(chat)
        kb.glide("thank")
        settledText("")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertTextStays("Thank you ", message = "the next word goes after the glide")
    }

    /** This stroke is used because it is ambiguous, so it has an alternate that is really close. */
    @Test
    fun afterAGlide_theLeftChipReplacesIt() {
        warmUpGlide()
        kb.show(chat)
        val landed = glideIsOrUs()
        record("landed", landed)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.LEFT)
        val text = settledText(landed)
        record("replacedWith", text)
        assertTrue("one word and a space must remain, but the field holds '$text'", ONE_WORD_AND_A_SPACE.matches(text))
        assertTrue("the left chip must be a different word from '$landed'", !text.equals(landed, ignoreCase = true))
    }

    @Test
    fun backspaceAfterAGlide_takesTheWholeWord() {
        warmUpGlide()
        kb.show(chat)
        kb.type("so ")
        kb.glide("hello")
        assertText("So hello ")
        kb.backspace()
        assertTextStays("So ", message = "one press takes back what one gesture wrote")
    }

    // ---------------------------------------------------------------------------------------------
    // Contractions
    // ---------------------------------------------------------------------------------------------

    /** Traced without the apostrophe, which is on another page and is never reached by a finger. */
    private fun assertContraction(traced: String, expected: String) {
        warmUpGlide()
        kb.show(chat)
        kb.type("so ")
        assertText("So ")
        assertEquals("a contraction must be reachable by a glide", "$expected ", glideAndRead(traced))
    }

    @Test
    fun contraction_dont() = assertContraction("dont", "don't")

    @Test
    fun contraction_im() = assertContraction("im", "I'm")

    @Test
    fun contraction_thats() = assertContraction("thats", "that's")

    @Test
    fun contraction_youre() = assertContraction("youre", "you're")

    /** The word the keyboard remembers has to be what the field holds, capital included, or one press takes one letter. */
    @Test
    fun glideIm_oneBackspaceWipesTheWholeWord() {
        warmUpGlide()
        kb.show(chat)
        kb.type("so ")
        assertText("So ")
        assertEquals("I'm ", glideAndRead("im"))
        kb.backspace()
        assertTextStays("So ", message = "one press takes back what one gesture wrote")
    }

    /** Past the first word of the field, where Shift would give the capital whatever else happened. */
    @Test
    fun glideIm_inASuggestionsOnlyField_getsTheCapital() {
        val web = HostActivity.WEB
        warmUpGlide()
        kb.show(web)
        kb.type("so ")
        assertTextStaysIgnoringCase("so ", field = web)
        val before = host.text(web)
        kb.glide("im")
        Wait.until(GLIDE_COMMIT_CEILING_MS) { host.text(web) != before }
        val text = Wait.untilStable(GLIDE_SETTLE_MS, 150) { host.text(web) }
        assertEquals("the keyboard wrote this word itself, so it takes its capital", "${before}I'm ", text)
    }

    @Test
    fun glideIm_inAnEmailField_staysLowerCase() {
        val email = HostActivity.EMAIL
        warmUpGlide()
        kb.show(email)
        kb.glide("im")
        Wait.until(GLIDE_COMMIT_CEILING_MS) { host.text(email).isNotEmpty() }
        assertTextStays("i'm", field = email, message = "an address field takes no capital and no space from a glide")
    }

    // ---------------------------------------------------------------------------------------------
    // Context: the words around the glide, whether or not this keyboard typed them
    // ---------------------------------------------------------------------------------------------

    /** The control for the tests below: this shows the sentence can decide when the keyboard saw it typed. */
    @Test
    fun typedWord_decidesAnAmbiguousGlide() {
        warmUpGlide()
        kb.show(chat)
        kb.type("let ")
        assertEquals("after 'let', the stroke is 'us'", "us ", glideIsOrUs().lowercase())
        kb.type("\n")
        kb.type("this ")
        assertEquals("after 'this', the stroke is 'is'", "is ", glideIsOrUs().lowercase())
        kb.type("\n")
        kb.type("fast ")
        assertEquals("after 'fast', the stroke is 'food'", "food ", glideFoodOrGood().lowercase())
    }

    private fun prefillThenShow(text: String) {
        warmUpGlide()
        kb.hideWithBack()
        host.setText(chat, text)
        showReady(chat)
    }

    @Test
    fun wordAlreadyInTheField_decidesAnAmbiguousGlide_us() {
        prefillThenShow("let ")
        assertEquals("the field says 'let', so the stroke is 'us'", "us ", glideIsOrUs().lowercase())
    }

    @Test
    fun wordAlreadyInTheField_decidesAnAmbiguousGlide_food() {
        prefillThenShow("fast ")
        assertEquals("the field says 'fast', so the stroke is 'food'", "food ", glideFoodOrGood().lowercase())
    }

    /** After the cursor moves back, the word before it is the context, not the last word typed. */
    @Test
    fun wordBeforeAMovedCursor_decidesAnAmbiguousGlide() {
        warmUpGlide()
        kb.show(chat)
        kb.type("this is what we want ")
        assertText("This is what we want ")
        kb.type("\n")
        kb.type("let ")
        kb.type("this ")
        assertText("This is what we want \nLet this ")
        moveCaretByHand("This is what we want \nLet ".length)
        val before = host.text(chat)
        touch.glide(listOf(between("i", "u"), centre("s")), AMBIGUOUS_GLIDE_MS, dwellEndMs = Keyboard.GLIDE_END_REST_MS)
        val text = settledText(before)
        assertTrue(
            "the word before the cursor is 'let', so the stroke is 'us'. Was '$before', now '$text'",
            text.lowercase().startsWith("this is what we want \nlet us "),
        )
    }

    /**
     * Two sentences the keyboard has seen, told apart only by the word before the last. With the last word alone the
     * same stroke would have to give the same word both times.
     */
    @Test
    fun wordBeforeTheLast_decidesAnAmbiguousGlide() {
        warmUpGlide()
        kb.show(chat)
        repeat(SENTENCE_LESSONS) {
            kb.type("so then is\n")
            kb.type("tell then us\n")
        }
        kb.type("so then ")
        assertEquals("after 'so then', the stroke is 'is'", "is ", glideIsOrUs().lowercase())
        kb.type("\n")
        kb.type("tell then ")
        assertEquals("after 'tell then', the stroke is 'us'", "us ", glideIsOrUs().lowercase())
    }

    /** The control for the next test. */
    @Test
    fun typedWord_predictsTheNextWord() {
        showReady(chat)
        kb.type("thank ")
        assertText("Thank ")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertTextStays("Thank you ")
    }

    @Test
    fun wordAlreadyInTheField_predictsTheNextWord() {
        host.setText(chat, "thank ")
        showReady(chat)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertTextStaysIgnoringCase("thank you ", message = "a draft the user came back to is the context")
    }

    private companion object {
        const val ATTEMPTS = 3
        const val SENTENCE_LESSONS = 4

        /** Each word follows one that can follow it in ordinary text, by the keyboard's own table of word pairs. */
        val COMMON_WORDS = (
            "hello world thanks again tomorrow will always think before going right after what could you know when they " +
                "please just really sorry about your time and maybe because their great people like today from where " +
                "would never have good with"
            ).split(" ")
        const val COMMON_WORDS_NEEDED = 38

        const val HOP_ATTEMPTS = 5
        const val REST_BEFORE_HOP_MS = 150L

        /** Where the finger is first seen on its way, as a share of the hop. A fast finger covers that in one report. */
        const val HOP_FIRST_SAMPLE = 0.33f
        const val HOP_END = 0.8f
        const val HOP_MS = 50L
        const val QUICK_LIFT_MS = 5L

        /**
         * The longest a glide's word may take to reach the field after the lift. The keyboard may hold a process's first
         * gesture for up to 1.5 s while it builds what it needs, and only then read it, so this has to be longer than that.
         */
        const val GLIDE_COMMIT_CEILING_MS = 2_500L

        /**
         * A ceiling on the wait for the field to stop changing, not a wait: three equal reads 150 ms apart end it, about
         * 300 ms after the last change, which says nothing about a word still to come.
         */
        const val GLIDE_SETTLE_MS = 1_500L
        const val GLIDE_PAUSE_MS = 400L
        const val AMBIGUOUS_GLIDE_MS = 500L

        /** A quick flick, over before anything the gesture itself set off could have finished. */
        const val FIRST_GLIDE_MS = 200L

        const val QUICK_WORD = "because"
        const val QUICK_WORD_MS_PER_KEY = 80L

        /** Past the time the keyboard trusts a reading from the stroke, so this is about the start and not about the lift. */
        const val QUICK_WORD_END_REST_MS = 250L
        const val BAR_SETTLE_MS = 600L
        const val SLIP_MS = 60L

        /** Invented, so the dictionary cannot know it. */
        const val UNKNOWN_WORD = "qwibbit"

        /** More than the keyboard needs to lead with a word it has learned. */
        const val UNKNOWN_WORD_USES = 3

        /** Quicker than a person slides, with next to no time spent on the keys. */
        const val SLIDE_UP_MS = 80L
        /** Long enough on the keys for a preview of the stroke to run and land before the finger gets to the bar. */
        const val ORDINARY_SLIDE_UP_MS = 250L
        const val SLIDE_UP_REST_MS = 120L

        /** Well past the stay that a chip taking something back asks of a finger. */
        const val CHIP_STAY_REST_MS = 300L

        /** How far above the keys a stroke overshoots, and how long it takes over three keys. */
        const val OVERSHOOT_DP = 15
        const val OVERSHOOT_GLIDE_MS = 510L

        const val FAR_BACK_KEYS = 5f
        const val FAR_BACK_MS = 600L
        const val FAR_BACK_REST_MS = 200L

        /** Inside the bar's main slot, with or without the mic. */
        const val MAIN_SLOT_X = 0.375f
        const val LEFT_SLOT_X = 0.125f
        val SLID_UP_WORD = Regex("So he[a-z]* ")
        val ONE_WORD_AND_A_SPACE = Regex("""\S+ """)
        val GLUED_TO_THE_DOT = Regex("""example\.\S+""", RegexOption.IGNORE_CASE)
        val OPENS_A_SENTENCE = Regex("""Ok\. \p{Lu}\S* """)
    }
}
