package com.localboard.keyboard.llm

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides when a draft has taken too long, by the stage it is in.
 *
 * Loading a model and waiting for an earlier run to let go of the engine can take a long time
 * and say nothing about this run, so they get their own generous limit. Reading the prompt is
 * the slow part of a healthy run on a phone. Once writing, a healthy model produces a token
 * every few seconds at worst, so a long silence means it has stopped.
 *
 * Times come from the caller, so the rules can be tested off the device. Stages are reported
 * from the decode thread and read from the main thread.
 */
class DraftClock(
    now: Long,
    private val setupLimitMs: Long = SETUP_LIMIT_MS,
    private val firstTokenLimitMs: Long = FIRST_TOKEN_LIMIT_MS,
    private val stallLimitMs: Long = STALL_LIMIT_MS,
    private val slowAfterMs: Long = SLOW_AFTER_MS
) {
    /** One value, because the two only mean something together: paired with the start of the
     *  stage before, the short writing limit would stop a healthy run at its first token. */
    private class Phase(val stage: GenerateStage, val since: Long)

    @Volatile
    private var phase = Phase(GenerateStage.LOADING, now)

    val stage: GenerateStage get() = phase.stage

    /** Also the way to report a token: each one restarts the stall limit. */
    fun enter(stage: GenerateStage, now: Long) {
        phase = Phase(stage, now)
    }

    fun isOverDeadline(now: Long): Boolean = phase.let { now - it.since > limitFor(it.stage) }

    /** Past the point where it is worth telling the user it has not stalled. */
    fun isSlow(now: Long): Boolean = phase.let { it.stage != GenerateStage.WRITING && now - it.since > slowAfterMs }

    /**
     * Waits for [work], looking at the clock every [tickMs]. True when the deadline came first,
     * with [work] possibly still running: this wait ends whatever [work] is doing, so [work] must
     * not be a child of the caller, whose own ending would wait for it. Native generation cannot
     * be interrupted, and a timeout around a child of its own just waits along with it.
     */
    suspend fun runsPastDeadline(
        work: Job,
        tickMs: Long,
        now: () -> Long,
        onTick: (Long) -> Unit = {}
    ): Boolean {
        while (withTimeoutOrNull(tickMs) { work.join() } == null) {
            val at = now()
            if (isOverDeadline(at)) return true
            onTick(at)
        }
        return false
    }

    /** The limit that applied at the moment of asking, for the diagnostics log. */
    fun limitMs(): Long = limitFor(stage)

    private fun limitFor(stage: GenerateStage): Long = when (stage) {
        GenerateStage.LOADING, GenerateStage.WAITING -> setupLimitMs
        GenerateStage.READING -> firstTokenLimitMs
        GenerateStage.WRITING -> stallLimitMs
    }

    companion object {
        const val SETUP_LIMIT_MS = 180_000L
        const val FIRST_TOKEN_LIMIT_MS = 120_000L
        const val STALL_LIMIT_MS = 20_000L
        const val SLOW_AFTER_MS = 30_000L
    }
}
