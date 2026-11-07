package com.localboard.keyboard.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The job comes round every few hours and does its work once a day, on a clock that can move. */
class NightlyRulesTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun noStamp_hasNotRun() {
        assertFalse(NightlyRules.ranRecently(null, now))
    }

    @Test
    fun aStampFromJustNow_hasRun() {
        assertTrue(NightlyRules.ranRecently(now, now))
    }

    @Test
    fun under20Hours_hasRun() {
        assertTrue(NightlyRules.ranRecently(now - 19 * hour, now))
        assertTrue(NightlyRules.ranRecently(now - 20 * hour + 1, now))
    }

    @Test
    fun from20Hours_isDueAgain() {
        assertFalse(NightlyRules.ranRecently(now - 20 * hour, now))
        assertFalse(NightlyRules.ranRecently(now - 21 * hour, now))
    }

    /** A clock put back would otherwise hold the job off for as long as it was out. */
    @Test
    fun aStampInTheFuture_saysNothing() {
        assertFalse(NightlyRules.ranRecently(now + 1, now))
        assertFalse(NightlyRules.ranRecently(now + 300 * hour, now))
    }

    @Test
    fun theQuickDefault_runsTheJobOnlyWhenSomeoneChoseIt() {
        val quick = ModelRegistry.QUICK_DEFAULT_ID
        assertFalse("nothing stored", NightlyRules.modelMayRun(quick, fileReady = true, storedActiveId = null))
        assertFalse("another one chosen", NightlyRules.modelMayRun(quick, fileReady = true, storedActiveId = "qwen3_17b"))
        assertTrue("chosen", NightlyRules.modelMayRun(quick, fileReady = true, storedActiveId = quick))
        assertFalse("chosen, with no file", NightlyRules.modelMayRun(quick, fileReady = false, storedActiveId = quick))
    }

    @Test
    fun anyOtherModel_runsTheJobWhenItsFileIsThere() {
        assertTrue(NightlyRules.modelMayRun("qwen3_17b", fileReady = true, storedActiveId = "qwen3_17b"))
        assertTrue(NightlyRules.modelMayRun("qwen3_17b", fileReady = true, storedActiveId = null))
        assertFalse(NightlyRules.modelMayRun("qwen3_17b", fileReady = false, storedActiveId = "qwen3_17b"))
    }
}
