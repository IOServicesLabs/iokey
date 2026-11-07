import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.KeyboardGeometry
import com.localboard.keyboard.engine.correction.CorrectionInput
import com.localboard.keyboard.engine.correction.CorrectionPolicy
import com.localboard.keyboard.engine.correction.FieldCorrection
import com.localboard.keyboard.engine.correction.LegacyTypoVerdicts
import com.localboard.keyboard.engine.correction.Provenance
import com.localboard.keyboard.engine.correction.Scenarios
import com.localboard.keyboard.engine.correction.Verdict
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlin.system.exitProcess

fun loadEngine(assets: File): FastEngine {
    val e = FastEngine(Context(assets))
    runBlocking {
        e.loadBaseDictionary()
        e.loadBaseLanguageModel()
    }
    return e
}

fun show(v: Verdict) = when (v) {
    is Verdict.AutoCommit -> v.word
    is Verdict.Hint -> "(" + v.word + ")"
    Verdict.Keep -> "-"
}

private fun pct(x: Double) = String.format(Locale.ROOT, "%.2f%%", x)

class GateTable(val title: String) {
    class Gate(val name: String, val target: String, val got: String, val ok: Boolean)

    val gates = ArrayList<Gate>()
    val missed get() = gates.filter { !it.ok }

    fun add(name: String, target: String, got: String, ok: Boolean) {
        gates += Gate(name, target, got, ok)
    }

    fun print() {
        println("\n=== Gates: $title")
        val nameWidth = gates.maxOf { it.name.length }
        val targetWidth = gates.maxOf { it.target.length }
        val gotWidth = gates.maxOf { it.got.length }
        for (g in gates) {
            println(g.name.padEnd(nameWidth) + "  " + g.target.padEnd(targetWidth) + "  " + g.got.padEnd(gotWidth) + "  " + if (g.ok) "ok" else "MISS")
        }
    }
}

/** A ten-key layout with keys 100 by 130, and a finger that lands with Gaussian noise on the key it meant. */
class Keys {
    val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    val centre = HashMap<Char, PointF>()
    val geometry = KeyboardGeometry()

    init {
        geometry.clear()
        val offsets = floatArrayOf(0f, 0.5f, 1.5f)
        for (r in rows.indices) for (i in rows[r].indices) {
            val left = (offsets[r] + i) * KEY_WIDTH
            val top = r * KEY_HEIGHT
            val rc = RectF(left, top, left + KEY_WIDTH, top + KEY_HEIGHT)
            geometry.register(rows[r][i], rc)
            centre[rows[r][i]] = PointF(rc.centerX(), rc.centerY())
        }
        geometry.finishLayout()
    }

    private fun nearest(x: Float, y: Float): Char {
        var best = 'a'
        var bd = Float.MAX_VALUE
        for ((c, p) in centre) {
            val d = (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y)
            if (d < bd) { bd = d; best = c }
        }
        return best
    }

    /** What the finger typed trying for [word], and where it landed. */
    fun tap(word: String, sigma: Float, r: java.util.Random): Pair<String, ArrayList<PointF>>? {
        val pts = ArrayList<PointF>()
        val sb = StringBuilder()
        for (c in word) {
            val p = centre[c] ?: return null
            val px = p.x + (r.nextGaussian().toFloat() * sigma * KEY_WIDTH)
            val py = p.y + (r.nextGaussian().toFloat() * sigma * KEY_HEIGHT)
            pts += PointF(px, py)
            sb.append(nearest(px, py))
        }
        return sb.toString() to pts
    }

    private companion object {
        const val KEY_WIDTH = 100f
        const val KEY_HEIGHT = 130f
    }
}

/** What each word follows in the shipped bigram table, so a sampled word can arrive in a likely context. */
fun loadPrevOf(assets: File): HashMap<String, ArrayList<Pair<String, Int>>> {
    val m = HashMap<String, ArrayList<Pair<String, Int>>>()
    File(assets, "base_bigrams.txt").forEachLine { line ->
        val parts = line.split('\t')
        for (i in 1 until parts.size) {
            val sp = parts[i].lastIndexOf(' ')
            if (sp <= 0) continue
            val c = parts[i].substring(sp + 1).toIntOrNull() ?: continue
            m.getOrPut(parts[i].substring(0, sp)) { ArrayList() }.add(parts[0] to c)
        }
    }
    return m
}

