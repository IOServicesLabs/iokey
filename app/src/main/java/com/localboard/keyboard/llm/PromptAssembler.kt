package com.localboard.keyboard.llm

import com.localboard.keyboard.memory.ControlTokens
import com.localboard.keyboard.memory.Speaker
import com.localboard.keyboard.memory.TextNorm
import com.localboard.keyboard.memory.Turn
import kotlin.math.ceil

enum class PromptMode { REPLY, FOLLOW_UP, CONTINUE, INSTRUCTION, REWRITE, COMPOSE, NO_CONTEXT }

/** What a model of this size is given to work with. Tokens, except where it says turns
 *  or lines. Starting points, to be tuned against real runs. */
data class Tier(
    val name: String,
    val nCtx: Int,
    val output: Int,
    val style: Int,
    val voiceLines: Int,
    val recentTurns: Int,
    val recentTokens: Int,
    val snippets: Int,
    val snippetTokens: Int,
    val promptTarget: Int
) {
    companion object {
        val T0 = Tier("T0", 1024, 64, 0, 0, 6, 200, 0, 0, 260)
        val T1 = Tier("T1", 1536, 80, 20, 1, 10, 350, 1, 40, 480)
        val T2 = Tier("T2", 2048, 96, 30, 2, 16, 500, 2, 80, 700)
        val T3 = Tier("T3", 2048, 120, 40, 3, 24, 900, 3, 150, 1200)

        /**
         * [modelId] is ModelSpec.id, [tiny] is ModelRegistry.isTinyModel. An imported model
         * is an unknown size, so it gets the shorter prompt: a big model that sees a little
         * less still answers, a small one handed too much context parrots it.
         */
        fun forModel(modelId: String, tiny: Boolean = false): Tier = when (modelId) {
            "smollm2_360m", "lfm25_230m" -> T0
            "qwen3_06b", "qwen25_05b", "lfm2_700m" -> T1
            "qwen3_17b", "lfm2_12b", "tinyllama_11b", "llama32_1b", "gemma3_1b" -> T2
            "qwen3_4b", "phi35_mini" -> T3
            else -> if (tiny) T0 else T1
        }
    }
}

fun interface TokenCounter { fun count(text: String): Int }

/** Conservative fallback; native or LEAP counts replace it when they exist. */
val ApproxCounter = TokenCounter { t -> ceil(t.length / 3.2).toInt() }

enum class Role { USER, ASSISTANT }

data class Msg(val role: Role, val text: String)

data class PromptInput(
    val isChatField: Boolean = true,
    val title: String = "",
    val appLabel: String = "",
    val isGroup: Boolean = false,
    /** Oldest to newest; the newest few hundred is plenty. */
    val turns: List<Turn> = emptyList(),
    /** BM25 hits, best first. */
    val snippets: List<Turn> = emptyList(),
    val draft: String = "",
    /** Text after the cursor: a quoted message, or the rest of a draft. Reference only. */
    val textAfter: String = "",
    val instruction: String? = null,
    val rewriteSource: String? = null,
    val styleLine: String? = null,
    val voiceLines: List<String> = emptyList(),
    val tone: String? = null,
    val dateLabel: (Long) -> String = { "" }
)

data class Assembled(
    val mode: PromptMode,
    val stableSystem: String,
    val dynamicSystem: String,
    val messages: List<Msg>,
    val prefill: String,
    val tokens: Int,
    val cap: Int,
    /** A rewrite source or an instruction that cannot go in whole. Do not run this prompt,
     *  and leave the field as it is. */
    val tooLong: Boolean = false,
    val keptTurns: Int = 0,
    val droppedTurns: Int = 0,
    val keptSnippets: Int = 0,
    val usedVoice: Int = 0,
    val usedStyle: Boolean = false
)

/**
 * Builds a budgeted prompt from what is known about the chat.
 *
 * The other person's lines are the user role and the owner's are the assistant role, so
 * the newest thing the model reads is the message it has to answer, and the owner's own
 * turns show the voice better than any description could. What goes in first survives
 * longest: the task and draft, the last two turns, older turns newest first, snippets,
 * voice lines, then style, which is the first thing to go.
 *
 * Everything from outside this app passes through [ControlTokens] here, so no caller has
 * to remember to.
 */
