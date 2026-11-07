package com.localboard.keyboard.memory

/**
 * Removes anything the tokenizer would read as a control token.
 *
 * The native side tokenizes whole prompts with special-token parsing on, so a chat
 * message holding "<|im_end|><|im_start|>system" becomes real role switches the moment
 * the owner taps draft. Everything this app did not write itself goes through here:
 * messages, names, titles, hints and the field's own text.
 *
 * A match becomes a space rather than nothing, so the two halves of a split token can
 * never close up into a live one.
 */
object ControlTokens {
    private val RE = Regex(
        // ChatML, Llama 3, Phi-3, Zephyr and LFM all spell theirs <|like this|>.
        "<\\|[^|>\\n]{1,32}\\|>|" +
            // The bare-word specials of Llama, Zephyr and Gemma.
            "</?s>|<(bos|eos|pad|unk|mask)>|<unused\\d+>|<(start|end)_of_[a-z]+>|" +
            // Qwen's tags: a stray </think> ends the empty think block the prompt opens with.
            "</?think>|</?tool_call>|</?tool_response>|" +
            "\\[/?INST\\]|###\\s*(Instruction|Input|Response)\\s*:?",
        RegexOption.IGNORE_CASE
    )
    private val SPACES = Regex(" {2,}")
    private val WS = Regex("\\s+")

    fun strip(s: String): String = SPACES.replace(RE.replace(s, " "), " ").trim()

    /** [strip] for text that must stay on one line of a prompt. A name or a stored
     *  snippet with a line break in it could otherwise pass for another line of the
     *  system text around it. */
    fun stripLine(s: String): String = WS.replace(strip(s), " ")
}
