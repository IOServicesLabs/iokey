package com.localboard.keyboard.llm

import com.localboard.keyboard.context.StyleProfile

data class GenerateRequest(
    /** Text immediately before the cursor (the drafting context). */
    val contextBefore: String,
    /** The writer's distilled style, if any. */
    val styleProfile: StyleProfile?,
    val maxWords: Int = 40,
    /** 0.2 = safe/predictable, 1.0 = adventurous */
    val temperature: Float = 0.6f,
    /** Text after the cursor (subject line, quoted message, following draft). */
    val contextAfter: String? = null,
    /** Per-app tone hint ("casual text message", "polished email", ...). */
    val appTone: String? = null,
    /** User-written style instructions; overrides the auto-distilled style. */
    val styleInstructions: String? = null,
    /** Quick-prompt instruction ("reply that I'm running late"). When set, the
     *  model follows the instruction using the context instead of continuing freely. */
    val instruction: String? = null,
    /** Nightly-distilled "who this person is" notes from the private typing
     *  log; blended into the system prompt alongside the style block. */
    val personalityNotes: String? = null,
    /** Nightly-counted subjects this person returns to and the conversations
     *  they write in. Separate from [personalityNotes] because it answers a
     *  different question — what they talk about, not how — and because the
     *  two are trimmed independently when the prompt budget is tight. */
    val personaNotes: String? = null,
    /** Real recent messages by this person (from the on-device typing log),
     *  shown to the model as voice examples. This is what makes drafts
     *  personal: a style profile says "casual, short"; an actual "lol yeah
     *  omw" SHOWS it. Never leaves the device. */
    val voiceExamples: List<String>? = null,
    /** MESSAGE outputs get echo-stripped, scaffolding-cut and word-capped.
     *  PROFILE outputs (style distillation) must NOT be mangled by the
     *  message sanitizer, and their context is the SAMPLE itself — never
     *  shrink it away to fit the prompt. */
    val purpose: Purpose = Purpose.MESSAGE,
    /** The text a rewrite replaces. It reaches the prompt whole, or the run is refused with
     *  [PromptTooLongException]: on success the field loses this text, so a model that only saw
     *  part of it would lose the rest. Set, [instruction] says how to rewrite it. */
    val rewriteSource: String? = null,
    /** Someone is watching this run, as opposed to a next-word pass or the nightly worker. */
    val userWaiting: Boolean = false,
    /** The field asked not to be remembered, so this prompt is not kept for the AI diagnostics. */
    val doNotRecordPrompt: Boolean = false,
    /** Hears how this run is going. Called on the generation thread. */
    val events: GenerateEvents? = null
) {
    enum class Purpose { MESSAGE, PROFILE }

    /** The verdict on what the model wrote for this request. A rewrite is compared with no
     *  turns, because staying close to its source is the whole point. */
    fun review(raw: String, words: Int = maxWords): OutputSanitizer.Verdict =
        if (rewriteSource != null) OutputSanitizer.review(raw, words)
        else OutputSanitizer.review(
            raw, words, OutputSanitizer.recentTurnsOf(contextBefore), voiceExamples, contextBefore
        )
}

/** Where a run is. Only the last two are the model working on this request. */
enum class GenerateStage { LOADING, WAITING, READING, WRITING }

/** All optional: most callers only want the finished text. */
interface GenerateEvents {
    /** WRITING repeats with every token, which is how a listener can tell it has not stalled. */
    fun onStage(stage: GenerateStage) {}

    /** The model wrote something and it cannot be used. [LLMProvider.generate] returned "" for it. */
    fun onRejected(rejected: OutputSanitizer.Rejected) {}

    /** The engine's finish code, as named by [AiDiagLog.finishLabel]. */
    fun onFinished(code: Int) {}
}

/** What the user asked for cannot fit the model's window, even with all the context shed. */
class PromptTooLongException : IllegalStateException("prompt does not fit the context window")

/** The model file is there and the engine could not load it. */
class ModelLoadException(model: String) : IllegalStateException("$model: model load failed")

/**
 * Plugin contract from the architecture doc: any language-model backend —
 * on-device GGUF via llama.cpp, ONNX, or the bundled offline composer —
 * plugs in here. The keyboard UI only ever talks to this interface.
 */
interface LLMProvider {
    val providerId: String
    val displayName: String
    val description: String

    /** False when the backend's model files/native bits aren't installed. */
    val isAvailable: Boolean

    /**
     * Streams a draft: [onToken] is invoked with successive chunks
     * (words/fragments); returns the full generated text.
     */
    suspend fun generate(request: GenerateRequest, onToken: (String) -> Unit): String

    /** Best-effort abort of any in-flight generation. No-op by default. */
    fun cancel() {}
}
