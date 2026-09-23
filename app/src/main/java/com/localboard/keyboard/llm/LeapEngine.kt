package com.localboard.keyboard.llm

import ai.liquid.leap.GenerationOptions
import ai.liquid.leap.LeapClient
import ai.liquid.leap.ModelLoadingOptions
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.message.ChatMessage
import ai.liquid.leap.message.GenerationStats
import ai.liquid.leap.message.MessageResponse
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * One instance lives inside LlamaCppProvider (the draft-lane dispatcher),
 * so the idle-unload watchdog and every call site (✨, test lab, nightly
 * distillation) treat it as the same "neural provider".
 */
class LeapEngine(private val context: Context) {

    @Volatile
    var loadedPath: String? = null
        private set

    /** A generation is streaming right now — never unload under it. */
    @Volatile
    var generating = false
        private set

    private var runner: ModelRunner? = null
    private val mutex = Mutex()

    data class Result(val text: String, val stats: GenerationStats?, val wallMs: Long)

    suspend fun ensureLoaded(path: String, ctx: Int, threads: Int) {
        if (loadedPath == path && runner != null) return
        mutex.withLock {
            if (loadedPath == path && runner != null) return@withLock
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
    }

    /**
     * One FRESH conversation per draft — shared history between drafts is
     * exactly the cross-contamination 1.6.x spent three releases removing.
     * LEAP's own prefix cache still makes the repeated system prompt cheap.
     */
    suspend fun generate(
        system: String,
        user: String,
        maxTokens: Int,
        onToken: (String) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        val r = runner ?: throw IllegalStateException("LEAP model not loaded")
        generating = true
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
            conv.generateResponse(
                ChatMessage(ChatMessage.Role.USER, user),
                GenerationOptions(maxTokens = maxTokens)
            ).collect { resp ->
                when (resp) {
                    is MessageResponse.Chunk -> {
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
            Result(sb.toString(), stats, android.os.SystemClock.elapsedRealtime() - t0)
        } finally {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_DEFAULT
            )
            generating = false
        }
    }

    suspend fun release(reason: String) {
        if (loadedPath == null) return
        if (generating) {
            // Same rule as the native lanes: freeing under a running decode
            // is a use-after-free — skip; the next idle sweep gets it.
            AiDiagLog.lastLoadEvent = "leap unload skipped ($reason) — gen in flight"
            return
        }
        mutex.withLock { releaseLocked(reason) }
    }

    private suspend fun releaseLocked(reason: String) {
        val r = runner ?: return
        try { r.unload() } catch (_: Throwable) { }
        runner = null
        loadedPath = null
        AiDiagLog.lastLoadEvent = "LFM unloaded ($reason) — will reload on next use"
    }

    companion object {
        /** LFM-family models route to LEAP. Matched on the file name so
         *  catalog entries AND user imports land here automatically. */
        fun handles(spec: ModelSpec): Boolean =
            spec.fileName.contains("lfm", ignoreCase = true)
    }
}
