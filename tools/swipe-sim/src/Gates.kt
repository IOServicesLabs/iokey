import android.content.Context
import android.graphics.PointF
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.KeyLayoutMath
import com.localboard.keyboard.engine.Suggestion
import com.localboard.keyboard.engine.SwipeDecoder
import com.localboard.keyboard.touch.DeviceLayout
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.BitSet
import kotlin.math.max
import kotlin.random.Random

/**
 * Whole sentences of an invented chat corpus, glided with assumed fingers, under several
 * amounts of personal history, with the previous words as the keyboard would know them. A
 * sentence with one wrong word is what a user lives with, so that is reported beside the
 * word rates.
 *
 * Every figure this prints is simulated. The corpus is invented and the decoder's weights
 * were chosen with it in view, so read them as direction and rough size, not as a forecast.
 * Gestures are paired: within a seed, the same finger and noise draw the same gestures at
 * every history level and on every build of the decoder, so differences are not luck of the draw.
 */
object Gates {

    private class History(val name: String, val scale: Int, val includeTest: Boolean = false, val dirty: Boolean = false)

    private val NONE = History("none", 0)
    /** Every pair counted once, which is what most of a real history is. */
    private val X1 = History("x1", 1)
    /** A low count over a history that is not clean, see [dirtied]. */
    private val DIRTY = History("dirty-x3", 3, dirty = true)
    private val X30 = History("x30", 30)
    private val X300 = History("x300", 300)
    private val X3000 = History("x3000", 3000)
    private val REPEATED = History("repeated-x30", 30, includeTest = true)
    private val HISTORIES = listOf(NONE, X1, DIRTY, X30, X300, X3000, REPEATED)

    private class Cell {
        var words = 0
        var top1 = 0
        var top3 = 0
        var contractions = 0
        var contractionTop1 = 0
        var midContractions = 0
        var midContractionTop1 = 0
        var plain = 0
        var plainTop1 = 0
        var sentences = 0
        var sentencesRight = 0
        /** Which gestures and sentences came out right. Gestures are paired, so these say
         *  what a change of history lost and gained, which the rates hide. */
        val wordHits = BitSet()
        val sentenceHits = BitSet()
        val misses = HashMap<String, Int>()
        val wordRate get() = 100.0 * top1 / max(1, words)
        val top3Rate get() = 100.0 * top3 / max(1, words)
        val sentenceRate get() = 100.0 * sentencesRight / max(1, sentences)
        val contractionRate get() = 100.0 * contractionTop1 / max(1, contractions)
        val midContractionRate get() = 100.0 * midContractionTop1 / max(1, midContractions)
        val plainRate get() = 100.0 * plainTop1 / max(1, plain)
    }