object PromptAssembler {
    const val BASE = "You are the phone's owner, texting in your own voice. Write your next " +
        "message only: short, natural, no offers to help, no explanations. Never mention being an AI."
    private const val REWRITE_BASE =
        "You rewrite the phone owner's message. Keep its meaning and facts. Output only the rewritten message."
    private const val COMPOSE_BASE =
        "You write for the phone's owner. Output only the text to send, no commentary."
    private const val MSG_OVERHEAD = 5
    /** The wordiest template's role markers, counted as plain text, beyond what the window
     *  already keeps free and [MSG_OVERHEAD] already charges. */
    private const val ROLE_MARKUP = 24
    private const val TEXT_AFTER_CHARS = 240
    /** A name is a few words, and the budget does not fit it the way it fits a message,
     *  so a line misread as a name has to be cut here. */
    private const val NAME_CHARS = 60
    private const val TONE_CHARS = 160

    fun chooseMode(inp: PromptInput): PromptMode = modeFor(inp, cleanTurns(inp.turns))

    /** Decided on the cleaned text: a message that was nothing but control tokens is no
     *  message, and must not make the prompt end on a turn that is about to vanish. */
    private fun modeFor(inp: PromptInput, turns: List<Turn>): PromptMode = when {
        inp.rewriteSource != null -> PromptMode.REWRITE
        !inp.instruction?.let(ControlTokens::strip).isNullOrBlank() -> PromptMode.INSTRUCTION
        !inp.isChatField -> PromptMode.COMPOSE
        ControlTokens.strip(inp.draft).isNotBlank() -> PromptMode.CONTINUE
        turns.none { it.speaker != Speaker.ME } -> PromptMode.NO_CONTEXT
        turns.last().speaker == Speaker.ME -> PromptMode.FOLLOW_UP
        else -> PromptMode.REPLY
    }

    private fun cleanTurns(turns: List<Turn>): List<Turn> = turns
        .map { it.copy(text = ControlTokens.strip(it.text), sender = cleanLine(it.sender, NAME_CHARS)) }
        .filter { it.text.isNotBlank() }

    private fun cleanLine(s: String, maxChars: Int): String {
        val cut = ControlTokens.stripLine(s).take(maxChars)
        // Half a surrogate pair reaches the tokenizer as invalid UTF-8.
        return (if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut).trimEnd()
    }

    private fun fitTail(text: String, maxTokens: Int, c: TokenCounter): String {
        if (c.count(text) <= maxTokens) return text
        var keep = (maxTokens * 3.2).toInt().coerceIn(minOf(16, text.length), text.length)
        while (keep > 16 && c.count(text.takeLast(keep)) > maxTokens) keep -= 8
        var cut = text.takeLast(keep)
        if (cut.firstOrNull()?.isLowSurrogate() == true) cut = cut.drop(1)
        val sp = cut.indexOf(' ')
        return if (sp in 0..24) cut.substring(sp + 1) else cut
    }

    private fun label(t: Turn, group: Boolean): String =
        if (group && t.speaker != Speaker.ME && t.sender.isNotBlank()) "${t.sender}: ${t.text}" else t.text

    private fun toMessages(turns: List<Turn>, group: Boolean): MutableList<Msg> {
        val out = ArrayList<Msg>()
        for (t in turns) {
            val role = if (t.speaker == Speaker.ME) Role.ASSISTANT else Role.USER
            val line = label(t, group)
            val last = out.lastOrNull()
            if (last != null && last.role == role) out[out.size - 1] = Msg(role, last.text + "\n" + line)
            else out += Msg(role, line)
        }
        return out
    }

