import android.content.Context
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.SwipeDecoder
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.max
import kotlin.random.Random

/** Offline glide-typing harness. This part measures single dictionary words, half of them
 *  with a previous word chosen so the language model knows the pair, which flatters context.
 *  Gates is the measure that resembles use. */
object Sim {
    val board by lazy { deviceBoard() }

    data class Case(val word: String, val prev: String?)

    @JvmStatic
    fun main(args: Array<String>) {
        val assets = File(args[0])
        if (args.getOrNull(1) == "gates") {
            Gates.run(assets, args.drop(2))
            return
        }
        val nCases = args.getOrNull(1)?.toIntOrNull() ?: 600
        val seed = args.getOrNull(2)?.toIntOrNull() ?: 42
        val ctx = Context(assets)
        val engine = FastEngine(ctx)
        runBlocking { engine.loadBaseDictionary(); engine.loadBaseLanguageModel() }
        val decoder = SwipeDecoder(engine, board.geom)
        // Force the table build synchronously via warmUp + wait.
        decoder.warmUp()
        var waited = 0
        while (decoder.decode(gesture("hello", board, NOISES[0], IDEAL, Random(1)), 1).isEmpty() && waited < 200) {
            Thread.sleep(50); waited++
        }
        val rnd = Random(seed)

        // Test set: half from real bigram pairs (context), half bare words.
        val words = engine.allWords().filter { (w, _) -> w.length in 2..12 && w.all { it in 'a'..'z' } }
        val weights = DoubleArray(words.size) { Math.pow(words[it].second.toDouble(), (args.firstOrNull { it.startsWith("exp=") }?.substring(4)?.toDoubleOrNull() ?: 0.55)) }
        val cum = DoubleArray(words.size)
        var acc = 0.0
        for (i in words.indices) { acc += weights[i]; cum[i] = acc }
        fun sampleWord(): String {
            val r = rnd.nextDouble() * acc
            var lo = 0; var hi = words.size - 1
            while (lo < hi) { val mid = (lo + hi) / 2; if (cum[mid] < r) lo = mid + 1 else hi = mid }
            return words[lo].first
        }
        val lm = engine.languageModel!!
        val cases = ArrayList<Case>()
        var tries = 0
        while (cases.size < nCases && tries < nCases * 20) {
            tries++
            val w = sampleWord()
            if (cases.size % 2 == 0) {
                // Find a realistic prev for this word: scan a few random prevs.
                var prev: String? = null
                for (k in 0 until 40) {
                    val cand = sampleWord()
                    if (lm.prob(cand, w) > 0.0) { prev = cand; break }
                }
                if (prev == null) continue
                cases += Case(w, prev)
            } else cases += Case(w, null)
        }

        if (args.contains("predict")) {
            // "history": a user with weeks of typing behind them — a few
            // thousand sightings of the usual function words plus a couple
            // of personal phrases. This is the state that reproduced the
            // "you, the, just after every space" bar.
            if (args.contains("history")) {
                engine.loadUserModel(
                    unigrams = listOf(
                        "you" to 2400, "the" to 2100, "just" to 1900, "i" to 1800,
                        "to" to 1500, "and" to 1400, "it" to 1200, "that" to 900,
                        "ok" to 600, "lol" to 500, "yeah" to 400, "later" to 300
                    ),
                    bigrams = listOf(
                        Triple("are", "you", 6), Triple("see", "ya", 3),
                        Triple("talk", "later", 4), Triple("be", "there", 2)
                    ),
                    trigrams = listOf(Triple("i" to "will", "be", 3))
                )
                println("-- with synthetic personal history --")
            }
            fun show(prefix: String, p1: String, p2: String = "") {
                val r = engine.predict(prefix, p1, p2, "", 5)
                println(String.format("%-12s | %-8s | %-6s -> %s", "$p2 $p1".trim(), prefix, "", r.joinToString(", ") { it.text + "(" + "%.1f".format(it.score) + ")" }))
            }
            show("", "thank"); show("", "i"); show("", "see"); show("", "how"); show("", "good")
            show("", "let"); show("", "want"); show("", "you"); show("", "the"); show("", "are", "how")
            show("", "tomorrow"); show("", "lol"); show("", "me")
            show("", "talk"); show("", "can"); show("", "will", "i"); show("", "be"); show("", "going")
            show("th", "i"); show("t", "the"); show("w", "i"); show("b", "see", "you"); show("h", "say")
            show("c", "i"); show("g", "i'm"); show("wh", ""); show("re", "i"); show("om", "")
            show("t", "you", "see"); show("s", "you", "see"); show("m", "in", "be"); show("l", "you"); show("", ".")
            println("autocorrect 'i wnat': " + engine.autoCorrectTop("wnat", 3, prev1 = "i"))
            println("autocorrect 'teh': " + engine.autoCorrectTop("teh", 3))
            println("autocorrect 'a lot fo': " + engine.autoCorrectTop("fo", 3, minLen = 2, prev1 = "lot", prev2 = "a"))
            println("probe 'a lot if': " + engine.correctionProbe("if", "lot", "a"))
            return
        }
        val useCtx = args.getOrNull(3) != "noctx"
        println("cases=${cases.size}  ctx=${useCtx}  dictWords=${words.size}")
        for (nz in NOISES) {
            var top1 = 0; var top3 = 0; var none = 0
            var top1Ctx = 0; var nCtx = 0
            var top1Long = 0; var nLong = 0
            var ms = 0.0; var maxMs = 0.0
            val misses = ArrayList<String>()
            for (c in cases) {
                val g = gesture(c.word, board, nz, IDEAL, rnd)
                val prev = if (useCtx) c.prev else null
                val t0 = System.nanoTime()
                val res = decoder.decode(g, 4, prev)
                val dt = (System.nanoTime() - t0) / 1e6
                ms += dt; if (dt > maxMs) maxMs = dt
                val hit1 = res.isNotEmpty() && res[0].text == c.word
                val hit3 = res.any { it.text == c.word }
                if (res.isEmpty()) { none++; if (args.contains("verbose")) println("   NONE: ${c.word}") }
                if (hit1) top1++
                if (hit3) top3++
                if (c.prev != null) { nCtx++; if (hit1) top1Ctx++ }
                if (c.word.length >= 7) { nLong++; if (hit1) top1Long++ }
                if (!hit1 && misses.size < 30 && (args.contains("long").not() || c.word.length >= 7)) {
                    misses += "${c.prev ?: "-"}|${c.word} -> ${res.take(3).joinToString(",") { it.text }}"
                    if (args.contains("explain")) {
                        misses += "      want: " + decoder.explain(g, c.word, prev)
                        if (res.isNotEmpty()) misses += "      got:  " + decoder.explain(g, res[0].text, prev)
                    }
                }
            }
            val n = cases.size
            println(String.format("%-7s top1=%.1f%% top3=%.1f%% none=%d  top1(ctx)=%.1f%% top1(len>=7)=%.1f%%  avg=%.2fms max=%.1fms",
                nz.name, 100.0 * top1 / n, 100.0 * top3 / n, none,
                100.0 * top1Ctx / max(1, nCtx), 100.0 * top1Long / max(1, nLong), ms / n, maxMs))
            if (args.contains("verbose")) misses.forEach { println("   miss: $it") }
        }
    }
}
