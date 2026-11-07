package com.localboard.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTrackerTest {

    private fun at(start: Int, end: Int = start) = EditorTracker().apply { reset(start, end) }

    private fun assertState(t: EditorTracker, start: Int, end: Int, composing: Int) {
        assertEquals("expected start", start, t.expStart)
        assertEquals("expected end", end, t.expEnd)
        assertEquals("composing start", composing, t.composingStart)
    }

    @Test
    fun `composing replaces the region, so each pass is measured from where it started`() {
        val t = at(10)
        t.onSetComposing(1)
        assertState(t, 11, 11, 10)
        t.onSetComposing(2)
        assertState(t, 12, 12, 10)
        t.onSetComposing(5)
        assertState(t, 15, 15, 10)
    }

    @Test
    fun `composing replaces a selection`() {
        val t = at(4, 9)
        t.onSetComposing(2)
        assertState(t, 6, 6, 4)
    }

    @Test
    fun `an empty composing text ends the region`() {
        val t = at(10)
        t.onSetComposing(3)
        t.onSetComposing(0)
        assertState(t, 10, 10, -1)
    }

    @Test
    fun `a commit replaces the composing region and ends it`() {
        val t = at(10)
        t.onSetComposing(3)
        t.onCommit(6)
        assertState(t, 16, 16, -1)
    }

    @Test
    fun `a commit with nothing composing goes in at the selection`() {
        val t = at(4, 9)
        t.onCommit(1)
        assertState(t, 5, 5, -1)
    }

    @Test
    fun `finishing the composing region leaves the cursor where it is`() {
        val t = at(10)
        t.onSetComposing(3)
        t.onFinishComposing()
        assertState(t, 13, 13, -1)
    }

    @Test
    fun `delete before takes from the cursor when nothing is composing`() {
        val t = at(10)
        t.onDeleteBefore(3)
        assertState(t, 7, 7, -1)
    }

    @Test
    fun `delete before takes text before the composing region and moves it`() {
        val t = at(10)
        t.onSetComposing(3)
        t.onDeleteBefore(2)
        assertState(t, 11, 11, 8)
    }

    @Test
    fun `delete before stops at the start of the field`() {
        val t = at(2)
        t.onDeleteBefore(5)
        assertState(t, 0, 0, -1)
    }

    @Test
    fun `delete before with a selection keeps the selection`() {
        val t = at(5, 8)
        t.onDeleteBefore(2)
        assertState(t, 3, 6, -1)
    }

    @Test
    fun `set selection is taken at its word and leaves composing alone`() {
        val t = at(10)
        t.onSetComposing(2)
        t.onSetSelection(3, 4)
        assertState(t, 3, 4, 10)
    }

    @Test
    fun `reset forgets composing, because what the editor reports overrides any expectation`() {
        val t = at(10)
        t.onSetComposing(2)
        t.reset(20, 22)
        assertState(t, 20, 22, -1)
    }

    @Test
    fun `an editor that reports no offsets is left unknown and edits do nothing`() {
        val t = EditorTracker()
        assertFalse(t.known)
        t.onSetComposing(3)
        t.onCommit(3)
        t.onDeleteBefore(1)
        assertFalse(t.known)
        assertState(t, -1, -1, -1)

        t.reset(-1, -1)
        t.onCommit(3)
        assertFalse("a reset to the editor's -1 is still unknown", t.known)
    }

    @Test
    fun `the echo of a single edit is belated`() {
        val t = at(10)
        t.onCommit(5)
        assertTrue(t.isBelated(10, 10, 15, 15))
    }

    @Test
    fun `a burst of edits is belated at every step of its way, in order`() {
        val t = at(10)
        for (len in 1..5) t.onSetComposing(len)
        assertTrue("first echo", t.isBelated(10, 10, 11, 11))
        assertTrue("second echo", t.isBelated(11, 11, 12, 12))
        assertTrue("third echo", t.isBelated(12, 12, 13, 13))
        assertTrue("fourth echo", t.isBelated(13, 13, 14, 14))
        assertTrue("last echo", t.isBelated(14, 14, 15, 15))
    }

    @Test
    fun `a burst of deletes is belated on its way down`() {
        val t = at(10)
        repeat(3) { t.onDeleteBefore(1) }
        assertTrue(t.isBelated(10, 10, 9, 9))
        assertTrue(t.isBelated(9, 9, 8, 8))
        assertTrue(t.isBelated(8, 8, 7, 7))
    }

    @Test
    fun `a cursor moved after everything landed is real`() {
        val t = at(15)
        assertFalse("tapped earlier in the text", t.isBelated(15, 15, 3, 3))
        assertFalse("tapped later in the text", t.isBelated(15, 15, 40, 40))
    }

    @Test
    fun `a cursor beyond where the edits are heading is real`() {
        val t = at(10)
        t.onCommit(5)
        assertFalse("past the expected end", t.isBelated(10, 10, 16, 16))
        assertFalse("behind the old position", t.isBelated(10, 10, 9, 9))
    }

    @Test
    fun `a selection the keyboard did not make is real`() {
        val t = at(10)
        t.onCommit(5)
        assertFalse(t.isBelated(10, 10, 11, 14))
    }

    @Test
    fun `a selection the keyboard made itself is its own echo`() {
        val t = at(10)
        t.onSetSelection(3, 7)
        assertTrue(t.isBelated(10, 10, 3, 7))
    }

    @Test
    fun `an update that moves nothing where it was expected to stay is belated`() {
        val t = at(15)
        assertTrue(t.isBelated(15, 15, 15, 15))
    }

    @Test
    fun `a field cleared by the app while the keyboard believes in a word is real`() {
        val t = at(0)
        for (len in 1..5) t.onSetComposing(len)
        for (len in 1..5) assertTrue(t.isBelated(len - 1, len - 1, len, len))
        assertFalse("the send button emptied the field", t.isBelated(5, 5, 0, 0))
    }

    @Test
    fun `an editor that truncates leaves the tracker ahead until the next real move`() {
        val t = at(0)
        for (len in 1..5) t.onSetComposing(len)
        // A length filter of 3: the editor stops there and never reports the rest.
        assertTrue("reads as an echo still on its way", t.isBelated(2, 2, 3, 3))
        assertEquals("so nothing has noticed yet", 5, t.expStart)
        assertFalse("a tap is still seen for what it is", t.isBelated(3, 3, 1, 1))
    }

    @Test
    fun `echoes are all belated however many edits they lag behind`() {
        val edits = listOf<(EditorTracker) -> Unit>(
            { it.onSetComposing(1) },
            { it.onSetComposing(2) },
            { it.onSetComposing(3) },
            { it.onCommit(4) },
            { it.onSetComposing(1) },
            { it.onSetComposing(2) },
            { it.onCommit(3) }
        )
        for (lag in 0..edits.size) {
            val t = at(10)
            val echoes = ArrayDeque<Int>()
            var reported = 10
            fun deliver() {
                val next = echoes.removeFirst()
                assertTrue("lag $lag, $reported to $next", t.isBelated(reported, reported, next, next))
                reported = next
            }
            for (edit in edits) {
                edit(t)
                echoes.addLast(t.expStart)
                if (echoes.size > lag) deliver()
            }
            while (echoes.isNotEmpty()) deliver()
            assertEquals("lag $lag", t.expStart, reported)
        }
    }

    @Test
    fun `an echo running against the latest edit is still known for an echo`() {
        // A backspace right after a letter, with the letter's echo not back yet. The final
        // expectation alone cannot tell this from something else moving the cursor, and a
        // real move drops composing, so the positions the writes left behind answer first.
        val t = at(10)
        for (len in 1..3) t.onSetComposing(len)
        t.onSetComposing(2)
        assertTrue(t.isOwnEcho(10, 10, 11, 11))
        assertTrue(t.isOwnEcho(11, 11, 12, 12))

        assertTrue("the letter's echo lands after the backspace", t.isOwnEcho(12, 12, 13, 13))
        assertTrue("then the backspace's", t.isOwnEcho(13, 13, 12, 12))
        assertEquals("the position ends up right", 12, t.expStart)
        assertEquals("and so does the composing region", 10, t.composingStart)
        assertTrue(t.atRest(12, 12))
    }

    /** Clock for the rows about age. */
    private class Clock(var now: Long = 0L)

    /** Every position in [positions], in order, starting from [from], is this keyboard's own. */
    private fun assertOwnEchoes(t: EditorTracker, from: Int, vararg positions: Int) {
        var reported = from
        for (next in positions) {
            assertTrue("$reported to $next", t.isOwnEcho(reported, reported, next, next))
            reported = next
        }
    }

    @Test
    fun `an alternate picked three times is its own all the way`() {
        val t = at(10)
        t.onCommit(5)
        t.onFinishComposing()
        t.onCommit(1)
        for ((old, new) in listOf(5 to 4, 4 to 6, 6 to 3)) {
            t.onDeleteBefore(old + 1)
            t.onCommit(new)
            t.onFinishComposing()
            t.onCommit(1)
        }
        assertOwnEchoes(t, 10, 15, 16, 10, 14, 15, 10, 16, 17, 10, 13, 14)
        assertTrue(t.atRest(14, 14))
    }

    @Test
    fun `double space twice is its own all the way`() {
        val t = at(13)
        t.onDeleteBefore(1)
        t.onCommit(2)
        t.onCommit(1)
        t.onDeleteBefore(1)
        t.onCommit(2)
        assertOwnEchoes(t, 13, 12, 14, 15, 14, 16)
        assertTrue(t.atRest(16, 16))
    }

    @Test
    fun `a snippet undone twice is its own all the way`() {
        val t = at(10)
        repeat(2) {
            for (len in 1..3) t.onSetComposing(len)
            t.beginBatch()
            t.onSetComposing(13)
            t.onFinishComposing()
            t.endBatch()
            t.onDeleteBefore(13)
            t.onSetComposing(3)
            t.onSetComposing(0)
        }
        assertOwnEchoes(t, 10, 11, 12, 13, 23, 10, 13, 10, 11, 12, 13, 23, 10, 13, 10)
        assertTrue(t.atRest(10, 10))
    }

    @Test
    fun `a shorter word picked from the bar twice is its own all the way`() {
        val t = at(10)
        for (len in 1..5) t.onSetComposing(len)
        t.onSetComposing(4)
        t.onFinishComposing()
        t.onCommit(1)
        for (len in 1..5) t.onSetComposing(len)
        t.onSetComposing(4)
        t.onFinishComposing()
        t.onCommit(1)
        assertOwnEchoes(t, 10, 11, 12, 13, 14, 15, 14, 15, 16, 17, 18, 19, 20, 19, 20)
        assertTrue(t.atRest(20, 20))
    }

    @Test
    fun `undo after a slow echo keeps the region`() {
        val t = at(10)
        for (len in 1..3) t.onSetComposing(len)
        t.beginBatch()
        t.onFinishComposing()
        t.onDeleteBefore(3)
        t.onCommit(3)
        t.onCommit(1)
        t.endBatch()
        t.beginBatch()
        t.onDeleteBefore(4)
        t.onSetComposing(3)
        t.endBatch()
        t.onSetComposing(4)
        assertOwnEchoes(t, 10, 11, 12, 13, 14, 13, 14)
        assertEquals(10, t.composingStart)
        assertTrue(t.atRest(14, 14))
    }

    @Test
    fun `re-entry after Space with a slow echo keeps the region`() {
        val t = at(10)
        for (len in 1..4) t.onSetComposing(len)
        t.beginBatch()
        t.onFinishComposing()
        t.onCommit(1)
        t.endBatch()
        t.beginBatch()
        t.onDeleteBefore(1)
        t.onDeleteBefore(4)
        t.onSetComposing(4)
        t.endBatch()
        assertOwnEchoes(t, 10, 11, 12, 13, 14, 15, 14)
        assertEquals(10, t.composingStart)
        assertTrue(t.atRest(14, 14))
    }

    @Test
    fun `a re-show on one connection keeps the position and the echoes owed`() {
        val t = at(50)
        t.onSetComposing(3)
        // The show ends the region and any batch, and the first offsets are not asked.
        t.onFinishComposing()
        t.clearBatch()
        assertEquals(53, t.expStart)
        t.onCommit(5)
        t.onCommit(1)
        assertOwnEchoes(t, 50, 53, 58, 59)
        assertTrue(t.atRest(59, 59))
    }

    @Test
    fun `a merged final report is its own, and what it left behind ages out`() {
        val clock = Clock()
        val t = EditorTracker { clock.now }
        t.reset(10, 10)
        t.onCommit(5)
        t.onCommit(1)
        t.onDeleteBefore(6)
        t.onCommit(4)
        t.onCommit(1)
        assertTrue("one report for all five", t.isOwnEcho(10, 10, 15, 15))
        assertFalse("the entries after the first match are still owed", t.atRest(15, 15))
        clock.now = 2_001
        assertTrue(t.atRest(15, 15))
    }

    @Test
    fun `a position older than two seconds no longer answers`() {
        val clock = Clock()
        val t = EditorTracker { clock.now }
        t.reset(20, 20)
        t.onDeleteBefore(5)
        t.onCommit(2)
        assertTrue("while it is fresh, even a tap there reads as an echo", t.isOwnEcho(20, 20, 15, 15))

        val u = EditorTracker { clock.now }
        clock.now = 0
        u.reset(20, 20)
        u.onDeleteBefore(5)
        u.onCommit(2)
        clock.now = 2_001
        assertFalse("past that a tap is a tap", u.isOwnEcho(17, 17, 15, 15))
    }

    @Test
    fun `seventeen writes keep sixteen positions and the first still reads as an echo`() {
        val t = at(0)
        for (len in 1..17) t.onSetComposing(len)
        val all = IntArray(17) { it + 1 }
        assertOwnEchoes(t, 0, *all)
        assertTrue(t.atRest(17, 17))
    }

    @Test
    fun `a length limit stops the echoes short, and a tap is still real`() {
        val t = at(0)
        for (len in 1..5) t.onSetComposing(len)
        assertOwnEchoes(t, 0, 1, 2, 3)
        assertEquals("nothing reports what the filter took", 5, t.expStart)
        assertFalse(t.atRest(3, 3))
        assertFalse("a tap", t.isOwnEcho(3, 3, 1, 1))
    }

    @Test
    fun `a reset keeps a region that is still alive, so the next letter is measured from it`() {
        val t = at(10)
        t.onSetComposing(3)
        t.reset(13, 13, 10)
        t.onSetComposing(4)
        assertEquals("the real caret", 14, t.expStart)
        assertEquals(10, t.composingStart)
    }

    @Test
    fun `a batch owes one echo, at its outer end`() {
        val t = at(10)
        t.beginBatch()
        t.onCommit(3)
        t.beginBatch()
        t.onCommit(1)
        t.endBatch()
        assertTrue("nothing is owed until the outer end", t.atRest(14, 14))
        t.endBatch()
        assertFalse(t.atRest(14, 14))
        assertTrue(t.isOwnEcho(10, 10, 14, 14))
        assertTrue(t.atRest(14, 14))
    }

    @Test
    fun `a batch that only finishes composing owes nothing`() {
        val t = at(10)
        t.onSetComposing(3)
        assertOwnEchoes(t, 10, 13)
        t.beginBatch()
        t.onFinishComposing()
        t.endBatch()
        assertTrue(t.atRest(13, 13))
    }

    @Test
    fun `finishing composing owes nothing, and its report is an echo`() {
        val t = at(10)
        t.onSetComposing(3)
        assertOwnEchoes(t, 10, 13)
        t.onFinishComposing()
        assertTrue(t.atRest(13, 13))
        assertTrue(t.isOwnEcho(13, 13, 13, 13))
    }

    @Test
    fun `deleting at the start of the field owes nothing`() {
        val t = at(0)
        t.onDeleteBefore(1)
        assertTrue(t.atRest(0, 0))
    }

    @Test
    fun `a reset forgets what was owed`() {
        val t = at(10)
        t.onSetComposing(3)
        t.reset(4, 4)
        assertTrue(t.atRest(4, 4))
    }

    @Test
    fun `the common loop of an echo for each edit never needs a reset`() {
        val t = at(0)
        var cursor = 0
        for (word in listOf("hello", "world", "again")) {
            for (len in 1..word.length) {
                t.onSetComposing(len)
                val next = t.expStart
                assertTrue(t.isBelated(cursor, cursor, next, next))
                cursor = next
            }
            t.onCommit(word.length + 1)
            val next = t.expStart
            assertTrue(t.isBelated(cursor, cursor, next, next))
            cursor = next
        }
        assertEquals("hello world again ".length, cursor)
    }
}
