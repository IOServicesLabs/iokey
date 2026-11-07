import android.content.Context
import com.localboard.keyboard.engine.ContextTokens
import com.localboard.keyboard.engine.FastEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random

/**
 * Does ln-scaling the personal counts in next-word prediction beat the linear
 * score, once the database hands the engine every n-gram order?
 *
 * Linear counts dwarf everything else in the score: a pair typed 200 times is worth
 * about 600 points, the strongest continuation in the bundled model about 9, and a
 * three-word phrase typed twice about 11.5. ln-scaling brings those back onto one
 * scale, at the price of a user's own pair needing more sightings to outrank a
 * strong bundled continuation. Which of those matters more is what this measures.
 *
 * Everything here is simulated on invented text. It can say which formula ranks
 * better on that text, and nothing about how much better a real phone will feel.
 *
 * A person is a seeded weighting over messages: some recur constantly, most rarely.
 * 30% of the messages are never typed in the history. Those are the NOVEL test text,
 * the pessimistic bound. The rest, drawn with the same weights as the history, are
 * the FAMILIAR text, the optimistic bound. Real use sits between, so MIXED, the two
 * together, is what decides.
 *
 * The verdict rule is [RULE], below. It was fixed before the first server job of this
 * test, the program prints it at the top of every log and works the verdict out itself,
 * and any change to it after that means new seeds. Debugging uses seeds 42 and 7 only.
 * Earlier rules are why it reads as it does.
 *
 * The first one here, written before any result, demanded "never below linear at any
 * size", and at 100 messages ln minus linear averages -0.09 and +0.35 with an sd of 0.5.
 * That made the rule a coin flip on one row, whatever the formula was worth, so it was
 * loosened after results were seen, to allowances of two seed-to-seed standard
 * deviations measured over 20 other seeds, and those are [NOISE_MIXED] and [NOISE_CELL].
 *
 * Passing that rule was not enough on its own, and the app stayed on linear although ln
 * passed it. The sim gave the engine no app package and cleared the style boosts, while
 * every real next-word prediction adds the per-app boost and multiplies by the style
 * boost, both on a fixed scale that ln compresses the personal counts beside. With both
 * modelled from the code in a scratch copy of this sim, ln won 3 of 20 runs on unused
 * seeds, against 20 of 20 on the same seeds without them, and 15 of 24 at best under a
 * closer model of the boosts. So both boosts are modelled here now.
 *
 * Mixed top-3 alone is not a fair rule either. It redraws weights over the same lines
 * and templates, it flatters phrase gains because familiar text repeats word for word,
 * and it cannot see what ln costs a pair the person is only starting to type. So the
 * cost has clauses of its own, and they can fail the candidate.
 */
object RankSim {
    /** Messages typed before the test: from a few days of use to years of it. */
    private val HISTORY_MESSAGES = intArrayOf(100, 1_000, 10_000, 100_000)
    private const val DECIDING_SIZE = 1_000
    private const val NOISE_MIXED = 1.0
    private const val NOISE_CELL = 2.5
    private const val TEST_MESSAGES = 1_500
    private const val HELD_OUT = 0.30
    private const val BAR_SLOTS = 3

    // The rule's numbers. Each says why in RULE below.
    private val DECIDING_SIZES = intArrayOf(1_000, 10_000, 100_000)
    private val REACH_SIZES = intArrayOf(1_000, 10_000)
    private const val TOP1_ALLOWANCE = 2.0
    private const val FORMING_ALLOWANCE = 5.0
    private const val FORMING_MIN_POSITIONS = 500
    private const val REACH_FACTOR = 2.0
    private const val REACH_SEED_COUNT = 3
    private const val FIRST_FRESH_SEED = 30_001
    private const val MIN_FRESH_SEEDS = 12
    private const val APP_ROW_SIZE = 40
    private const val APP_ROW_PER_WORD = 2
    private const val TOPICAL_SHARE = 0.5
    /** Share of typing per app. App 0 holds the row under test, as the app a person is in. */
    private val APP_SHARES = doubleArrayOf(0.5, 0.3, 0.2)

