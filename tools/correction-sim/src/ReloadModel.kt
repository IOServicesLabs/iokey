import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.correction.LegacyTypoVerdicts
import com.localboard.keyboard.engine.correction.Verdict
import java.io.File

/**
 * The ngrams table and the verdict rows of style_stats, modelled in memory. SQLite is not run: the
 * writes and the SELECTs follow Daos.kt and ContextEngine.queuePersist, so this can drift from them.
 *
 * Rows come in two shapes. Stock rows are what the persist queue writes at commit bf247dd: one row on
 * the exact context, so a word committed mid-sentence never gains a bare count. All-order rows also
 * write the bare count and the bigram, which is what the reload gates depend on.
 */
class RowStore(val allOrders: Boolean) {
    val rows = HashMap<Triple<String, String, String>, Int>()   // prev1, prev2, word
    var suppressions: Set<String> = emptySet()
    var strikes: Map<String, String> = emptyMap()
    var confirmed: Set<String> = emptySet()
    var rejections: String? = null

    val shape get() = if (allOrders) "all-order rows" else "stock rows"

    fun queuePersist(p1: String, p2: String, w: String) {
        if (!allOrders) { rows.merge(Triple(p1, p2, w), 1, Int::plus); return }
        rows.merge(Triple("", "", w), 1, Int::plus)
        if (p1.isNotEmpty()) rows.merge(Triple(p1, "", w), 1, Int::plus)
        if (p1.isNotEmpty() && p2.isNotEmpty()) rows.merge(Triple(p1, p2, w), 1, Int::plus)
    }

    fun unigrams() = rows.filter { it.key.first == "" && it.key.second == "" }.map { it.key.third to it.value }

    /** The stock SELECT has no prev1 condition, so a bare count reads back as a bigram after nothing as well. */
    fun bigrams() = rows.filter { it.key.second == "" && (!allOrders || it.key.first != "") }
        .map { Triple(it.key.first, it.key.third, it.value) }

    fun trigrams() = rows.filter { it.key.second != "" }.map { Triple(it.key.second to it.key.first, it.key.third, it.value) }

    /** NGramDao.deleteUnigram: the bare count goes, the context rows stay. */
    fun deleteUnigram(w: String) { rows.remove(Triple("", "", w)) }

    /** NGramDao.deleteWord: every row that ends in the word. */
    fun deleteWord(w: String) { rows.keys.removeIf { it.third == w } }

    /** The verdict rows as ContextEngine writes them to style_stats. */
    fun suppressionsRow() = suppressions.joinToString(",")
    fun strikesRow() = strikes.entries.joinToString(",") { "${it.key}=${it.value}" }

    /** The aggregating Room migration, from its SQL: bigrams from trigrams, then unigrams from bigrams, MAX on conflict. */
    fun aggregate() {
        val bi = rows.entries.filter { it.key.second != "" }.groupBy { it.key.first to it.key.third }
            .mapValues { en -> en.value.sumOf { it.value } }
        for ((k, c) in bi) rows.merge(Triple(k.first, "", k.second), c) { a, b -> maxOf(a, b) }
        val uni = rows.entries.filter { it.key.first != "" && it.key.second == "" }.groupBy { it.key.third }
            .mapValues { en -> en.value.sumOf { it.value } }
        for ((w, c) in uni) rows.merge(Triple("", "", w), c) { a, b -> maxOf(a, b) }
    }
}

