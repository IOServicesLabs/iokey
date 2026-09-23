package com.localboard.keyboard.llm

import com.localboard.keyboard.util.Prefs

/**
 * Registry for swappable language-model backends.
 *
 * Ships with the offline personal composer. A llama.cpp-backed provider
 * (GGUF models like SmolLM2 / TinyLlama, running fully on-device) can be
 * registered here later — see LLAMA_CPP.md in the project root for the
 * native build recipe. The registry pattern means the keyboard UI never
 * changes when providers do.
 */
class ProviderRegistry(
    private val prefs: Prefs,
    localComposer: LocalStyleComposer
) {

    private val providers = LinkedHashMap<String, LLMProvider>()

    init {
        register(localComposer)
    }

    fun register(provider: LLMProvider) {
        providers[provider.providerId] = provider
    }

    fun all(): List<LLMProvider> = providers.values.toList()

    fun active(): LLMProvider {
        val chosen = providers[prefs.activeProviderId]
        return if (chosen != null && chosen.isAvailable) chosen
        else providers.getValue("local_style")
    }

    fun statusSummary(): String = buildString {
        providers.values.forEach { p ->
            append("• ${p.displayName}: ")
            append(if (p.isAvailable) "ready" else "not installed")
            append('\n')
        }
    }.trim()
}