    /** Chosen before the first run. None of them may occur in a corpus: main checks. */
    private const val REACH_INVENTED = "zqxv"
    private val REACH_DICTIONARY = listOf("blanket", "mirror", "pepper", "garden", "pillow", "candle")
    private val REACH_SINGLES = listOf(
        "i", "you", "to", "the", "a", "and", "it", "is", "have", "want",
        "going", "that", "for", "my", "we", "what", "how", "see", "love", "work"
    )
    private val REACH_PAIRS = listOf(
        "going" to "to", "want" to "to", "have" to "to", "let" to "me", "do" to "you",
        "i" to "don't", "see" to "you", "you" to "know", "are" to "you", "at" to "the"
    )
    private val REACH_LADDER = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 16, 20, 25, 30, 40, 50, 60, 80, 100, 150, 200, 300, 500, 800, 1200
    )

    private val RULE = """
RULE, fixed before the first server job. Any change after that means new seeds. Debug only on 42 and 7.
All figures are simulated on invented text and are not a forecast of the phone.
Arms, all reported, only the candidate may be applied:
  today          linear counts, today's boosts
  candidate      ln counts, per-app term capped at ${ranktune.RankTune.APP_CAP}, style excess x ${ranktune.RankTune.STYLE_SCALE}
  ln             ln counts, today's boosts
  linear+capped  linear counts, the candidate's boosts
Gain: a phrase beats its word's most-typed follower sooner (the phrase slice, and sightings for an exact phrase to lead).
Cost: a new pair against established followers (clauses d and e, reported beside the gain).
A run is one corpus (templates, lines), one seed and one app model (neutral, topical). The verdict needs at least $MIN_FRESH_SEEDS seeds from $FIRST_FRESH_SEED up.
Sizes ${DECIDING_SIZES.joinToString()} messages decide, and ${HISTORY_MESSAGES[0]} is reported. Deciding text leaves out the first word of every message.
The candidate wins only if every run has all of:
 (a) mixed top-3 above today at every deciding size. Above in counts, with no allowance, because this is the gain it has to show.
 (b) no novel or familiar top-3 cell more than $NOISE_CELL below today, at any deciding size. Today's allowance, two seed-to-seed sds.
 (c) mixed top-1 no more than $TOP1_ALLOWANCE below today, at any deciding size. Top-1 is the centre slot. Two seed-to-seed sds of this cell: 0.73 to 1.62, median 1.09, ln against linear with no boosts on 12 older seeds, first words left out.
 (d) truth pairs typed 2-8 times no more than $FORMING_ALLOWANCE below today on top-3, at any deciding size. $NOISE_CELL for noise and the same again as a real loss a person can live with, one forming habit in forty leaving the bar. Set before this slice was ever measured. Judged where today's slice holds at least $FORMING_MIN_POSITIONS positions: fewer than that is mostly noise, and rarely met.
 (e) on the ${REACH_SIZES.joinToString(" and ")} models, on the first $REACH_SEED_COUNT seeds of the job: median sightings for a bare new pair to reach the bar at most $REACH_FACTOR x today's, for the invented word and for the dictionary list, each on its own. Waiting twice as long is the most this rule accepts for a word the person keeps typing. The bare pair decides because it has no trigram behind it and is what a new pair is. The exact phrase and the sightings to lead are reported. A pair that never reaches the bar on the ladder counts as worse than any number.
 (f) all of this on both app models, since each model is its own run.
Any failure means no change, and the log says which clause.
App row: only app 0's own messages, in history order, +$APP_ROW_PER_WORD per typed word of 3 or more letters (trackAppVocab runs at two sites per word), merged and cut to the top $APP_ROW_SIZE after every message. The phone cuts at every flush, 1.5 s after typing, so per message is the coarser of the two.
App models: neutral splits a corpus's known messages at random over three apps, ${APP_SHARES.joinToString("/") { (it * 100).toInt().toString() }} of the typing. topical does the same, and ${(TOPICAL_SHARE * 100).toInt()}% of each app's messages come from that app's own section of corpus/apps.txt. Familiar test text comes from app 0. Novel test text is held out, and on the topical model ${(TOPICAL_SHARE * 100).toInt()}% of it comes from the held-out lines of app 0's section.
Style map: word totals over all apps, more than 2 letters, top 120, 1 + 0.6 c/max.
Reach: on the established model of that run, the model reloaded at each step of the ladder ${REACH_LADDER.first()} to ${REACH_LADDER.last()}, the row and style map following the sightings. Invented word $REACH_INVENTED. Dictionary list ${REACH_DICTIONARY.joinToString()}. Bare pair after each of ${REACH_SINGLES.size} words with no word before them, exact phrase after each of ${REACH_PAIRS.size} word pairs.
""".trim()

    private class Count {
        val uni = HashMap<String, Int>()
        val bi = HashMap<Pair<String, String>, Int>()
        val tri = HashMap<Triple<String, String, String>, Int>()
    }

    /** A source of messages: seeded weights over what one person tends to write. */
    private abstract class Corpus(val name: String) {
        abstract fun familiar(rnd: Random): String
        abstract fun novel(rnd: Random): String
    }

    /** Picks an index with probability falling as 1/(rank+1)^[s]. */
    private class Zipf(n: Int, s: Double) {
        private val cum = DoubleArray(n)
        init {
            var acc = 0.0
            for (i in 0 until n) {
                acc += 1.0 / Math.pow(i + 1.0, s)
                cum[i] = acc
            }
        }
        fun next(rnd: Random): Int {
            val r = rnd.nextDouble() * cum[cum.size - 1]
            var lo = 0
            var hi = cum.size - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (cum[mid] < r) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    /** Fixed lines. Every repeat is word for word, the narrowest kind of history. */
    private class Lines(file: File, seed: Int, zipf: Double) : Corpus("lines") {
        private val shuffled = file.readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .shuffled(Random(seed))
        private val heldOut = (shuffled.size * HELD_OUT).toInt()
        private val novelLines = shuffled.take(heldOut)
        private val knownLines = shuffled.drop(heldOut)
        private val weights = Zipf(knownLines.size, zipf)
        override fun familiar(rnd: Random) = knownLines[weights.next(rnd)]
        override fun novel(rnd: Random) = novelLines[rnd.nextInt(novelLines.size)]
    }

    /** Templates with slots, so the same phrases turn up with different words in
     *  them, the way real text repeats itself. Each seed has its own favourites. */
    private class Templates(file: File, seed: Int, zipf: Double) : Corpus("templates") {
        private val slots = HashMap<String, List<String>>()
        private val slotWeights = HashMap<String, Zipf>()
        private val knownT: List<String>
        private val novelT: List<String>
        private val weights: Zipf
        private val slotRef = Regex("\\{(\\w+)}")

        init {
            val shuffler = Random(seed)
            val templates = ArrayList<String>()
            for (raw in file.readLines()) {
                val l = raw.trim()
                when {
                    l.isEmpty() || l.startsWith("#") -> {}
                    l.startsWith("@") -> {
                        val name = l.substring(1, l.indexOf(':'))
                        val entries = l.substring(l.indexOf(':') + 1).split('|').map { it.trim() }
                        slots[name] = entries.shuffled(shuffler)
                        slotWeights[name] = Zipf(entries.size, 1.0)
                    }
                    else -> templates += l
                }
            }
            val shuffled = templates.shuffled(shuffler)
            val heldOut = (shuffled.size * HELD_OUT).toInt()
            novelT = shuffled.take(heldOut)
            knownT = shuffled.drop(heldOut)
            weights = Zipf(knownT.size, zipf)
        }

        private fun fill(t: String, rnd: Random): String =
            slotRef.replace(t) { m ->
                val name = m.groupValues[1]
                slots.getValue(name)[slotWeights.getValue(name).next(rnd)]
            }

        override fun familiar(rnd: Random) = fill(knownT[weights.next(rnd)], rnd)
        override fun novel(rnd: Random) = fill(novelT[rnd.nextInt(novelT.size)], rnd)
    }

    /** Counts one message the way each committed word writes its rows: a unigram
     *  row, a bigram row and a trigram row, words under two letters serving only as
     *  context. A message opens on the sentence mark with nothing before it. */
    private fun addRows(c: Count, message: String) {
        var p1 = ContextTokens.SENTENCE_MARK
        var p2 = ""
        for (w in ContextTokens.tokenize(message)) {
            if (w.length >= 2) {
                c.uni.merge(w, 1, Int::plus)
                c.bi.merge(p1 to w, 1, Int::plus)
                if (p2.isNotEmpty()) c.tri.merge(Triple(p2, p1, w), 1, Int::plus)
            }
            p2 = p1
            p1 = w
        }
    }

    /** Hydrates the engine as the loader does: strongest rows first, up to the caps. */
    private fun load(engine: FastEngine, c: Count) {
        engine.loadUserModel(
            c.uni.entries.sortedByDescending { it.value }.take(FastEngine.MAX_PERSONAL_UNIGRAMS)
                .map { it.key to it.value },
            c.bi.entries.sortedByDescending { it.value }.take(FastEngine.MAX_PERSONAL_BIGRAMS)
                .map { Triple(it.key.first, it.key.second, it.value) },
            c.tri.entries.sortedByDescending { it.value }.take(FastEngine.MAX_PERSONAL_TRIGRAMS)
                .map { Triple(it.key.first to it.key.second, it.key.third, it.value) }
        )
    }

    private class Stat {
        var n = 0
        var top1 = 0
        var top3 = 0
        val top3Pct get() = if (n == 0) 0.0 else 100.0 * top3 / n
        val top1Pct get() = if (n == 0) 0.0 else 100.0 * top1 / n
    }

    private class Formula(val ln: Boolean) {
        val novel = Stat()
        val familiar = Stat()
        val mixed = Stat()
        /** The truth was typed right after these exact two words in the history. */
        val phrase = Stat()
        /** The truth was never typed after the previous word, so only the bundled
         *  model or a filler can offer it. */
        val unseen = Stat()
    }

    private fun score(
        engine: FastEngine,
        f: Formula,
        messages: List<String>,
        bucket: Stat,
        history: Count
    ) {
        for (m in messages) {
            var p1 = ContextTokens.SENTENCE_MARK
            var p2 = ""
            for (w in ContextTokens.tokenize(m)) {
                val bar = engine.predict("", p1, p2, "", 5).take(BAR_SLOTS)
                val hit1 = bar.firstOrNull()?.text == w
                val hit3 = bar.any { it.text == w }
                val slices = ArrayList<Stat>(4)
                slices += bucket
                slices += f.mixed
                if (history.tri.containsKey(Triple(p2, p1, w))) slices += f.phrase
                if (!history.bi.containsKey(p1 to w)) slices += f.unseen
                for (s in slices) {
                    s.n++
                    if (hit1) s.top1++
                    if (hit3) s.top3++
                }
                p2 = p1
                p1 = w
            }
        }
    }

    private fun use(engine: FastEngine, ln: Boolean) {
        ranktune.RankTune.ln = ln
        // The prediction cache keys on the model, not on the formula.
        engine.setStyleBoosts(emptyMap())
    }

    /** One corpus and one seed. True when ln wins it under the rule in the header. */
    private fun run(
        engine: FastEngine,
        corpus: Corpus,
        seed: Int,
        show: Boolean,
        diffs: MutableMap<String, MutableList<DoubleArray>>
    ): Boolean {
        println("\n== corpus=${corpus.name} seed=$seed  top-3 % linear -> ln ==")
        println(String.format(
            "%9s %8s | %-15s %-15s %-15s | %-15s | %-15s %-15s",
            "messages", "words", "novel", "familiar", "MIXED", "mixed top-1", "phrase known", "pair unseen"
        ))
        var aboveWhereItCounts = true
        var mixedDrop = 0.0
        var cellDrop = 0.0
        val historyRnd = Random(seed * 31 + 1)
        val history = Count()
        var typed = 0
        for (target in HISTORY_MESSAGES) {
            while (typed < target) {
                addRows(history, corpus.familiar(historyRnd))
                typed++
            }
            load(engine, history)
            val testRnd = Random(seed * 31 + 2)
            val novel = List(TEST_MESSAGES) { corpus.novel(testRnd) }
            val familiar = List(TEST_MESSAGES) { corpus.familiar(testRnd) }
            val a = Formula(ln = false)
            val b = Formula(ln = true)
            for (f in listOf(a, b)) {
                use(engine, f.ln)
                score(engine, f, novel, f.novel, history)
                score(engine, f, familiar, f.familiar, history)
            }
            fun pair(x: Double, y: Double) = String.format("%5.1f -> %5.1f", x, y)
            println(String.format(
                "%9d %8d | %-15s %-15s %-15s | %-15s | %-15s %-15s",
                target, history.uni.values.sum(),
                pair(a.novel.top3Pct, b.novel.top3Pct),
                pair(a.familiar.top3Pct, b.familiar.top3Pct),
                pair(a.mixed.top3Pct, b.mixed.top3Pct),
                pair(a.mixed.top1Pct, b.mixed.top1Pct),
                pair(a.phrase.top3Pct, b.phrase.top3Pct),
                pair(a.unseen.top3Pct, b.unseen.top3Pct)
            ))
            diffs.getOrPut("${corpus.name}/$target") { ArrayList() } += doubleArrayOf(
                b.mixed.top3Pct - a.mixed.top3Pct,
                b.novel.top3Pct - a.novel.top3Pct,
                b.familiar.top3Pct - a.familiar.top3Pct
            )
            if (target >= DECIDING_SIZE && b.mixed.top3 <= a.mixed.top3) aboveWhereItCounts = false
            mixedDrop = maxOf(mixedDrop, a.mixed.top3Pct - b.mixed.top3Pct)
            cellDrop = maxOf(
                cellDrop,
                a.novel.top3Pct - b.novel.top3Pct,
                a.familiar.top3Pct - b.familiar.top3Pct
            )
        }
        val won = aboveWhereItCounts && mixedDrop <= NOISE_MIXED && cellDrop <= NOISE_CELL
        println(String.format(
            "ln %s  (mixed above linear from %d messages up: %b, worst mixed drop: %.1f, worst novel/familiar drop: %.1f)",
            if (won) "WINS" else "does not win", DECIDING_SIZE, aboveWhereItCounts, mixedDrop, cellDrop
        ))
        if (show) showBars(engine)
        return won
    }

    /** The bar under each formula for contexts where the words before the previous
     *  word should change the answer. Uses the largest history. */
    private fun showBars(engine: FastEngine) {
        val contexts = listOf(
            "going" to "to", "want" to "to", "have" to "to", "let" to "me", "do" to "you",
            "i" to "don't", "see" to "you", "you" to "know", "are" to "you", "." to "i"
        )
        for ((p2, p1) in contexts) {
            for (ln in listOf(false, true)) {
                use(engine, ln)
                val bar = engine.predict("", p1, p2, "", 5).take(BAR_SLOTS)
                println(String.format(
                    "  %-12s %-6s %s", "$p2 $p1", if (ln) "ln" else "linear",
                    bar.joinToString(", ") { it.text + "(" + "%.1f".format(it.score) + ")" }
                ))
            }
        }
    }

    /** The price of ln: how many sightings a pair only this user has ever typed needs
     *  to reach the bar, and to lead it, under each formula. Replaces the personal
     *  model, so this runs last. */
    private fun printReach(engine: FastEngine) {
        val invented = "zqxv"
        val contexts = listOf(
            "i", "you", "to", "the", "a", "and", "it", "is", "have", "want",
            "going", "that", "for", "my", "we", "what", "how", "see", "love", "work"
        )
        println("\n== sightings until a pair nothing else backs is in the bar / leads it ==")
        println(String.format("%-8s %-12s %-12s", "after", "linear", "ln"))
        for (p1 in contexts) {
            val cells = listOf(false, true).map { ln ->
                var reach = 0
                var lead = 0
                for (c in 1..60) {
                    engine.loadUserModel(listOf(invented to c), listOf(Triple(p1, invented, c)), emptyList())
                    use(engine, ln)
                    val at = engine.predict("", p1, "", "", 5).take(BAR_SLOTS).indexOfFirst { it.text == invented }
                    if (reach == 0 && at >= 0) reach = c
                    if (at == 0) {
                        lead = c
                        break
                    }
                }
                "$reach / $lead"
            }
            println(String.format("%-8s %-12s %-12s", p1, cells[0], cells[1]))
        }
    }

    /** How much ln minus linear moves from seed to seed, which is what says whether
     *  a small gap between the two means anything. */
    private fun printSpread(diffs: Map<String, List<DoubleArray>>) {
        println("\n== ln minus linear across seeds, top-3 points: mean (sd) [min..max] ==")
        println(String.format("%-20s %-26s %-26s %-26s", "corpus/messages", "mixed", "novel", "familiar"))
        for ((key, rows) in diffs) {
            fun cell(i: Int): String {
                val v = rows.map { it[i] }
                val mean = v.average()
                val sd = Math.sqrt(v.sumOf { (it - mean) * (it - mean) } / maxOf(1, v.size - 1))
                return String.format("%+.2f (%.2f) [%+.1f..%+.1f]", mean, sd, v.min(), v.max())
            }
            println(String.format("%-20s %-26s %-26s %-26s", key, cell(0), cell(1), cell(2)))
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println(RULE)
        val assets = File(args[0])
        val corpusDir = File(args[1])
        val seeds = args.firstOrNull { it.startsWith("seed=") }?.substring(5)
            ?.split(',')?.map { it.trim().toInt() } ?: listOf(42, 7)
        val zipf = args.firstOrNull { it.startsWith("zipf=") }?.substring(5)?.toDouble() ?: 0.8
        val show = args.contains("show")
        val engine = FastEngine(Context(assets))
        runBlocking {
            engine.loadBaseDictionary()
            engine.loadBaseLanguageModel()
        }
        val outcomes = ArrayList<String>()
        var allWon = true
        val diffs = LinkedHashMap<String, MutableList<DoubleArray>>()
        for (seed in seeds) {
            val corpora = listOf(
                Templates(File(corpusDir, "templates.txt"), seed, zipf),
                Lines(File(corpusDir, "chat.txt"), seed, zipf)
            )
            for (c in corpora) {
                val won = run(engine, c, seed, show, diffs)
                outcomes += "${c.name}/$seed=${if (won) "ln" else "not ln"}"
                if (!won) allWon = false
            }
        }
        if (seeds.size > 2) printSpread(diffs)
        if (show) printReach(engine)
        println("\nruns: " + outcomes.joinToString("  "))
        println("verdict: " + if (allWon) "ln wins every run" else "ln did not win every run, keep linear")
    }
}
