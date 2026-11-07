import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.KeyboardGeometry
import com.localboard.keyboard.engine.NextCharPrior
import com.localboard.keyboard.engine.SwipeDecoder
import com.localboard.keyboard.engine.Trie
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs

/**
 * What FastEngine's learning and lookup calls do to its real maps and its real
 * dictionary. These cannot live with the unit tests: the engine needs an Android
 * Context and the bundled assets, which only the sim shims provide off a device.
 *
 * Words starting zq are invented, so the bundled data cannot be what passes a check.
 */
object EngineChecks {
    private var failed = 0
    private var passed = 0

    private fun check(name: String, ok: Boolean, detail: () -> Any? = { null }) {
        if (ok) {
            passed++
            println("ok    $name")
        } else {
            failed++
            println("FAIL  $name  ${detail() ?: ""}")
        }
    }

    private fun bar(e: FastEngine, p1: String, p2: String = "") =
        e.predict("", p1, p2, "", 5).map { it.text }

    private fun known(e: FastEngine, w: String) = e.allWords().any { it.first == w }

    private fun unlearn(e: FastEngine) {
        e.loadUserModel(emptyList(), emptyList(), emptyList())
        repeat(3) {
            e.learn("zqflight", "you", "are")
        }
        e.learn("zqflight", "we", "")
        e.learn("zqother", "you", "are")

        check("learn writes the rows", e.bigramRowLower("you")?.get("zqflight") == 3 &&
            e.trigramRowLower("are", "you")?.get("zqflight") == 3)

        check("a decrement that leaves counts behind reports false",
            !e.unlearnRecent("zqflight", "you", "are"))
        check("unigram lost one", e.userFrequency("zqflight") == 3) { e.userFrequency("zqflight") }
        check("bigram lost one", e.bigramRowLower("you")?.get("zqflight") == 2)
        check("trigram lost one", e.trigramRowLower("are", "you")?.get("zqflight") == 2)
        check("a row from another context is left alone", e.bigramRowLower("we")?.get("zqflight") == 1)
        check("a neighbour in the same row is left alone", e.bigramRowLower("you")?.get("zqother") == 1 &&
            e.trigramRowLower("are", "you")?.get("zqother") == 1)

        e.unlearnRecent("zqflight", "you", "are")
        e.unlearnRecent("zqflight", "you", "are")
        check("a row counted down to nothing leaves the index, its neighbour stays",
            e.bigramRowLower("you")?.containsKey("zqflight") == false &&
                e.trigramRowLower("are", "you")?.containsKey("zqflight") == false &&
                e.bigramRowLower("you")?.get("zqother") == 1)

        check("emptying the word reports true", e.unlearnRecent("zqflight", "we", ""))
        check("the word left the personal model", e.userFrequency("zqflight") == 0)
        check("an emptied word scrubs its other rows", e.bigramRowLower("we") == null)
        check("the last entry of a trigram row removes the row",
            e.unlearnRecent("zqother", "you", "are") &&
                e.trigramRowLower("are", "you") == null && e.bigramRowLower("you") == null)

        // Context rows with no unigram: hydrated and capped separately, so this is real.
        e.loadUserModel(emptyList(), listOf(Triple("you", "zqrow", 3)),
            listOf(Triple("are" to "you", "zqrow", 2)))
        check("context rows are undone without a unigram, reporting false",
            !e.unlearnRecent("zqrow", "you", "are") &&
                e.bigramRowLower("you")?.get("zqrow") == 2 &&
                e.trigramRowLower("are", "you")?.get("zqrow") == 1)
        check("no unigram and no context is a no-op", !e.unlearnRecent("zqrow") &&
            e.bigramRowLower("you")?.get("zqrow") == 2)
        check("a one letter word is refused", !e.unlearnRecent("z", "you", "are"))

        // The old one-argument form: counts only, rows untouched until the word empties.
        e.loadUserModel(emptyList(), emptyList(), emptyList())
        e.learn("zqsolo", "you", "are")
        e.learn("zqsolo", "you", "are")
        check("the one argument form keeps the rows while the word stays",
            !e.unlearnRecent("zqsolo") && e.userFrequency("zqsolo") == 1 &&
                e.bigramRowLower("you")?.get("zqsolo") == 2)
        check("the one argument form still scrubs on the last one",
            e.unlearnRecent("zqsolo") && e.bigramRowLower("you") == null &&
                e.trigramRowLower("are", "you") == null)

        // A real word must survive being taken back to nothing.
        e.loadUserModel(emptyList(), emptyList(), emptyList())
        e.learn("people", "", "")
        check("a dictionary word stays in the dictionary", e.unlearnRecent("people") && known(e, "people"))

        // The prediction LRU has to notice. Same key twice, with a change in between.
        e.loadUserModel(emptyList(), emptyList(), emptyList())
        e.learn("zqshown", "zqafter", "")
        check("a learned pair predicts", "zqshown" in bar(e, "zqafter"))
        e.learn("zqshown", "zqafter", "")
        e.unlearnRecent("zqshown", "zqafter", "")
        check("still shown while a count remains", "zqshown" in bar(e, "zqafter"))
        e.unlearnRecent("zqshown", "zqafter", "")
        check("gone from the same cached query once undone", "zqshown" !in bar(e, "zqafter"))
    }

