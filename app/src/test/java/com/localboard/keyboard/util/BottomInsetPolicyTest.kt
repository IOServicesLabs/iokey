package com.localboard.keyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomInsetPolicyTest {

    private class Row(
        val mode: Int,
        val display: Int?,
        val dispatch: Int?,
        val lastGood: Int?,
        val expected: Int,
        val meaning: String
    )

    @Test
    fun `resolves every combination that decides whether the keys clear the gesture bar`() {
        val table = listOf(
            Row(0, 68, null, null, 68, "cold seed"),
            Row(0, 68, 0, null, 68, "a cold or stale frame dispatch cannot lower"),
            Row(0, 68, 135, 68, 135, "a caption bar raises"),
            Row(0, 0, 68, null, 68, "display is blind, the dispatch sees it"),
            Row(0, 0, 0, 68, 0, "both say none and the query worked"),
            Row(0, null, 0, 68, 68, "the query threw and the dispatch said zero, so last good stands"),
            Row(0, null, null, null, 0, "nothing known"),
            Row(BottomInsetPolicy.MODE_RECLAIM, 68, 68, 68, 0, "the user gave the space back")
        )
        for (r in table) {
            assertEquals(r.meaning, r.expected, BottomInsetPolicy.resolve(r.mode, r.display, r.dispatch, r.lastGood))
        }
    }

    @Test
    fun `a dispatch can never lower the answer, whatever order the sources report in`() {
        val modes = listOf(0, 1, 2)
        val values = listOf(0, 40, 68, 135)
        for (mode in modes) for (display in values) for (dispatch in values) {
            val withDispatch = BottomInsetPolicy.resolve(mode, display, dispatch, null)
            val without = BottomInsetPolicy.resolve(mode, display, null, null)
            assertEquals("max of the live sources", maxOf(display, dispatch), withDispatch)
            assertTrue("$without then $withDispatch", withDispatch >= without)
        }
    }

    @Test
    fun `last good is only for a query that failed`() {
        assertEquals(68, BottomInsetPolicy.resolve(0, null, null, 68))
        assertEquals("a working query that says zero is believed", 0, BottomInsetPolicy.resolve(0, 0, null, 68))
        assertEquals("a live value beats a remembered one", 40, BottomInsetPolicy.resolve(0, null, 40, 68))
    }
}
