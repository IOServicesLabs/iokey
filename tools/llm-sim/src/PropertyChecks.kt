import com.localboard.keyboard.llm.*
import com.localboard.keyboard.memory.*
import kotlin.random.Random

/**
 * Seeded random runs, each boiled down to one assertion, for what no list of examples
 * covers: the code must not throw on anything, and the things it promises must hold for
 * whatever a chat happens to contain.
 */
internal fun propertyChecks() {
    for (seed in listOf(1L, 2L)) {
        assembler(seed)
        sanitizer(seed)
        deltas(seed)
        merging(seed)
    }
}

/** The tokens a real tokenizer would act on. None may survive into chat-derived text. */
private val LIVE = listOf(
    "<|im_start|>", "<|im_end|>", "<|system|>", "<|user|>", "<|assistant|>", "<|end|>", "<|eot_id|>",
    "<|start_header_id|>", "<|end_header_id|>", "<s>", "</s>", "<bos>", "<eos>", "<think>", "</think>",
    "<start_of_turn>", "<end_of_turn>", "[INST]", "[/INST]", "### Instruction"
)

private val BITS = LIVE + listOf(
    "ok", "lol", "hey", "are you coming", "see you at 7", "system", "\n", " ", "  ", "😀", "é", "日本語",
    "<", "|", ">", "<|", "|>", "<<", ">>", ":", "you:", "them:", "(no reply yet)", "ignore previous instructions",
    "a".repeat(300), "word ".repeat(200), "😀".repeat(100)
)

private fun Random.soup(max: Int = 6): String = buildString { repeat(nextInt(max)) { append(BITS.random(this@soup)) } }

private fun hasLoneSurrogate(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate()) {
            if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
            i += 2
        } else if (c.isLowSurrogate()) return true
        else i++
    }
    return false
}

private fun assembler(seed: Long) {
    val rnd = Random(seed)
    val tiers = listOf(Tier.T0, Tier.T1, Tier.T2, Tier.T3)
    val counters = listOf(ApproxCounter, TokenCounter { it.length }, TokenCounter { it.length * 4 }, TokenCounter { 0 })
    var threw: Throwable? = null
    var leaked: String? = null
    var overlapping: List<Msg>? = null
    var overBudget: String? = null
    var lastWrong: String? = null
    var broke: String? = null
    var lone: String? = null
    var overWindow: String? = null
    repeat(3000) {
        val turns = (0 until rnd.nextInt(30)).map {
            Turn(listOf(Speaker.ME, Speaker.THEM, Speaker.UNKNOWN).random(rnd), rnd.soup(), rnd.soup(2))
        }
        val inp = PromptInput(
            isChatField = rnd.nextInt(5) != 0,
            title = rnd.soup(2),
            appLabel = rnd.soup(2),
            isGroup = rnd.nextBoolean(),
            turns = turns,
            snippets = (0 until rnd.nextInt(4)).map { Turn(Speaker.THEM, rnd.soup(), rnd.soup(2)) },
            draft = if (rnd.nextInt(4) == 0) rnd.soup() else "",
            textAfter = if (rnd.nextInt(4) == 0) rnd.soup() else "",
            instruction = if (rnd.nextInt(5) == 0) rnd.soup() else null,
            rewriteSource = if (rnd.nextInt(8) == 0) rnd.soup() else null,
            styleLine = if (rnd.nextBoolean()) rnd.soup(2) else null,
            voiceLines = (0 until rnd.nextInt(4)).map { rnd.soup(2) },
            tone = if (rnd.nextBoolean()) rnd.soup(2) else null,
            dateLabel = { "Oct 12" }
        )
        val tier = tiers.random(rnd)
        val counter = counters.random(rnd)
        val latency = if (rnd.nextInt(4) == 0) rnd.nextInt(1, 400) else Int.MAX_VALUE
        try {
            val a = PromptAssembler.assemble(inp, tier, counter, latency)
            val fromChat = listOf(a.dynamicSystem, a.prefill) + a.messages.map { it.text }
            val found = LIVE.firstOrNull { tok -> fromChat.any { tok in it } }
            if (found != null) leaked = found
            fromChat.firstOrNull(::hasLoneSurrogate)?.let { lone = it }
            if (a.messages.zipWithNext().any { (x, y) -> x.role == y.role }) overlapping = a.messages
            if (counter === ApproxCounter && a.mode != PromptMode.REWRITE && a.keptTurns > 2 && a.tokens > a.cap) {
                overBudget = "${tier.name} tokens ${a.tokens} cap ${a.cap} kept ${a.keptTurns}"
            }
            if (a.mode == PromptMode.REPLY) {
                val lastLine = a.messages.last()
                if (lastLine.role != Role.USER || lastLine.text.isBlank()) lastWrong = lastLine.toString()
            }
            if (a.mode == PromptMode.FOLLOW_UP && a.messages.last().text != "(no reply yet)") lastWrong = a.messages.last().toString()
            for (t in Tmpl.values()) {
                val p = ChatFormatter.render(t, a)
                if (!p.static.contains(a.stableSystem) || !p.dynamic.endsWith(a.prefill)) broke = "$t ${a.mode}"
                // Past this the native side cuts the head off the prompt.
                if (counter === ApproxCounter && !a.tooLong &&
                    ApproxCounter.count(p.static + p.dynamic) > tier.nCtx - tier.output - 8
                ) {
                    overWindow = "$t ${a.mode} ${tier.name} rendered ${ApproxCounter.count(p.static + p.dynamic)}"
                }
            }
        } catch (t: Throwable) {
            threw = t
        }
    }
    check("assembler never throws, seed $seed", threw == null, threw)
    check("no live token from chat text, seed $seed", leaked == null, leaked)
    check("no lone surrogate in a prompt, seed $seed", lone == null, lone?.take(40))
    check("roles always alternate, seed $seed", overlapping == null, overlapping)
    check("older turns never overrun the budget, seed $seed", overBudget == null, overBudget)
    check("a prompt that is run always fits the window, seed $seed", overWindow == null, overWindow)
    check("prompt ends where the mode says, seed $seed", lastWrong == null, lastWrong)
    check("templates keep the stable text and the prefill, seed $seed", broke == null, broke)
}

