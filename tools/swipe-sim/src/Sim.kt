import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.KeyboardGeometry
import com.localboard.keyboard.engine.SwipeDecoder
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** Offline glide-typing harness: synthesises sloppy human-like gestures for
 *  real dictionary words (with and without a previous-word context) and
 *  measures the decoder's top-1 / top-3 accuracy and latency. */
object Sim {
    const val KW = 100f
    const val KH = 128f
    val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    val ROW_OFF = floatArrayOf(0f, 0.5f, 1.5f)

    lateinit var geom: KeyboardGeometry
    val centre = HashMap<Char, PointF>()

    fun buildGeometry() {
        geom = KeyboardGeometry()
        geom.clear()
        for (r in ROWS.indices) {
            val row = ROWS[r]
            for (i in row.indices) {
                val left = (ROW_OFF[r] + i) * KW
                val top = r * KH
                val rect = RectF(left, top, left + KW, top + KH)
                geom.register(row[i], rect)
                centre[row[i]] = PointF(rect.centerX(), rect.centerY())
            }
        }
        geom.finishLayout()
    }

    class Noise(val pos: Float, val cut: Float, val jitter: Float, val dwellP: Float, val name: String)

    val NOISES = listOf(
        Noise(0.18f, 0.25f, 0.02f, 0.7f, "clean"),
        Noise(0.28f, 0.45f, 0.03f, 0.5f, "normal"),
        Noise(0.38f, 0.6f, 0.04f, 0.3f, "sloppy")
    )

    fun gauss(rnd: Random, s: Float): Float = (rnd.nextDouble() * 2 - 1 + rnd.nextDouble() * 2 - 1 + rnd.nextDouble() * 2 - 1).toFloat() * s * 0.8f

    /** Synthesise one gesture for [word]. */
    fun gesture(word: String, nz: Noise, rnd: Random): List<PointF> {
        val n = word.length
        val tx = FloatArray(n)
        val ty = FloatArray(n)
        for (i in 0 until n) {
            val c = centre[word[i]]!!
            tx[i] = c.x + gauss(rnd, nz.pos * KW)
            ty[i] = c.y + gauss(rnd, nz.pos * KH)
        }
        // Corner cutting for interior letters.
        for (i in 1 until n - 1) {
            if (word[i] == word[i - 1] || word[i] == word[i + 1]) continue
            // Humans round gentle corners but cannot skip a reversal: the
            // cut scales with sin(turn angle), zero for a straight-back turn.
            val ax = tx[i] - tx[i - 1]; val ay = ty[i] - ty[i - 1]
            val bx = tx[i + 1] - tx[i]; val by = ty[i + 1] - ty[i]
            val la = sqrt(ax * ax + ay * ay); val lb = sqrt(bx * bx + by * by)
            if (la < 1f || lb < 1f) continue
            val cross = kotlin.math.abs(ax * by - ay * bx) / (la * lb)   // sin(angle)
            val f = rnd.nextFloat() * nz.cut * cross
            val mx = (tx[i - 1] + tx[i + 1]) / 2f
            val my = (ty[i - 1] + ty[i + 1]) / 2f
            tx[i] += (mx - tx[i]) * f
            ty[i] += (my - ty[i]) * f
        }
        // Collapse repeated letters into one target, remembering dwell spots.
        val px = ArrayList<Float>()
        val py = ArrayList<Float>()
        val dwell = ArrayList<Boolean>()
        for (i in 0 until n) {
            if (i > 0 && word[i] == word[i - 1]) {
                dwell[dwell.size - 1] = true
                continue
            }
            px += tx[i]; py += ty[i]; dwell += false
        }
        val m = px.size
        val out = ArrayList<PointF>()
        // Touch-down rest.
        repeat(3) { out += PointF(px[0] + gauss(rnd, 1f), py[0] + gauss(rnd, 1f)) }
        if (m == 1) {
            repeat(4) { out += PointF(px[0] + gauss(rnd, 2f), py[0] + gauss(rnd, 2f)) }
            return out
        }
        // Catmull-Rom through the targets, sampled every ~0.08 key widths.
        fun cr(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val t2 = t * t; val t3 = t2 * t
            return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
        }
        for (i in 0 until m - 1) {
            val x0 = px[max(0, i - 1)]; val y0 = py[max(0, i - 1)]
            val x1 = px[i]; val y1 = py[i]
            val x2 = px[i + 1]; val y2 = py[i + 1]
            val x3 = px[minOf(m - 1, i + 2)]; val y3 = py[minOf(m - 1, i + 2)]
            val segLen = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
            val steps = max(2, (segLen / (0.08f * KW)).toInt())
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                val x = cr(x0, x1, x2, x3, t) + gauss(rnd, nz.jitter * KW)
                val y = cr(y0, y1, y2, y3, t) + gauss(rnd, nz.jitter * KW)
                out += PointF(x, y)
            }
            // Dwell at a doubled letter (the target we just LEFT was the
            // doubled one when i's dwell flag is set) — emitted at the start
            // of the segment, i.e. while still on that key.
            if (dwell[i] && i > 0 && rnd.nextFloat() < nz.dwellP) {
                val at = out.size - steps
                val dx = px[i]; val dy = py[i]
                val ins = ArrayList<PointF>()
                repeat(7) { ins += PointF(dx + gauss(rnd, 2.5f), dy + gauss(rnd, 2.5f)) }
                out.addAll(at, ins)
            }
        }
        out += PointF(px[m - 1], py[m - 1])
        // Lift rest.
        repeat(3) { out += PointF(px[m - 1] + gauss(rnd, 1.5f), py[m - 1] + gauss(rnd, 1.5f)) }
        // Thin like the view does (>= 2 px apart).
        val thin = ArrayList<PointF>()
        for (p in out) {
            if (thin.isEmpty()) { thin += p; continue }
            val l = thin.last()
            val d = sqrt((p.x - l.x) * (p.x - l.x) + (p.y - l.y) * (p.y - l.y))
            if (d >= 2f) thin += p
        }
        if (thin.last() !== out.last()) thin += out.last()
        return thin
    }

    data class Case(val word: String, val prev: String?)

    @JvmStatic
    fun main(args: Array<String>) {
        val assets = File(args[0])
        val nCases = args.getOrNull(1)?.toIntOrNull() ?: 600
        val seed = args.getOrNull(2)?.toIntOrNull() ?: 42
        val ctx = Context(assets)
        val engine = FastEngine(ctx)
        runBlocking { engine.loadBaseDictionary(); engine.loadBaseLanguageModel() }
        buildGeometry()
        val decoder = SwipeDecoder(engine, geom)
        // Force the table build synchronously via warmUp + wait.
        decoder.warmUp()
        var waited = 0
        while (decoder.decode(gesture("hello", NOISES[0], Random(1)), 1).isEmpty() && waited < 200) {
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
                val g = gesture(c.word, nz, rnd)
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
