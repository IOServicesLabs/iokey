import com.localboard.keyboard.llm.*
import com.localboard.keyboard.memory.*

/**
 * Exact text per template, written out by hand from the markup each model family expects.
 * A change here is a change to what every model of that family reads, so make it on
 * purpose.
 */
internal fun goldenChecks() {
    val now = 1_800_000_000_000L
    val day = 86_400_000L
    val inp = PromptInput(
        title = "John Smith", appLabel = "Messages",
        turns = listOf(them("are you coming tonight"), me("yeah what time"), them("7 at marios")),
        snippets = listOf(Turn(Speaker.THEM, "marios closes at 9 on sundays", "John", now - 30 * day)),
        styleLine = "capitalizes sentences, few periods, about 6 words, rarely emoji",
        dateLabel = { "Oct 12" }
    )
    val a = PromptAssembler.assemble(inp, Tier.T2)
    val base = PromptAssembler.BASE
    val dyn = "You are texting John Smith in Messages.\n" +
        "How you text: capitalizes sentences, few periods, about 6 words, rarely emoji\n" +
        "Earlier in this chat (only if relevant):\n" +
        "- [Oct 12] John: marios closes at 9 on sundays"

    fun golden(t: Tmpl, static: String, dynamic: String) {
        val p = ChatFormatter.render(t, a)
        check("$t static", p.static == static, p.static)
        check("$t dynamic", p.dynamic == dynamic, p.dynamic)
    }

    golden(
        Tmpl.QWEN3,
        "<|im_start|>system\n$base\n",
        "$dyn /no_think<|im_end|>\n" +
            "<|im_start|>user\nare you coming tonight<|im_end|>\n" +
            "<|im_start|>assistant\nyeah what time<|im_end|>\n" +
            "<|im_start|>user\n7 at marios<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    )
    golden(
        Tmpl.CHATML,
        "<|im_start|>system\n$base\n",
        "$dyn<|im_end|>\n" +
            "<|im_start|>user\nare you coming tonight<|im_end|>\n" +
            "<|im_start|>assistant\nyeah what time<|im_end|>\n" +
            "<|im_start|>user\n7 at marios<|im_end|>\n" +
            "<|im_start|>assistant\n"
    )
    golden(
        Tmpl.ZEPHYR,
        "<|system|>\n$base\n",
        "$dyn</s>\n" +
            "<|user|>\nare you coming tonight</s>\n" +
            "<|assistant|>\nyeah what time</s>\n" +
            "<|user|>\n7 at marios</s>\n" +
            "<|assistant|>\n"
    )
    golden(
        Tmpl.LLAMA3,
        "<|start_header_id|>system<|end_header_id|>\n\n$base\n",
        "$dyn<|eot_id|>" +
            "<|start_header_id|>user<|end_header_id|>\n\nare you coming tonight<|eot_id|>" +
            "<|start_header_id|>assistant<|end_header_id|>\n\nyeah what time<|eot_id|>" +
            "<|start_header_id|>user<|end_header_id|>\n\n7 at marios<|eot_id|>" +
            "<|start_header_id|>assistant<|end_header_id|>\n\n"
    )
    golden(
        Tmpl.GEMMA,
        "<start_of_turn>user\n$base\n",
        "$dyn\n\nare you coming tonight<end_of_turn>\n" +
            "<start_of_turn>model\nyeah what time<end_of_turn>\n" +
            "<start_of_turn>user\n7 at marios<end_of_turn>\n" +
            "<start_of_turn>model\n"
    )
    golden(
        Tmpl.PHI3,
        "<|system|>\n$base\n",
        "$dyn<|end|>\n" +
            "<|user|>\nare you coming tonight<|end|>\n" +
            "<|assistant|>\nyeah what time<|end|>\n" +
            "<|user|>\n7 at marios<|end|>\n" +
            "<|assistant|>\n"
    )
    golden(
        Tmpl.ALPACA,
        "### Instruction:\n$base\n",
        "$dyn\n\n### Input:\n" +
            "Them: are you coming tonight\nYou: yeah what time\nThem: 7 at marios\n" +
            "\n### Response:\n"
    )

    // The prompt a model reads from a draft already in the field ends on the draft.
    val cont = PromptAssembler.assemble(inp.copy(draft = "yeah i can do"), Tier.T2)
    check("continue ends on the draft, zephyr",
        ChatFormatter.render(Tmpl.ZEPHYR, cont).dynamic.endsWith("<|assistant|>\nyeah i can do"))
    check("continue ends on the draft, llama3",
        ChatFormatter.render(Tmpl.LLAMA3, cont).dynamic.endsWith("<|end_header_id|>\n\nyeah i can do"))
    check("continue ends on the draft, gemma",
        ChatFormatter.render(Tmpl.GEMMA, cont).dynamic.endsWith("<start_of_turn>model\nyeah i can do"))
    check("continue ends on the draft, phi3",
        ChatFormatter.render(Tmpl.PHI3, cont).dynamic.endsWith("<|assistant|>\nyeah i can do"))
    check("continue ends on the draft, alpaca",
        ChatFormatter.render(Tmpl.ALPACA, cont).dynamic.endsWith("### Response:\nyeah i can do"))

    // Model registry to markup: mirrors what the provider decides today.
    check("qwen3 is its own template", Tmpl.of("chatml", "Qwen_Qwen3-1.7B-Q4_K_M.gguf") == Tmpl.QWEN3)
    check("qwen2.5 is plain chatml", Tmpl.of("chatml", "Qwen2.5-0.5B-Q4_K_M.gguf") == Tmpl.CHATML)
    check("smollm is plain chatml", Tmpl.of("chatml", "SmolLM2-360M.gguf") == Tmpl.CHATML)
    check("zephyr id", Tmpl.of("zephyr", "tinyllama.gguf") == Tmpl.ZEPHYR)
    check("llama3 id", Tmpl.of("llama3", "llama-3.2-1b.gguf") == Tmpl.LLAMA3)
    check("gemma id", Tmpl.of("gemma", "gemma-3-1b.gguf") == Tmpl.GEMMA)
    check("phi3 id", Tmpl.of("phi3", "phi-3.5-mini.gguf") == Tmpl.PHI3)
    check("alpaca id", Tmpl.of("alpaca", "x.gguf") == Tmpl.ALPACA)
    check("unknown id falls back like ChatTemplate.fromId", Tmpl.of("nope", "x.gguf") == Tmpl.CHATML)

    // Every registered model has its tier, smallest models on the smallest budget.
    val tiers = mapOf(
        "smollm2_360m" to Tier.T0, "lfm25_230m" to Tier.T0,
        "qwen3_06b" to Tier.T1, "qwen25_05b" to Tier.T1, "lfm2_700m" to Tier.T1,
        "qwen3_17b" to Tier.T2, "lfm2_12b" to Tier.T2, "tinyllama_11b" to Tier.T2,
        "llama32_1b" to Tier.T2, "gemma3_1b" to Tier.T2,
        "qwen3_4b" to Tier.T3, "phi35_mini" to Tier.T3
    )
    for ((id, tier) in tiers) check("tier of $id", Tier.forModel(id) == tier)
    check("unknown import takes the short prompt", Tier.forModel("import_x") == Tier.T1)
    check("tiny import takes the tiny prompt", Tier.forModel("import_x", tiny = true) == Tier.T0)
}