/** ContextEngine as the correction wiring needs it: every call also reaches the modelled rows. */
class RowWritingPort(e: FastEngine, private val s: RowStore) : EnginePort(e) {
    private fun persistVerdicts() {
        s.suppressions = e.suppressionsSnapshotDated()
        s.strikes = e.strikesSnapshot()
    }
    override fun learn(word: String, prev1: String, prev2: String, selected: Boolean) {
        val w = word.trim().lowercase()
        if (w.length < 2 || w.any { it.isDigit() }) return
        val hadStrike = e.hasStrike(w)
        val wasSuppressed = e.isSuppressed(w)
        e.learn(w, prev1, prev2, selected)
        if ((wasSuppressed && !e.isSuppressed(w)) || (hadStrike && !e.hasStrike(w))) persistVerdicts()
        s.queuePersist(prev1.lowercase(), prev2.lowercase(), w)
        if (selected) s.rows.merge(Triple("", "", w), 3, Int::plus)
    }
    override fun unlearn(word: String) {
        e.unlearnRecent(word)
        val k = Triple("", "", word.lowercase())
        if (k in s.rows) s.rows[k] = s.rows.getValue(k) - 1
    }
    override fun unconfirm(word: String) {
        e.unconfirm(word)
        s.confirmed = e.confirmedWordsSnapshot()
    }
    override fun noteTypo(word: String) {
        val w = word.trim().lowercase()
        e.noteTypo(w)
        if (e.isSuppressed(w)) s.deleteWord(w)
        persistVerdicts()
    }
    override fun clearTypoVerdict(word: String) {
        e.clearTypoVerdict(word)
        persistVerdicts()
    }
    override fun tapSignal(raw: String, fix: String) {}
    override fun persistRejections() {
        super.persistRejections()
        s.rejections = savedRejections
    }
}

/**
 * Restarts for real: each process is a fresh FastEngine hydrated through loadUserModel in the order
 * ContextEngine.initialize uses, except that a struck word stays in the model, because a strike is
 * only a note now. Every number from here is on modelled rows and synthetic words.
 */
class ReloadModel(private val assets: File) {
    private val clock = Clock()

    private inner class Process(val s: RowStore) {
        val e = hydrate(s)
        val typist = Typist(e, RowWritingPort(e, s), clock, s.rejections)
    }

    private fun hydrate(s: RowStore): FastEngine {
        val e = loadEngine(assets)
        e.restoreSuppressions(s.suppressions)
        e.restoreStrikes(s.strikes)
        e.restoreConfirmed(s.confirmed)
        val live = s.suppressions.map { it.substringBefore('@').lowercase() }.filter { e.isSuppressed(it) }.toSet()
        live.forEach { e.removeWordEverywhere(it) }
        if (live.isNotEmpty()) e.restoreSuppressions(s.suppressions)   // removal re-stamps them
        e.loadUserModel(
            s.unigrams().filter { it.first !in live },
            s.bigrams().filter { it.second !in live },
            s.trigrams().filter { it.second !in live },
        )
        return e
    }

    class Headline(val looped: List<String>, val reCorrected: List<String>, val notRising: List<String>, val lines: List<String>)

    /** One revert, then five restarts. Tokens the engine did not rewrite have nothing to revert and are left out. */
    fun headline(allOrders: Boolean, tokens: List<String>): Headline {
        val s = RowStore(allOrders)
        var p = Process(s)
        val first = LinkedHashMap<String, String>()
        for (t in tokens) {
            p.typist.midSentence()
            val f = p.typist.typeSpace(t)
            if (f != t) first[t] = "$f, undo+space=${p.typist.undoThenSpace()}"
            clock.ms += 60_000
        }
        p.typist.hide()
        val looped = first.keys.toList()
        val results = looped.associateWith { ArrayList<String>() }
        val loaded = looped.associateWith { ArrayList<Int>() }
        val after = looped.associateWith { ArrayList<Int>() }
        repeat(5) {
            clock.ms += DAY
            p = Process(s)
            for (t in looped) {
                loaded.getValue(t) += p.e.userFrequency(t)
                p.typist.midSentence()
                results.getValue(t) += p.typist.typeSpace(t)
                after.getValue(t) += p.e.userFrequency(t)
            }
            p.typist.hide()
        }
        clock.ms += DAY
        p = Process(s)
        val lines = looped.map { t ->
            "$t: first=${first[t]} | typed again=${results[t]} | count at load=${loaded[t]} after commit=${after[t]}" +
                " | final load: count=${p.e.userFrequency(t)} strike=${p.e.hasStrike(t)} suppressed=${p.e.isSuppressed(t)}" +
                " kept=${p.typist.mem.keptCount(t)} banned=${p.typist.mem.bannedFixes(t)}"
        }
        return Headline(
            looped,
            looped.filter { t -> results.getValue(t).any { it != t } },
            looped.filter { t -> loaded.getValue(t).zipWithNext().any { (a, b) -> b <= a } },
            lines,
        )
    }

