package com.localboard.keyboard.ime

import com.localboard.keyboard.engine.ContextTokens
import com.localboard.keyboard.engine.FakeEditor
import com.localboard.keyboard.engine.SelectionReport
import com.localboard.keyboard.engine.Suggestion
import com.localboard.keyboard.engine.TrackedEditor
import com.localboard.keyboard.engine.correction.FakeIme
import com.localboard.keyboard.engine.correction.TrailCountRules
import com.localboard.keyboard.ime.GlideRules.Shift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideRulesTest {

    private val mark = ContextTokens.SENTENCE_MARK

    // ------------------------------------------------------------------
    // The words before a glide
    // ------------------------------------------------------------------

    private fun before(composing: String, vararg trail: String) = GlideRules.wordsBefore(composing, trail.toList())

    @Test
    fun `with nothing composing the glide follows the last two of the trail`() {
        assertEquals(GlideRules.WordsBefore(null, null), before(""))
        assertEquals(GlideRules.WordsBefore("so", null), before("", "so"))
        assertEquals(GlideRules.WordsBefore("then", "so"), before("", "so", "then"))
        assertEquals(GlideRules.WordsBefore("then", "so"), before("", "well", "so", "then"))
        assertEquals(GlideRules.WordsBefore("then", mark), before("", mark, "then"))
    }

    /** Dropping the trail's last word here would skip the real previous word, since a composing word is not on the trail. */
    @Test
    fun `a word still composing is the word before and the trail's last comes next`() {
        assertEquals(GlideRules.WordsBefore("then", "so"), before("then", "so"))
        assertEquals(GlideRules.WordsBefore("then", null), before("then"))
        assertEquals(GlideRules.WordsBefore("then", "so"), before("Then", "well", "so"))
        assertEquals(GlideRules.WordsBefore("don't", "so"), before("Don’t", "so"))
        assertEquals(GlideRules.WordsBefore("dogs", "so"), before("dogs'", "so"))
    }

    // ------------------------------------------------------------------
    // Words typed since the field was read
    // ------------------------------------------------------------------

    /** A typed word, a glide and a bar pick all arrive as one word added. */
    @Test
    fun `a word adds one and taking one back removes one`() {
        assertEquals(1, GlideRules.countAfterAdding(0))
        assertEquals(4, GlideRules.countAfterAdding(3))
        assertEquals(3, GlideRules.countAfterAdding(1, 2))
        assertEquals(2, GlideRules.countAfterRemoving(3))
    }

    @Test
    fun `the count never goes below nothing`() {
        assertEquals(0, GlideRules.countAfterRemoving(0))
        assertEquals(0, GlideRules.countAfterRemoving(-3))
        assertEquals(1, GlideRules.countAfterAdding(-3))
    }

    @Test
    fun `only what was typed since the read teaches how this person writes`() {
        val own = listOf(mark, "see", "you", "there")
        assertEquals("there", GlideRules.sentenceToLearn(own, 1))
        assertEquals("you there", GlideRules.sentenceToLearn(own, 2))
        assertEquals(". see you there", GlideRules.sentenceToLearn(own, 4))
    }

    @Test
    fun `a sentence with nothing typed since the read teaches nothing`() {
        val own = listOf(mark, "see", "you")
        assertNull(GlideRules.sentenceToLearn(own, 0))
        assertNull(GlideRules.sentenceToLearn(emptyList(), 0))
    }

    /** takeLast throws on a negative count, and that would be on the main thread. */
    @Test
    fun `a count below nothing is no sentence and no crash`() {
        assertNull(GlideRules.sentenceToLearn(listOf("see"), -1))
    }

    @Test
    fun `marks alone are no sentence`() {
        assertNull(GlideRules.sentenceToLearn(listOf("see", mark), 1))
        assertNull(GlideRules.sentenceToLearn(listOf(mark), 5))
    }

    @Test
    fun `a sentence is at most twenty-four words`() {
        val own = (1..30).map { "w$it" }
        val sentence = GlideRules.sentenceToLearn(own, 30)!!.split(" ")
        assertEquals(24, sentence.size)
        assertEquals("w7", sentence.first())
        assertEquals("w30", sentence.last())
    }

    // ------------------------------------------------------------------
    // The same count on the hand port. This proves the rules and the port. The service's own count sites were only read.
    // ------------------------------------------------------------------

    private val countRules = object : TrailCountRules {
        override fun added(count: Int) = GlideRules.countAfterAdding(count)
        override fun removed(count: Int) = GlideRules.countAfterRemoving(count)
        override fun sentence(own: List<String>, count: Int) = GlideRules.sentenceToLearn(own, count)
    }

    private fun counting() = FakeIme({ 0L }).also {
        it.countRules = countRules
        it.known = setOf("hello", "there", "world", "see", "you")
    }

    private fun FakeIme.fieldHolds(text: String) {
        appSetsText(text)
        reseed(ContextTokens.fromFieldWindow(text))
    }

    @Test
    fun `a typed word, a glide and a bar pick each add one`() {
        val k = counting()
        k.type("hello"); k.separator(" ")
        assertEquals(1, k.typedSinceSeed)
        k.glide("there")
        assertEquals(2, k.typedSinceSeed)
        k.chip("world")
        assertEquals(3, k.typedSinceSeed)
    }

    @Test
    fun `taking a word back removes one`() {
        val k = counting()
        k.glide("there")
        k.glide("world")
        k.backspace()
        assertEquals(1, k.typedSinceSeed)
        assertEquals(listOf("there"), k.committedWords.toList())
    }

    @Test
    fun `backspacing into a word removes one`() {
        val k = counting()
        k.type("hello"); k.separator(" ")
        k.backspace()
        assertEquals("hello", k.composing.toString())
        assertEquals(0, k.typedSinceSeed)
    }

    /** The word comes off the trail though nobody counted it. */
    @Test
    fun `backspacing into a word the field gave the trail stays at nothing`() {
        val k = counting()
        k.fieldHolds("see you ")
        k.backspace()
        assertEquals("you", k.composing.toString())
        assertEquals(0, k.typedSinceSeed)
        assertEquals(listOf(mark, "see"), k.committedWords.toList())
    }

    @Test
    fun `an accepted draft adds nothing`() {
        val k = counting()
        k.glide("there")
        k.draftAccepted("see you ")
        assertEquals(1, k.typedSinceSeed)
    }

    @Test
    fun `reading the field resets the count`() {
        val k = counting()
        k.glide("there")
        k.fieldHolds("see you ")
        assertEquals(0, k.typedSinceSeed)
        assertEquals(listOf(mark, "see", "you"), k.committedWords.toList())
    }

    @Test
    fun `a new field resets the count`() {
        val k = counting()
        k.glide("there")
        k.showNewField()
        assertEquals(0, k.typedSinceSeed)
    }

    @Test
    fun `reading the field and then Enter teaches no sentence`() {
        val k = counting()
        k.fieldHolds("see you ")
        k.enter(actionKey = false)
        assertEquals(emptyList<String>(), k.sentencesLearned)
    }

    @Test
    fun `a word typed after the read teaches that word and none of the field's`() {
        val k = counting()
        k.fieldHolds("see you ")
        k.glide("there")
        k.enter(actionKey = false)
        assertEquals(listOf("there"), k.sentencesLearned)
    }

    /** A draft's words are not the person's, even when one of them is spelled the same as a word they typed. */
    @Test
    fun `a sentence leaves out an accepted draft's words`() {
        val k = counting()
        k.glide("there")
        k.draftAccepted("there world ")
        k.enter(actionKey = false)
        assertEquals(listOf("there"), k.sentencesLearned)
    }

    @Test
    fun `selecting everything and deleting it resets the count`() {
        val k = counting()
        k.type("hello"); k.separator(" ")
        k.selectThenBackspace(0, k.ed.toString().length)
        assertEquals(0, k.typedSinceSeed)
    }

    // ------------------------------------------------------------------
    // Reading the field
    // ------------------------------------------------------------------

    private val text = 0x1
    private val number = 0x2
    private val uri = 0x10
    private val email = 0x20
    private val shortMessage = 0x40
    private val password = 0x80
    private val visiblePassword = 0x90
    private val filter = 0xb0
    private val webEmail = 0xd0
    private val webPassword = 0xe0
    private val numberPassword = 0x10
    private val capSentences = 0x4000
    private val autoCorrect = 0x8000
    private val multiLine = 0x20000
    private val noSuggestions = 0x80000
    private val noPersonalizedLearning = 0x1000000

    private fun mayRead(
        inputType: Int,
        imeOptions: Int = 0,
        mayLearnInApp: Boolean = true,
        ownPackage: Boolean = false,
    ) = GlideRules.mayReadField(inputType, imeOptions, mayLearnInApp, ownPackage)

    @Test
    fun `a plain chat field may be read`() {
        assertTrue(mayRead(text or capSentences or autoCorrect or multiLine))
        assertTrue(mayRead(text or shortMessage or autoCorrect))
    }

    @Test
    fun `no kind of password field is ever read`() {
        assertFalse(mayRead(text or password))
        assertFalse(mayRead(text or visiblePassword))
        assertFalse(mayRead(text or webPassword))
        assertFalse(mayRead(number or numberPassword))
    }

    @Test
    fun `a field that asked for privacy or no suggestions is not read`() {
        assertFalse(mayRead(text or noSuggestions))
        assertFalse(mayRead(text or autoCorrect, imeOptions = noPersonalizedLearning))
        assertFalse(mayRead(text or autoCorrect, mayLearnInApp = false))
    }

    @Test
    fun `this app's own fields are not read`() {
        assertFalse(mayRead(text or autoCorrect, ownPackage = true))
    }

    @Test
    fun `addresses and filters are not read`() {
        assertFalse(mayRead(text or uri))
        assertFalse(mayRead(text or email))
        assertFalse(mayRead(text or webEmail))
        assertFalse(mayRead(text or filter))
    }

    private fun replaces(restarting: Boolean, read: String?, trail: List<String>, selStart: Int = 0) =
        GlideRules.showReplacesTrail(restarting, read, trail, selStart)

    @Test
    fun `a new field takes any read and a failed read leaves the trail empty`() {
        assertTrue(replaces(false, "see you ", emptyList()))
        assertTrue(replaces(false, "", emptyList()))
        assertFalse(replaces(false, null, emptyList()))
    }

    @Test
    fun `a rotation keeps a trail holding words`() {
        assertFalse(replaces(true, "see you ", listOf("see", "you"), selStart = 8))
        assertFalse(replaces(true, "", listOf("see", "you"), selStart = 8))
        assertFalse(replaces(true, null, listOf("see", "you")))
    }

    @Test
    fun `a draft restored after the first show replaces a trail with no word on it`() {
        assertTrue(replaces(true, "thank ", listOf(mark), selStart = 6))
        assertTrue(replaces(true, "thank ", emptyList(), selStart = 6))
    }

    @Test
    fun `an app clearing its field empties a trail holding words`() {
        assertTrue(replaces(true, "", listOf("let"), selStart = 0))
        assertTrue(replaces(true, "", listOf("let"), selStart = -1))
    }

    private val fired = GlideRules.MoveState("com.example.chat", 7, 3)

    private fun mayAct(
        now: GlideRules.MoveState = fired,
        composing: String = "",
        inputType: Int = text or autoCorrect,
    ) = GlideRules.moveMayAct(fired, now, composing, inputType)

    @Test
    fun `a moved caret acts when nothing has changed`() {
        assertTrue(mayAct())
    }

    @Test
    fun `a moved caret does nothing once the field has changed`() {
        assertFalse(mayAct(now = fired.copy(fieldId = 8)))
        assertFalse(mayAct(now = fired.copy(packageName = "com.example.other")))
    }

    @Test
    fun `a moved caret does nothing once a word has been typed or a word is being typed`() {
        assertFalse(mayAct(now = fired.copy(typedSinceSeed = 4)))
        assertFalse(mayAct(now = fired.copy(typedSinceSeed = 2)))
        assertFalse(mayAct(composing = "h"))
    }

    @Test
    fun `a moved caret does nothing in a password field by what the editor reports now`() {
        assertFalse(mayAct(inputType = text or password))
        assertFalse(mayAct(inputType = text or visiblePassword))
        assertFalse(mayAct(inputType = text or webPassword))
        assertFalse(mayAct(inputType = number or numberPassword))
    }

    // ------------------------------------------------------------------
    // Capitals
    // ------------------------------------------------------------------

    private fun case(word: String, shift: Shift, address: Boolean = false, afterEnd: Boolean = false) =
        GlideRules.caseWord(word, shift, address, afterEnd)

    @Test
    fun `shift decides how a glide's word is written`() {
        assertEquals("hello", case("hello", Shift.OFF))
        assertEquals("Hello", case("hello", Shift.ON))
        assertEquals("HELLO", case("hello", Shift.CAPS_LOCK))
        assertEquals("DON'T", case("don't", Shift.CAPS_LOCK))
    }

    /** Shift is on for an email field's first letter, by the field's own flags, and an address takes no capital from a glide. */
    @Test
    fun `an address takes no first capital and caps lock still holds`() {
        assertEquals("hello", case("hello", Shift.ON, address = true))
        assertEquals("hello", case("hello", Shift.OFF, address = true, afterEnd = true))
        assertEquals("HELLO", case("hello", Shift.CAPS_LOCK, address = true))
    }

    /** The check behind Shift reads 'llo.' as mid-sentence, so without this 'hello.' plus a glide gives 'hello. world '. */
    @Test
    fun `a space going in after a sentence end gives a capital`() {
        assertEquals("World", case("world", Shift.OFF, afterEnd = true))
        assertEquals("World", case("world", Shift.ON, afterEnd = true))
        assertEquals("WORLD", case("world", Shift.CAPS_LOCK, afterEnd = true))
    }

    @Test
    fun `a sentence ends at a full stop and at no other mark`() {
        assertTrue(GlideRules.endsSentence('.'))
        assertTrue(GlideRules.endsSentence('!'))
        assertTrue(GlideRules.endsSentence('?'))
        assertFalse(GlideRules.endsSentence(','))
        assertFalse(GlideRules.endsSentence('a'))
        assertFalse(GlideRules.endsSentence(null))
    }

    // ------------------------------------------------------------------
    // Spaces
    // ------------------------------------------------------------------

    private fun lead(before: Char?, autoSpace: Boolean = true, address: Boolean = false, glued: Boolean = false) =
        GlideRules.wantsLeadSpace(autoSpace, address, glued, before)

    private fun tail(after: Char?, autoSpace: Boolean = true, address: Boolean = false) =
        GlideRules.wantsTailSpace(autoSpace, address, after)

    @Test
    fun `a word needs a space after a letter, a digit, an emoji or closing punctuation`() {
        for (c in "aZé7.,;:!?)]}") assertTrue("before '$c'", lead(c))
        assertTrue("the end of an emoji pair", lead('\uDE00'))
        assertTrue("a symbol drawn as an emoji", lead('❤'))
        assertTrue("the selector that makes one", lead('️'))
    }

    @Test
    fun `a word needs no space at the start, after a space or after an opening mark`() {
        assertFalse(lead(null))
        for (c in " \n\t([{\"-") assertFalse("before '$c'", lead(c))
    }

    @Test
    fun `no lead space with auto-space off, in an address field, or glued to an address`() {
        assertFalse(lead('a', autoSpace = false))
        assertFalse(lead('a', address = true))
        assertFalse(lead('a', glued = true))
    }

    @Test
    fun `a word gets a space after it, unless what follows keeps it apart already`() {
        assertTrue(tail(null))
        assertTrue(tail('a'))
        assertTrue(tail('('))
        for (c in " \n\t.,;:!?)]}\"'’”") assertFalse("after '$c'", tail(c))
    }

    @Test
    fun `no tail space with auto-space off or in an address field`() {
        assertFalse(tail(null, autoSpace = false))
        assertFalse(tail('a', autoSpace = false))
        assertFalse(tail(null, address = true))
        assertFalse(tail('a', address = true))
    }

    @Test
    fun `a URL field's run reads as an address only with a dot, a slash, a colon or an at`() {
        for (run in listOf("example.", "example.com", "a/b", "https:", "john@", "see example.com")) {
            assertTrue(run, GlideRules.uriRunIsAddress(run))
        }
        for (run in listOf("", "weather", "weather ", "example.com weather", "a.b ", "a.b\nweather")) {
            assertFalse(run, GlideRules.uriRunIsAddress(run))
        }
        assertFalse("a read that failed", GlideRules.uriRunIsAddress(null))
    }

    @Test
    fun `two glides in a URL field stay apart and teach nothing, as the hand port has it`() {
        val k = FakeIme({ 0L }).also { it.urlField = true }
        k.glide("weather")
        k.glide("tomorrow")
        assertEquals("weather tomorrow ", k.ed.toString())
        assertTrue(k.log.none { it.startsWith("learn") })
    }

    @Test
    fun `a glide then a typed letter stays apart in a URL field`() {
        val k = FakeIme({ 0L }).also { it.urlField = true }
        k.glide("weather")
        k.type("t")
        assertEquals("weather t", k.ed.toString())
    }

    @Test
    fun `a word composing or a dot in the run stays glued in a URL field`() {
        val composing = FakeIme({ 0L }).also { it.urlField = true }
        composing.type("exam")
        composing.glide("ple")
        assertEquals("example", composing.ed.toString())

        val dotted = FakeIme({ 0L }).also { it.urlField = true }
        dotted.ed.commit("example.")
        dotted.glide("com")
        assertEquals("example.com", dotted.ed.toString())
    }

    @Test
    fun `a stay on a chip is the time between coming onto it and the lift`() {
        assertFalse("a lift at once", GlideRules.stayedOnChip(1_000, 1_000))
        assertFalse("just short", GlideRules.stayedOnChip(1_000, 1_000 + GlideRules.CHIP_STAY_MS - 1))
        assertTrue("just enough", GlideRules.stayedOnChip(1_000, 1_000 + GlideRules.CHIP_STAY_MS))
    }

    // ------------------------------------------------------------------
    // The run touching the caret
    // ------------------------------------------------------------------

    @Test
    fun `the run a read of the field left out is the one word touching the caret`() {
        assertEquals("tomorrow", ContextTokens.runAtCursor("see you tomorrow"))
        assertEquals("le", ContextTokens.runAtCursor("le"))
        assertEquals("don't", ContextTokens.runAtCursor("I don\u2019t"))
        assertEquals("well-known", ContextTokens.runAtCursor("a Well-Known"))
        assertNull("no run", ContextTokens.runAtCursor("see you "))
        assertNull("no run", ContextTokens.runAtCursor("see you,"))
        assertNull("an empty read", ContextTokens.runAtCursor(""))
        assertNull("a run with a digit is no word", ContextTokens.runAtCursor("at 3pm"))
        assertNull("a run filling the read may be cut off", ContextTokens.runAtCursor("abcdef", window = 6))
        assertEquals("a shorter run in a full read is whole", "cdef", ContextTokens.runAtCursor("a cdef", window = 6))
    }

    @Test
    fun `the run a separator ended is read back past the separator`() {
        assertEquals("tomorrow", ContextTokens.runBeforeSeparators("see you tomorrow "))
        assertEquals("tomorrow", ContextTokens.runBeforeSeparators("see you tomorrow, "))
        assertEquals("tomorrow", ContextTokens.runBeforeSeparators("see you tomorrow."))
        assertEquals("tomorrow", ContextTokens.runBeforeSeparators("see you tomorrow"))
        assertNull(ContextTokens.runBeforeSeparators("  "))
        assertNull(ContextTokens.runBeforeSeparators(""))
    }

    @Test
    fun `a run that ends is on the trail uncounted, and the word after it counts`() {
        val k = counting()
        k.appSetsText("see you there")
        k.reseed(ContextTokens.fromFieldWindow("see you there"), ContextTokens.runAtCursor("see you there"))
        assertEquals(0, k.typedSinceSeed)
        k.glide("hello")
        assertEquals("see you there hello ", k.ed.toString())
        assertEquals(1, k.typedSinceSeed)
        assertEquals(listOf(mark, "see", "you", "there", "hello"), k.committedWords.toList())
    }

    @Test
    fun `capitals in an address field stay as they were, whatever its spaces do`() {
        assertEquals("weather", case("weather", Shift.ON, address = true))
        assertEquals("WEATHER", case("weather", Shift.CAPS_LOCK, address = true))
    }

    // ------------------------------------------------------------------
    // The bar after a glide
    // ------------------------------------------------------------------

    private fun word(text: String, score: Double = 2.0) = Suggestion(text, score)

    private fun texts(list: List<Suggestion>) = list.map { it.text }

    /** By the bar's slot order this draws the first next word in the centre, the alternate on the left and the second on the right. */
    @Test
    fun `the bar offers what comes next with one way back`() {
        val bar = GlideRules.barAfterGlide(listOf(word("you"), word("for"), word("a")), word("thanks"), "thank")
        assertEquals(listOf("you", "thanks", "for"), texts(bar))
    }

    @Test
    fun `one next word and the alternate make two chips`() {
        val bar = GlideRules.barAfterGlide(listOf(word("you")), word("thanks"), "thank")
        assertEquals(listOf("you", "thanks"), texts(bar))
    }

    /** A centre tap equal to the alternate would replace the glide, when the person only meant the next word. */
    @Test
    fun `a next word equal to the alternate goes`() {
        val bar = GlideRules.barAfterGlide(listOf(word("Thanks"), word("you"), word("for")), word("thanks"), "thank")
        assertEquals(listOf("you", "thanks", "for"), texts(bar))
    }

    @Test
    fun `a next word equal to the glided word goes`() {
        val bar = GlideRules.barAfterGlide(listOf(word("Thank"), word("you"), word("for")), word("thanks"), "thank")
        assertEquals(listOf("you", "thanks", "for"), texts(bar))
    }

    @Test
    fun `with no next words the alternate stands alone, marked for the left`() {
        val bar = GlideRules.barAfterGlide(emptyList(), word("thanks"), "thank")
        assertEquals(listOf(Suggestion("thanks", 0.0)), bar)
    }

    @Test
    fun `with only the two words themselves on offer the alternate stands alone`() {
        val bar = GlideRules.barAfterGlide(listOf(word("thanks"), word("thank")), word("thanks"), "thank")
        assertEquals(listOf(Suggestion("thanks", 0.0)), bar)
    }

    @Test
    fun `with no alternate the bar is what it was`() {
        val display = listOf(word("you"), word("thank"))
        assertEquals(display, GlideRules.barAfterGlide(display, null, "thank"))
    }

    /** 0.0 and -1.0 mean the word as typed and the revert chip, and an alternate beside next words is neither. */
    @Test
    fun `an alternate beside next words never carries a marker score`() {
        for (marker in listOf(0.0, -1.0)) {
            val bar = GlideRules.barAfterGlide(listOf(word("you")), word("thanks", marker), "thank")
            assertEquals("thanks", bar[1].text)
            assertTrue("score ${bar[1].score}", bar[1].score > 0.0)
        }
        assertEquals(1.5, GlideRules.barAfterGlide(listOf(word("you")), word("thanks", 1.5), "thank")[1].score, 0.0)
    }

    private val strokeSoFar = listOf(word("there"), word("three"), word("these"))

    @Test
    fun `with nothing to take back a stroke's words fill the bar`() {
        assertEquals(strokeSoFar, GlideRules.barDuringStroke(strokeSoFar, null, null))
    }

    /** A finger sliding up to the way back from the last glide finds it on the left, where it was. */
    @Test
    fun `the last glide's alternate keeps its place during a stroke`() {
        val bar = GlideRules.barDuringStroke(strokeSoFar, word("Us"), null)
        assertEquals(listOf("there", "Us", "three"), texts(bar))
    }

    @Test
    fun `the chip that undoes a correction keeps its place during a stroke`() {
        val bar = GlideRules.barDuringStroke(strokeSoFar, null, "teh")
        assertEquals(listOf("there", "three", "teh"), texts(bar))
        assertEquals(-1.0, bar[2].score, 0.0)
    }

    @Test
    fun `a stroke's word equal to the chip is not shown twice`() {
        val bar = GlideRules.barDuringStroke(listOf(word("Teh"), word("there"), word("three")), null, "teh")
        assertEquals(listOf("there", "three", "teh"), texts(bar))
        assertEquals(listOf("there", "Us"), texts(GlideRules.barDuringStroke(listOf(word("us"), word("there")), word("Us"), null)))
    }

    // ------------------------------------------------------------------
    // A glide read before its table is ready
    // ------------------------------------------------------------------

    @Test
    fun `a path of thirty percent of the view is a glide that must not go silently`() {
        assertTrue(GlideRules.isLongPath(300f, 1000))
        assertTrue(GlideRules.isLongPath(800f, 1000))
        assertFalse(GlideRules.isLongPath(299f, 1000))
        assertFalse(GlideRules.isLongPath(0f, 1000))
    }

    private val atTurn = GlideRules.HoldSnapshot("so", "ok", Shift.OFF, 5_000L, "com.example.chat", 7, "")

    @Test
    fun `a held word lands when nothing has changed`() {
        assertTrue(GlideRules.holdMayCommit(atTurn, atTurn.copy(), keyboardShown = true))
    }

    /** It would land as 'w hello ', or with a letter gone, or in another field. */
    @Test
    fun `a held word is dropped when anything came between the finger and the word`() {
        val key = atTurn.copy(lastKeystrokeAt = 5_100L)
        val letter = atTurn.copy(composing = "w")
        val backspace = atTurn.copy(prev1 = "ok", prev2 = null, lastKeystrokeAt = 5_100L)
        val otherField = atTurn.copy(fieldId = 8)
        val otherApp = atTurn.copy(packageName = "com.example.other")
        val shift = atTurn.copy(shift = Shift.ON)
        for (now in listOf(key, letter, backspace, otherField, otherApp, shift)) {
            assertFalse("$now", GlideRules.holdMayCommit(atTurn, now, keyboardShown = true))
        }
    }

    @Test
    fun `a held word is dropped when the keyboard has gone down`() {
        assertFalse(GlideRules.holdMayCommit(atTurn, atTurn.copy(), keyboardShown = false))
    }

    // ------------------------------------------------------------------
    // What glide has learned
    // ------------------------------------------------------------------

    private val today = 20_000L

    @Test
    fun `the stored form is word, tab, value, tab, day`() {
        val entries = mapOf("hello" to GlideRules.BiasEntry(2, 19_990L, 2), "there" to GlideRules.BiasEntry(-1, 19_999L, -1))
        assertEquals("hello\t2\t19990\nthere\t-1\t19999", GlideRules.formatBias(entries))
    }

    @Test
    fun `what is stored loads as it was written`() {
        val stored = "hello\t2\t$today\nthere\t-1\t$today"
        val loaded = GlideRules.loadBias(stored, today)
        assertEquals(GlideRules.BiasEntry(2, today, 2), loaded["hello"])
        assertEquals(GlideRules.BiasEntry(-1, today, -1), loaded["there"])
        assertEquals(stored, GlideRules.formatBias(loaded))
    }

    @Test
    fun `a line with no day counts as learned today`() {
        assertEquals(GlideRules.BiasEntry(2, today, 2), GlideRules.loadBias("hello\t2", today)["hello"])
    }

    @Test
    fun `a line that cannot be read costs that line alone`() {
        val stored = "hello\t2\t$today\nbad\t2\tnotaday\nworse\tnotavalue\t$today\n\tno word\nnotabs\nthere\t1\t$today\ntoo\tmany\tfields\there"
        assertEquals(setOf("hello", "there"), GlideRules.loadBias(stored, today).keys)
    }

    @Test
    fun `nothing stored loads nothing`() {
        assertTrue(GlideRules.loadBias("", today).isEmpty())
    }

    @Test
    fun `a bias halves every thirty days and goes once under a half`() {
        assertEquals(3, GlideRules.decayedBias(3, 0))
        assertEquals(2, GlideRules.decayedBias(3, 30))
        assertEquals(1, GlideRules.decayedBias(3, 60))
        assertEquals(1, GlideRules.decayedBias(1, 29))
        assertEquals(1, GlideRules.decayedBias(1, 30))
        assertEquals(0, GlideRules.decayedBias(1, 31))
        assertEquals(0, GlideRules.decayedBias(3, 365))
        assertEquals(0, GlideRules.decayedBias(0, 0))
    }

    @Test
    fun `a dislike fades the same way`() {
        assertEquals(-2, GlideRules.decayedBias(-3, 30))
        assertEquals(-1, GlideRules.decayedBias(-1, 30))
        assertEquals(0, GlideRules.decayedBias(-1, 31))
    }

    /** A wrong clock must not make learning stronger. */
    @Test
    fun `a day in the future counts as today`() {
        assertEquals(3, GlideRules.decayedBias(3, -400))
    }

    @Test
    fun `a value worth nothing by now is left out`() {
        assertTrue(GlideRules.loadBias("hello\t1\t${today - 90}", today).isEmpty())
    }

    @Test
    fun `an old value loads at what it is worth now and keeps what was stored`() {
        assertEquals(GlideRules.BiasEntry(3, today - 30, 2), GlideRules.loadBias("hello\t3\t${today - 30}", today)["hello"])
    }

    /** Restore runs at every view creation. If a load rewrote what it worked out, learning would decay each time. */
    @Test
    fun `restoring twice in a day and saving after it change no date and do not decay twice`() {
        val stored = "hello\t3\t${today - 30}\nthere\t2\t${today - 5}"
        val first = GlideRules.loadBias(stored, today)
        val second = GlideRules.loadBias(stored, today)
        assertEquals(first, second)
        val held = first.mapValues { it.value.worth }
        val saved = GlideRules.formatBias(GlideRules.biasToStore(held, first, today))
        assertEquals(stored, saved)
        assertEquals(first, GlideRules.loadBias(saved, today))
    }

    @Test
    fun `saving long after the load still changes nothing the decoder did not change`() {
        val stored = "hello\t3\t${today - 30}"
        val loaded = GlideRules.loadBias(stored, today)
        val held = loaded.mapValues { it.value.worth }
        val saved = GlideRules.formatBias(GlideRules.biasToStore(held, loaded, today + 40))
        assertEquals(stored, saved)
    }

    @Test
    fun `a value the decoder changed is new learning, dated today`() {
        val loaded = GlideRules.loadBias("hello\t3\t${today - 30}\nthere\t2\t${today - 5}", today)
        val held = mapOf("hello" to 3, "there" to 2, "new" to -1)
        val saved = GlideRules.biasToStore(held, loaded, today)
        assertEquals(GlideRules.BiasEntry(3, today, 3), saved["hello"])
        assertEquals(GlideRules.BiasEntry(2, today - 5, 2), saved["there"])
        assertEquals(GlideRules.BiasEntry(-1, today, -1), saved["new"])
    }

    @Test
    fun `a word the decoder dropped is not stored`() {
        val loaded = GlideRules.loadBias("hello\t3\t$today", today)
        assertTrue(GlideRules.biasToStore(emptyMap(), loaded, today).isEmpty())
    }

    // ------------------------------------------------------------------
    // A burst on a slow editor
    // ------------------------------------------------------------------

    /**
     * Words typed quickly on an editor whose reports arrive late. If the keyboard took one of its own late echoes for a
     * moved caret, the check that follows would throw away the trail and with it the word just typed. The report is the
     * first guard, and the count that has moved on by the time the check fires is the second.
     */
    @Test
    fun burstKeepsItsTrailThroughADelayedEcho() {
        val words = "so then we all went home".split(" ")
        for (echoDelay in listOf(0L, 40L, 150L, 400L, 900L)) {
            var now = 1_000L
            val fake = FakeEditor(echoDelayMs = echoDelay, nowMs = { now })
            val editor = TrackedEditor { now }
            val id = editor.onNewConnection()
            editor.onShow(fake.selStart, fake.selEnd)
            editor.connectionIsNew = false

            val trail = ArrayList<String>()
            var count = 0
            var composing = ""
            val realReports = ArrayList<GlideRules.MoveState>()

            fun deliverDue() {
                for (r in fake.takeDue()) {
                    val report = editor.onSelectionReported(
                        r.oldStart, r.oldEnd, r.newStart, r.newEnd, r.candidatesStart, false, composing.isNotEmpty(),
                    )
                    if (report == SelectionReport.REAL) realReports += GlideRules.MoveState("com.example.chat", 7, count)
                }
            }

            for (word in words) {
                for (len in 1..word.length) {
                    val part = word.take(len)
                    editor.setComposingText(id, part.length) { fake.setComposingText(part) }
                    composing = part
                    now += KEY_GAP_MS
                    deliverDue()
                }
                editor.beginBatchEdit(id) { fake.beginBatchEdit() }
                editor.finishComposingText(id) { fake.finishComposingText() }
                editor.commitText(id, 1) { fake.commitText(" ") }
                editor.endBatchEdit(id) { fake.endBatchEdit() }
                composing = ""
                trail += word
                count = GlideRules.countAfterAdding(count)
                now += KEY_GAP_MS
                deliverDue()
            }
            now += SETTLE_MS
            deliverDue()

            val label = "reports $echoDelay ms late"
            assertEquals("$label: ${realReports.size} of the keyboard's own writes read as a moved caret", 0, realReports.size)
            assertEquals(label, words, trail)
            assertEquals(label, words.size, count)
            assertEquals(label, words.joinToString(" ") + " ", fake.text)

            // Even a report taken for a moved caret could not act once a word had landed after it.
            val stale = GlideRules.MoveState("com.example.chat", 7, count - 1)
            val current = GlideRules.MoveState("com.example.chat", 7, count)
            assertFalse(label, GlideRules.moveMayAct(stale, current, "", text or autoCorrect))
        }
    }

    private companion object {
        const val KEY_GAP_MS = 60L
        const val SETTLE_MS = 2_000L
    }
}