    private fun verdicts(e: FastEngine) {
        e.loadUserModel(emptyList(), emptyList(), emptyList())
        check("no strike to begin with", !e.hasStrike("zqslip"))
        e.noteTypo("zqslip")
        check("first correction is a strike, in any case", e.hasStrike("ZQSLIP") && !e.isSuppressed("zqslip"))
        e.clearTypoVerdict("zqslip")
        check("a verdict clears the strike", !e.hasStrike("zqslip"))

        e.noteTypo("zqslip")
        e.noteTypo("zqslip")
        check("second correction blacklists", e.isSuppressed("zqslip") && !e.hasStrike("zqslip"))
        e.learn("zqslip", "zqafter", "")
        check("the blacklist holds against typing", e.userFrequency("zqslip") == 0)
        e.clearTypoVerdict("zqslip")
        check("a verdict clears the blacklist", !e.isSuppressed("zqslip") &&
            "zqslip" !in e.suppressionsSnapshot())
        e.learn("zqslip", "zqafter", "")
        check("after which it can be learned and predicted", e.userFrequency("zqslip") == 1 &&
            "zqslip" in bar(e, "zqafter"))

        // The verdict must not make anything known by itself.
        e.loadUserModel(listOf("zqcount" to 3), emptyList(), emptyList())
        e.clearTypoVerdict("zqcount")
        check("a verdict leaves counts alone", e.userFrequency("zqcount") == 3)
    }

    private fun confirmations(e: FastEngine) {
        // Bar tapped: confirmed, and at promotion strength, so it is in the trie.
        e.loadUserModel(listOf("zqtapped" to 4), emptyList(), emptyList())
        e.noteConfirmed("zqtapped")
        check("confirmed at promotion strength enters the dictionary", known(e, "zqtapped"))
        e.unconfirm("zqtapped")
        check("unconfirm takes back the entry it alone held up",
            !known(e, "zqtapped") && "zqtapped" !in e.confirmedWordsSnapshot())
        check("the count survives", e.userFrequency("zqtapped") == 4)
        check("and it can still be completed", e.predict("zqtap", "", "", "", 5).any { it.text == "zqtapped" })

        // Enough usage earns the entry on its own, confirmed or not.
        e.loadUserModel(listOf("zqstrong" to 8), emptyList(), emptyList())
        e.noteConfirmed("zqstrong")
        e.unconfirm("zqstrong")
        check("usage alone keeps a strong word in the dictionary", known(e, "zqstrong"))
        check("the shipped dictionary is untouched by unconfirm", run {
            e.noteConfirmed("people")
            e.unconfirm("people")
            known(e, "people")
        })
    }