    class Usage(val corrected: List<String>, val lines: List<String>)

    /** A word typed ten times and never refused has no rejection entry, only its count. */
    fun usage(allOrders: Boolean, tokens: List<String>): Usage {
        val s = RowStore(allOrders)
        var p = Process(s)
        val before = LinkedHashMap<String, String>()
        for (t in tokens) {
            repeat(10) { p.typist.port.learn(t, "so", "well", false) }
            p.typist.midSentence()
            before[t] = "count=${p.e.userFrequency(t)} verdict=${show(p.typist.verdict(t))}"
        }
        p.typist.hide()
        p = Process(s)
        val corrected = ArrayList<String>()
        val lines = tokens.map { t ->
            p.typist.midSentence()
            val v = p.typist.verdict(t)
            if (v !== Verdict.Keep) corrected += t
            "$t: before restart ${before[t]} | after restart count=${p.e.userFrequency(t)} verdict=${show(v)}"
        }
        return Usage(corrected, lines)
    }

    class Accepted(val looped: List<String>, val leftAlone: List<String>, val lines: List<String>)

    /** A typo whose correction stood, twice, across restarts, must keep being corrected. Each token has the model to itself. */
    fun accepted(allOrders: Boolean, tokens: List<String>): Accepted {
        val each = tokens.map { acceptedTogether(allOrders, listOf(it)) }
        return Accepted(each.flatMap { it.looped }, each.flatMap { it.leftAlone }, each.flatMap { it.lines })
    }

    /**
     * The same, with every token's fix learned into one model in one context. A fix can then be a close
     * competitor for another token and take the margin away, so a few tokens drop to a chip. That is
     * the margin rule working and a real user's model can do it too, so it is reported but not gated.
     */
    fun acceptedTogether(allOrders: Boolean, tokens: List<String>): Accepted {
        val s = RowStore(allOrders)
        var p = Process(s)
        val a = LinkedHashMap<String, String>()
        for (t in tokens) {
            p.typist.midSentence()
            val f = p.typist.typeSpace(t)
            if (f != t) { a[t] = f; p.typist.typeSpace("next") }
        }
        p.typist.hide()
        val looped = a.keys.toList()
        p = Process(s)
        val once = HashMap<String, String>()
        val b = HashMap<String, String>()
        for (t in looped) {
            once[t] = "strike=${p.e.hasStrike(t)} suppressed=${p.e.isSuppressed(t)}"
            p.typist.midSentence()
            b[t] = p.typist.typeSpace(t)
            p.typist.typeSpace("next")
        }
        p.typist.hide()
        p = Process(s)
        val leftAlone = ArrayList<String>()
        val lines = looped.map { t ->
            val twice = "strike=${p.e.hasStrike(t)} suppressed=${p.e.isSuppressed(t)}"
            p.typist.midSentence()
            val c = p.typist.typeSpace(t)
            if (b[t] == t || c == t) leftAlone += t
            "$t: ${a[t]} | load: ${once[t]} | ${b[t]} | load: $twice | $c | count=${p.e.userFrequency(t)}"
        }
        return Accepted(looped, leftAlone, lines)
    }

    private fun blacklistLifted(t: GateTable) {
        val w = "oof"
        val s = RowStore(true)
        var p = Process(s)
        repeat(2) {
            p.typist.midSentence()
            p.typist.typeSpace(w)
            p.typist.typeSpace("next")
            p.typist.hide()
            p = Process(s)
        }
        p.typist.midSentence()
        val blacklisted = p.e.isSuppressed(w)
        val fix = p.typist.typeSpace(w)
        val back = p.typist.undoThenSpace()
        p.typist.hide()
        p = Process(s)
        p.typist.midSentence()
        val lifted = !p.e.isSuppressed(w) && !p.e.hasStrike(w)
        val again = p.typist.typeSpace(w)
        println("$w: blacklisted=$blacklisted | typed=$fix undo+space=$back | lifted=$lifted count=${p.e.userFrequency(w)} | typed again=$again")
        t.add("Added: a blacklist is lifted by one revert, across a reload", "lifted, stays as typed",
            "blacklisted=$blacklisted lifted=$lifted typed again=$again",
            blacklisted && back == w && lifted && again == w)
    }

