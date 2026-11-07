package com.localboard.keyboard.memory

import com.localboard.keyboard.memory.ChatCapture.ScrollBox
import com.localboard.keyboard.memory.ChatCapture.TextNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCaptureTest {
    private val w = 1080
    private val h = 2316
    private val screen = w.toLong() * h

    /** The chat's list, under a toolbar: what most messaging apps look like with the keyboard up. */
    private fun list(canScrollForward: Boolean = false) =
        ScrollBox(parent = -1, depth = 6, area = w.toLong() * 900, top = 240, canScrollForward = canScrollForward)

    private fun incoming(text: String, top: Int, right: Int = 700, box: Int = 0) =
        TextNode(text, top, 170, right, null, box)

    private fun outgoing(text: String, top: Int, left: Int = 500, box: Int = 0) =
        TextNode(text, top, left, 1040, null, box)

    private fun toolbar(text: String, top: Int = 110) = TextNode(text, top, 150, 600, null, -1)

    @Test
    fun onlyLinesInsideTheListAreKept() {
        val r = ChatCapture.shape(
            listOf(
                toolbar("Sam Example"),
                incoming("are you coming tonight", 300),
                outgoing("yeah what time", 400),
                incoming("seven at the usual place", 500),
                TextNode("Encrypted chat", 1200, 300, 780, null, -1)
            ),
            listOf(list()), w, h
        )
        assertTrue(r.listFound)
        assertEquals(
            listOf(
                Turn(Speaker.THEM, "are you coming tonight"),
                Turn(Speaker.ME, "yeah what time"),
                Turn(Speaker.THEM, "seven at the usual place")
            ),
            r.turns
        )
    }

    @Test
    fun theTitleComesFromAboveTheList_topmostFirst() {
        val r = ChatCapture.shape(
            listOf(toolbar("online", 170), toolbar("Sam Example", 110), incoming("hello", 300)),
            listOf(list()), w, h
        )
        assertEquals(listOf("Sam Example", "online"), r.titleCandidates)
    }

    @Test
    fun aMessageIsNeverATitleCandidate() {
        val r = ChatCapture.shape(listOf(incoming("hello there", 300)), listOf(list()), w, h)
        assertTrue(r.titleCandidates.isEmpty())
    }

    @Test
    fun aVeryShortChatIsKept() {
        val r = ChatCapture.shape(listOf(toolbar("Sam Example"), incoming("hey", 300)), listOf(list()), w, h)
        assertEquals(listOf("hey"), r.turns.map { it.text })
    }

    @Test
    fun twoLinesOneEachWayAreEnoughForSpeakers() {
        val r = ChatCapture.shape(listOf(incoming("hey", 300), outgoing("hi", 400, left = 900)), listOf(list()), w, h)
        assertEquals(listOf(Speaker.THEM, Speaker.ME), r.turns.map { it.speaker })
    }

    @Test
    fun aOneSidedListGetsNoSpeakersFromGeometry() {
        // The shape of a settings page as much as of a chat.
        val rows = (0..4).map { incoming("Setting number $it", 300 + it * 100, right = 800) }
        val r = ChatCapture.shape(rows, listOf(list()), w, h)
        assertTrue(r.turns.all { it.speaker == Speaker.UNKNOWN })
    }

    @Test
    fun aViewIdStillSpeaksInAOneSidedList() {
        val r = ChatCapture.shape(
            listOf(TextNode("on my way", 300, 500, 1040, Speaker.ME, 0)),
            listOf(list()), w, h
        )
        assertEquals(Speaker.ME, r.turns.single().speaker)
    }

    @Test
    fun avatarsDoNotConfuseTheSides() {
        // A long incoming bubble runs past the old right band and starts past the old left one.
        val r = ChatCapture.shape(
            listOf(
                TextNode("a long message from them that fills the row", 300, 170, 930, null, 0),
                TextNode("short one", 400, 170, 420, null, 0),
                TextNode("my reply", 500, 620, 1040, null, 0),
                TextNode("my long reply that nearly fills the row", 600, 300, 1040, null, 0)
            ),
            listOf(list()), w, h
        )
        assertEquals(listOf(Speaker.THEM, Speaker.THEM, Speaker.ME, Speaker.ME), r.turns.map { it.speaker })
    }

    @Test
    fun aCenteredDateIsDropped_butASideAlignedOneIsAMessage() {
        val r = ChatCapture.shape(
            listOf(
                TextNode("Today", 280, 480, 600, null, 0),
                TextNode("Tue, Nov 4", 300, 420, 660, null, 0),
                incoming("today", 400),
                outgoing("yes today", 500)
            ),
            listOf(list()), w, h
        )
        assertEquals(listOf("today", "yes today"), r.turns.map { it.text })
    }

    @Test
    fun aBubbleFillingTheRowIsNotADate_evenIfItOpensLikeOne() {
        val text = "March 4 is when we leave for the coast, so pack tonight"
        val short = "Sat 9 is fine with me"
        val r = ChatCapture.shape(
            listOf(
                TextNode(text, 300, 40, 1040, null, 0),
                TextNode(short, 500, 40, 1040, null, 0)
            ),
            listOf(list()), w, h
        )
        assertEquals(listOf(text, short), r.turns.map { it.text })
    }

    @Test
    fun aTitleBarDrawnOverTheListStillGivesTheTitle() {
        val overlaid = ScrollBox(parent = -1, depth = 6, area = w.toLong() * 1200, top = 0, canScrollForward = false)
        val r = ChatCapture.shape(
            listOf(toolbar("Sam Example"), TextNode("a note lower down the page", 1500, 40, 900, null, -1), incoming("hello", 300)),
            listOf(overlaid), w, h
        )
        assertEquals(listOf("Sam Example"), r.titleCandidates)
        assertEquals(listOf("hello"), r.turns.map { it.text })
    }

    @Test
    fun aCenteredLineThatIsNotADateStays() {
        val r = ChatCapture.shape(listOf(TextNode("Sam joined the chat", 300, 300, 780, null, 0)), listOf(list()), w, h)
        assertEquals(listOf("Sam joined the chat"), r.turns.map { it.text })
    }

    @Test
    fun linesComeOutOldestFirst_whateverOrderTheWalkFoundThem() {
        val r = ChatCapture.shape(
            listOf(outgoing("newest", 700), incoming("older", 500), incoming("oldest", 300)),
            listOf(list()), w, h
        )
        assertEquals(listOf("oldest", "older", "newest"), r.turns.map { it.text })
    }

    @Test
    fun aMessageOnSeveralLinesStaysOneLine() {
        val r = ChatCapture.shape(listOf(incoming("line one\nline  two", 300)), listOf(list()), w, h)
        assertEquals("line one line two", r.turns.single().text)
    }

    @Test
    fun atBottomFollowsTheList() {
        val texts = listOf(incoming("hello there friend", 300))
        assertTrue(ChatCapture.shape(texts, listOf(list(canScrollForward = false)), w, h).atBottom)
        assertFalse(ChatCapture.shape(texts, listOf(list(canScrollForward = true)), w, h).atBottom)
    }

    @Test
    fun noList_keepsEverythingAboveTheFloor() {
        val r = ChatCapture.shape(
            listOf(
                TextNode("a form label on the page", 500, 40, 700, null, -1),
                TextNode("another label down the page", 600, 40, 700, null, -1)
            ),
            emptyList(), w, h
        )
        assertFalse(r.listFound)
        assertTrue(r.atBottom)
        assertEquals(2, r.turns.size)
        assertTrue(r.turns.all { it.speaker == Speaker.UNKNOWN })
    }

    @Test
    fun noList_dropsWhatIsBelowTheFloor() {
        val r = ChatCapture.shape(listOf(TextNode("Done", 500, 40, 200, null, -1)), emptyList(), w, h)
        assertTrue(r.turns.isEmpty())
    }

    @Test
    fun noList_speakersNeedRealBackAndForth() {
        fun left(t: String, top: Int) = TextNode(t, top, 40, 600, null, -1)
        fun right(t: String, top: Int) = TextNode(t, top, 500, 1040, null, -1)
        val two = ChatCapture.shape(listOf(left("one side says this", 500), right("other side says that", 600)), emptyList(), w, h)
        assertTrue(two.turns.all { it.speaker == Speaker.UNKNOWN })
        val four = ChatCapture.shape(
            listOf(left("first", 500), right("second", 600), left("third", 700), right("fourth line", 800)),
            emptyList(), w, h
        )
        assertEquals(listOf(Speaker.THEM, Speaker.ME, Speaker.THEM, Speaker.ME), four.turns.map { it.speaker })
    }

    @Test
    fun noList_takesTheTitleFromTheTopBand_longestFirst() {
        val r = ChatCapture.shape(
            listOf(toolbar("online", 170), toolbar("Sam Example", 110), TextNode("some text lower down the page", 1500, 40, 900, null, -1)),
            emptyList(), w, h
        )
        assertEquals(listOf("Sam Example", "online"), r.titleCandidates)
    }

    @Test
    fun aListWithNoTextInItDoesNotCount() {
        val r = ChatCapture.shape(
            listOf(TextNode("text elsewhere on the page here", 1500, 40, 900, null, -1)),
            listOf(list()), w, h
        )
        assertFalse(r.listFound)
        assertEquals(1, r.turns.size)
    }

    @Test
    fun choose_takesTheLargest() {
        val boxes = listOf(
            ScrollBox(-1, 3, w.toLong() * 400, 100, false),
            ScrollBox(-1, 3, w.toLong() * 900, 600, false)
        )
        assertEquals(1, ChatCapture.chooseList(boxes, screen))
    }

    @Test
    fun choose_prefersAListThatFillsItsScrollingWrapper() {
        val boxes = listOf(
            ScrollBox(-1, 2, w.toLong() * 1200, 100, true),
            ScrollBox(0, 5, w.toLong() * 900, 240, false)
        )
        assertEquals(1, ChatCapture.chooseList(boxes, screen))
    }

    @Test
    fun choose_keepsTheWrapperOverASmallListInsideIt() {
        val boxes = listOf(
            ScrollBox(-1, 2, w.toLong() * 1200, 100, true),
            ScrollBox(0, 5, w.toLong() * 200, 240, false)
        )
        assertEquals(0, ChatCapture.chooseList(boxes, screen))
    }

    @Test
    fun choose_ignoresASideListOfSimilarSize() {
        val boxes = listOf(
            ScrollBox(-1, 2, w.toLong() * 1200, 100, true),
            ScrollBox(-1, 5, w.toLong() * 900, 240, false)
        )
        assertEquals(0, ChatCapture.chooseList(boxes, screen))
    }

    @Test
    fun choose_refusesASmallScrollable() {
        val boxes = listOf(ScrollBox(-1, 3, 400L * 200, 500, true))
        assertEquals(-1, ChatCapture.chooseList(boxes, screen))
    }

    @Test
    fun choose_withNoLists_findsNone() {
        assertEquals(-1, ChatCapture.chooseList(emptyList(), screen))
    }

    @Test
    fun textInANestedListCountsAsInsideTheChosenOne() {
        val boxes = listOf(
            ScrollBox(-1, 2, w.toLong() * 1200, 100, false),
            ScrollBox(0, 5, w.toLong() * 100, 240, false)
        )
        val r = ChatCapture.shape(listOf(incoming("in the inner strip", 300, box = 1)), boxes, w, h)
        assertTrue(r.listFound)
        assertEquals(1, r.turns.size)
    }
}
