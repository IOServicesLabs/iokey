package com.localboard.keyboard.llm

import com.localboard.keyboard.memory.TextNorm

/**
 * Decides what a generated draft may become, and says why when the answer is nothing.
 *
 * Tiny models fail in a few recognisable ways: they repeat the chat, continue the prompt,
 * invent the other side's next message, or answer and then ramble on. All of that is cut
 * or refused here. A short answer is not one of those failures: "sounds good" is a real
 * reply, even to "sounds good", so nothing under [MIN_ECHO_CHARS] can count as an echo.
 *
 * No model and no Android in here, so every rule runs in tools/llm-sim.
 */
object OutputSanitizer {
    /** How many recent turns a draft is compared with. Beyond that, people repeat
     *  themselves in the ordinary way. */
    const val ECHO_TURNS = 6
    private const val MIN_ECHO_CHARS = 18
    /** Fewer words than this, left behind by a cut, are a fragment and not a reply. */
    private const val MIN_CUT_WORDS = 3

    enum class Reason {
        /** The model returned nothing. */
        EMPTY,

        /** It returned text, and all of it was prompt scaffolding. */
        SCAFFOLDING,

        /** It repeated the conversation instead of answering it. */
        ECHO,

        /** It reproduced one of the owner's messages from another chat. */
        VOICE_LEAK
    }

    sealed class Verdict

    data class Accepted(val text: String) : Verdict()

    /** [raw] is exactly what the model wrote, so the UI can offer to show it. */
    data class Rejected(val reason: Reason, val raw: String) : Verdict()

    /**
     * The whole review of one message draft.
     *
     * [recentTurns] is message text, newest last, without speaker labels. It must not
     * include the owner's run-on of typed history: saying something close to what you said
     * last week is not an echo. Pass nothing when rewriting, where a close match to the
     * source is the whole point.
     *
     * [echoContext] is what the prompt ended on, so a repeat of it can be cut off the front
     * of the draft. When continuing a draft, that includes the draft.
     */
    fun review(
        raw: String,
        maxWords: Int,
        recentTurns: List<String> = emptyList(),
        voiceExamples: List<String>? = null,
        echoContext: String = recentTurns.joinToString("\n")
    ): Verdict {
        if (raw.isBlank()) return Rejected(Reason.EMPTY, raw)
        var body = stripEcho(raw, echoContext)
        val wasCut = body.length < raw.trimStart().length
        if (body.isBlank() || (wasCut && wordCount(body) < MIN_CUT_WORDS)) {
            // All or nearly all of it repeats the end of the prompt, and "too" is no reply to
            // anything. Long, that is parroting. Short, it may simply be the natural answer.
            if (raw.trim().length >= MIN_ECHO_CHARS) return Rejected(Reason.ECHO, raw)
            body = raw
        }
        val out = sanitize(body, maxWords)
        return when {
            out.isBlank() -> Rejected(Reason.SCAFFOLDING, raw)
            echoesTurns(out, recentTurns) -> Rejected(Reason.ECHO, raw)
            leaksVoiceExample(out, voiceExamples) -> Rejected(Reason.VOICE_LEAK, raw)
            else -> Accepted(out)
        }
    }

    /**
     * The chat lines of the prompt's flat context block, newest last and without their
     * labels. Stops at the owner's run-on, which is typed history rather than turns.
     * Comes back empty when only the owner's side is visible, so nothing there can be
     * called an echo.
     */
    fun recentTurnsOf(contextBlock: String, n: Int = ECHO_TURNS): List<String> =
        contextBlock.lineSequence()
            .map { it.trim() }
            .takeWhile { line -> RUN_ON_HEADERS.none { line.startsWith(it, ignoreCase = true) } }
            .filter { it.isNotEmpty() && !it.equals(CHAT_HEADER, ignoreCase = true) }
            .map { LINE_LABEL.replace(it, "") }
            .toList()
            .takeLast(n)