fun policyInput(e: FastEngine, keys: Keys, typed: String, pts: List<PointF>?, prev: String, level: Int): CorrectionInput {
    val lower = typed.lowercase()
    val ev = e.correctionEvidence(lower, prev, "", typed[0].isUpperCase())
    val sp = if (pts != null && pts.size == typed.length) e.spatialEvidence(lower, pts, keys.geometry, prev, "", level) else null
    return CorrectionInput(
        typed = typed, provenance = Provenance.TYPED, field = FieldCorrection.AUTO,
        charBefore = null, charAfter = null, separator = ' ', level = level,
        known = ev.known, confirmed = ev.confirmed, strongUsage = ev.strongUsage,
        rejected = false, banned = emptySet(), closedFix = ev.closedFix, candidates = ev.candidates,
        spatialWinner = sp, hasTrace = pts != null, contextOverride = ev.contextOverride, split = ev.split,
    )
}

/**
 * Everything a seed changes: which words get typed, in which context, and where the finger lands. The
 * random draws are in the order of the runs the thresholds were set on, so seed 11 reproduces them.
 */
class SeededRun(
    private val e: FastEngine,
    private val prevOf: Map<String, List<Pair<String, Int>>>,
    private val keys: Keys,
    private val seed: Int,
    private val t: GateTable,
) {
    private val dict = e.allWords().toMap()
    private val words = dict.entries.filter { en -> en.key.all { it in 'a'..'z' } && en.key.length >= 3 }
        .sortedByDescending { it.value }.take(6000)
    private val total = words.sumOf { it.value.toDouble() }

    /** Typos Balanced rewrote at the gated noise, in the order they came up, for the reload checks. */
    val rewritten = LinkedHashSet<String>()

    private class Row(var right: Int = 0, var wrong: Int = 0, var left: Int = 0, var chipRight: Int = 0)

    private fun input(typed: String, pts: List<PointF>?, prev: String, level: Int) = policyInput(e, keys, typed, pts, prev, level)

    /** A word by dictionary frequency, and the word before it by bigram frequency. */
    private fun sampleWord(rk: Random): Pair<String, String> {
        var x = rk.nextDouble() * total
        var pick = words.last().key
        for (en in words) {
            x -= en.value.toDouble()
            if (x <= 0) { pick = en.key; break }
        }
        val ps = prevOf[pick]
        val prev = if (ps.isNullOrEmpty()) "" else {
            val s = ps.sumOf { it.second.toLong() }
            var y = rk.nextDouble() * s
            var q = ps.first().first
            for ((a, c) in ps) {
                y -= c
                if (y <= 0) { q = a; break }
            }
            q
        }
        return pick to prev
    }

    /** The mixed set: finger slips plus the three errors a finger does not explain. Only 0.22 is gated. */
    fun mixed() {
        val letters = keys.rows.flatMap { it.toList() }
        val names = listOf("Light", "Balanced", "Bold", "Balanced no trace", "Bold no trace")
        val levelOf = mapOf("Light" to 1, "Balanced" to 2, "Bold" to 3, "Balanced no trace" to 2, "Bold no trace" to 3)
        for (sigma in listOf(0.18f, 0.22f)) {
            val gated = sigma == 0.22f
            val r = java.util.Random(seed.toLong())
            val rk = Random(seed)
            val byClass = HashMap<String, MutableMap<String, Row>>()
            val wrongShown = LinkedHashMap<String, Int>()
            fun mark(cls: String, n: String, v: Verdict, want: String, typed: String) {
                val cr = byClass.getOrPut(cls) { HashMap() }.getOrPut(n) { Row() }
                when (v) {
                    is Verdict.AutoCommit -> {
                        if (v.word == want) cr.right++ else {
                            cr.wrong++
                            if (n == "Balanced") wrongShown.merge("$typed->${v.word}($want)", 1, Int::plus)
                        }
                        if (gated && n == "Balanced") rewritten += typed
                    }
                    is Verdict.Hint -> { cr.left++; if (v.word == want) cr.chipRight++ }
                    Verdict.Keep -> cr.left++
                }
            }
            val quota = hashMapOf("slip" to 500, "transposition" to 400, "omission" to 400, "insertion" to 400)
            val got = HashMap<String, Int>()
            while (quota.any { (got[it.key] ?: 0) < it.value }) {
                val (pick, prev) = sampleWord(rk)
                val cls = listOf("slip", "transposition", "omission", "insertion")[rk.nextInt(4)]
                if ((got[cls] ?: 0) >= quota[cls]!!) continue
                val intended = when (cls) {
                    "slip" -> pick
                    "transposition" -> {
                        val i = rk.nextInt(pick.length - 1)
                        pick.substring(0, i) + pick[i + 1] + pick[i] + pick.substring(i + 2)
                    }
                    "omission" -> {
                        val i = rk.nextInt(pick.length)
                        pick.removeRange(i, i + 1)
                    }
                    else -> {
                        val i = rk.nextInt(pick.length)
                        val ch = pick[i]
                        val nb = letters.filter { it != ch }.minByOrNull { c ->
                            val a = keys.centre[c]!!
                            val b = keys.centre[ch] ?: keys.centre['a']!!
                            val dx = a.x - b.x
                            val dy = a.y - b.y
                            dx * dx + dy * dy + rk.nextFloat() * 8000f
                        }!!
                        pick.substring(0, i + 1) + (if (rk.nextBoolean()) ch else nb) + pick.substring(i + 1)
                    }
                }
                if (intended.length < 2) continue
                val tp = keys.tap(intended, sigma, r) ?: continue
                val typed = tp.first
                val pts: List<PointF> = tp.second
                if (typed == pick || dict.containsKey(typed)) continue
                got[cls] = (got[cls] ?: 0) + 1
                for (nm in names) {
                    val v = CorrectionPolicy.decide(input(typed, if (nm.endsWith("no trace")) null else pts, prev, levelOf[nm]!!))
                    mark(cls, nm, v, pick, typed)
                }
            }
            println("\n=== Mixed set, seed=$seed sigma=$sigma quotas=$quota")
            val weights = mapOf("slip" to 0.60, "transposition" to 0.15, "omission" to 0.15, "insertion" to 0.10)
            for (nm in names) {
                var wr = 0.0
                var ww = 0.0
                var wc = 0.0
                val sb = StringBuilder()
                for ((cls, m) in byClass.toSortedMap()) {
                    val a = m[nm] ?: Row()
                    val n = (a.right + a.wrong + a.left).coerceAtLeast(1)
                    sb.append(String.format(Locale.ROOT, "  %s fix=%.1f%% wrong=%.1f%%", cls.take(6), 100.0 * a.right / n, 100.0 * a.wrong / n))
                    wr += weights[cls]!! * a.right / n
                    ww += weights[cls]!! * a.wrong / n
                    wc += weights[cls]!! * a.chipRight / n
                }
                val prec = 100 * wr / (wr + ww)
                println(String.format(Locale.ROOT, "%-18s weighted: fixed=%.1f%% wrong=%.1f%% left=%.1f%% (right word on the chip=%.1f%%) prec=%.1f%% |%s",
                    nm, 100 * wr, 100 * ww, 100 * (1 - wr - ww), 100 * wc, prec, sb))
                if (gated && nm == "Balanced") {
                    t.add("Balanced precision", ">= 98.5%", pct(prec), prec >= 98.5)
                    t.add("Balanced typos fixed", ">= 75%", pct(100 * wr), 100 * wr >= 75.0)
                }
                if (gated && nm == "Bold") t.add("Bold typos fixed", ">= 83%", pct(100 * wr), 100 * wr >= 83.0)
            }
            println("Balanced wrong rewrites, top 40: " + wrongShown.entries.sortedByDescending { it.value }.take(40).joinToString(" ") { it.key + "x" + it.value })
            deliberate(sigma, r, gated)
        }
    }

    /** Words typed on purpose and spelled as meant, with taps that landed clean. Continues the mixed set's finger. */
    private fun deliberate(sigma: Float, r: java.util.Random, gated: Boolean) {
        val toks = DELIBERATE.split(' ').filter { !dict.containsKey(it) }.distinct() +
            ELONGATED.split(' ').filter { !dict.containsKey(it) }.distinct()
        for ((label, lv) in listOf("Light" to 1, "Balanced" to 2, "Bold" to 3)) {
            var dn = 0
            var dh = 0
            var benign = 0
            val fired = LinkedHashMap<String, Int>()
            for (tok in toks) repeat(10) {
                val tp = keys.tap(tok, sigma, r) ?: return@repeat
                if (tp.first != tok) return@repeat
                dn++
                val v = CorrectionPolicy.decide(input(tok, tp.second, "", lv))
                if (v is Verdict.AutoCommit) {
                    dh++
                    if (v.word == tok.dropLast(CorrectionPolicy.trailingRun(tok) - 1) && CorrectionPolicy.trailingRun(tok) == 2) benign++
                    fired.merge("$tok->${v.word}", 1, Int::plus)
                }
            }
            println(String.format(Locale.ROOT, "deliberate tokens %s: n=%d rewritten=%d (%.0f%%), of which doubled-tail collapses=%d: %s",
                label, dn, dh, 100.0 * dh / dn, benign, fired.entries.sortedByDescending { it.value }.take(30).joinToString(" ") { it.key + "x" + it.value }))
            if (gated && lv == 2) {
                val rate = 100.0 * dh / dn
                t.add("Deliberate tokens rewritten at Balanced, clean taps", "<= 25%", "${pct(rate)} of $dn", rate <= 25.0)
            }
        }
    }

    /** Pairs from the bigram table with the space lost, sampled the same way as the mixed set. */
    fun runOns() {
        val rk = Random(seed + 12)
        var n = 0
        var right = 0
        var wrong = 0
        var left = 0
        var tableHits = 0
        var dictLeft = 0
        val shownWrong = LinkedHashMap<String, Int>()
        while (n < 2000) {
            val (b, a) = sampleWord(rk)
            if (a.isEmpty() || !a.all { it in 'a'..'z' }) continue
            val typed = a + b
            if (typed.length > 24) continue
            if (dict.containsKey(typed)) { dictLeft++; continue }
            if (e.correctionEvidence(typed, "", "", false).closedFix != null) { tableHits++; continue }
            n++
            val want = "$a $b"
            val v = CorrectionPolicy.decide(input(typed, null, "", 2))
            if (v is Verdict.AutoCommit) {
                if (v.word.lowercase() == want) right++ else { wrong++; shownWrong.merge("$typed->${v.word}", 1, Int::plus) }
            } else left++
        }
        println("\n=== True run-ons from the bigram table, seed=$seed n=$n, no trace, no context")
        println("sampled concatenations outside n: closed-table hits=$tableHits, still dictionary entries=$dictLeft")
        println("policy at Balanced: right=$right wrong=$wrong left=$left   wrong examples: " + shownWrong.entries.sortedByDescending { it.value }.take(25).joinToString(" ") { it.key + "x" + it.value })
        val rightRate = 100.0 * right / n
        val wrongRate = 100.0 * wrong / n
        t.add("True missed spaces, of 2000", "right >= 95%, wrong <= 1%", "right ${pct(rightRate)}, wrong ${pct(wrongRate)}", rightRate >= 95.0 && wrongRate <= 1.0)
    }

    /** Correctly spelled words, in a likely context. Every rewrite here is a word the user meant being changed. */
    fun correctWords() {
        val rk = Random(seed + 20)
        var balanced = 0
        var bold = 0
        var chips = 0
        val shown = LinkedHashMap<String, Int>()
        repeat(CORRECT_WORDS) {
            val (w, p) = sampleWord(rk)
            val a = CorrectionPolicy.decide(input(w, null, p, 2))
            val b = CorrectionPolicy.decide(input(w, null, p, 3))
            if (a is Verdict.AutoCommit) balanced++
            if (b is Verdict.AutoCommit) { bold++; shown.merge("[$p] $w->${b.word}", 1, Int::plus) }
            if (a is Verdict.Hint || b is Verdict.Hint) chips++
        }
        println("\n=== Correct words in context, seed=$seed n=$CORRECT_WORDS: Balanced rewrites=$balanced, Bold rewrites=$bold, known words given a chip=$chips  " +
            shown.entries.sortedByDescending { it.value }.take(20).joinToString(" ") { it.key + "x" + it.value })
        t.add("Correct words in context rewritten, Balanced and Bold, of $CORRECT_WORDS", "0", "$balanced and $bold", balanced == 0 && bold == 0)
        t.add("Known words given a chip, of $CORRECT_WORDS", "0", "$chips", chips == 0)
    }

    private companion object {
        const val CORRECT_WORDS = 20000
        const val DELIBERATE = "aight ite oof sry soz imy bae bussin yalls ain woulda musta ayy sike cto pto gboard rivian ozempic deven jdoe cashapp faceid myshop mailto daxton lakeisha nvmd pwease pwetty ish johnsmith chonk gon tol wou hel morn wher tod whe"
        const val ELONGATED = "noo hiii sooo yesss heyy yayy yeahh reallyy thanksss thankss helloo byee okayy tooo"
    }
}