    private fun rows(e: FastEngine) {
        e.loadUserModel(emptyList(), emptyList(), emptyList())
        check("no row is null, not empty", e.bigramRowLower("zqnone") == null &&
            e.trigramRowLower("zqnone", "zqnone") == null)
        e.learn("zqword", "ZQPREV", "ZQPREV2")
        check("rows are keyed on the lowercase words",
            e.bigramRowLower("zqprev")?.get("zqword") == 1 &&
                e.trigramRowLower("zqprev2", "zqprev")?.get("zqword") == 1)
        check("the caps are public and where the eviction uses them",
            FastEngine.MAX_PERSONAL_UNIGRAMS == 10_000 && FastEngine.MAX_PERSONAL_BIGRAMS == 32_000 &&
                FastEngine.MAX_PERSONAL_TRIGRAMS == 24_000 && FastEngine.SELECTION_BOOST == 4)
    }

    private fun prior(e: FastEngine, assets: File) {
        val out = FloatArray(27)
        check("an engine with no dictionary has no opinion",
            !FastEngine(Context(assets)).nextCharPrior("th", out))
        check("an unknown prefix has no opinion", !e.nextCharPrior("zqzqzq", out))
        check("a known prefix sums to one", e.nextCharPrior("th", out) && abs(out.sum() - 1f) < 1e-3f) { out.sum() }
        check("the likely letter leads", out.indices.maxByOrNull { out[it] } == 'e' - 'a') {
            out.indices.maxByOrNull { out[it] }
        }
        out.fill(0f)
        check("the prefix is case blind", e.nextCharPrior("TH", out) && abs(out.sum() - 1f) < 1e-3f)
        check("the end of a word has a slot", e.nextCharPrior("the", out) && out[26] > 0.1f) { out[26] }

        // The touch path's pure copy of the same walk has to give the same answer.
        val copy = Trie()
        for ((w, f) in e.allWords()) copy.insert(w, f)
        val a = FloatArray(27)
        val b = FloatArray(27)
        var compared = 0
        var differ = 0
        val letters = 'a'..'z'
        for (x in letters) for (y in letters) for (z in listOf("", "e", "a", "s")) {
            val p = "$x$y$z"
            val ra = e.nextCharPrior(p, a)
            val rb = NextCharPrior.fill(copy, p, b)
            compared++
            if (ra != rb || (ra && a.indices.any { abs(a[it] - b[it]) > 1e-5f })) differ++
        }
        check("agrees with NextCharPrior on $compared prefixes of the real dictionary", differ == 0) { differ }

        fun bad(n: Trie.Node): Int {
            var kids = n.frequency
            var errs = 0
            for (c in n.children.values) {
                kids += c.subtree
                errs += bad(c)
            }
            return errs + if (kids == n.subtree) 0 else 1
        }
        check("every subtree sum in the real dictionary adds up", bad(e.rootNode()!!) == 0)
    }

    private fun ranking(e: FastEngine) {
        val floorBigram = 1 * 3.0 + 2.5
        e.loadUserModel(emptyList(), emptyList(), emptyList())
        val starters = e.predict("", "zqunknown", "", "", 5)
        check("one sighting beats the strongest starter", floorBigram > starters.first().score) {
            starters.first()
        }
        e.loadUserModel(emptyList(), listOf(Triple("zqunknown", "zqonce", 1)), emptyList())
        check("and does in the engine", e.predict("", "zqunknown", "", "", 5).first().text == "zqonce")

        e.loadUserModel(emptyList(),
            listOf(Triple("zqtwo", "zqbi", 1), Triple("zqtwo", "zqtri", 1)),
            listOf(Triple("zqone" to "zqtwo", "zqtri", 1)))
        val scored = e.predict("", "zqtwo", "zqone", "", 5)
        check("the longer match wins at equal counts", scored[0].text == "zqtri" && scored[1].text == "zqbi") { scored }

        // Counts enter the next-word score as they are, so a word's most typed follower
        // holds the bar until a phrase has been typed about half as often: a phrase scores
        // 5.5t + 6 (trigram 4t + 3.5, halved bigram 1.5t + 2.5) and the pair 3p + 2.5, which
        // cross between 108 and 109 sightings at p = 200. ln-scaled counts move that below 10,
        // and they lost to these once the per-app and style boosts were modelled (see
        // RankSim's header), so a change here is a change to the formula and needs that gate.
        fun phraseLeads(typed: Int): Boolean {
            e.loadUserModel(emptyList(),
                listOf(Triple("zqtwo", "zqloud", 200), Triple("zqtwo", "zqquiet", typed)),
                listOf(Triple("zqone" to "zqtwo", "zqquiet", typed)))
            return e.predict("", "zqtwo", "zqone", "", 5)[0].text == "zqquiet"
        }
        check("a pair typed 200 times beats a phrase typed 10 times", !phraseLeads(10))
        check("and one typed 108 times", !phraseLeads(108))
        check("a phrase typed 109 times beats that pair", phraseLeads(109))
    }