    /**
     * Tiny models sometimes continue the PROMPT instead of answering, or answer and then
     * keep going: fake next turns, quoted conversation, prompt scaffolding, run-on ramble.
     * Cut ALL of it:
     * 1. template and scaffolding markers: cut at the first hit, and position 0 means there
     *    was no real answer at all. Words a person might also write count only where the
     *    prompt has them, at the start of a line. A cut inside a line that leaves a few words
     *    is no answer either, because the words before it were never finished;
     * 2. a new line opening a fake turn ("User:", "Them:", "Assistant:"): the reply is
     *    whatever came before it;
     * 3. anything past about 1.6x the word budget, trimmed at a sentence boundary so the
     *    reply still reads complete.
     */
    fun sanitize(raw: String, maxWords: Int): String {
        if (raw.isEmpty()) return raw
        var raw2 = raw
        // Models sometimes re-emit the owner label ("you: yeah, want to come"). A leading
        // owner label is deterministic scaffolding, never message content.
        val label = OWNER_LABEL.find(raw2)
        if (label != null && label.range.first == 0) {
            raw2 = raw2.substring(label.range.last + 1).trimStart()
            if (raw2.isEmpty()) return ""
        }
        val lower = raw2.lowercase()
        if (lower.startsWith("system\n") || lower.startsWith("assistant\n") ||
            lower.startsWith("user\n")
        ) return ""
        var cut = raw2.length
        for (marker in TEMPLATE_MARKERS) {
            val i = lower.indexOf(marker)
            if (i in 0 until cut) cut = i
        }
        var phrase = raw2.length
        for (marker in LEAK_PHRASES) {
            val i = lower.indexOf(marker)
            if (i in 0 until phrase) phrase = i
        }
        for (marker in LINE_START_PHRASES) {
            var i = lower.indexOf(marker)
            while (i in 0 until phrase) {
                if (opensLine(lower, i)) phrase = i else i = lower.indexOf(marker, i + 1)
            }
        }
        if (phrase < cut) {
            if (!opensLine(lower, phrase) && wordCount(raw2.substring(0, phrase)) < MIN_CUT_WORDS) return ""
            cut = phrase
        }
        var out = raw2.substring(0, cut).replace("\r", "")
        val turn = FAKE_TURN.find(out)
        if (turn != null) {
            if (turn.range.first == 0) return ""
            out = out.substring(0, turn.range.first)
        }
        out = out.trim()
        // Run-on ramble cap: tiny models sometimes answer well in sentence one and then
        // drift. Keep the reply inside its word budget, cut at the last sentence end that
        // fits.
        val words = out.split(WHITESPACE).filter { it.isNotBlank() }
        val budget = (maxWords * 1.6f).toInt().coerceAtLeast(12)
        if (words.size > budget) {
            val hardCap = words.take(budget).joinToString(" ")
            val lastEnd = hardCap.indexOfLast { it == '.' || it == '!' || it == '?' }
            out = if (lastEnd >= hardCap.length / 2) hardCap.substring(0, lastEnd + 1)
                  else hardCap
        }
        return out.trim()
    }

    private fun wordCount(s: String) = s.split(WHITESPACE).count { it.isNotBlank() }

    private fun opensLine(s: String, at: Int) = s.substring(s.lastIndexOf('\n', at - 1) + 1, at).isBlank()

    /** Drops the longest prefix of [out] that verbatim repeats a suffix of [context]
     *  (the "Lol yea, hold q for 1…" echo failure). */
    fun stripEcho(out: String, context: String): String {
        if (out.isEmpty() || context.isEmpty()) return out
        val max = minOf(out.length, context.length, 240)
        var cut = 0
        var len = max
        while (len >= 10) {
            if (context.endsWith(out.substring(0, len))) { cut = len; break }
            len--
        }
        return out.drop(cut).trimStart()
    }

    /** True when [out] is one of the last few turns, or mostly a slice of them. Compared
     *  with case and spacing evened out, and across turn boundaries, because a model that
     *  regurgitates tends to run two messages together. */
    fun echoesTurns(out: String, turns: List<String>): Boolean {
        if (out.length < MIN_ECHO_CHARS || turns.isEmpty()) return false
        val recent = turns.takeLast(ECHO_TURNS).map(TextNorm::cmp)
        val low = TextNorm.cmp(out)
        return low in recent || isEchoOf(low, recent.joinToString(" "))
    }

