package com.localboard.keyboard.llm

import com.localboard.keyboard.util.NightlyOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a nightly run that reached the model's turn ended, and what that lets it keep. */
class NightlyOutcomeTest {

    private fun outcome(
        stopped: Boolean = false,
        tooHot: Boolean = false,
        modelReady: Boolean = true,
        gotRewrite: Boolean = false,
    ) = NightlyRules.outcome(stopped, tooHot, modelReady, gotRewrite)

    @Test
    fun aFinishedRun_saysWhereItsNotesCameFrom() {
        assertEquals(NightlyOutcome.MODEL_REWRITE, outcome(gotRewrite = true))
        assertEquals(NightlyOutcome.MODEL_FALLBACK, outcome(gotRewrite = false))
        assertEquals(NightlyOutcome.NO_MODEL_FALLBACK, outcome(modelReady = false))
    }

    /** A cancellation falls into the fallback, so the notes alone cannot tell a stopped run from a finished one. */
    @Test
    fun aStop_beatsAFinishedRewrite() {
        assertEquals(NightlyOutcome.STOPPED, outcome(stopped = true, gotRewrite = true))
        assertEquals(NightlyOutcome.STOPPED, outcome(stopped = true, gotRewrite = false))
        assertEquals(NightlyOutcome.STOPPED, outcome(stopped = true, modelReady = false))
    }

    @Test
    fun aStop_beatsHeat() {
        assertEquals(NightlyOutcome.STOPPED, outcome(stopped = true, tooHot = true))
    }

    @Test
    fun heat_isOnlyAboutAModelThatWouldLoad() {
        assertEquals(NightlyOutcome.TOO_HOT, outcome(tooHot = true))
    }

    @Test
    fun aStoppedOrHotRun_savesNothing() {
        assertFalse(NightlyRules.saves(NightlyOutcome.STOPPED))
        assertFalse(NightlyRules.saves(NightlyOutcome.TOO_HOT))
    }

    @Test
    fun aFinishedRun_saves() {
        assertTrue(NightlyRules.saves(NightlyOutcome.MODEL_REWRITE))
        assertTrue(NightlyRules.saves(NightlyOutcome.MODEL_FALLBACK))
        assertTrue(NightlyRules.saves(NightlyOutcome.NO_MODEL_FALLBACK))
    }

    @Test
    fun aRoundThatEndedEarly_savesNothing() {
        for (early in listOf(
            NightlyOutcome.OFF, NightlyOutcome.NOT_CHARGING, NightlyOutcome.BATTERY_BELOW_90,
            NightlyOutcome.TOO_FEW_WORDS, NightlyOutcome.STARTED, NightlyOutcome.FAILED,
        )) {
            assertFalse(early.name, NightlyRules.saves(early))
        }
    }
}
