package com.localboard.keyboard.llm

import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftClockTest {
    private val clock = DraftClock(0L, setupLimitMs = 100, firstTokenLimitMs = 50, stallLimitMs = 10, slowAfterMs = 30)

    /** What a native call looks like from here: a blocked thread that nothing can interrupt. */
    private val nativeCallReturns = CountDownLatch(1)
    private val beside = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun letTheStuckThreadGo() {
        nativeCallReturns.countDown()
        beside.cancel()
    }

    private fun stuckNativeCall() = beside.async { nativeCallReturns.await() }

    private fun wallClock() = System.nanoTime() / 1_000_000

    @Test
    fun aLoadDoesNotUseUpTheTimeForReadingThePrompt() {
        assertFalse(clock.isOverDeadline(90))
        clock.enter(GenerateStage.READING, 90)
        assertFalse(clock.isOverDeadline(130))
        assertTrue(clock.isOverDeadline(141))
    }

    @Test
    fun waitingForAnEarlierRunHasTheLongLimit() {
        clock.enter(GenerateStage.WAITING, 0)
        assertFalse(clock.isOverDeadline(100))
        assertTrue(clock.isOverDeadline(101))
    }

    @Test
    fun everyTokenRestartsTheStallLimit() {
        clock.enter(GenerateStage.WRITING, 0)
        clock.enter(GenerateStage.WRITING, 9)
        assertFalse(clock.isOverDeadline(18))
        assertTrue(clock.isOverDeadline(20))
    }

    @Test
    fun aHealthyRunIsNeverCalledSlowOnceWriting() {
        clock.enter(GenerateStage.READING, 0)
        assertFalse(clock.isSlow(30))
        assertTrue(clock.isSlow(31))
        clock.enter(GenerateStage.WRITING, 31)
        assertFalse(clock.isSlow(100))
    }

    @Test(timeout = 20_000)
    fun theDeadlineComesWhileTheNativeCallIsStillStuck() = runBlocking<Unit> {
        val started = wallClock()
        val watched = DraftClock(started, setupLimitMs = 150)
        val work = stuckNativeCall()

        assertTrue(watched.runsPastDeadline(work, 10, ::wallClock))

        assertTrue("the call must still be stuck, or this proved nothing", work.isActive)
    }

    @Test(timeout = 20_000)
    fun droppingARunDoesNotWaitForTheNativeCall() = runBlocking<Unit> {
        val watched = DraftClock(wallClock())
        val work = stuckNativeCall()
        val waiting = launch(Dispatchers.Default) { watched.runsPastDeadline(work, 10, ::wallClock) }
        delay(50)

        waiting.cancelAndJoin()

        assertTrue("the wait must be over", waiting.isCancelled)
        assertTrue("the call must still be stuck, or this proved nothing", work.isActive)
    }

    @Test(timeout = 20_000)
    fun aRunThatEndsIsNotATimeout() = runBlocking<Unit> {
        val watched = DraftClock(wallClock())

        assertFalse(watched.runsPastDeadline(beside.async { "finished" }, 10, ::wallClock))
    }

    /** On a clock moved by hand, so a busy machine cannot change the answer. */
    @Test(timeout = 20_000)
    fun tokensKeepARunGoingAndSilenceEndsIt() = runBlocking<Unit> {
        var time = 0L
        var ticks = 0
        val watched = DraftClock(0L, stallLimitMs = 50)
        watched.enter(GenerateStage.WRITING, 0L)

        val timedOut = watched.runsPastDeadline(stuckNativeCall(), 1, { time }) {
            ticks++
            time += 10
            if (ticks <= 20) watched.enter(GenerateStage.WRITING, time)
        }

        assertTrue(timedOut)
        assertTrue("20 tokens, 200 ms in all, each inside the limit, ended after tick $ticks", ticks in 21..30)
    }

    @Test
    fun theLimitsReadAsThePhoneActuallyWorks() {
        // A cold 1000-token prompt on a big model was estimated at over two minutes, and the old
        // one-minute limit for everything, load included, could never let that finish.
        assertTrue(DraftClock.FIRST_TOKEN_LIMIT_MS >= 120_000L)
        assertTrue(DraftClock.SETUP_LIMIT_MS >= DraftClock.FIRST_TOKEN_LIMIT_MS)
        assertTrue(DraftClock.STALL_LIMIT_MS < DraftClock.FIRST_TOKEN_LIMIT_MS)
    }
}