    /** True when [out] is mostly a verbatim slice of [context]: at least 50% of its 4-word
     *  shingles appear in the context. Catches "continue the text I just read" failures
     *  that a prefix strip can't (the echo can start anywhere, even mid-word: "...r to a
     *  nudge system"). */
    fun isEchoOf(out: String, context: String): Boolean {
        if (out.length < MIN_ECHO_CHARS || context.isEmpty()) return false
        val low = out.lowercase()
        val ctx = " ${context.lowercase()} "
        if (low.length >= 24 && ctx.contains(low)) return true
        val words = low.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size < 5) return false
        var hits = 0
        var total = 0
        for (i in 0..words.size - 4) {
            total++
            if (ctx.contains(words.subList(i, i + 4).joinToString(" "))) hits++
        }
        return total > 0 && hits * 2 >= total
    }

    /** True when [out] reproduces a chunk of one of the owner's messages from ANOTHER
     *  conversation. Those are in the prompt as style examples; they are private content
     *  from elsewhere and must never be emitted verbatim. Screen text from the current app
     *  is written by whoever is messaging you, so "repeat the examples above" is a live
     *  path from a hostile message to another chat's content in the compose box.
     *
     *  Deliberately stricter than [isEchoOf]: a voice example is short, so a 5-word run in
     *  common is already most of one. Normalised on words so punctuation and casing cannot
     *  slip a leak through. */
    fun leaksVoiceExample(out: String, examples: List<String>?): Boolean {
        if (out.isBlank() || examples.isNullOrEmpty()) return false
        fun words(s: String) = s.lowercase().split(NOT_WORD).filter { it.isNotBlank() }
        val o = words(out)
        if (o.size < 5) return false
        val joined = " " + o.joinToString(" ") + " "
        for (ex in examples) {
            val e = words(ex)
            if (e.size < 5) continue
            for (i in 0..e.size - 5) {
                if (joined.contains(" " + e.subList(i, i + 5).joinToString(" ") + " ")) return true
            }
        }
        return false
    }

    private val WHITESPACE = Regex("\\s+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}']+")

    private const val CHAT_HEADER = "Chat on my screen:"
    private val RUN_ON_HEADERS = listOf("Messages I sent recently:", "My recent messages in this chat")

    /** "Them: ", "You: ", or a sender's name. */
    private val LINE_LABEL = Regex("^\\p{L}[\\p{L} .'\\-]{0,30}:\\s+")

    /** Model markup. It ends the reply wherever it turns up, and what came before it is the
     *  answer however short: "sure thing<|im_end|>". All lowercase; matched case-insensitively. */
    private val TEMPLATE_MARKERS = listOf(
        "<|im_start|>", "<|im_end|>", "<|start_header_id|>", "<|end_header_id|>",
        "<|eot_id|>", "<|begin_of_text|>", "<|end_of_text|>", "<|endoftext|>", "<|end|>",
        "<start_of_turn>", "<end_of_turn>", "<bos>", "<eos>",
        "<|system|>", "<|user|>", "<|assistant|>", "</s>", "<s>",
        "<think>", "</think>", "/no_think", "[inst]", "[/inst]",
        "### instruction", "### response", "### input"
    )

    /** Text that only ever appears in OUR prompts, never in a real reply; seeing it in the
     *  output means the model is regurgitating the prompt. All lowercase; matched
     *  case-insensitively. */
    private val LEAK_PHRASES = listOf(
        "rules: output only", "never repeat, quote", "you ghostwrite",
        "two examples of the task", "background (never quote it)", "(a new chat)",
        "owner's voice from other chats",
        "my recent messages in this chat",
        // The chat-shaped few-shot block's scaffolding.
        "here is how the job looks", "write the owner's next reply",
        // PromptAssembler's system text and placeholder turns.
        "you are the phone's owner", "you write for the phone's owner", "phone owner's message",
        "write your next message only", "finish the message you already started",
        "text after the cursor, for reference only", "things you wrote before, for style only",
        "earlier in this chat (only if relevant)",
        "(no reply yet)", "(earlier messages)", "(write my next message"
    )

    /** Prompt text that a person could also write in passing ("can you do this: call me"), so
     *  it only counts at the start of a line, which is where the prompts have it. */
    private val LINE_START_PHRASES = listOf(
        "ghostwrite", "my next message", "now the real one", "do this:",
        // The few-shot block's own labels: the model's favorite thing to parrot verbatim is
        // example #1 ("Conversation: \"you still coming over tonight?\"").
        "conversation:",
        // The labeled context sections the keyboard prepends: a reply that opens with a
        // label is parroting, not drafting.
        "chat on my screen:", "messages i sent recently:",
        // The chat-shaped few-shot block's scaffolding.
        "now this chat:", "maya: yo did you", "sam: running late",
        // PromptAssembler's system text.
        "the phone's owner", "write your next message", "how you text:"
    )

    /** A new line that opens a FAKE turn ("User:", "Them:", "Assistant:"): models that don't
     *  stop at the end of the reply invent the other side's next message. The real reply is
     *  whatever came before. */
    private val FAKE_TURN =
        Regex("(?im)^[ \\t]*(user|assistant|system|them|friend|me|you)\\s*:\\s")

    /** A leading owner label on the WHOLE output ("you: yeah, i'm in"). When the model
     *  re-emits the label, strip it instead of rejecting the whole draft ([FAKE_TURN] would
     *  nuke it as position 0). */
    private val OWNER_LABEL =
        Regex("(?i)^[ \\t]*(you|owner)\\s*:\\s+")
}
