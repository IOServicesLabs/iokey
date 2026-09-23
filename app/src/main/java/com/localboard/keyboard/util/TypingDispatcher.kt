package com.localboard.keyboard.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The one background thread that per-keystroke work runs on.
 *
 * WHY NOT Dispatchers.Default
 * --------------------------
 * Default is a fork-join pool sized to the core count. Handing it a 2 ms
 * prediction on every keystroke lets the scheduler pick whichever worker is
 * free, which on a big.LITTLE SoC regularly means **waking a big core** — and
 * the wake-up plus the ramp costs more energy than the work itself. Typing at
 * 5 keys a second can keep several cores from ever reaching a deep idle state.
 *
 * One dedicated thread keeps that work on a single core that stays warm, lets
 * every other core sleep, and — because prediction work is strictly
 * latest-wins — loses nothing in throughput: there is never useful parallelism
 * between a keystroke's prediction and the next keystroke's, since the earlier
 * result is discarded the moment the newer one starts.
 *
 * Priority is set below default so a prediction can never out-schedule the UI
 * thread servicing the next touch. A keyboard that misses a touch to finish a
 * suggestion has made the wrong trade.
 */
object TypingDispatcher {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "localboard-typing").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 2
        }
    }

    /** Use in place of Dispatchers.Default for per-keystroke work. */
    val io: CoroutineDispatcher = executor.asCoroutineDispatcher()

    private val glideExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "localboard-glide").apply {
            isDaemon = true
            // Normal priority, unlike [io]: a live glide preview is the one
            // background result the user is actively watching for.
            priority = Thread.NORM_PRIORITY
        }
    }

    /**
     * Mid-glide preview decodes. Its own thread, not [io]: the quick-model
     * LLM lanes (phrase suggestion, LLM autocorrect) also run on [io] and a
     * native generate call there is hundreds of milliseconds during which
     * every queued preview would sit behind it — the bar froze mid-word
     * whenever the model happened to be busy.
     */
    val glide: CoroutineDispatcher = glideExecutor.asCoroutineDispatcher()
}
