package com.localboard.keyboard.llm

enum class Tmpl {
    QWEN3, CHATML, ZEPHYR, LLAMA3, GEMMA, PHI3, ALPACA;

    companion object {
        /**
         * [templateId] is [ChatTemplate.id]. Qwen3 shares ChatML's markup but needs its
         * think block closed, and only the file name tells the two apart. Strings rather
         * than a ModelSpec, so this file stays free of Android and runs in the desktop sim.
         */
        fun of(templateId: String, fileName: String): Tmpl = when (templateId) {
            "zephyr" -> ZEPHYR
            "llama3" -> LLAMA3
            "gemma" -> GEMMA
            "phi3" -> PHI3
            "alpaca" -> ALPACA
            else -> if (fileName.contains("qwen3", ignoreCase = true)) QWEN3 else CHATML
        }
    }
}

/** The prompt, split where the native side snapshots: it keeps the decoded state of
 *  [static] and only decodes [dynamic] on later runs. */
data class PromptParts(val static: String, val dynamic: String)

/**
 * Raw prompt text per template. The native side tokenizes static with
 * addSpecial=true, so a model that wants a BOS gets exactly one and no template
 * here writes it literally. Split after the stable sentence so the snapshot stays valid.
 */
object ChatFormatter {
    fun render(t: Tmpl, a: Assembled): PromptParts {
        val sysTail = a.dynamicSystem
        val msgs = a.messages.ifEmpty { listOf(Msg(Role.USER, "Write it.")) }
        return when (t) {
            Tmpl.QWEN3, Tmpl.CHATML -> {
                val think = t == Tmpl.QWEN3
                PromptParts(
                    "<|im_start|>system\n${a.stableSystem}\n",
                    buildString {
                        append(sysTail)
                        if (think) append(" /no_think")
                        append("<|im_end|>\n")
                        for (m in msgs) {
                            append("<|im_start|>").append(if (m.role == Role.USER) "user" else "assistant")
                            append('\n').append(m.text).append("<|im_end|>\n")
                        }
                        append("<|im_start|>assistant\n")
                        if (think) append("<think>\n\n</think>\n\n")
                        append(a.prefill)
                    }
                )
            }
            Tmpl.ZEPHYR -> tagged(a, msgs, "<|system|>\n", "</s>\n", "<|user|>\n", "<|assistant|>\n")
            Tmpl.PHI3 -> tagged(a, msgs, "<|system|>\n", "<|end|>\n", "<|user|>\n", "<|assistant|>\n")
            Tmpl.LLAMA3 -> tagged(
                a, msgs,
                "<|start_header_id|>system<|end_header_id|>\n\n", "<|eot_id|>",
                "<|start_header_id|>user<|end_header_id|>\n\n",
                "<|start_header_id|>assistant<|end_header_id|>\n\n"
            )
            Tmpl.GEMMA -> {
                // Gemma has no system turn and insists on alternating from the user side.
                val fixed = if (msgs.first().role == Role.ASSISTANT)
                    listOf(Msg(Role.USER, "(earlier messages)")) + msgs else msgs
                PromptParts(
                    "<start_of_turn>user\n${a.stableSystem}\n",
                    buildString {
                        if (sysTail.isNotEmpty()) append(sysTail).append("\n\n")
                        for ((i, m) in fixed.withIndex()) {
                            if (i > 0) {
                                append("<start_of_turn>").append(if (m.role == Role.USER) "user" else "model").append('\n')
                            }
                            append(m.text).append("<end_of_turn>\n")
                        }
                        append("<start_of_turn>model\n").append(a.prefill)
                    }
                )
            }
            Tmpl.ALPACA -> PromptParts(
                "### Instruction:\n${a.stableSystem}\n",
                buildString {
                    append(sysTail).append("\n\n### Input:\n")
                    for (m in msgs) {
                        append(if (m.role == Role.USER) "Them: " else "You: ").append(m.text).append('\n')
                    }
                    append("\n### Response:\n").append(a.prefill)
                }
            )
        }
    }

    private fun tagged(
        a: Assembled, msgs: List<Msg>, sysOpen: String, end: String, user: String, asst: String
    ) = PromptParts(
        "$sysOpen${a.stableSystem}\n",
        buildString {
            append(a.dynamicSystem).append(end)
            for (m in msgs) append(if (m.role == Role.USER) user else asst).append(m.text).append(end)
            append(asst).append(a.prefill)
        }
    )
}