    private enum class OneShot(val label: String) {
        KEEP_ROWS("verdicts cleared, rows kept"),
        BARE_COUNTS("verdicts cleared, bare counts deleted"),
        ALL_ROWS("verdicts cleared, every row of the word deleted"),
    }

    /**
     * Old data, then both upgrade steps: the Room migration rebuilds counts from the trigram rows and
     * the one-shot deletes what the old rules struck. The struck list goes through the same parser the
     * one-shot will use, on the text ContextEngine wrote.
     */
    private fun upgraded(oneShot: OneShot): RowStore {
        val s = RowStore(true)
        s.rows[Triple("so", "well", "teh")] = 9        // committed raw by Enter, then one correction struck it
        s.rows[Triple("so", "well", "oof")] = 12       // typed often, no verdict
        s.rows[Triple("so", "well", "faceid")] = 2     // blacklisted by the fight, typed again since
        s.strikes = mapOf("teh" to "1@${clock.ms}")
        s.suppressions = setOf("faceid@${clock.ms}")
        s.aggregate()
        val struck = LegacyTypoVerdicts.struckWords(s.suppressionsRow(), s.strikesRow())
        when (oneShot) {
            OneShot.KEEP_ROWS -> {}
            OneShot.BARE_COUNTS -> struck.forEach { s.deleteUnigram(it) }
            OneShot.ALL_ROWS -> struck.forEach { s.deleteWord(it) }
        }
        s.strikes = emptyMap()
        s.suppressions = emptySet()
        return s
    }

    private fun migration(t: GateTable) {
        val struckWords = listOf("teh", "faceid")
        for (oneShot in OneShot.values()) {
            val p = Process(upgraded(oneShot))
            p.typist.midSentence()
            val status = listOf("teh", "oof", "faceid").associateWith { w -> p.e.userFrequency(w) to show(p.typist.verdict(w)) }
            // Far past the five the bar shows, so a struck word ranked low still counts as offered.
            val next = p.e.predict("", "so", "well", "", 50).map { it.text.lowercase() }
            val offered = struckWords.filter { it in next }
            val shownOnTheBar = p.e.predict("", "so", "well", "", 5).map { it.text.lowercase() }.filter { it in struckWords }
            println("${oneShot.label}: " + status.entries.joinToString(" | ") { (w, r) -> "$w count=${r.first} verdict=${r.second}" } +
                " | struck words offered next after [well so]: $offered, of those in the top 5: $shownOnTheBar")
            if (oneShot == OneShot.ALL_ROWS) {
                val teh = status.getValue("teh")
                val oof = status.getValue("oof")
                val faceid = status.getValue("faceid")
                t.add("Struck typo with 9 historic rows, after both migrations (modelled table)", "corrected",
                    "teh count=${teh.first} verdict=${teh.second}", teh.first == 0 && teh.second == "the")
                t.add("Added: a word with no verdict keeps its count through the migrations", "count 12, no correction",
                    "oof count=${oof.first} verdict=${oof.second}", oof.first == 12 && oof.second == "-")
                t.add("Added: a blacklisted word starts over after the migrations", "count 0",
                    "faceid count=${faceid.first} verdict=${faceid.second}", faceid.first == 0)
                t.add("Added: a struck word with context rows is not offered after reload (modelled table)", "not offered",
                    "offered: $offered", offered.isEmpty())
            }
        }
    }