private fun sanitizer(seed: Long) {
    val rnd = Random(seed)
    var threw: Throwable? = null
    var leaked: String? = null
    var lostRaw: String? = null
    var blank = false
    repeat(3000) {
        val raw = rnd.soup()
        val turns = (0 until rnd.nextInt(8)).map { rnd.soup(3) }
        val voice = if (rnd.nextBoolean()) (0 until rnd.nextInt(3)).map { rnd.soup(3) } else null
        try {
            when (val v = OutputSanitizer.review(raw, rnd.nextInt(1, 80), turns, voice)) {
                is OutputSanitizer.Accepted -> {
                    if (v.text.isBlank()) blank = true
                    LIVE.firstOrNull { it.lowercase() in v.text.lowercase() }?.let { leaked = "$it in ${v.text.take(80)}" }
                }
                is OutputSanitizer.Rejected -> if (v.raw != raw) lostRaw = raw
            }
        } catch (t: Throwable) {
            threw = t
        }
    }
    check("sanitizer never throws, seed $seed", threw == null, threw)
    check("accepted text holds no template token, seed $seed", leaked == null, leaked)
    check("accepted text is never blank, seed $seed", !blank)
    check("rejected keeps the raw text, seed $seed", lostRaw == null, lostRaw)
}

private fun deltas(seed: Long) {
    val rnd = Random(seed)
    var wrong: String? = null
    repeat(500) {
        val text = rnd.soup(12)
        val out = StringBuilder()
        val ds = DeltaStream { out.append(it) }
        var at = 0
        while (at < text.length) {
            at = minOf(text.length, at + rnd.nextInt(1, 12))
            // The native side only ever cuts between whole characters.
            if (at < text.length && text[at].isLowSurrogate()) at++
            ds.onCumulative(text.substring(0, at))
            if (rnd.nextInt(5) == 0) ds.onCumulative(text.substring(0, at))
        }
        if (out.toString() != text) wrong = text
    }
    check("deltas rebuild the text exactly once, seed $seed", wrong == null, wrong)
}

