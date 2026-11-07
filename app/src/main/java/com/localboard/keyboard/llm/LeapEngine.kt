package com.localboard.keyboard.llm

import ai.liquid.leap.GenerationOptions
import ai.liquid.leap.LeapClient
import ai.liquid.leap.ModelLoadingOptions
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.message.ChatMessage
import ai.liquid.leap.message.GenerationStats
import ai.liquid.leap.message.MessageResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Liquid AI's LEAP runtime — the engine for LFM-family GGUFs.
 *
 * Why a second engine: llama.cpp runs every other model fine, but LFM
 * models ship with their vendor's chat template and manifest-tuned sampling
 * INSIDE the LEAP runtime. The Qwen3.5 investigation (lbgen5) proved the
 * model is only ever as good as its recipe, and the recipe's owner is the
 * vendor — so on the LEAP path we override NOTHING: all sampling fields
 * stay null (manifest defaults); only the token budget is set. The user
 * reports LFM drafts feel right on-device — this class is how they get
 * that exact behavior inside the keyboard.
 *
 * There is one of these per process, behind every LlamaCppProvider (the draft-lane
 * dispatcher), so the idle-unload watchdog and every call site (✨, test lab, nightly
 * distillation) treat it as the same "neural provider". One each would mean a model
 * loaded each, and one instance unloading what it knows of while another is running.
 *
 * Whoever loads or generates holds [lock] for the whole run, the same lock that orders
 * the llama.cpp decodes on the draft lane. That covers what can be seen from here: the
 * lock is let go when the collect exits, and whether the SDK's own native work has
 * stopped by then cannot be read from outside it.
 */
class LeapEngine private constructor() {

    /** Who runs next. Take it around [ensureLoaded] and [generate] together. */
    val lock: Mutex get() = LlamaCppBridge.slotDecodeLock(LlamaCppBridge.SLOT_DRAFT)

    @Volatile
    var loadedPath: String? = null
        private set

    /** Written and read under [lock]. */
    private var runner: ModelRunner? = null

    /** An unload that a run stood in the way of: why it was wanted, until that run ends. */
    private val releaseWanted = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** [readingMs] runs up to the first token, and is all of [wallMs] when none came. */
    data class Result(val text: String, val stats: GenerationStats?, val wallMs: Long, val readingMs: Long)

    fun isLoaded(path: String): Boolean = loadedPath == path

    /** [lock] must be held. */
    suspend fun ensureLoaded(path: String, ctx: Int, threads: Int) {
        if (loadedPath == path && runner != null) return
        releaseLocked("model switch")
        // Multi-second load + worker threads must never compete with
        // typing — same background-priority rule as the llama lanes.
        val r = try {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND
            )
            LeapClient.loadModel(
                path,
                ModelLoadingOptions(cpuThreads = threads, contextSize = ctx)
            )
        } finally {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_DEFAULT
            )
        }
        runner = r
        loadedPath = path
        AiDiagLog.lastLoadEvent =
            "LFM loaded via LEAP (ctx $ctx, threads $threads, manifest sampling)"
    }

    /**
     * One FRESH conversation per draft — shared history between drafts is
     * exactly the cross-contamination 1.6.x spent three releases removing.
     * LEAP's own prefix cache still makes the repeated system prompt cheap.
     * [lock] must be held.
     */
    suspend fun generate(
        system: String,
        user: String,
        maxTokens: Int,
        onToken: (String) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        val r = runner ?: throw IllegalStateException("LEAP model not loaded")
        // Decode at background priority, same rule as the load path: the
        // runner's worker threads must never compete with typing.
        android.os.Process.setThreadPriority(
            android.os.Process.THREAD_PRIORITY_BACKGROUND
        )
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            val conv = r.createConversation(system)
            val sb = StringBuilder()
            var stats: GenerationStats? = null
            var failure: Throwable? = null
            var firstTokenAt = 0L
            conv.generateResponse(
                ChatMessage(ChatMessage.Role.USER, user),
                GenerationOptions(maxTokens = maxTokens)
            ).collect { resp ->
                when (resp) {
                    is MessageResponse.Chunk -> {
                        if (firstTokenAt == 0L) firstTokenAt = android.os.SystemClock.elapsedRealtime()
                        sb.append(resp.text)
                        onToken(resp.text)
                    }
                    is MessageResponse.Complete -> stats = resp.stats
                    is MessageResponse.Error ->
                        failure = resp.throwable
                            ?: RuntimeException(resp.message ?: "LEAP generation error")
                    else -> { /* ReasoningChunk / FunctionCalls / audio — nothing to stream */ }
                }
            }
            failure?.let { throw it }
            val now = android.os.SystemClock.elapsedRealtime()
            Result(sb.toString(), stats, now - t0, (if (firstTokenAt == 0L) now else firstTokenAt) - t0)
        } finally {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_DEFAULT
            )
        }
    }

    suspend fun release(reason: String) {
        if (loadedPath == null) return
        if (!lock.tryLock()) {
            // Same rule as the native lanes: never unload under a run. This one
            // goes when that run ends, see [releaseIfWanted].
            releaseWanted.set(reason)
            AiDiagLog.lastLoadEvent = "leap unload skipped ($reason) — gen in flight"
            return
        }
        try {
            releaseLocked(reason)
        } finally {
            lock.unlock()
        }
    }

    /** Someone came back for the model, so an unload put off until now is not wanted any more. */
    fun forgetRelease() = releaseWanted.set(null)

    /** A run has ended. Does the unload it stood in the way of, if nobody came back since. */
    suspend fun releaseIfWanted() {
        val reason = releaseWanted.getAndSet(null) ?: return
        release(reason)
    }

    private suspend fun releaseLocked(reason: String) {
        val r = runner ?: return
        try { r.unload() } catch (_: Throwable) { }
        runner = null
        loadedPath = null
        AiDiagLog.lastLoadEvent = "LFM unloaded ($reason) — will reload on next use"
    }

    companion object {
        val shared = LeapEngine()

        /** LFM-family models route to LEAP. Matched on the file name so
         *  catalog entries AND user imports land here automatically. */
        fun handles(spec: ModelSpec): Boolean =
            spec.fileName.contains("lfm", ignoreCase = true)
    }
}