    /** The tokens of the audited table. Nothing here depends on a seed, so one pass is enough. */
    fun fixedGates(t: GateTable) {
        val loop = "oof faceid woulda lakeisha nvmd pwease ozempic morn teh".split(' ')
        for (allOrders in listOf(false, true)) {
            val h = headline(allOrders, loop)
            val shape = RowStore(allOrders).shape
            println("\n=== One revert, then five restarts through loadUserModel, $shape, Balanced, no trace, prev1=so prev2=well")
            h.lines.forEach(::println)
            if (allOrders) t.add("One revert, then five reloads (modelled table, $shape)", "never re-corrected, count rises",
                "${h.looped.size} looped, ${h.reCorrected.size} re-corrected, ${h.notRising.size} not rising",
                h.looped.isNotEmpty() && h.reCorrected.isEmpty() && h.notRising.isEmpty())
            else println("Stock rows, for reading, not a gate: ${h.reCorrected.size} re-corrected, ${h.notRising.size} of ${h.looped.size} with a flat count.")
        }
        val toks = "oof faceid lakeisha nvmd morn".split(' ')
        for (allOrders in listOf(false, true)) {
            val u = usage(allOrders, toks)
            val shape = RowStore(allOrders).shape
            println("\n=== Typed 10 times mid-sentence, never refused, then a restart, $shape")
            u.lines.forEach(::println)
            if (allOrders) t.add("Word typed 10 times, never refused, one reload (modelled table, $shape)", "no correction",
                "${u.corrected.size} of ${toks.size} corrected", u.corrected.isEmpty())
            else println("Stock rows, for reading, not a gate: ${u.corrected.size} of ${toks.size} corrected.")
        }
        for (allOrders in listOf(false, true)) {
            val a = accepted(allOrders, "thsi teh wiht".split(' '))
            val shape = RowStore(allOrders).shape
            println("\n=== Real typo accepted, across restarts, $shape")
            a.lines.forEach(::println)
            t.add("Added: a real typo accepted twice stays corrected across reloads ($shape)", "corrected every time",
                "${a.looped.size} looped, ${a.leftAlone.size} left alone", a.looped.isNotEmpty() && a.leftAlone.isEmpty())
        }
        println("\n=== Blacklisted, then one revert, then a restart, all-order rows")
        blacklistLifted(t)
        println("\n=== Upgrade: the aggregating Room migration, then the one-shot")
        migration(t)
    }

    /**
     * The same reload checks on typos a seed picked rather than on the audited tokens, which is the
     * only way a second seed can say anything about them.
     */
    fun seededGates(tokens: List<String>, t: GateTable) {
        val h = headline(true, tokens)
        println("Seeded typos, one revert, five reloads, all-order rows: ${h.looped.size} looped, re-corrected=${h.reCorrected.take(20)} not rising=${h.notRising.take(20)}")
        t.add("Added: seeded typos, one revert, five reloads (modelled table)", "never re-corrected, count rises",
            "${h.looped.size} looped, ${h.reCorrected.size} re-corrected, ${h.notRising.size} not rising",
            h.looped.isNotEmpty() && h.reCorrected.isEmpty() && h.notRising.isEmpty())
        val u = usage(true, tokens)
        println("Seeded typos, typed 10 times, one reload, all-order rows: corrected=${u.corrected.take(20)}")
        t.add("Added: seeded typos typed 10 times, one reload (modelled table)", "no correction",
            "${u.corrected.size} of ${tokens.size} corrected", u.corrected.isEmpty())
        val a = accepted(true, tokens)
        println("Seeded typos, accepted twice across reloads, each alone in its model, all-order rows: left alone=${a.leftAlone.take(20)}")
        t.add("Added: seeded typos accepted twice stay corrected across reloads (modelled table)", "corrected every time",
            "${a.looped.size} looped, ${a.leftAlone.size} left alone", a.looped.isNotEmpty() && a.leftAlone.isEmpty())
        val together = acceptedTogether(true, tokens)
        println("Seeded typos, accepted twice, all in one model, for reading, not a gate: ${together.leftAlone.size} of ${together.looped.size} dropped to a chip: ${together.leftAlone.take(20)}")
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