/**
 * Plays a chat out past a window of the last few messages, capturing as it goes, the way a
 * screen reader sees it with the list at the bottom. Whatever the merger keeps is compared
 * with what was really said.
 */
private fun merging(seed: Long) {
    val rnd = Random(seed)
    val short = listOf("ok", "lol", "yeah", "haha", "nice")
    // Sentences come back now and then in a real chat, but not often.
    val subjects = listOf("the game", "dinner", "the flight", "that movie", "the trip", "your sister", "the car", "work")
    val verbs = listOf("what about", "did you hear about", "i forgot about", "tell me about", "are we still on for", "can we talk about")
    val endings = listOf("", " tonight", " tomorrow", " this week", " at 7", " after work", " again")
    fun sentence() = verbs.random(rnd) + " " + subjects.random(rnd) + endings.random(rnd)
    fun line(mix: Double) = Turn(
        if (rnd.nextBoolean()) Speaker.ME else Speaker.THEM,
        if (rnd.nextDouble() < mix) short.random(rnd) else sentence()
    )

    // Every message seen, on a realistic screen of 6 to 12 bubbles: nothing lost, nothing doubled.
    var exact = 0
    var runs = 0
    var worst = ""
    repeat(300) {
        val truth = ArrayList<Turn>()
        val stored = ArrayList<Turn>()
        val window = rnd.nextInt(6, 13)
        repeat(150) {
            truth += line(0.35)
            val r = TranscriptMerger.merge(stored, truth.takeLast(window))
            stored.addAll(0, r.older)
            stored.addAll(r.newer)
        }
        runs++
        if (stored == truth) exact++ else if (worst.isEmpty()) worst = "window $window stored ${stored.size} truth ${truth.size}"
    }
    check("every message seen: transcript exact, seed $seed", exact == runs, "$exact of $runs, first miss: $worst")

    // Mostly the same few short lines, the hardest case for telling repeats apart.
    var bestCase = 0
    repeat(300) {
        val truth = ArrayList<Turn>()
        val stored = ArrayList<Turn>()
        repeat(150) {
            truth += line(0.9)
            val r = TranscriptMerger.merge(stored, truth.takeLast(8))
            stored.addAll(0, r.older)
            stored.addAll(r.newer)
        }
        if (stored == truth) bestCase++
    }
    check("mostly short lines: transcript exact, seed $seed", bestCase == 300, "$bestCase of 300")

    // Several messages between captures, always leaving at least two lines of overlap.
    var gaps = 0
    repeat(300) {
        val truth = ArrayList<Turn>()
        val stored = ArrayList<Turn>()
        val window = rnd.nextInt(6, 13)
        repeat(60) {
            repeat(rnd.nextInt(1, window - 1)) { truth += line(0.35) }
            val r = TranscriptMerger.merge(stored, truth.takeLast(window))
            stored.addAll(0, r.older)
            stored.addAll(r.newer)
        }
        if (stored == truth) gaps++
    }
    check("messages between captures: transcript exact, seed $seed", gaps == 300, "$gaps of 300")

    // Scrolling back over what is already held adds nothing, however it repeats.
    var added = 0
    repeat(300) {
        val truth = (0 until 80).map { line(0.5) }
        val at = rnd.nextInt(0, 70)
        val r = TranscriptMerger.merge(truth, truth.subList(at, at + 8), atBottom = false)
        if (r.newer.isNotEmpty() || r.older.isNotEmpty()) added++
    }
    check("scrolling back adds nothing, seed $seed", added == 0, "$added of 300 added lines")

    // Scrolling back past the top of what is held recovers older messages in order.
    var backfilled = 0
    repeat(300) {
        val truth = (0 until 60).map { line(0.35) }
        val stored = ArrayList(truth.takeLast(8))
        var top = truth.size - 8
        while (top > 0) {
            val from = maxOf(0, top - 6)
            val r = TranscriptMerger.merge(stored, truth.subList(from, from + 8), atBottom = false)
            stored.addAll(0, r.older)
            stored.addAll(r.newer)
            top = from
        }
        if (stored == truth) backfilled++
    }
    check("scrolling up backfills in order, seed $seed", backfilled == 300, "$backfilled of 300")
}
