package com.localboard.keyboard.context

import com.localboard.keyboard.memory.Speaker
import com.localboard.keyboard.memory.Turn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenBufferTest {
    private val app = "test.chat.app"

    private fun them(s: String) = Turn(Speaker.THEM, s)
    private fun me(s: String) = Turn(Speaker.ME, s)

    private val realClock = ScreenBuffer.elapsedMs
    private var nowMs = 1_000L

    @Before
    fun fresh() {
        ScreenBuffer.clearAll()
        ScreenBuffer.noScreenPackages = emptySet()
        ScreenBuffer.redactEnabled = true
        ScreenBuffer.elapsedMs = { nowMs }
    }

    @After
    fun tidy() {
        ScreenBuffer.clearAll()
        ScreenBuffer.noScreenPackages = emptySet()
        ScreenBuffer.elapsedMs = realClock
    }

    @Test
    fun aShortChatIsKept() {
        ScreenBuffer.put(app, listOf(Turn(Speaker.UNKNOWN, "hey")), "Sam Example")
        assertEquals("hey", ScreenBuffer.get(app))
        assertEquals("Sam Example", ScreenBuffer.currentScreenKey(app))
    }

    /** A walk already under way when the app's text is dropped must not put it straight back. */
    @Test
    fun forgetDropsTheTextAndTheTitleAndALateCaptureWithThem() {
        val privateApp = "test.private.app"
        ScreenBuffer.put(privateApp, listOf(them("are you coming")), "Sam Example")

        ScreenBuffer.forget(privateApp)

        assertEquals("", ScreenBuffer.get(privateApp))
        assertEquals("", ScreenBuffer.currentScreenKey(privateApp))
        ScreenBuffer.put(privateApp, listOf(them("are you coming")), "Sam Example")
        assertEquals("", ScreenBuffer.get(privateApp))
        assertEquals("", ScreenBuffer.currentScreenKey(privateApp))
    }

    /** A second sparkle soon after the first asks for a new read, and must not draft with no chat at all. */
    @Test
    fun aTapThatAsksForAReadLiftsTheRefusalAForgetLeft() {
        val privateApp = "test.private.retry"
        ScreenBuffer.put(privateApp, listOf(them("are you coming")))
        ScreenBuffer.forget(privateApp)
        nowMs += 1_000

        ScreenBuffer.allowRead(privateApp)
        ScreenBuffer.put(privateApp, listOf(them("are you coming")))
        assertEquals("Them: are you coming", ScreenBuffer.get(privateApp))

        // That draft's own forget clears it and refuses the walk behind it, just as the first did.
        ScreenBuffer.forget(privateApp)
        ScreenBuffer.put(privateApp, listOf(them("are you coming")))
        assertEquals("", ScreenBuffer.get(privateApp))
    }

    /** The refusal runs out by the clock that cannot be set back. */
    @Test
    fun theRefusalRunsOutByItself() {
        val privateApp = "test.private.expiry"
        ScreenBuffer.forget(privateApp)

        nowMs += 9_999
        ScreenBuffer.put(privateApp, listOf(them("still refused")))
        assertEquals("", ScreenBuffer.get(privateApp))

        nowMs += 1
        ScreenBuffer.put(privateApp, listOf(them("kept now")))
        assertEquals("Them: kept now", ScreenBuffer.get(privateApp))
    }

    /** One app's retry says nothing about another app's walk. */
    @Test
    fun allowingOneAppLeavesAnotherRefused() {
        val first = "test.private.first"
        val second = "test.private.second"
        ScreenBuffer.forget(first)
        ScreenBuffer.forget(second)

        ScreenBuffer.allowRead(first)
        ScreenBuffer.put(second, listOf(them("are you coming")))

        assertEquals("", ScreenBuffer.get(second))
    }

    @Test
    fun linesCarryTheirSpeakers() {
        ScreenBuffer.put(app, listOf(them("are you coming"), me("on my way")), "Sam Example")
        assertEquals("Them: are you coming\nYou: on my way", ScreenBuffer.get(app))
    }

    @Test
    fun aRepeatedLineIsANewMessage() {
        ScreenBuffer.put(app, listOf(them("hi there"), me("ok")), "Sam Example")
        ScreenBuffer.put(app, listOf(them("hi there"), me("ok"), me("ok")), "Sam Example")
        assertEquals("Them: hi there\nYou: ok\nYou: ok", ScreenBuffer.get(app))
    }

    @Test
    fun aChatMovingOnAppendsOnlyWhatIsNew() {
        ScreenBuffer.put(app, listOf(them("first question here"), me("first answer here")), "Sam Example")
        ScreenBuffer.put(app, listOf(me("first answer here"), them("second question here")), "Sam Example")
        assertEquals(
            "Them: first question here\nYou: first answer here\nThem: second question here",
            ScreenBuffer.get(app)
        )
    }

    @Test
    fun twoNamedChatsNeverMergeOverALineInCommon() {
        val shared = "sounds good to me then"
        ScreenBuffer.put(app, listOf(them("lunch tomorrow at noon"), me(shared)), "Sam Example")
        ScreenBuffer.put(app, listOf(me(shared), them("the invoice went out today")), "Robin Sample")
        assertEquals("You: $shared\nThem: the invoice went out today", ScreenBuffer.get(app))
        assertEquals("Robin Sample", ScreenBuffer.currentScreenKey(app))
        assertEquals(
            "Them: lunch tomorrow at noon\nYou: $shared",
            ScreenBuffer.getForScreen(app, "Sam Example")
        )
    }

    @Test
    fun goingBackToAChatPicksUpItsOwnLines() {
        ScreenBuffer.put(app, listOf(them("lunch tomorrow at noon")), "Sam Example")
        ScreenBuffer.put(app, listOf(them("the invoice went out today")), "Robin Sample")
        ScreenBuffer.put(app, listOf(them("lunch tomorrow at noon"), me("works for me")), "Sam Example")
        assertEquals("Them: lunch tomorrow at noon\nYou: works for me", ScreenBuffer.get(app))
    }

    @Test
    fun anUnnamedScreenWithNothingInCommonReplaces_atTheBottomOrNot() {
        ScreenBuffer.put(app, listOf(them("lunch tomorrow at noon")))
        ScreenBuffer.put(app, listOf(them("completely different page")))
        assertEquals("Them: completely different page", ScreenBuffer.get(app))
        ScreenBuffer.put(app, listOf(them("yet another page of text")), atBottom = false)
        assertEquals("Them: yet another page of text", ScreenBuffer.get(app))
    }

    @Test
    fun scrolledFarUpInANamedChat_holdsOnToTheNewestLines() {
        ScreenBuffer.put(app, listOf(them("the latest thing said here")), "Sam Example")
        ScreenBuffer.put(app, listOf(them("something from weeks ago")), "Sam Example", atBottom = false)
        assertEquals("Them: the latest thing said here", ScreenBuffer.get(app))
    }

    @Test
    fun scrollingUpAddsHistoryAboveWhatIsHeld() {
        val a = them("older line that scrolled in")
        val b = me("a line both captures can see")
        val c = them("the newest line in the chat")
        ScreenBuffer.put(app, listOf(b, c), "Sam Example")
        ScreenBuffer.put(app, listOf(a, b), "Sam Example", atBottom = false)
        assertEquals(
            "Them: older line that scrolled in\nYou: a line both captures can see\nThem: the newest line in the chat",
            ScreenBuffer.get(app)
        )
    }

    @Test
    fun anUnnamedChatHasNoKey_becauseItsFirstLineIsAMessage() {
        ScreenBuffer.put(app, listOf(them("first line of the chat"), me("second line of the chat")))
        assertEquals("", ScreenBuffer.currentScreenKey(app))
        assertEquals("Them: first line of the chat\nYou: second line of the chat", ScreenBuffer.get(app))
    }

    @Test
    fun unnamedCapturesStillLineUp() {
        ScreenBuffer.put(app, listOf(them("first line of the chat"), me("second line of the chat")))
        ScreenBuffer.put(app, listOf(me("second line of the chat"), them("third line of the chat")))
        assertEquals(
            "Them: first line of the chat\nYou: second line of the chat\nThem: third line of the chat",
            ScreenBuffer.get(app)
        )
    }

    @Test
    fun aNameHoldsWhenALaterCaptureOfTheSameChatCarriesNone() {
        ScreenBuffer.put(app, listOf(them("first line of the chat")), "Sam Example")
        ScreenBuffer.put(app, listOf(them("first line of the chat"), me("second line of the chat")))
        assertEquals("Sam Example", ScreenBuffer.currentScreenKey(app))
        assertEquals(
            "Them: first line of the chat\nYou: second line of the chat",
            ScreenBuffer.getForScreen(app, "Sam Example")
        )
    }

    @Test
    fun aDifferentScreenWithNoNameDoesNotKeepTheOldName() {
        ScreenBuffer.put(app, listOf(them("first line of the chat")), "Sam Example")
        ScreenBuffer.put(app, listOf(them("a page with nothing in common")))
        assertEquals("", ScreenBuffer.currentScreenKey(app))
    }

    @Test
    fun theCapKeepsTheNewestWholeLines() {
        val many = (1..200).map { i -> if (i % 2 == 0) me("message number $i in this chat") else them("message number $i in this chat") }
        ScreenBuffer.put(app, many, "Sam Example")
        val text = ScreenBuffer.get(app)
        assertTrue(text.length <= ScreenBuffer.MAX_CHARS)
        val lines = text.lines()
        assertEquals("You: message number 200 in this chat", lines.last())
        assertTrue(lines.all { it.startsWith("You: ") || it.startsWith("Them: ") })
    }

    @Test
    fun codesAreRedactedBeforeTheyAreStored() {
        ScreenBuffer.put(app, listOf(them("your code is 481516 ok")), "Sam Example")
        assertEquals("Them: your code is [redacted] ok", ScreenBuffer.get(app))
        assertFalse(ScreenBuffer.getForScreen(app, "Sam Example").contains("481516"))
    }

    @Test
    fun aBlockedAppIsNeverStored() {
        ScreenBuffer.noScreenPackages = setOf(app)
        ScreenBuffer.put(app, listOf(them("this should not stay")), "Sam Example")
        assertEquals("", ScreenBuffer.get(app))
    }

    @Test
    fun anEmptyCaptureChangesNothing() {
        ScreenBuffer.put(app, listOf(them("still the conversation")), "Sam Example")
        ScreenBuffer.put(app, emptyList(), "Sam Example")
        assertEquals("Them: still the conversation", ScreenBuffer.get(app))
    }
}