    private class Times {
        private var a = LongArray(1 shl 16)
        private var n = 0
        fun add(nanos: Long) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = nanos
        }
        fun report(): String {
            val s = a.copyOf(n)
            s.sort()
            fun at(p: Double) = s[((n - 1) * p).toInt()] / 1e6
            return String.format("p50 %.2f ms  p95 %.2f ms  p99 %.2f ms  max %.1f ms  (%d decodes)", at(0.5), at(0.95), at(0.99), s[n - 1] / 1e6, n)
        }
    }

    private var board = deviceBoard()
    private lateinit var assets: File

    private fun freshEngine(): FastEngine {
        val e = FastEngine(Context(assets))
        runBlocking {
            e.loadBaseDictionary()
            e.loadBaseLanguageModel()
        }
        return e
    }

    private fun tokenize(line: String): List<String> =
        line.lowercase().split(' ').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The sentences as months of real use might have left them: about one word in ten replaced
     * by another, so that about one pair in ten is wrong, and every apostrophe split, as a phone's
     * old history has them, where "don't" went in as "don" then "t".
     */
    private fun dirtied(lines: List<String>): List<String> {
        val rnd = Random(97)
        val vocab = lines.flatMap { tokenize(it) }.distinct().sorted()
        return lines.map { l ->
            tokenize(l).flatMap { w ->
                val typed = if (rnd.nextDouble() < 0.1) vocab[rnd.nextInt(vocab.size)] else w
                typed.split('\'').filter { it.isNotEmpty() }
            }.joinToString(" ")
        }
    }

    /** Learns [lines] the way the keyboard's context tracker does, [scale] times over. */
    private fun train(engine: FastEngine, lines: List<String>, scale: Int) {
        val uni = HashMap<String, Int>()
        val bi = HashMap<Pair<String, String>, Int>()
        val tri = HashMap<Triple<String, String, String>, Int>()
        for (l in lines) {
            var p1 = "."
            var p2 = ""
            for (w in tokenize(l)) {
                if (w.length >= 2) {
                    uni.merge(w, scale, Int::plus)
                    bi.merge(p1 to w, scale, Int::plus)
                    if (p2.isNotEmpty()) tri.merge(Triple(p2, p1, w), scale, Int::plus)
                }
                p2 = p1
                p1 = w
            }
        }
        engine.loadUserModel(
            uni.map { it.key to it.value },
            bi.map { Triple(it.key.first, it.key.second, it.value) },
            tri.map { Triple(it.key.first to it.key.second, it.key.third, it.value) }
        )
    }

    private fun warmedDecoder(engine: FastEngine): SwipeDecoder {
        val d = SwipeDecoder(engine, board.geom)
        d.warmUp()
        var waited = 0
        val r = Random(1)
        while (d.decode(gesture("hello", board, NOISES[0], IDEAL, r), 1).isEmpty() && waited < 400) {
            Thread.sleep(50)
            waited++
        }
        return d
    }

    private fun evalSentences(
        d: SwipeDecoder, test: List<String>, f: Finger, nz: Noise, seed: Int, reps: Int, timed: Boolean, times: Times?
    ): Cell {
        val rnd = Random(seed)
        val c = Cell()
        for (rep in 0 until reps) for (line in test) {
            var p1 = "."
            var p2 = ""
            var ok = true
            c.sentences++
            for (w in tokenize(line)) {
                val letters = w.filter { it in 'a'..'z' }
                if (letters.length < 2) {
                    p2 = p1
                    p1 = w
                    continue
                }
                val path: List<PointF>? =
                    if (timed) capture(timedGesture(letters, board, nz, f, rnd), board) else gesture(letters, board, nz, f, rnd)
                var res: List<Suggestion> = emptyList()
                if (path != null) {
                    val t0 = System.nanoTime()
                    res = d.decode(path, 4, p1, p2.ifEmpty { null })
                    times?.add(System.nanoTime() - t0)
                }
                val hit1 = res.firstOrNull()?.text == w
                c.words++
                if (hit1) {
                    c.wordHits.set(c.words - 1)
                    c.top1++
                }
                if (res.take(3).any { it.text == w }) c.top3++
                if ('\'' in w) {
                    c.contractions++
                    if (hit1) c.contractionTop1++
                    if (p1 != ".") {
                        c.midContractions++
                        if (hit1) c.midContractionTop1++
                    }
                } else {
                    c.plain++
                    if (hit1) c.plainTop1++
                }
                if (!hit1) {
                    ok = false
                    c.misses.merge("$p1|$w->${res.firstOrNull()?.text}", 1, Int::plus)
                }
                p2 = p1
                p1 = w
            }
            if (ok) {
                c.sentenceHits.set(c.sentences - 1)
                c.sentencesRight++
            }
        }
        return c
    }

    private fun row(hist: String, finger: String, nz: String, c: Cell) = String.format(
        "%-13s %-12s %-7s word %5.1f  top3 %5.1f  sent %5.1f  contr %5.1f  mid-contr %5.1f  plain %5.1f",
        hist, finger, nz, c.wordRate, c.top3Rate, c.sentenceRate, c.contractionRate, c.midContractionRate, c.plainRate
    )

    private class Failure(val text: String)

    private const val IDEAL_WORD_TOLERANCE = -0.5
    private const val IDEAL_SENTENCE_TOLERANCE = -2.5
    /** Well under the decoder's 20 s cooldown, or a decoder that waited it out would pass. */
    private const val FIRST_LOAD_WAIT_MS = 10_000L
    private val SCORED_SEEDS = listOf(42, 7, 123)
    /** What the decoder scored by gate, on each of [SCORED_SEEDS], read from `run.sh gates` on the
     *  build server, where the gates run. They say nothing about what is good enough. Each seed answers
     *  to its own, to catch a loss on that seed, and they should rise as the decoder does: a change that
     *  is meant to move them replaces them with its own run's. Old flat floors stay only on rows given
     *  an atLeast. Re-baselined 2026-11-06 from rebaseline-20261106.log (desktop run, deterministic):
     *  the Gboard-parity geometry (keyHeight 56dp -> 40dp, owner ruling) shrank the keys, and Fingers.kt's
     *  fixed-pixel noise is relatively larger on smaller keys, so every noise-exposed row sits lower.
     *  Stored values are the run's one-decimal printed scores; an identical re-run lands exactly on them. */
    private val SEED_SCORES = mapOf(
        "contraction top-1, ideal, normal" to listOf(96.1, 100.0, 96.0),
        "mid-sentence contraction top-1, whole corpus, ideal, normal" to listOf(94.4, 94.4, 96.0),
        "word top-1, ideal, clean" to listOf(98.7, 99.0, 98.7),
        "word top-1, ideal, normal" to listOf(96.3, 96.6, 97.4),
        "word top-1, ideal, sloppy" to listOf(89.8, 89.3, 91.0),
        "word top-1, real-finger, clean" to listOf(88.8, 91.7, 91.1),
        "word top-1, real-finger, normal" to listOf(85.3, 85.0, 86.9),
        "word top-1, real-finger, sloppy" to listOf(76.5, 74.8, 75.7),
        "word top-1, hooked, clean" to listOf(97.6, 96.9, 97.0),
        "word top-1, hooked, normal" to listOf(91.1, 91.6, 90.8),
        "word top-1, hooked, sloppy" to listOf(83.5, 83.7, 83.1),
        // The one history line that has fallen short of its plain tolerance, because wrong pairs in
        // a history still count. Worth raising once they stop counting for as much.
        "sentences gain from history dirty-x3, real-finger, normal" to listOf(4.2, 0.0, 2.4),
        "twin rate, contractions mid-sentence, ideal, normal" to listOf(95.6, 96.7, 95.2),
        "twin rate, contractions mid-sentence, real-finger, normal" to listOf(59.6, 62.2, 63.3),
        "twin rate, plain words against a contraction, ideal, normal" to listOf(95.1, 94.7, 93.3),
        "twin rate, plain words against a contraction, real-finger, normal" to listOf(66.7, 63.1, 56.4)
    )
    /** How far under its score a seed may fall: this much, or one draw where a draw is worth more. */
    private const val FLOOR_MARGIN = 1.0
    private const val DEFAULT_REPS = 3
    /** The test sentences hold about 10 mid-sentence contractions, which at 3 repetitions is 3.3
     *  points a draw. The whole corpus holds 31, which at this many is 124 draws. */
    private const val MID_CONTRACTION_REPS = 4
    private const val TWIN_RATE_REPS = 5

    /** Null for a seed with no scores on file. */
    private fun seedFloor(name: String, seed: Int, draws: Int): Double? {
        val at = SCORED_SEEDS.indexOf(seed)
        return if (at < 0) null else SEED_SCORES.getValue(name)[at] - max(FLOOR_MARGIN, 100.0 / max(1, draws))
    }

    /** Runs the corpus on one seed and returns the gates it failed. */
    private fun runSeed(lines: List<String>, seed: Int, splitSeed: Int, reps: Int, verbose: Boolean, times: Times): List<Failure> {
        val shuffled = lines.shuffled(Random(splitSeed))
        val testN = shuffled.size * 3 / 10
        val test = shuffled.take(testN)
        val trainSet = shuffled.drop(testN)
        println("\n== seed $seed, split $splitSeed: ${test.size} test sentences x$reps, ${trainSet.size} history sentences. Simulated, invented corpus ==")

        val timedCells = HashMap<String, Cell>()
        val staticCells = HashMap<String, Cell>()
        var midSample = Cell()
        for (h in HISTORIES) {
            val engine = freshEngine()
            if (h.scale > 0) {
                val history = if (h.includeTest) trainSet + test else trainSet
                train(engine, if (h.dirty) dirtied(history) else history, h.scale)
            }
            val d = warmedDecoder(engine)
            // With no history nothing can leak from one sentence to another, so this row can read them all.
            if (h === NONE) {
                val withMid = lines.filter { l -> tokenize(l).drop(1).any { '\'' in it } }
                midSample = evalSentences(d, withMid, IDEAL, NOISES[1], seed, MID_CONTRACTION_REPS, timed = true, times = null)
            }
            val fingers = if (h === NONE) listOf(IDEAL, REAL_FINGER, OFFSET_FINGER, HOOKED_FINGER) else listOf(IDEAL, REAL_FINGER)
            for (f in fingers) for (nz in NOISES) {
                val c = evalSentences(d, test, f, nz, seed, reps, timed = true, times = times)
                timedCells["${h.name}/${f.name}/${nz.name}"] = c
                println(row(h.name, f.name, nz.name, c))
                if (verbose && nz.name == "normal") {
                    println("    misses: " + c.misses.entries.sortedByDescending { it.value }.take(16).joinToString("  ") { it.key + " x" + it.value })
                }
            }
            // The untimed form the history regression was first measured on.
            val c = evalSentences(d, test, IDEAL, NOISES[1], seed, reps, timed = false, times = null)
            staticCells[h.name] = c
            println(row(h.name, "untimed", "normal", c))
        }

        val failed = ArrayList<Failure>()
        fun gate(name: String, value: Double, min: Double, note: String = "") {
            val ok = value >= min
            println(String.format("GATE %-58s %6.1f >= %5.1f  %s%s", name, value, min, if (ok) "pass" else "FAIL", note))
            if (!ok) failed += Failure("seed $seed: $name $value < $min")
        }
        fun cell(h: History, f: Finger, nz: Noise) = timedCells.getValue("${h.name}/${f.name}/${nz.name}")
        // Another split or number of repetitions is another sample, which the scores do not describe.
        val scored = seed == splitSeed && reps == DEFAULT_REPS
        fun seedGate(name: String, value: Double, draws: Int, atLeast: Double? = null, note: String = "") {
            val floor = listOfNotNull(if (scored) seedFloor(name, seed, draws) else null, atLeast).maxOrNull()
            if (floor != null) gate(name, value, floor, note)
            else println(String.format("REPORT %-56s %6.1f  no score on file for this run%s", name, value, note))
        }
        fun wordRow(name: String, c: Cell, atLeast: Double? = null) = seedGate(name, c.wordRate, c.words, atLeast)
        fun lostGained(before: BitSet, after: BitSet): String {
            val lost = (before.clone() as BitSet).apply { andNot(after) }.cardinality()
            val gained = (after.clone() as BitSet).apply { andNot(before) }.cardinality()
            return "  lost $lost gained $gained"
        }
        fun wordGain(before: Cell, after: Cell) = after.wordRate - before.wordRate
        fun sentenceGain(before: Cell, after: Cell) = after.sentenceRate - before.sentenceRate
        println("-- gates, seed $seed")
        val (clean, normal, sloppy) = NOISES
        cell(NONE, IDEAL, normal).let { seedGate("contraction top-1, ideal, normal", it.contractionRate, it.contractions, atLeast = 90.0) }
        seedGate(
            "mid-sentence contraction top-1, whole corpus, ideal, normal", midSample.midContractionRate, midSample.midContractions,
            atLeast = 90.0, note = "  of ${midSample.midContractions} draws"
        )
        wordRow("word top-1, ideal, clean", cell(NONE, IDEAL, clean), atLeast = 98.0)
        wordRow("word top-1, ideal, normal", cell(NONE, IDEAL, normal), atLeast = 95.0)
        wordRow("word top-1, ideal, sloppy", cell(NONE, IDEAL, sloppy), atLeast = 87.0)
        // 88.5 since 2026-11-06 (owner ruling, same as the seed scores): the Gboard-parity geometry
        // (56dp -> 40dp keys) put this row's measured score at 88.8, under the old 89.0 flat floor.
        wordRow("word top-1, real-finger, clean", cell(NONE, REAL_FINGER, clean), atLeast = 88.5)
        wordRow("word top-1, real-finger, normal", cell(NONE, REAL_FINGER, normal), atLeast = 83.0)
        wordRow("word top-1, real-finger, sloppy", cell(NONE, REAL_FINGER, sloppy), atLeast = 72.0)
        wordRow("word top-1, hooked, clean", cell(NONE, HOOKED_FINGER, clean))
        wordRow("word top-1, hooked, normal", cell(NONE, HOOKED_FINGER, normal))
        wordRow("word top-1, hooked, sloppy", cell(NONE, HOOKED_FINGER, sloppy))
        // A paired delta near 97 to 99 percent moves by single gestures, and the ideal finger
        // has nothing to gain from history that would make up for one. Its lines allow what a
        // few close calls cost. The real finger has a lot to gain, so it must not lose.
        for (h in listOf(X1, DIRTY, X30, X300)) for (f in listOf(IDEAL, REAL_FINGER)) for (nz in listOf(clean, normal)) {
            val before = cell(NONE, f, nz)
            val after = cell(h, f, nz)
            val min = if (f === IDEAL) IDEAL_WORD_TOLERANCE else 0.0
            gate("word top-1 gain from history ${h.name}, ${f.name}, ${nz.name}", wordGain(before, after), min, lostGained(before.wordHits, after.wordHits))
        }
        for (h in listOf(X1, DIRTY, X300)) for (f in listOf(IDEAL, REAL_FINGER)) for (nz in listOf(clean, normal)) {
            val before = cell(NONE, f, nz)
            val after = cell(h, f, nz)
            val name = "sentences gain from history ${h.name}, ${f.name}, ${nz.name}"
            val note = lostGained(before.sentenceHits, after.sentenceHits)
            if (f === REAL_FINGER && h === DIRTY && nz === normal) {
                seedGate(name, sentenceGain(before, after), before.sentences, note = note)
            } else {
                gate(name, sentenceGain(before, after), if (f === IDEAL) IDEAL_SENTENCE_TOLERANCE else 0.0, note)
            }
        }
        // x300 and x3000 give the same rows while the caps saturate, so one line is enough to
        // catch the caps going. It is on the untimed form the 25-point regression was found on.
        for (f in listOf(IDEAL, REAL_FINGER)) for (nz in listOf(clean, normal)) {
            val before = cell(NONE, f, nz)
            val after = cell(X3000, f, nz)
            println(String.format("REPORT word top-1 gain from history x3000, %s, %s  %.1f%s", f.name, nz.name, wordGain(before, after), lostGained(before.wordHits, after.wordHits)))
            println(String.format("REPORT sentences gain from history x3000, %s, %s  %.1f%s", f.name, nz.name, sentenceGain(before, after), lostGained(before.sentenceHits, after.sentenceHits)))
        }
        val beforeUntimed = staticCells.getValue(NONE.name)
        val afterUntimed = staticCells.getValue(X3000.name)
        gate("sentences gain from history x3000, untimed, normal", sentenceGain(beforeUntimed, afterUntimed), IDEAL_SENTENCE_TOLERANCE, lostGained(beforeUntimed.sentenceHits, afterUntimed.sentenceHits))
        return failed
    }

    /** A given mask has to help a doubled word after a real pause, and do nothing else. */
    private fun dwellCase(seed: Int) {
        val engine = freshEngine()
        val d = warmedDecoder(engine)
        val all = engine.allWords()
        val common = all.filter { (w, f) -> w.length in 4..10 && f > 20000 && w.all { it in 'a'..'z' } }.map { it.first }
        val doubled = common.filter { w -> (2 until w.length - 2).any { w[it] == w[it - 1] } }
        val single = common.filter { w -> (1 until w.length).none { w[it] == w[it - 1] } }.take(250)
        println("\n-- dwell, seed $seed: ${doubled.size} words with an interior double. Simulated traces, mask from raw samples")
        val pause = Noise(0.28f, 0.45f, 0.03f, 1f, "pause")
        val noPause = Noise(0.28f, 0.45f, 0.03f, 0f, "no-pause")
        fun measure(label: String, words: List<String>, nz: Noise, useMask: Boolean) {
            val rnd = Random(seed)
            var n = 0
            var top1 = 0
            var fired = 0
            for (rep in 0 until 3) for (w in words) {
                val raw = timedGesture(w, board, nz, IDEAL, rnd)
                val path = capture(raw, board) ?: continue
                val mask = dwellMaskOf(raw, board)
                n++
                if (mask != 0) fired++
                val res = d.decode(path, 4, null, null, if (useMask) mask else 0)
                if (res.firstOrNull()?.text == w) top1++
            }
            println(String.format("%-34s %-8s %-9s top-1 %5.1f  mask fired on %5.1f%%  (n=%d)", label, nz.name, if (useMask) "mask" else "no mask", 100.0 * top1 / max(1, n), 100.0 * fired / max(1, n), n))
        }
        val dbl = doubled.take(150)
        for (nz in listOf(pause, noPause)) for (useMask in listOf(false, true)) measure("doubled words", dbl, nz, useMask)
        for (useMask in listOf(false, true)) measure("words with no double", single, noPause, useMask)
    }

    /** The commonest words at the bottom of the per-word bias, which is where a run of
     *  wiped words leaves them, against the same gestures with no bias. */
    private fun biasCase(lines: List<String>, seed: Int) {
        val engine = freshEngine()
        val d = warmedDecoder(engine)
        val counts = HashMap<String, Int>()
        for (l in lines) for (w in tokenize(l)) if (w.count { it in 'a'..'z' } >= 2) counts.merge(w, 1, Int::plus)
        val common = counts.entries.sortedByDescending { it.value }.take(25).map { it.key }
        println("\n-- per-word bias, seed $seed: the 25 commonest words of the corpus, word top-1. Simulated")
        for (bias in listOf(0, -5, 5)) {
            d.restoreBias(if (bias == 0) emptyMap() else common.associateWith { bias })
            val sb = StringBuilder(String.format("bias %+d, clamped by the decoder:", bias))
            for (nz in NOISES) {
                val rnd = Random(seed)
                var inN = 0
                var inTop1 = 0
                var outN = 0
                var outTop1 = 0
                for (rep in 0 until 3) for (l in lines) {
                    var p1 = "."
                    var p2 = ""
                    for (w in tokenize(l)) {
                        val letters = w.filter { it in 'a'..'z' }
                        if (letters.length >= 2) {
                            val path = capture(timedGesture(letters, board, nz, IDEAL, rnd), board)
                            val hit = path != null && d.decode(path, 4, p1, p2.ifEmpty { null }).firstOrNull()?.text == w
                            if (w in common) {
                                inN++
                                if (hit) inTop1++
                            } else {
                                outN++
                                if (hit) outTop1++
                            }
                        }
                        p2 = p1
                        p1 = w
                    }
                }
                sb.append(String.format("  %s common %5.1f other %5.1f", nz.name, 100.0 * inTop1 / max(1, inN), 100.0 * outTop1 / max(1, outN)))
            }
            println(sb)
        }
    }

    /** The boards a user can choose in settings, with noise of the same physical size. The
     *  gates hold for the stock board: here the question is only whether another one falls apart. */
    private fun boardsCase(lines: List<String>, seed: Int) {
        val test = lines.shuffled(Random(seed)).take(lines.size * 3 / 10)
        val stock = board
        val density = DeviceLayout.DENSITY
        val width = DeviceLayout.WIDTH_PX
        val boards = listOf(
            "stock" to KeyLayoutMath.metrics(density, width),
            "height x0.8" to KeyLayoutMath.metrics(density, width, heightScale = 0.8f),
            "height x1.3" to KeyLayoutMath.metrics(density, width, heightScale = 1.3f),
            "height x1.5" to KeyLayoutMath.metrics(density, width, heightScale = 1.5f),
            "spacing 0.4" to KeyLayoutMath.metrics(density, width, keySpacing = 0.4f),
            "spacing 1.8" to KeyLayoutMath.metrics(density, width, keySpacing = 1.8f),
            "one-handed" to KeyLayoutMath.metrics(density, width, oneHandedMode = 1)
        )
        println("\n-- boards, seed $seed, no history, word top-1 / sentences. Simulated")
        for ((name, m) in boards) {
            board = deviceBoard(m)
            val d = warmedDecoder(freshEngine())
            val sb = StringBuilder(String.format("%-12s", name))
            for ((f, nz) in listOf(IDEAL to NOISES[1], IDEAL to NOISES[2], REAL_FINGER to NOISES[1])) {
                val c = evalSentences(d, test, f, nz, seed, 2, timed = true, times = null)
                sb.append(String.format("  %s %s %5.1f / %5.1f", f.name, nz.name, c.wordRate, c.sentenceRate))
            }
            println(sb)
        }
        board = stock
    }

    /** Words that trace the same curve, so that only language can tell them apart. All of these
     *  have to hold, in both directions: a change that helps one side tends to cost the other. */
    private val TWIN_CONTRACTIONS_ALONE = listOf(
        "." to "it's", "." to "we're", "." to "i'll", "." to "i'd", "." to "let's", "." to "she'll"
    )
    private val TWIN_CONTRACTIONS_MID = listOf(
        "so" to "it's", "and" to "it's", "but" to "it's", "because" to "it's", "think" to "it's", "that" to "it's",
        "if" to "it's", "when" to "it's", "know" to "it's", "guess" to "it's", "like" to "it's", "said" to "it's",
        "hope" to "it's", "yeah" to "it's", "maybe" to "it's", "what" to "it's", "how" to "it's",
        "and" to "we're", "but" to "we're", "so" to "we're", "if" to "we're", "when" to "we're", "think" to "we're", "because" to "we're",
        "and" to "we'll", "but" to "we'll", "think" to "we'll", "hope" to "we'll", "then" to "we'll", "maybe" to "we'll",
        "and" to "i'll", "so" to "i'll", "but" to "i'll", "think" to "i'll", "good" to "i'll", "yet" to "i'll", "on" to "i'll",
        "so" to "i'd", "and" to "i'd", "think" to "i'd",
        "is" to "it's", "is" to "we're", "is" to "i'd", "feel" to "it's", "feel" to "we're", "feel" to "i'd",
        "and" to "he'll", "so" to "he'll", "but" to "he'll", "think" to "he'll",
        "and" to "she'll", "think" to "she'll",
        "so" to "let's", "too" to "let's"
    )
    private val TWIN_PLAIN = listOf(
        "." to "well", "i'm" to "well", "you're" to "well", "very" to "well", "as" to "well", "oh" to "well",
        "my" to "well", "do" to "well", "is" to "well", "feel" to "well", "get" to "well", "really" to "well",
        "." to "hell", "the" to "hell", "to" to "hell", "oh" to "hell", "in" to "hell", "as" to "hell", "is" to "hell", "was" to "hell",
        "feel" to "ill", "was" to "ill", "got" to "ill", "i'm" to "ill",
        "the" to "shell", "a" to "shell",
        "of" to "its", "in" to "its", "on" to "its", "to" to "its", "for" to "its", "with" to "its", "was" to "its",
        "they" to "were", "there" to "were", "you" to "were", "we" to "were", "who" to "were",
        "what" to "were", "where" to "were", "how" to "were",
        "these" to "ones", "those" to "ones", "the" to "ones",
        "the" to "shed"
    )
    /** The plain word has to beat its twin here, but "is" sits one key away and comes first, as
     *  it would for any rare word ending near the s until curated rows say "my is" is not English. */
    private val TWIN_PLAIN_OVER_RIVAL = listOf("your" to "id", "my" to "id", "the" to "id")
    /** Words that are not twins, kept from when this list only printed. */
    private val TWIN_OTHERS = listOf(
        "i" to "think", "you" to "want", "i" to "don't", "." to "i'm", "." to "that's", "." to "you're",
        "so" to "don't", "so" to "that's", "so" to "you're", "that" to "isn't", "her" to "i'm"
    )
    /** Some of these contradict each other on purpose, so no one pair is held to: pairs close to even
     *  in real chat, or that the bundled rows know nothing about, which only curated rows can settle. */
    private val TWIN_HARD = listOf(
        "so" to "we'll", "that" to "we're", "since" to "it's", "yeah" to "we'll", "ok" to "we'll", "how" to "we'll", "what" to "we're",
        "so" to "well", "all" to "well", "sea" to "shell", "like" to "hell", "and" to "its", "that" to "were"
    )
    /** How many of [TWIN_HARD] come out as listed. The list argues with itself, so the count is what
     *  can be held: it may rise, and it falls only with the reason named here. No reason yet. */
    private const val RATCHET_TWIN_HARD = 2
    /** Chat pairs that no weight or rule was chosen on, so that this count says something the strict
     *  lists cannot. Keep it that way. They come from a review of the decoder. Many are wrong today, and
     *  wait for curated rows, a second word of context or learned pairs. The run of "<word> we're" is
     *  right today, and a rule favouring "were" after a word with no sign of a clause would flip it. None
     *  may contradict a strict probe, because a pair and its twin cannot both pass: twinCase checks that. */
    private val TWIN_HELD_OUT = listOf(
        "is" to "i'll", "why" to "we're", "as" to "it's", "see" to "it's",
        "tomorrow" to "we'll", "tonight" to "we'll", "no" to "we'll", "right" to "we'll",
        "cool" to "we'll", "fine" to "we'll", "thanks" to "we'll",
        "ok" to "we're", "okay" to "we're", "yeah" to "we're", "no" to "we're", "lol" to "we're", "haha" to "we're",
        "tonight" to "we're", "tomorrow" to "we're", "bro" to "we're", "dude" to "we're", "right" to "we're",
        "anyway" to "we're", "actually" to "we're", "honestly" to "we're", "basically" to "we're", "on" to "we're",
        "sorry" to "we're", "thanks" to "we're", "cool" to "we're", "great" to "we're", "nice" to "we're", "fine" to "we're",
        "that" to "well", "after" to "its", "than" to "its", "did" to "its",
        "cops" to "were", "coworkers" to "were", "neighbors" to "were", "ppl" to "were", "roommates" to "were",
        "cats" to "were", "flights" to "were", "pics" to "were", "people" to "were", "kids" to "were", "friends" to "were",
        "he" to "lets", "she" to "lets", "it" to "lets", "you" to "wed", "on" to "wed", "next" to "wed",
        "no" to "id", "student" to "id"
    )
    /** How many of [TWIN_HELD_OUT] come out as listed. It may rise, and it falls only with the reason
     *  named here. No reason yet. */
    private const val RATCHET_HELD_OUT = 25
    /** How many of the six plain spellings after "is"/"feel" stay offered in the top four. Was all six,
     *  until the 2026-11-06 Gboard-parity geometry (owner ruling: keys 56dp -> 40dp) put "feel id" fifth
     *  — the same fixed-pixel finger noise that lowered the seed scores. Floor set to the post-change
     *  run's count (5), like the seed scores; it may rise, and falls only with a reason named here. */
    private const val RATCHET_OFFERED = 5

    private fun twinCase(): List<Failure> {
        val engine = freshEngine()
        val d = warmedDecoder(engine)
        val failed = ArrayList<Failure>()
        println("\n-- spellings that trace the same curve, clean gesture. Simulated")
        fun right(p1: String, w: String): Boolean {
            val letters = w.filter { it in 'a'..'z' }
            val res = d.decode(gesture(letters, board, NOISES[0], IDEAL, Random(5)), 4, p1)
            val ok = res.firstOrNull()?.text == w
            println(String.format("prev=%-6s want=%-8s %-4s -> %s", p1, w, if (ok) "ok" else "MISS", res.joinToString(", ") { it.text }))
            return ok
        }
        for ((name, probes) in listOf(
            "contractions with no context" to TWIN_CONTRACTIONS_ALONE,
            "contractions mid-sentence" to TWIN_CONTRACTIONS_MID,
            "plain words against a contraction" to TWIN_PLAIN,
            "other close spellings" to TWIN_OTHERS
        )) {
            val n = probes.count { (p1, w) -> right(p1, w) }
            val ok = n == probes.size
            println(String.format("GATE %-58s %3d of %3d  %s", "twin probes, $name", n, probes.size, if (ok) "pass" else "FAIL"))
            if (!ok) failed += Failure("twin probes, $name: $n of ${probes.size}")
        }
        val overRival = TWIN_PLAIN_OVER_RIVAL.count { (p1, w) ->
            val res = d.decode(gesture(w, board, NOISES[0], IDEAL, Random(5)), 8, p1).map { it.text }
            val at = res.indexOf(w)
            val rival = res.indexOfFirst { it.length > w.length && it.replace("'", "") == w }
            val ok = at >= 0 && (rival < 0 || at < rival)
            println(String.format("prev=%-6s %-8s above its twin %-4s -> %s", p1, w, if (ok) "ok" else "MISS", res.joinToString(", ")))
            ok
        }
        val overOk = overRival == TWIN_PLAIN_OVER_RIVAL.size
        println(String.format("GATE %-58s %3d of %3d  %s", "twin probes, plain word above its twin", overRival, TWIN_PLAIN_OVER_RIVAL.size, if (overOk) "pass" else "FAIL"))
        if (!overOk) failed += Failure("twin probes, plain word above its twin: $overRival of ${TWIN_PLAIN_OVER_RIVAL.size}")
        // A word and its twin cannot both come first, so the other direction after "is" and "feel" is
        // that the plain spelling is still offered. Picking it once is what teaches "is its".
        val clauseOrPlain = TWIN_CONTRACTIONS_MID.filter { (p1, _) -> p1 == "is" || p1 == "feel" }
        val offered = clauseOrPlain.count { (p1, w) ->
            val plain = w.filter { it in 'a'..'z' }
            val res = d.decode(gesture(plain, board, NOISES[0], IDEAL, Random(5)), 4, p1).map { it.text }
            val ok = plain in res
            println(String.format("prev=%-6s %-8s still offered %-4s -> %s", p1, plain, if (ok) "ok" else "MISS", res.joinToString(", ")))
            ok
        }
        val offeredOk = offered >= RATCHET_OFFERED
        println(String.format("GATE %-58s %3d of %3d, at least %d  %s", "twin probes, plain word still offered after is and feel", offered, clauseOrPlain.size, RATCHET_OFFERED, if (offeredOk) "pass" else "FAIL"))
        if (!offeredOk) failed += Failure("twin probes, plain word still offered after is and feel: $offered of ${clauseOrPlain.size}, under $RATCHET_OFFERED")
        fun ratchet(name: String, probes: List<Pair<String, String>>, floor: Int) {
            val n = probes.count { (p1, w) -> right(p1, w) }
            val ok = n >= floor
            println(String.format("GATE %-58s %3d of %3d, at least %d  %s", name, n, probes.size, floor, if (ok) "pass" else "FAIL"))
            if (!ok) failed += Failure("$name: $n of ${probes.size}, under $floor")
        }
        ratchet("near-even pairs come out as listed", TWIN_HARD, RATCHET_TWIN_HARD)
        ratchet("held-out probes come out as listed", TWIN_HELD_OUT, RATCHET_HELD_OUT)
        val strict = TWIN_CONTRACTIONS_ALONE + TWIN_CONTRACTIONS_MID + TWIN_PLAIN + TWIN_PLAIN_OVER_RIVAL + TWIN_OTHERS
        fun curve(w: String) = w.filter { it in 'a'..'z' }
        val clashes = TWIN_HELD_OUT.filter { (p1, w) -> strict.any { (sp, sw) -> sp == p1 && sw != w && curve(sw) == curve(w) } }
        println(String.format("GATE %-58s %3d  %s", "held-out probes that contradict a strict probe", clashes.size, if (clashes.isEmpty()) "pass" else "FAIL ${clashes}"))
        if (clashes.isNotEmpty()) failed += Failure("held-out probes contradict strict ones: $clashes")
        return failed
    }

    /** The same pairs as rates, with fingers that are not clean. The corpus cannot show these: most
     *  of its contractions open a sentence, and hardly any of the rest have a plain twin. */
    private fun twinRateCase(seed: Int): List<Failure> {
        println("\n-- twin pairs as rates, seed $seed, no history, timed traces, top-1. Simulated")
        val d = warmedDecoder(freshEngine())
        val failed = ArrayList<Failure>()
        val normal = NOISES[1]
        fun rate(probes: List<Pair<String, String>>, f: Finger): Double {
            val rnd = Random(seed)
            var n = 0
            var right = 0
            for (rep in 0 until TWIN_RATE_REPS) for ((p1, w) in probes) {
                val path = capture(timedGesture(w.filter { it in 'a'..'z' }, board, normal, f, rnd), board)
                n++
                if (path != null && d.decode(path, 4, p1).firstOrNull()?.text == w) right++
            }
            return 100.0 * right / max(1, n)
        }
        for ((name, probes) in listOf(
            "contractions mid-sentence" to TWIN_CONTRACTIONS_MID,
            "plain words against a contraction" to TWIN_PLAIN
        )) {
            for (f in listOf(IDEAL, REAL_FINGER)) {
                val row = "twin rate, $name, ${f.name}, normal"
                val v = rate(probes, f)
                val floor = seedFloor(row, seed, probes.size * TWIN_RATE_REPS)
                if (floor == null) {
                    println(String.format("REPORT %-56s %6.1f  no score on file for this seed", row, v))
                    continue
                }
                val ok = v >= floor
                println(String.format("GATE %-58s %6.1f >= %5.1f  %s", row, v, floor, if (ok) "pass" else "FAIL"))
                if (!ok) failed += Failure("seed $seed: $row: $v < $floor")
            }
        }
        return failed
    }

    /** Words a user has typed, in the shapes the penalty after "the" and "my" must not reach. */
    private fun typedPairCase(): List<Failure> {
        println("\n-- spellings a user has typed, clean gesture. Simulated")
        val engine = freshEngine()
        engine.loadUserModel(
            listOf("mom's" to 12, "friend's" to 12, "we're" to 3),
            listOf(
                Triple("my", "mom's", 3), Triple("my", "friend's", 3), Triple("our", "we're", 2),
                // Once is enough where the plain word is what the previous word usually wants.
                Triple("is", "i'll", 1), Triple("feel", "i'll", 1)
            ),
            emptyList()
        )
        val d = warmedDecoder(engine)
        val probes = listOf("my" to "mom's", "your" to "mom's", "my" to "friend's", "our" to "we're", "is" to "i'll", "feel" to "i'll")
        val n = probes.count { (p1, w) ->
            val res = d.decode(gesture(w.filter { it in 'a'..'z' }, board, NOISES[0], IDEAL, Random(5)), 4, p1)
            println(String.format("prev=%-6s want=%-8s -> %s", p1, w, res.joinToString(", ") { it.text }))
            res.firstOrNull()?.text == w
        }
        val ok = n == probes.size
        println(String.format("GATE %-58s %3d of %3d  %s", "typed contractions and possessives", n, probes.size, if (ok) "pass" else "FAIL"))
        return if (ok) emptyList() else listOf(Failure("typed contractions and possessives: $n of ${probes.size}"))
    }

    /** A table built just ahead of the learned words has to take them in as soon as they load,
     *  rather than after the cooldown, whether it had finished by then or not. */
    private fun firstLoadCase(): List<Failure> {
        println("\n-- learned words reach glide at the first load. Not an accuracy figure")
        val failed = ArrayList<Failure>()
        val word = "zorbly"
        val path = gesture(word, board, NOISES[0], IDEAL, Random(5))
        fun waitForWord(d: SwipeDecoder): Long {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < FIRST_LOAD_WAIT_MS) {
                if (d.decode(path, 1).firstOrNull()?.text == word) return System.currentTimeMillis() - t0
                Thread.sleep(25)
            }
            return -1L
        }
        val finished = freshEngine()
        val a = warmedDecoder(finished)
        finished.loadUserModel(listOf(word to 12), emptyList(), emptyList())
        a.warmUpAfterFirstLoad()
        val running = freshEngine()
        val b = SwipeDecoder(running, board.geom)
        b.warmUp()
        running.loadUserModel(listOf(word to 12), emptyList(), emptyList())
        b.warmUpAfterFirstLoad()
        for ((name, d) in listOf("table finished" to a, "table still building" to b)) {
            val ms = waitForWord(d)
            val ok = ms >= 0
            println(String.format("GATE %-58s %6d ms  %s", "learned word in glide, $name", ms, if (ok) "pass" else "FAIL"))
            if (!ok) failed += Failure("learned word not in glide within $FIRST_LOAD_WAIT_MS ms, $name")
        }
        return failed
    }

    fun run(assetDir: File, args: List<String>) {
        assets = assetDir
        val corpus = File(args.first { it.startsWith("corpus=") }.substring(7))
        val lines = corpus.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val seeds = args.mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(42, 7) }
        val splitSeed = args.firstOrNull { it.startsWith("split=") }?.substring(6)?.toIntOrNull()
        val reps = args.firstOrNull { it.startsWith("reps=") }?.substring(5)?.toIntOrNull() ?: DEFAULT_REPS
        val verbose = "verbose" in args
        val probesOnly = "probesonly" in args
        // Warm the JIT, so that the timings describe a keyboard already in use.
        val warmEngine = freshEngine()
        val warmDecoder = warmedDecoder(warmEngine)
        val warmRnd = Random(1)
        repeat(600) { warmDecoder.decode(gesture("something", board, NOISES[1], IDEAL, warmRnd), 4, "the") }

        val failed = ArrayList<Failure>()
        if (!probesOnly) for (seed in seeds) {
            val times = Times()
            failed += runSeed(lines, seed, splitSeed ?: seed, reps, verbose, times)
            println("-- decode cost, seed $seed, this JVM with other work on the machine, phone not measured: ${times.report()}")
        }
        if ("dwell" in args) for (seed in seeds) dwellCase(seed)
        if ("bias" in args) biasCase(lines, seeds.first())
        if ("boards" in args) boardsCase(lines, seeds.first())
        failed += twinCase()
        for (seed in seeds) failed += twinRateCase(seed)
        failed += typedPairCase()
        failed += firstLoadCase()
        println("\n${failed.size} gates failed on ${seeds.size} seeds. All accuracy above is simulated, on an invented corpus.")
        failed.forEach { println("  FAILED ${it.text}") }
        if (failed.isNotEmpty()) kotlin.system.exitProcess(1)
    }
}