/** Gates with no random draw in them: one pass on one engine, whatever the seeds. */
private fun fixedGates(assets: File, t: GateTable) {
    val e = loadEngine(assets)
    learnedWordStaysOffered(e, t)
    verdictsLeaveShippedWordsAlone(e, t)
    headlineLoop(e, t)
    deleteAndRetype(e, t)
    silentAcceptance(e, t)
    parsersReadWhatTheEngineWrites(assets, t)
    val all = Scenarios().runAll()
    val failures = all.count { !it.ok }
    t.add("Scenario suite", "${Scenarios.EXPECTED_CHECKS} checks, 0 failures", "${all.size} checks, $failures failures",
        all.size == Scenarios.EXPECTED_CHECKS && failures == 0)
    ReloadModel(assets).fixedGates(t)
}

private fun learnedWordStaysOffered(e: FastEngine, t: GateTable) {
    println("\n=== Personal word visibility")
    val w = "zorptastic"
    val missedAt = ArrayList<Int>()
    for (i in 1..9) {
        e.learn(w, "", "")
        val offered = e.predict("zorp", "", "", "", 5).any { it.text == w }
        if (!offered) missedAt += i
        println("commits=$i count=${e.userFrequency(w)} offered=$offered in-trie=${e.allWords().any { it.first == w }}")
    }
    t.add("Learned word offered at counts 1 to 9", "always", if (missedAt.isEmpty()) "always" else "missed at $missedAt", missedAt.isEmpty())
}

