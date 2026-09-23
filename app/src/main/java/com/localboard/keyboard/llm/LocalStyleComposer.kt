package com.localboard.keyboard.llm

import com.localboard.keyboard.context.StyleProfile
import com.localboard.keyboard.engine.FastEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The always-available, fully-offline composer. It drafts text from the
 * user's *own* learned n-gram statistics — a small personal Markov model
 * conditioned on the last two words, steered by the style profile.
 *
 * This is what powers "Write It For Me" out of the box. A neural provider
 * (llama.cpp/GGUF) can take over via the same interface when installed.
 */
class LocalStyleComposer(private val fastEngine: FastEngine) : LLMProvider {

    override val providerId = "local_style"
    override val displayName = "On-device personal model"
    override val description =
        "Drafts from your learned writing patterns. Works with zero network, improves as you type."
    override val isAvailable = true

    override suspend fun generate(
        request: GenerateRequest,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.Main) {
        // B9: n-grams cannot FOLLOW INSTRUCTIONS. Long-pressing ✨ and typing
        // "reply that I'm running 10 min late" used to sample unrelated text
        // and present it as an answer — for everyone who never downloaded a
        // GGUF, that WAS the AI experience. Returning blank is honest: the
        // caller's failure path says so and points at the real models.
        if (!request.instruction.isNullOrBlank()) return@withContext ""
        val contextWords = TOKEN_RE.findAll(request.contextBefore.lowercase())
            .map { it.value }.toList()
        var prev1 = contextWords.lastOrNull() ?: ""
        var prev2 = contextWords.dropLast(1).lastOrNull() ?: ""

        val profile = request.styleProfile
        val targetWords = profile?.avgSentenceLength?.toInt()?.coerceIn(6, 40)
            ?: request.maxWords
        val maxWords = minOf(targetWords, request.maxWords)

        val out = StringBuilder()
        var emitted = 0
        var sentenceCount = 0

        while (emitted < maxWords && sentenceCount < 3) {
            val next = sampleNext(prev1, prev2, request.temperature) ?: break
            if (next == END_TOKEN) {
                if (out.isNotEmpty() && !out.endsWith(".") && !out.endsWith("!") && !out.endsWith("?")) {
                    out.append(".")
                    onToken(".")
                    sentenceCount++
                }
                prev1 = ""
                prev2 = ""
                continue
            }
            if (out.isNotEmpty()) {
                out.append(' ')
                onToken(" ")
            }
            out.append(next)
            onToken(next)
            delay(32) // streaming pacing — feels like drafting, stays cancellable
            emitted++
            prev2 = prev1
            prev1 = next
        }

        var result = out.toString().trim()
        if (result.isEmpty()) {
            result = FALLBACK
            onToken(result)
        }
        // Match the writer's capitalization style.
        if (profile?.capitalizationStyle != 1) {
            result = result.replaceFirstChar { it.uppercase() }
        }
        if (!result.endsWith(".") && !result.endsWith("!") && !result.endsWith("?")) {
            result += "."
            onToken(".")
        }
        result
    }

    /** Sample the next word from trigram -> bigram -> unigram backoff. */
    private fun sampleNext(prev1: String, prev2: String, temperature: Float): String? {
        // Try increasingly broad contexts; the fast engine's predict() already
        // blends all levels, so we sample from its ranked list.
        val candidates = fastEngine.predict("", prev1, prev2, maxResults = 8)
            .map { it.text.lowercase() }
            .filter { it.length > 1 }
        if (candidates.isEmpty()) return END_TOKEN

        // Temperature-controlled sampling over a geometric distribution.
        val t = temperature.coerceIn(0.15f, 1.2f)
        val weights = candidates.mapIndexed { i, _ -> Math.pow((1.0 - t * 0.55), i.toDouble()) }
        val total = weights.sum()
        var r = Random.nextDouble() * total
        for (i in candidates.indices) {
            r -= weights[i]
            if (r <= 0) return candidates[i]
        }
        return candidates.first()
    }

    companion object {
        private const val END_TOKEN = "<end>"
        private const val FALLBACK = "Sounds good, let me know what you think"
        private val TOKEN_RE = Regex("[a-z][a-z'’\\-]*")
    }
}