    private fun decoderParams(e: FastEngine) {
        val kw = 108f
        val kh = 128f
        val geom = KeyboardGeometry()
        geom.clear()
        val centre = HashMap<Char, PointF>()
        val rowsOf = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val offset = floatArrayOf(0f, 0.5f, 1.5f)
        for (r in rowsOf.indices) for (i in rowsOf[r].indices) {
            val left = (offset[r] + i) * kw
            val top = r * kh
            val rect = RectF(left, top, left + kw, top + kh)
            geom.register(rowsOf[r][i], rect)
            centre[rowsOf[r][i]] = PointF(rect.centerX(), rect.centerY())
        }
        geom.finishLayout()
        val path = ArrayList<PointF>()
        val word = "hello"
        for (i in 0 until word.length - 1) {
            val a = centre.getValue(word[i])
            val b = centre.getValue(word[i + 1])
            for (t in 0 until 12) {
                val f = t / 12f
                path += PointF(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            }
        }
        path += centre.getValue(word.last())

        e.loadUserModel(emptyList(), emptyList(), emptyList())
        val decoder = SwipeDecoder(e, geom)
        decoder.warmUp()
        var waited = 0
        while (decoder.decode(path, 1).isEmpty() && waited < 400) {
            Thread.sleep(50)
            waited++
        }
        val plain = decoder.decode(path, 8, "say")
        check("decode returns candidates", plain.isNotEmpty())
        check("prev2 with no trigram row moves nothing", plain == decoder.decode(path, 8, "say", "to"))
        check("prev2 with no prev1 changes nothing", decoder.decode(path, 8) == decoder.decode(path, 8, null, "to"))

        val lBit = 1 shl ('l' - 'a')
        val noDwell = decoder.decode(path, 8, "say", null, 0)
        val dwelt = decoder.decode(path, 8, "say", null, lBit)
        fun scoreOf(list: List<com.localboard.keyboard.engine.Suggestion>) = list.firstOrNull { it.text == "hello" }?.score
        val sn = scoreOf(noDwell)
        val sd = scoreOf(dwelt)
        check("a given dwell mask reaches the score of a doubled letter",
            sn != null && sd != null && sd > sn) { "$sn -> $sd" }
        val otherLetter = decoder.decode(path, 8, "say", null, 1 shl ('z' - 'a'))
        check("a mask on a letter the word does not double gives no credit", scoreOf(otherLetter) == sn) {
            "${scoreOf(otherLetter)} vs $sn"
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val assets = File(args[0])
        val e = FastEngine(Context(assets))
        runBlocking {
            e.loadBaseDictionary()
            e.loadBaseLanguageModel()
        }
        unlearn(e)
        verdicts(e)
        confirmations(e)
        rows(e)
        prior(e, assets)
        ranking(e)
        decoderParams(e)
        PersonalModelChecks.run(assets, e) { name, ok, detail -> check(name, ok, detail) }
        println("\n$passed passed, $failed failed")
        if (failed > 0) System.exit(1)
    }
}