private fun verdictsLeaveShippedWordsAlone(e: FastEngine, t: GateTable) {
    println("\n=== Typo verdicts")
    fun known(w: String) = e.correctionEvidence(w, "", "", false).known
    val version = e.trieVersion
    e.noteTypo("form")
    val first = e.trieVersion == version && known("form") && e.hasStrike("form") && !e.isSuppressed("form")
    e.noteTypo("form")
    val second = e.trieVersion == version && known("form") && !e.hasStrike("form") && e.isSuppressed("form")
    println("form: after strike 1 ok=$first, after strike 2 ok=$second")
    t.add("form stays known after strike 1 and 2, trieVersion unchanged", "yes", "strike 1 ok=$first, strike 2 ok=$second", first && second)
    e.clearTypoVerdict("form")
    val cleared = !e.hasStrike("form") && !e.isSuppressed("form")
    repeat(3) { e.learn("zorpa", "", "") }
    e.noteTypo("zorpa")
    val countAfterStrike = e.userFrequency("zorpa")
    e.noteConfirmed("zorpb")
    e.noteTypo("zorpb")
    val confirmedStruck = e.hasStrike("zorpb")
    println("cleared=$cleared, count after a strike on a word counted 3 times=$countAfterStrike, confirmed word struck=$confirmedStruck")
    t.add("Added: one strike costs a counted word nothing, a confirmed word takes none, clearing clears",
        "count 3, no strike, cleared", "count $countAfterStrike, struck=$confirmedStruck, cleared=$cleared",
        countAfterStrike == 3 && !confirmedStruck && cleared)
}

