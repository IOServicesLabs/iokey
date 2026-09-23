package com.localboard.keyboard.llm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling, RAM-only diagnostics for the AI engine: the last few generation
 * runs (with native timing stats) and model-load events, plus the size of
 * the context streams at the last draft. Shown in Settings → Advanced → AI
 * diagnostics and copyable for bug reports. Never written to disk.
 */
object AiDiagLog {

    data class Entry(
        val at: Long,
        val model: String,
        val purpose: String,            // "draft", "quick prompt", "profile"
        val promptTokens: Long,
        val reusedTokens: Long,
        val promptMs: Long,
        val genTokens: Long,
        val genMs: Long,
        val finish: Int,                // native finish code; -1 = Kotlin-side failure
        val note: String = ""
    )

    private const val MAX_ENTRIES = 12
    private val entries = ArrayDeque<Entry>()

    /** Screen/field context sizes captured at the last draft assembly. */
    @Volatile var lastScreenChars: Int = -1
    @Volatile var lastFieldChars: Int = -1
    @Volatile var lastTypedChars: Int = -1
    @Volatile var lastLoadEvent: String = ""

    /** P5: the VERBATIM last assembled prompt (RAM-only, never written to
     *  disk, capped) — for a feature whose failure mode is bad context, the
     *  Test Lab must show what the model actually saw, not just char counts. */
    @Volatile var lastPrompt: String = ""
    @Volatile var lastPromptAt: Long = 0L
    @Volatile var lastPromptPurpose: String = ""

    fun recordPrompt(purpose: String, full: String) {
        lastPromptPurpose = purpose
        lastPromptAt = System.currentTimeMillis()
        lastPrompt = if (full.length > 3000) "…" + full.takeLast(3000) else full
    }

    /** Quick-lane (autocorrect) rolling counters — per-run entries would
     *  drown the log, so only totals are kept. */
    @Volatile var quickRuns: Int = 0
    @Volatile var quickTotalMs: Long = 0L

    @Synchronized
    fun add(e: Entry) {
        entries.addLast(e)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    fun finishLabel(code: Int): String = when (code) {
        0 -> "ok"
        1 -> "hit token cap"
        2 -> "cancelled"
        3 -> "tokenize failed"
        4 -> "prompt decode failed"
        5 -> "model not loaded"
        6 -> "generation decode failed"
        -1 -> "engine error"
        else -> "unknown ($code)"
    }

    private val TIME = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun formatEntry(e: Entry): String = buildString {
        append(TIME.format(Date(e.at))).append("  ").append(e.purpose)
        if (e.promptTokens > 0 || e.finish >= 0) {
            append(" · prompt ").append(e.promptTokens).append(" tok")
            if (e.reusedTokens > 0) append(" (").append(e.reusedTokens).append(" reused")
            append(", ").append(String.format(Locale.US, "%.1f", e.promptMs / 1000f)).append("s")
            append(" → ").append(e.genTokens).append(" tok in ")
            append(String.format(Locale.US, "%.1f", e.genMs / 1000f)).append("s")
            if (e.genMs > 0 && e.genTokens > 0) {
                append(" (").append(String.format(Locale.US, "%.1f", e.genTokens * 1000f / e.genMs))
                append(" tok/s)")
            }
            append(" · ").append(finishLabel(e.finish))
        }
        if (e.note.isNotEmpty()) append(" — ").append(e.note)
    }

    /** Full multi-line report for the clipboard / bug reports. */
    fun report(modelLine: String, screenLine: String): String = buildString {
        append(modelLine).append('\n')
        append(screenLine).append('\n')
        append("screen ctx last draft: ")
            .append(if (lastScreenChars >= 0) "$lastScreenChars chars" else "—")
            .append(" · typed ctx: ")
            .append(if (lastTypedChars >= 0) "$lastTypedChars chars" else "—")
            .append(" · field ctx: ")
            .append(if (lastFieldChars >= 0) "$lastFieldChars chars" else "—")
            .append('\n')
        if (lastPrompt.isNotEmpty()) {
            append("last prompt (").append(lastPromptPurpose).append(", ")
                .append(TIME.format(Date(lastPromptAt))).append("):\n")
            append(lastPrompt).append('\n')
        }
        if (lastLoadEvent.isNotEmpty()) append("load: ").append(lastLoadEvent).append('\n')
        if (quickRuns > 0) {
            append("quick lane: ").append(quickRuns).append(" runs · avg ")
                .append(String.format(Locale.US, "%.2f", quickTotalMs / 1000f / quickRuns))
                .append("s\n")
        }
        val snap = snapshot()
        if (snap.isEmpty()) append("(no AI runs yet this session)")
        else snap.forEach { append(formatEntry(it)).append('\n') }
    }
}
