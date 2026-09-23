package com.localboard.keyboard.llm

/** Thin JNI bridge to the statically-linked llama.cpp library. */
object LlamaCppBridge {

    private var libraryLoaded = false

    @Synchronized
    fun ensureLibraryLoaded(): Boolean {
        if (libraryLoaded) return true
        return try {
            System.loadLibrary("localboard_llm")
            libraryLoaded = true
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun interface TokenCallback {
        fun onToken(piece: String)
    }

    /** Two resident model slots: [SLOT_DRAFT] for the big drafting model,
     *  [SLOT_QUICK] for the tiny always-warm autocorrect model. */
    const val SLOT_DRAFT = 0
    const val SLOT_QUICK = 1

    private const val SLOT_COUNT = SLOT_QUICK + 1

    /** Process-WIDE per-slot state. The native slots are process globals, but
     *  several LlamaCppProvider instances can exist at once (keyboard service,
     *  test lab, settings, nightly distill worker). Per-instance flags let one
     *  instance nativeFree a slot another instance was mid-decode on — a
     *  native use-after-free. All lane loaded/generating state lives here. */
    private val slotGenerating =
        Array(SLOT_COUNT) { java.util.concurrent.atomic.AtomicBoolean(false) }
    private val slotLoadedPath =
        java.util.concurrent.atomic.AtomicReferenceArray<String?>(SLOT_COUNT)
    private val slotLoadedCtx =
        java.util.concurrent.atomic.AtomicIntegerArray(SLOT_COUNT)
    private val slotMonitors = Array(SLOT_COUNT) { Any() }

    /** Serializes "decode starts" vs "check + free" on a slot across ALL
     *  provider instances: take this monitor around setting the generating
     *  flag and around the generating-check + nativeFree pair. */
    fun slotMonitor(slot: Int): Any = slotMonitors[slot]

    private val slotDecodeLocks = Array(SLOT_COUNT) { kotlinx.coroutines.sync.Mutex() }

    /** Serialises native DECODES on a slot across all provider instances:
     *  hold it around nativeGenerate / nativePredictWords. Two decodes on
     *  one llama_context at the same time is a native crash, and the
     *  keyboard service, settings, test lab and nightly worker each own a
     *  provider instance that can reach the same slot. */
    fun slotDecodeLock(slot: Int): kotlinx.coroutines.sync.Mutex = slotDecodeLocks[slot]

    fun isGenerating(slot: Int): Boolean = slotGenerating[slot].get()
    fun setGenerating(slot: Int, v: Boolean) { slotGenerating[slot].set(v) }
    fun loadedPath(slot: Int): String? = slotLoadedPath.get(slot)
    fun loadedCtx(slot: Int): Int = slotLoadedCtx.get(slot)
    fun setLoadedPath(slot: Int, path: String?) { slotLoadedPath.set(slot, path) }
    fun setLoadedCtx(slot: Int, ctx: Int) { slotLoadedCtx.set(slot, ctx) }

    external fun nativeLoad(path: String, nThreads: Int, nCtx: Int, slot: Int): Boolean
    external fun nativeIsLoaded(slot: Int): Boolean
    /** The prompt arrives pre-split: [staticPrompt] (system + few-shot —
     *  byte-identical across runs, state-snapshotted natively) and
     *  [dynamicPrompt] (the conversation tail, decoded fresh each run). */
    external fun nativeGenerate(
        staticPrompt: String,
        dynamicPrompt: String,
        maxTokens: Int,
        temperature: Float,
        seed: Int,
        topK: Int,
        topP: Float,
        repeatPen: Float,
        presencePen: Float,
        freqPen: Float,
        slot: Int,
        callback: TokenCallback?
    ): String
    external fun nativeCancel()
    external fun nativeFree(slot: Int)

    /**
     * Next-word prediction with an incremental context (lbg_predict_v1).
     * [text] is the message so far, WITHOUT a trailing space — BPE attaches
     * the space to the following word, so the prediction comes back as
     * " you" and a trailing space in the prompt would tokenize separately
     * and skew the distribution. Returns up to [maxWords] lines of
     * `word<TAB>probability`, best first; empty when the slot is idle, the
     * text is empty, or nothing word-shaped is likely.
     *
     * The tokens of [text] stay in the slot's KV cache between calls, so a
     * call after one more word decodes only that word. Any nativeGenerate
     * on the slot discards that cache (the next call re-decodes in full).
     */
    external fun nativePredictWords(text: String, maxWords: Int, slot: Int): String
    /** Drop the incremental prediction context for [slot]. */
    external fun nativePredictReset(slot: Int)

    /** [promptTokens, reusedTokens, promptMs, genTokens, genMs, finishCode]
     *  of the most recent nativeGenerate call. */
    external fun nativeLastStats(): LongArray
}