/** The complaint itself: taking a correction back must stick, every time the keyboard comes back. */
private fun headlineLoop(e: FastEngine, t: GateTable) {
    println("\n=== Headline loop against the real engine, Balanced, no trace")
    val tokens = "oof faceid woulda lakeisha nvmd pwease ozempic morn teh".split(' ')
    var looped = 0
    val failed = ArrayList<String>()
    for (tok in tokens) {
        val rig = Typist(e, EnginePort(e))
        val first = rig.typeSpace(tok)
        if (first == tok) {
            println("$tok: no rewrite at Balanced")
            continue
        }
        looped++
        val kept = rig.undoThenSpace()
        val line = StringBuilder("$tok: first=$first undo+space=$kept count=${e.userFrequency(tok)} strike=${e.hasStrike(tok)} kept=${rig.mem.keptCount(tok)} banned=${rig.mem.bannedFixes(tok)}")
        val counts = ArrayList<Int>()
        val results = ArrayList<String>()
        repeat(5) {
            rig.hideAndRestart()
            rig.clock.ms += 3_600_000
            results += rig.typeSpace(tok)
            counts += e.userFrequency(tok)
        }
        val monotonic = counts.zipWithNext().all { (a, b) -> b >= a }
        println(line.append(" cycles=$results counts=$counts monotonic=$monotonic suppressed=${e.isSuppressed(tok)} confirmed=${e.isConfirmed(tok)}"))
        if (kept != tok || results.any { it != tok } || !monotonic) failed += tok
    }
    t.add("Headline loop, all tokens", "monotonic, no re-correction", "$looped of ${tokens.size} looped, failed: $failed", looped > 0 && failed.isEmpty())
}

