package com.localboard.keyboard.engine.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** One JUnit test per check in [Scenarios], so the report counts them and names any that fail. */
@RunWith(Parameterized::class)
class CorrectionScenariosTest(private val check: Scenarios.Check) {
    @Test
    fun holds() = assertTrue(check.detail, check.ok)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun checks(): List<Scenarios.Check> = Scenarios().runAll()
    }
}

class ScenarioSuiteSizeTest {
    /** A check that silently stopped running would otherwise look like a pass. */
    @Test
    fun noCheckHasBeenLost() {
        val all = Scenarios().runAll()
        println("checks=${all.size} failures=${all.count { !it.ok }}")
        assertEquals(Scenarios.EXPECTED_CHECKS, all.size)
    }
}
