package com.localboard.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackedEditorTest {

    /** A keyboard's writes going through [TrackedEditor] to a [FakeEditor], with a clock the test moves. */
    private class Rig(
        echoDelayMs: Long = 0L,
        text: String = "",
        maxLength: Int = Int.MAX_VALUE,
    ) {
        var now = 1_000L
        val fake = FakeEditor(text, maxLength = maxLength, echoDelayMs = echoDelayMs, nowMs = { now })
        val editor = TrackedEditor { now }
        var id = editor.onNewConnection()
        val seen = ArrayList<SelectionReport>()

        /** What the service holds in `composing`. Its only effect here is on how a report reads. */
        var composing = ""

        init {
            editor.onShow(fake.selStart, fake.selEnd)
            editor.connectionIsNew = false
        }

        fun compose(t: String) {
            editor.setComposingText(id, t.length) { fake.setComposingText(t) }
            composing = t
        }

        fun commit(t: String) {
            editor.commitText(id, t.length) { fake.commitText(t) }
            composing = ""
        }

        fun delete(n: Int) {
            editor.deleteSurroundingText(id, n) { fake.deleteSurroundingText(n) }
        }

        fun finish() {
            editor.finishComposingText(id) { fake.finishComposingText() }
            composing = ""
        }

        fun select(start: Int, end: Int = start) {
            editor.setSelection(id, start, end) { fake.setSelection(start, end) }
        }

        fun batch(block: () -> Unit) {
            editor.beginBatchEdit(id) { fake.beginBatchEdit() }
            block()
            editor.endBatchEdit(id) { fake.endBatchEdit() }
        }

        /** The time between two key presses, in which any report now due arrives. */
        fun gap(ms: Long = KEY_GAP_MS) {
            now += ms
            deliverDue()
        }

        fun deliverDue() {
            fake.takeDue().forEach(::deliver)
        }

        fun deliverMerged() {
            fake.takeMerged()?.let(::deliver)
        }

        /** Long enough for the slowest report here to arrive, and not so long that a position ages out. */
        fun settle() {
            gap(SETTLE_MS)
        }

        fun deliver(r: FakeEditor.Report, selfEditing: Boolean = false): SelectionReport {
            val result = editor.onSelectionReported(
                r.oldStart, r.oldEnd, r.newStart, r.newEnd, r.candidatesStart, selfEditing, composing.isNotEmpty(),
            )
            seen += result
            return result
        }

        val tracker get() = editor.tracker

        /** A glide's word: the commit, the finish that has nothing to do and the automatic space. */
        fun glide(word: String) {
            commit(word)
            finish()
            commit(" ")
        }

        fun typeWord(word: String) {
            for (len in 1..word.length) {
                compose(word.take(len))
                gap()
            }
        }

        fun space() {
            batch {
                finish()
                commit(" ")
            }
        }
    }

    private fun assertAllEchoes(rig: Rig, label: String) {
        assertTrue("$label: something must have been reported", rig.seen.isNotEmpty())
        assertEquals("$label: ${rig.seen}", emptyList<SelectionReport>(), rig.seen.filter { it != SelectionReport.ECHO })
    }

    /** Runs [sequence] with reports arriving at each delay, each followed by [check], which sees all of them delivered. */
    private fun atEveryDelay(
        text: String = "",
        sequence: Rig.() -> Unit,
        check: (Rig, String) -> Unit,
    ) {
        for (delay in ECHO_DELAYS) {
            val rig = Rig(delay, text)
            rig.sequence()
            rig.settle()
            val label = "echoes at $delay ms"
            assertAllEchoes(rig, label)
            check(rig, label)
        }
    }

    private val alternatePickedThreeTimes: Rig.() -> Unit = {
        glide("hello")
        gap()
        for ((old, new) in listOf("hello" to "jello", "jello" to "help", "help" to "hallo")) {
            delete(old.length + 1)
            commit(new)
            finish()
            commit(" ")
            gap()
        }
    }

    @Test
    fun `an alternate picked three times is all one's own`() {
        atEveryDelay("so ", alternatePickedThreeTimes) { rig, label ->
            assertEquals(label, "so hallo ", rig.fake.text)
            assertTrue(label, rig.tracker.atRest(9, 9))
        }
    }

    @Test
    fun `double space twice is all one's own`() {
        atEveryDelay("so ", {
            typeWord("fine")
            space()
            gap()
            delete(1)
            commit(". ")
            gap()
            typeWord("yes")
            space()
            gap()
            delete(1)
            commit(". ")
        }) { rig, label ->
            assertEquals(label, "so fine. yes. ", rig.fake.text)
            assertTrue(label, rig.tracker.atRest(14, 14))
        }
    }

    @Test
    fun `a snippet undone twice is all one's own and leaves the trigger composing`() {
        atEveryDelay("so ", {
            repeat(2) {
                typeWord("brb")
                batch {
                    compose("be right back")
                    finish()
                }
                gap()
                delete("be right back".length)
                compose("brb")
                gap()
                // Backspace takes the trigger back out, letter by letter, ready for the next time.
                compose("br")
                gap()
                compose("b")
                gap()
                compose("")
                finish()
                gap()
            }
            typeWord("brb")
        }) { rig, label ->
            assertEquals(label, "so brb", rig.fake.text)
            assertEquals(label, 3, rig.tracker.composingStart)
        }
    }

    @Test
    fun `a shorter word picked from the bar twice is all one's own`() {
        atEveryDelay("", {
            repeat(2) {
                typeWord("helpp")
                compose("help")
                finish()
                commit(" ")
                gap()
            }
        }) { rig, label ->
            assertEquals(label, "help help ", rig.fake.text)
            assertTrue(label, rig.tracker.atRest(10, 10))
        }
    }

    @Test
    fun `undo after a slow echo keeps the word composing, so one more letter makes one word`() {
        atEveryDelay("so ", {
            typeWord("teh")
            batch {
                finish()
                delete(3)
                commit("the")
                commit(" ")
            }
            gap()
            batch {
                delete(4)
                compose("teh")
            }
            gap()
            compose("tehx")
            gap()
        }) { rig, label ->
            assertEquals(label, "so tehx", rig.fake.text)
            assertEquals(label, 3, rig.tracker.composingStart)
            assertEquals(label, 3, rig.fake.regionStart)
        }
    }

    @Test
    fun `re-entry after Space with a slow echo keeps the word composing`() {
        atEveryDelay("so ", {
            typeWord("word")
            space()
            gap()
            batch {
                delete(1)
                delete(4)
                compose("word")
            }
            gap()
            compose("words")
            gap()
        }) { rig, label ->
            assertEquals(label, "so words", rig.fake.text)
            assertEquals(label, 3, rig.tracker.composingStart)
        }
    }

    @Test
    fun `a re-show on one connection keeps the position, so a glide's echoes are not real`() {
        for (delay in ECHO_DELAYS) {
            val label = "echoes at $delay ms"
            val rig = Rig(delay, "a long enough line of text ")
            val caret = rig.fake.selStart
            rig.typeWord("word")
            rig.settle()
            // Back. The framework ends the region itself, around the tracked connection, and
            // the service still holds the word, so this looks like an app dropping the region.
            rig.fake.finishComposingText()
            rig.settle()
            assertEquals(label, SelectionReport.ECHO_REGION_GONE, rig.seen.last())
            rig.seen.clear()
            rig.composing = ""

            rig.editor.onShow(0, 0)
            assertEquals(label, caret + 4, rig.tracker.expStart)
            assertEquals(label, -1, rig.tracker.composingStart)
            rig.glide("hello")
            rig.settle()
            assertAllEchoes(rig, label)
        }
    }

    @Test
    fun `a re-show on a new connection takes the editor's offsets`() {
        val rig = Rig(text = "some text ")
        rig.typeWord("word")
        rig.id = rig.editor.onNewConnection()
        assertFalse("nothing known about the old field holds", rig.tracker.known)
        assertTrue(rig.editor.connectionIsNew)
        rig.editor.onShow(3, 3)
        assertEquals(3, rig.tracker.expStart)
        assertEquals(3, rig.editor.lastStart)
        assertEquals(-1, rig.tracker.composingStart)
    }

    @Test
    fun `a merged final report is one's own, and the word stays in step`() {
        val rig = Rig(echoDelayMs = 1_000, text = "so ")
        rig.typeWord("teh")
        rig.batch {
            rig.finish()
            rig.delete(3)
            rig.commit("the")
            rig.commit(" ")
        }
        rig.gap()
        rig.batch {
            rig.delete(4)
            rig.compose("teh")
        }
        rig.gap()
        rig.deliverMerged()
        assertAllEchoes(rig, "merged")
        assertEquals(3, rig.tracker.composingStart)
    }

    @Test
    fun `an echo slower than two seconds on a straight run still reads as one`() {
        val rig = Rig(echoDelayMs = 2_500)
        rig.typeWord("hello")
        rig.gap(2_600)
        assertAllEchoes(rig, "slow")
        assertEquals(0, rig.tracker.composingStart)
    }

    @Test
    fun `seventeen writes with every echo late are all one's own`() {
        val word = "abcdefghijklmnopq"
        val rig = Rig(echoDelayMs = 400)
        for (len in 1..word.length) {
            rig.compose(word.take(len))
            rig.gap(5)
        }
        rig.settle()
        assertAllEchoes(rig, "seventeen")
        assertTrue(rig.tracker.atRest(17, 17))
    }

    @Test
    fun `a length limit stops the echoes short and nothing reads as real`() {
        val rig = Rig(echoDelayMs = 50, maxLength = 5)
        rig.typeWord("abcdefgh")
        rig.settle()
        assertAllEchoes(rig, "limit")
        assertEquals("abcde", rig.fake.text)
        assertEquals("the tracker is ahead, as nothing reported what the filter took", 8, rig.tracker.expStart)
    }

    @Test
    fun `not known and self editing is an echo`() {
        val rig = Rig()
        rig.id = rig.editor.onNewConnection()
        assertEquals(
            SelectionReport.ECHO,
            rig.editor.onSelectionReported(-1, -1, -1, -1, -1, selfEditing = true, composingNow = false),
        )
    }

    @Test
    fun `not known and not self editing is real`() {
        val rig = Rig()
        rig.id = rig.editor.onNewConnection()
        assertEquals(
            SelectionReport.REAL,
            rig.editor.onSelectionReported(-1, -1, -1, -1, -1, selfEditing = false, composingNow = false),
        )
    }

    @Test
    fun `the old timer changes nothing once offsets are known`() {
        val rig = Rig(text = "some text")
        assertEquals(
            SelectionReport.REAL,
            rig.editor.onSelectionReported(9, 9, 2, 2, -1, selfEditing = true, composingNow = false),
        )
    }

    @Test
    fun `a tap with nothing in flight is real, and its report still shows the word composing`() {
        val rig = Rig(text = "some text ")
        rig.typeWord("word")
        rig.settle()
        rig.fake.tapMovesCaret(2)
        rig.gap()
        assertEquals(SelectionReport.REAL, rig.seen.last())
        assertEquals(2, rig.tracker.expStart)
        assertEquals("a tap moves only the selection", 10, rig.fake.regionStart)
        assertEquals("the region comes from the report", 10, rig.tracker.composingStart)

        // What the service then does: the caret left the region, so the word ends where it stands.
        rig.finish()
        rig.settle()
        assertEquals(SelectionReport.ECHO, rig.seen.last())
        assertEquals(-1, rig.tracker.composingStart)

        rig.compose("x")
        rig.settle()
        assertEquals("the next letter lands at the tap, with no pull back", "soxme text word", rig.fake.text)
        assertEquals(SelectionReport.ECHO, rig.seen.last())
        assertEquals(2, rig.fake.regionStart)
        assertEquals(2, rig.tracker.composingStart)
        assertTrue(rig.tracker.atRest(3, 3))
    }

    @Test
    fun `a tap just past a word still composing is real, however late the word's echoes are`() {
        for (delay in ECHO_DELAYS) {
            val label = "echoes at $delay ms"
            val rig = Rig(delay, "so more")
            rig.select(3)
            rig.settle()
            rig.typeWord("hel")
            rig.fake.tapMovesCaret(7)
            rig.settle()
            assertEquals(label, SelectionReport.REAL, rig.seen.last())
            assertEquals(label, 7, rig.tracker.expStart)
            assertEquals(label, 3, rig.tracker.composingStart)

            rig.finish()
            rig.compose("x")
            rig.settle()
            assertEquals(label, "so helmxore", rig.fake.text)
            assertEquals(label, SelectionReport.ECHO, rig.seen.last())
            assertEquals(label, 7, rig.tracker.composingStart)
            assertTrue(label, rig.tracker.atRest(8, 8))
        }
    }

    @Test
    fun `an app clearing its field with echoes in flight is real`() {
        val rig = Rig(echoDelayMs = 200)
        rig.typeWord("hello")
        rig.fake.appClearsField()
        rig.settle()
        assertEquals(SelectionReport.REAL, rig.seen.last())
        assertTrue(rig.tracker.atRest(0, 0))
    }

    @Test
    fun `a real move with the word alive keeps its region, so a tap one past it is seen`() {
        val rig = Rig(text = "so ")
        rig.typeWord("hel")
        rig.settle()
        // Text the keyboard did not write comes in before the word.
        val report = rig.editor.onSelectionReported(6, 6, 8, 8, 5, selfEditing = false, composingNow = true)
        assertEquals(SelectionReport.REAL, report)
        assertEquals(5, rig.tracker.composingStart)
        rig.compose("help")
        assertEquals("a letter lands at the real caret", 9, rig.tracker.expStart)
    }

    @Test
    fun `a write that returned false moves nothing`() {
        val rig = Rig(text = "some text ")
        val before = rig.tracker.expStart
        rig.fake.connected = false
        rig.commit("word")
        rig.compose("word")
        rig.delete(1)
        rig.select(1)
        assertEquals(before, rig.tracker.expStart)
        assertEquals(-1, rig.tracker.composingStart)
    }

    @Test
    fun `an old wrapper cannot feed the tracker`() {
        val rig = Rig(text = "some text ")
        val oldId = rig.id
        rig.id = rig.editor.onNewConnection()
        rig.editor.onShow(4, 4)
        rig.editor.commitText(oldId, 10) { true }
        rig.editor.setComposingText(oldId, 10) { true }
        rig.editor.deleteSurroundingText(oldId, 1) { true }
        rig.editor.setSelection(oldId, 1, 1) { true }
        rig.editor.beginBatchEdit(oldId) { true }
        rig.commit("x")
        assertEquals(5, rig.tracker.expStart)
        assertFalse("the old wrapper opened no batch, so this write is owed its echo now", rig.tracker.atRest(5, 5))
    }

    @Test
    fun `a new connection forgets the position, an open batch and what was owed`() {
        val rig = Rig(text = "some text ")
        rig.editor.beginBatchEdit(rig.id) { true }
        rig.commit("word")
        rig.id = rig.editor.onNewConnection()
        rig.editor.onShow(0, 0)
        rig.commit("x")
        assertFalse("the write landed outside any batch and is owed one echo", rig.tracker.atRest(1, 1))
        assertTrue(rig.tracker.isOwnEcho(0, 0, 1, 1))
        assertTrue(rig.tracker.atRest(1, 1))
    }

    @Test
    fun `reads and key events move nothing and only return what the editor said`() {
        val rig = Rig(text = "some text ")
        val before = rig.tracker.expStart
        assertTrue(rig.editor.unpredictable(rig.id) { true })
        assertFalse(rig.editor.unpredictable(rig.id) { false })
        assertEquals(before, rig.tracker.expStart)
    }

    @Test
    fun `the end of a batch counts whatever its result says`() {
        val rig = Rig(text = "so ")
        rig.editor.beginBatchEdit(rig.id) { true }
        rig.commit("x")
        // The documented false for "no batch is open any more".
        assertFalse(rig.editor.endBatchEdit(rig.id) { false })
        assertTrue(rig.tracker.isOwnEcho(3, 3, 4, 4))
        assertTrue(rig.tracker.atRest(4, 4))
    }

    @Test
    fun `onWrite runs after each tracked write when the position is known`() {
        val rig = Rig(text = "so ")
        var calls = 0
        rig.editor.onWrite = { calls++ }
        rig.commit("x")
        rig.compose("y")
        rig.delete(1)
        rig.finish()
        rig.select(0)
        assertEquals(5, calls)
        rig.fake.connected = false
        rig.commit("z")
        assertEquals("a write that failed", 5, calls)
        rig.fake.connected = true
        rig.id = rig.editor.onNewConnection()
        rig.commit("z")
        assertEquals("nothing known yet", 5, calls)
    }

    // --- The region going away with the caret where it should be ---

    @Test
    fun `an app dropping the region at rest is told apart`() {
        val rig = Rig(text = "so ")
        rig.typeWord("hel")
        rig.settle()
        rig.composing = "hel"
        rig.fake.appDropsRegion()
        rig.gap()
        assertEquals(SelectionReport.ECHO_REGION_GONE, rig.seen.last())
        assertTrue(rig.editor.regionStillGone())
    }

    @Test
    fun `no region ever seen says nothing about one going`() {
        val rig = Rig(text = "so ")
        rig.compose("h")
        // An editor that never reports a region.
        val report = rig.editor.onSelectionReported(3, 3, 4, 4, -1, selfEditing = false, composingNow = true)
        assertEquals(SelectionReport.ECHO, report)
    }

    @Test
    fun `no region with echoes still in flight is only an echo`() {
        val rig = Rig(echoDelayMs = 200, text = "so ")
        rig.typeWord("hel")
        rig.settle()
        rig.compose("help")
        rig.commit(" ")
        rig.compose("x")
        rig.settle()
        assertAllEchoes(rig, "in flight")
    }

    @Test
    fun `a newer write or a report with a region takes back that the region is gone`() {
        val rig = Rig(text = "so ")
        rig.typeWord("hel")
        rig.settle()
        rig.composing = "hel"
        rig.fake.appDropsRegion()
        rig.gap()
        assertTrue(rig.editor.regionStillGone())

        rig.compose("help")
        assertFalse("a write has gone out since", rig.editor.regionStillGone())
        rig.settle()
        assertFalse("and its echo has a region", rig.editor.regionStillGone())
    }

    // --- A quiet moment ---

    private fun Rig.quiet() = editor.afterQuiet(composing) { n -> fake.getTextBeforeCursor(n) }

    @Test
    fun `a text view's filter takes the region with what it cut, so Backspace works on plain text`() {
        val rig = Rig(maxLength = 5)
        rig.typeWord("abcdefgh")
        rig.settle()
        assertEquals("abcde", rig.fake.text)
        assertEquals("the region went with the cut", -1, rig.fake.regionStart)
        assertEquals(QuietCheck.LOST, rig.quiet())
        assertEquals(1, rig.fake.reads)
        assertEquals("the tracker is back on the editor's last word", 5, rig.tracker.expStart)
        assertEquals(-1, rig.tracker.composingStart)

        // What the service then does, and what Backspace then does with nothing composing.
        rig.finish()
        rig.batch {
            rig.delete(1)
            rig.delete(4)
            rig.compose("abcd")
        }
        rig.settle()
        assertEquals("abcd", rig.fake.text)
        assertEquals(0, rig.fake.regionStart)
        assertEquals(4, rig.tracker.expStart)
        assertEquals(0, rig.tracker.composingStart)

        // There is room again, so the next letter stays.
        rig.compose("abcdx")
        rig.settle()
        assertEquals("abcdx", rig.fake.text)
        assertTrue(rig.tracker.atRest(5, 5))
    }

    @Test
    fun `a read that shows only the start of a word still composing ends the word and shortens nothing`() {
        val rig = Rig()
        rig.typeWord("hell")
        rig.settle()
        rig.compose("hello")
        assertEquals(QuietCheck.LOST, rig.editor.afterQuiet("hello") { "hell" })
        assertEquals("the tracker is back on the editor's last word", 4, rig.tracker.expStart)
        assertEquals(0, rig.tracker.composingStart)

        // What the service then does. Ending the word loses no letter, where a shorter word would.
        rig.finish()
        assertEquals("hello", rig.fake.text)
        assertEquals(-1, rig.fake.regionStart)
        assertEquals(-1, rig.tracker.composingStart)
    }

    @Test
    fun `case does not count, because a filter can change it`() {
        val rig = Rig(text = "abc")
        rig.compose("de")
        assertEquals(QuietCheck.IN_STEP, rig.editor.afterQuiet("de") { "CDE" })
    }

    @Test
    fun `a slow editor changes nothing, because reads come after writes`() {
        val rig = Rig(echoDelayMs = 1_000)
        rig.typeWord("hello")
        val expected = rig.tracker.expStart
        assertEquals(QuietCheck.IN_STEP, rig.quiet())
        assertEquals(1, rig.fake.reads)
        assertEquals("the tracker is left where the writes put it", expected, rig.tracker.expStart)
        assertEquals(0, rig.tracker.composingStart)
        rig.gap(1_100)
        assertAllEchoes(rig, "slow editor")
        assertTrue(rig.tracker.atRest(5, 5))
    }

    @Test
    fun `no binder call when every echo has landed`() {
        val rig = Rig()
        rig.typeWord("hello")
        rig.settle()
        assertEquals(QuietCheck.IN_STEP, rig.quiet())
        assertEquals(0, rig.fake.reads)
    }

    @Test
    fun `no binder call with nothing composing`() {
        val rig = Rig(text = "abcdefg", maxLength = 7)
        rig.commit(" ")
        assertEquals(QuietCheck.IN_STEP, rig.quiet())
        assertEquals(0, rig.fake.reads)
    }

    @Test
    fun `a read that failed changes nothing`() {
        val rig = Rig(maxLength = 5)
        rig.typeWord("abcdefgh")
        rig.settle()
        rig.fake.connected = false
        assertEquals(QuietCheck.IN_STEP, rig.quiet())
        assertEquals(8, rig.tracker.expStart)
    }

    @Test
    fun `a whole word the filter dropped is lost, and the text stays`() {
        val rig = Rig(text = "abc def", maxLength = 7)
        rig.commit(" ")
        rig.gap()
        rig.compose("g")
        rig.gap()
        assertEquals(QuietCheck.LOST, rig.quiet())
        assertEquals("abc def", rig.fake.text)
        assertEquals("the tracker is back on the editor's last word", 7, rig.tracker.expStart)
    }

    @Test
    fun `text that is not the word is lost, whatever the lengths say`() {
        val rig = Rig(text = "abc")
        rig.compose("x")
        rig.settle()
        rig.compose("xy")
        assertEquals(QuietCheck.IN_STEP, rig.editor.afterQuiet("xy") { "cxy" })
        assertEquals(QuietCheck.LOST, rig.editor.afterQuiet("xy") { "qqq" })
    }

    @Test
    fun `a word dropped then Backspace lands on what is really there`() {
        val rig = Rig(maxLength = 7)
        rig.typeWord("abc")
        rig.space()
        rig.gap()
        rig.typeWord("def")
        rig.space()
        rig.gap()
        rig.typeWord("ghi")
        rig.space()
        rig.gap()
        assertEquals("abc def", rig.fake.text)
        assertEquals("the tracker is ahead, and no text tells", 12, rig.tracker.expStart)

        // Backspace reads the field, takes one letter and re-enters what is left of the word.
        val before = rig.fake.getTextBeforeCursor(48).toString()
        assertEquals("abc def", before)
        rig.batch {
            rig.delete(1)
            rig.delete(2)
            rig.compose("de")
        }
        rig.settle()
        assertEquals("abc de", rig.fake.text)
        assertEquals("the region's own report brings it back", SelectionReport.REAL, rig.seen.last())
        assertEquals(6, rig.tracker.expStart)
        assertEquals(4, rig.tracker.composingStart)
    }

    @Test
    fun `a failed write and a stale id leave the editor's result as it was`() {
        val rig = Rig()
        assertFalse(rig.editor.commitText(rig.id, 1) { false })
        assertTrue(rig.editor.commitText(rig.id + 9, 1) { true })
        assertNull(rig.fake.takeMerged())
    }

    private companion object {
        const val KEY_GAP_MS = 30L
        const val SETTLE_MS = 500L
        val ECHO_DELAYS = listOf(0L, 50L, 200L, 400L)
    }
}