    fun assemble(
        inp: PromptInput,
        tier: Tier,
        counter: TokenCounter = ApproxCounter,
        latencyCapTokens: Int = Int.MAX_VALUE
    ): Assembled {
        val allTurns = cleanTurns(inp.turns)
        val mode = modeFor(inp, allTurns)
        val cap = minOf(tier.promptTarget, tier.nCtx - tier.output - 48, latencyCapTokens)
        // A quick prompt in an email field is still not a text message.
        val composing = mode == PromptMode.COMPOSE || (mode == PromptMode.INSTRUCTION && !inp.isChatField)
        val stable = when {
            mode == PromptMode.REWRITE -> REWRITE_BASE
            composing -> COMPOSE_BASE
            else -> BASE
        }
        if (mode == PromptMode.NO_CONTEXT) {
            return Assembled(mode, stable, "", emptyList(), "", 0, cap)
        }
        val title = cleanLine(inp.title, NAME_CHARS)
        val app = cleanLine(inp.appLabel, NAME_CHARS)
        val instr = inp.instruction?.let(ControlTokens::strip)
        val inApp = if (app.isNotEmpty()) " in $app" else ""
        val who = when {
            mode == PromptMode.REWRITE -> ""
            composing -> inp.tone?.let { cleanLine(it, TONE_CHARS) }?.takeIf { it.isNotEmpty() }?.let { "This is $it." } ?: ""
            inp.isGroup && title.isNotEmpty() -> "You are in the group chat \"$title\"$inApp."
            title.isNotEmpty() -> "You are texting $title$inApp."
            app.isNotEmpty() -> "You are texting in $app."
            else -> ""
        }
        val modeLine = when (mode) {
            PromptMode.FOLLOW_UP -> "They have not replied yet. Send a short follow-up that adds something."
            PromptMode.CONTINUE -> "Finish the message you already started."
            else -> ""
        }
        var used = counter.count(stable) + counter.count(who) + counter.count(modeLine) + 2 * MSG_OVERHEAD

        // REWRITE: the source text is the whole job, and it is never silently cut.
        if (mode == PromptMode.REWRITE) {
            val src = ControlTokens.strip(inp.rewriteSource.orEmpty())
            val ask = instr?.takeIf { it.isNotBlank() } ?: "Make it read well."
            val body = "$ask\n\nMy message:\n$src"
            val tokens = used + counter.count(body)
            return Assembled(mode, stable, "", listOf(Msg(Role.USER, body)), "", tokens, cap, tooLong = tokens > cap)
        }

        val clean = if (mode == PromptMode.COMPOSE) emptyList() else allTurns

        // Never dropped: the task itself, the draft, and the last two turns.
        val instrCost = instr?.let { counter.count(it) + 12 } ?: 0
        // A long draft gives up its start, which costs nothing: the model carries on from the
        // end, and only what it adds goes into the field.
        val room = (cap - used - instrCost).coerceAtLeast(40)
        val draft = fitTail(ControlTokens.strip(inp.draft), if (clean.isEmpty()) room else room / 2, counter)
        used += counter.count(draft) + instrCost
        val perMandatory = ((cap - used).coerceAtLeast(40)) / 3
        val mandatory = clean.takeLast(2).map { it.copy(text = fitTail(it.text, perMandatory, counter)) }
        used += mandatory.sumOf { counter.count(label(it, inp.isGroup)) + MSG_OVERHEAD }

        // The task is never cut, so one that leaves no room for all this is refused: sent as it
        // is, the native side would cut the head off the prompt, role markers and all. It is
        // only known here, because the draft and those turns keep a floor however little room
        // the task leaves. Everything budgeted below has to fit under the cap.
        val tooLong = used + ROLE_MARKUP > tier.nCtx - tier.output - 48

        // Next, what follows the cursor: often the only sign of what a compose field is
        // replying to. It goes in the system text so the prompt still ends on the task.
        var afterLine = ""
        val after = cleanLine(inp.textAfter, TEXT_AFTER_CHARS)
        if (after.isNotEmpty()) {
            val line = "Text after the cursor, for reference only: $after"
            val cost = counter.count(line)
            if (used + cost <= cap) {
                afterLine = line
                used += cost
            }
        }

        // Then older turns, newest first, inside their own budgets.
        val older = clean.dropLast(mandatory.size)
        val picked = ArrayList<Turn>()
        var recentUsed = 0
        for (t in older.asReversed()) {
            if (picked.size + mandatory.size >= tier.recentTurns) break
            val cost = counter.count(label(t, inp.isGroup)) + MSG_OVERHEAD
            if (recentUsed + cost > tier.recentTokens || used + cost > cap) break
            picked += t
            recentUsed += cost
            used += cost
        }
        val window = picked.asReversed() + mandatory
        val shown = window.map { TextNorm.cmp(it.text) }.toSet()

        // Then relevant older snippets.
        val snippetLines = ArrayList<String>()
        var snippetUsed = 0
        for (s in inp.snippets) {
            if (snippetLines.size >= tier.snippets) break
            val text = ControlTokens.stripLine(s.text)
            if (text.isBlank() || TextNorm.cmp(text) in shown) continue
            val sender = cleanLine(s.sender, NAME_CHARS)
            val who2 =if (s.speaker == Speaker.ME) "You" else sender.ifBlank { title.ifBlank { "Them" } }
            val date = inp.dateLabel(s.ts)
            val line = "- " + (if (date.isNotEmpty()) "[$date] " else "") + "$who2: $text"
            val cost = counter.count(line)
            if (snippetUsed + cost > tier.snippetTokens || used + cost > cap) continue
            snippetLines += line
            snippetUsed += cost
            used += cost
        }

        // Voice lines only when the chat itself shows too little of the owner.
        val voice = ArrayList<String>()
        if (window.count { it.speaker == Speaker.ME } < 3) {
            for (v in inp.voiceLines) {
                if (voice.size >= tier.voiceLines) break
                val text = ControlTokens.stripLine(v)
                val line = "- $text"
                val cost = counter.count(line)
                if (text.isBlank() || TextNorm.cmp(text) in shown || used + cost > cap) continue
                voice += line
                used += cost
            }
        }

        // Style is the first thing to go.
        var styleUsed: String? = null
        inp.styleLine?.let(ControlTokens::stripLine)?.takeIf { it.isNotBlank() }?.let { st ->
            val cost = counter.count(st)
            if (tier.style > 0 && cost <= tier.style && used + cost <= cap) {
                styleUsed = st
                used += cost
            }
        }

        val dyn = buildString {
            if (who.isNotEmpty()) append(who).append('\n')
            if (modeLine.isNotEmpty()) append(modeLine).append('\n')
            if (afterLine.isNotEmpty()) append(afterLine).append('\n')
            styleUsed?.let { append("How you text: ").append(it).append('\n') }
            if (voice.isNotEmpty()) {
                append("Things you wrote before, for style only:\n")
                voice.forEach { append(it).append('\n') }
            }
            if (snippetLines.isNotEmpty()) {
                append("Earlier in this chat (only if relevant):\n")
                snippetLines.forEach { append(it).append('\n') }
            }
        }.trimEnd()

        val msgs = toMessages(window, inp.isGroup)
        when (mode) {
            PromptMode.FOLLOW_UP -> msgs += Msg(Role.USER, "(no reply yet)")
            PromptMode.CONTINUE ->
                if (msgs.lastOrNull()?.role == Role.ASSISTANT) msgs += Msg(Role.USER, "(no reply yet)")
            PromptMode.INSTRUCTION -> {
                val ask = "(Write my next message. What to say: $instr)"
                val last = msgs.lastOrNull()
                if (last?.role == Role.USER) msgs[msgs.size - 1] = Msg(Role.USER, last.text + "\n\n" + ask)
                else msgs += Msg(Role.USER, ask)
            }
            PromptMode.COMPOSE ->
                msgs += Msg(Role.USER, if (draft.isNotEmpty()) "Continue this." else "Write it.")
            else -> Unit
        }
        // Whatever the field holds is where the answer starts. What the model writes goes in
        // after it, so a quick prompt has to see it as much as a continuation does.
        return Assembled(
            mode, stable, dyn, msgs, draft, used, cap,
            tooLong = tooLong,
            keptTurns = window.size,
            droppedTurns = clean.size - window.size,
            keptSnippets = snippetLines.size,
            usedVoice = voice.size,
            usedStyle = styleUsed != null
        )
    }
}