/** No undo this time: the correction is deleted as a word and the same word typed again. */
private fun deleteAndRetype(e: FastEngine, t: GateTable) {
    println("\n=== The fight without undo: word delete and retype, Balanced, no trace")
    val failed = ArrayList<String>()
    for (tok in "jhon tomarrow pwetty hsve".split(' ')) {
        val rig = Typist(e, EnginePort(e))
        val seq = ArrayList<String>()
        var cur = rig.typeSpace(tok)
        seq += cur
        repeat(4) {
            rig.wordDelete(cur)
            rig.clock.ms += 5_000
            cur = rig.typeSpace(tok)
            seq += cur
        }
        val soft = rig.mem.softCount(tok)
        val isProtected = rig.mem.isProtected(tok)
        val struck = e.hasStrike(tok)
        val blacklisted = e.isSuppressed(tok)
        println("$tok: $seq soft=$soft protected=$isProtected strike=$struck suppressed=$blacklisted count=${e.userFrequency(tok)}")
        if (seq.first() == tok || seq.drop(1).any { it != tok } || !isProtected || struck || blacklisted) failed += tok
    }
    t.add("Added: delete and retype wins on the second try, protected, no strike", "all stay as typed", "failed: $failed", failed.isEmpty())
}

private fun silentAcceptance(e: FastEngine, t: GateTable) {
    println("\n=== Silent acceptance: the fix stands when the next word commits")
    val rig = Typist(e, EnginePort(e))
    val first = rig.typeSpace("thsi")
    val before = rig.port.calls.toList()
    val second = rig.typeSpace("next")
    val after = rig.port.calls.toList()
    println("typed thsi next: field=[${rig.ed}] first=$first second=$second before-next=$before after-next=$after strike(thsi)=${e.hasStrike("thsi")}")
    t.add("Added: a correction teaches nothing until the next word commits", "nothing, then the fix and a strike",
        "before: ${before.size} calls, after: ${after.take(2)}",
        first == "this" && before.isEmpty() && "learn(this,false)" in after && "noteTypo(thsi)" in after && e.hasStrike("thsi"))
}

/** The one-shot will read rows the old code wrote, so the formats are taken from the engine, not retyped here. */
private fun parsersReadWhatTheEngineWrites(assets: File, t: GateTable) {
    println("\n=== Verdict rows, written by the engine and read by the parsers")
    val e = loadEngine(assets)
    e.noteTypo("zorpx")
    e.noteTypo("zorpy")
    e.noteTypo("zorpy")
    val suppressions = e.suppressionsSnapshotDated().joinToString(",")
    val strikes = e.strikesSnapshot().entries.joinToString(",") { "${it.key}=${it.value}" }
    println("typo_suppressions=$suppressions typo_strikes=$strikes")
    val struck = LegacyTypoVerdicts.struckWords(suppressions, strikes)
    val strike = LegacyTypoVerdicts.parseStrikes(strikes).singleOrNull()
    val suppression = LegacyTypoVerdicts.parseSuppressions(suppressions).singleOrNull()
    t.add("Added: the legacy parsers read what the engine writes", "both words, count and stamps", "$struck",
        struck == setOf("zorpx", "zorpy") && strike?.count == 1 && strike.at != null && suppression?.at != null)
}

