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
 * Priority is a little below the UI thread, so a prediction can never
 * out-schedule the thread servicing the next touch. It has to be set as a nice
 * value: every Java priority below normal maps to nice 10 or more, which Android
 * treats as a background thread with few CPU shares and a little core, and the
 * correction a Space press may be waiting on has no business there.
 */
object TypingDispatcher {

    /** Nice value for per-keystroke work. Still foreground scheduling. */
    private const val TYPING_NICE = 2

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(TYPING_NICE)
            r.run()
        }, "localboard-typing").apply { isDaemon = true }
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
     * Mid-glide preview decodes. Its own thread, not [io]: a correction walk or
     * a reload of the personal model runs there, and every preview queued behind
     * one would freeze the bar mid-word.
     */
    val glide: CoroutineDispatcher = glideExecutor.asCoroutineDispatcher()
}