/** Printed for reading, not gated: the words the thresholds were argued over. */
private fun handList(assets: File) {
    val e = loadEngine(assets)
    val keys = Keys()
    fun verdict(typed: String, prev: String, level: Int) = show(CorrectionPolicy.decide(policyInput(e, keys, typed, null, prev, level)))
    println("=== Hand list, no trace, no context: typed Light | Balanced | Bold  ((x) = chip only, - = nothing)")
    for (typed in "teh hsve realy tomorow thnks helo becuz wnat plase hwo gake yuo adn thsi wiht whhat thaat wjat recieve definately seperate occurence accomodate gon wou tol tod whe hel morn wher aight ite oof sry soz imy bae ain ayy sike cto pto gboard rivian ozempic deven jdoe faceid lakeisha nvmd pwease pwetty chonk woulda noo hiii sooo yesss heyy yayy thanksss byee wouldnt whod woudnt hed howd whys dont im alot dosent occured recomend suprise remeber happend i".split(' ')) {
        println(String.format(Locale.ROOT, "%-11s %-14s %-14s %-14s", typed, verdict(typed, "", 1), verdict(typed, "", 2), verdict(typed, "", 3)))
    }
    println("\n=== Context ties, no trace: [prev] typed Balanced | Bold")
    for ((p, typed) in listOf("i" to "wnat", "" to "wnat", "what" to "wnat", "thanks" to "hwo", "" to "hwo", "know" to "hwo", "and" to "adn", "the" to "tge", "for" to "teh", "you" to "realy", "so" to "tehn", "i" to "hsve", "good" to "morn", "on" to "ot", "what" to "ot")) {
        println(String.format(Locale.ROOT, "[%s] %-8s %-14s %-14s", p, typed, verdict(typed, p, 2), verdict(typed, p, 3)))
    }
    println("\n=== Run-on tokens: typed | Balanced verdict")
    for (typed in "ithink iwant ilove alittle ajob thisis sortof gotit itis ofthe inthe tothe bussin cashapp myshop mailto johnsmith facebook yourstruly goodnight thatthe inthis tohis theyare isnot anout".split(' ')) {
        println(String.format(Locale.ROOT, "%-12s %-14s", typed, verdict(typed, "", 2)))
    }
}

object Gates {
    /** The assets directory, then "hand", or "gates" and the seeds. */
    @JvmStatic
    fun main(args: Array<String>) {
        val assets = File(args[0])
        if (args[1] == "hand") {
            handList(assets)
            exitProcess(0)
        }
        val prevOf = loadPrevOf(assets)
        val keys = Keys()
        val tables = ArrayList<GateTable>()
        tables += GateTable("no seed in them").also { fixedGates(assets, it) }
        for (seed in args.drop(2).map { it.toInt() }) {
            val t = GateTable("seed $seed")
            tables += t
            val run = SeededRun(loadEngine(assets), prevOf, keys, seed, t)
            run.mixed()
            run.runOns()
            run.correctWords()
            println("\n=== Reload checks on typos seed $seed picked")
            ReloadModel(assets).seededGates(run.rewritten.take(SEEDED_TOKENS), t)
        }
        tables.forEach { it.print() }
        println("\n=== Summary. Simulated on synthetic typing and modelled rows, not measured on a person or a device.")
        for (t in tables) println("${t.title}: ${t.gates.size} gates, ${t.missed.size} missed" + t.missed.joinToString("") { "\n  MISS ${it.name}: ${it.got}, target ${it.target}" })
        exitProcess(if (tables.any { it.missed.isNotEmpty() }) 1 else 0)
    }

    private const val SEEDED_TOKENS = 200
}
