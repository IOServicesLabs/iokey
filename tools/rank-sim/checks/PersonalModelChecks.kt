import android.content.Context
import com.localboard.keyboard.data.db.NGramKey
import com.localboard.keyboard.data.db.NGramQueue
import com.localboard.keyboard.data.db.NGramRows
import com.localboard.keyboard.data.db.NGramSql
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.correction.LegacyTypoVerdicts
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * What the learned model does across a restart and an erase: the real engine, the real SQL on a real
 * SQLite, and the shipped code for the rows. The typing is simulated, and the rows reach the table
 * through a plain copy of the increment-or-insert the app's flush does, which the JVM tests run for
 * real. Words starting zq are invented, so the bundled data cannot be what passes a check.
 */
object PersonalModelChecks {
    private lateinit var checkFn: (String, Boolean, () -> Any?) -> Unit

    private fun check(name: String, ok: Boolean, detail: () -> Any? = { null }) = checkFn(name, ok, detail)

    private fun known(e: FastEngine, w: String) = e.allWords().any { it.first == w }

    private fun frequency(e: FastEngine, w: String) = e.allWords().firstOrNull { it.first == w }?.second ?: 0L

    private fun completes(e: FastEngine, prefix: String, w: String) =
        e.predict(prefix, "", "", "", 5).any { it.text == w }

    private fun bar(e: FastEngine, p1: String, p2: String = "") =
        e.predict("", p1, p2, "", 5).map { it.text }

    @Suppress("UNCHECKED_CAST")
    private fun field(e: FastEngine, name: String): Map<String, Int> {
        val f = FastEngine::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(e) as Map<String, Int>
    }

    private fun freshEngine(assets: File): FastEngine {
        val e = FastEngine(Context(assets))
        runBlocking {
            e.loadBaseDictionary()
            e.loadBaseLanguageModel()
        }
        return e
    }

    private fun newStore(): Connection {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.execute(
                "CREATE TABLE ngrams (prev1 TEXT NOT NULL, prev2 TEXT NOT NULL, word TEXT NOT NULL, " +
                    "count INTEGER NOT NULL, lastUsed INTEGER NOT NULL, PRIMARY KEY(prev1, prev2, word))"
            )
        }
        return db
    }

    private fun Connection.insert(key: NGramKey, count: Int) {
        prepareStatement("INSERT INTO ngrams VALUES (?, ?, ?, ?, 1)").use {
            it.setString(1, key.first)
            it.setString(2, key.second)
            it.setString(3, key.third)
            it.setInt(4, count)
            it.executeUpdate()
        }
    }

    private fun Connection.exec(sql: String, vararg args: String) {
        prepareStatement(sql).use { s ->
            args.forEachIndexed { i, a -> s.setString(i + 1, a) }
            s.executeUpdate()
        }
    }

    /** What a flush does to the table, in one transaction. */
    private fun Connection.flush(q: NGramQueue) {
        val batch = q.drain()
        autoCommit = false
        for (w in batch.deletions) exec(NGramSql.DELETE_WORD, w)
        for ((key, delta) in batch.increments) {
            if (delta == 0) continue
            val updated = prepareStatement(NGramSql.INCREMENT).use {
                it.setInt(1, delta)
                it.setLong(2, 1)
                it.setString(3, key.first)
                it.setString(4, key.second)
                it.setString(5, key.third)
                it.executeUpdate()
            }
            if (delta > 0 && updated == 0) insert(key, delta)
        }
        commit()
        autoCommit = true
    }

    /** The shipped queries at the shipped caps into [e], which is what a process start does. */
    private fun Connection.hydrate(e: FastEngine) {
        fun rows(sql: String, limit: Int): List<Pair<NGramKey, Int>> =
            prepareStatement(sql).use { s ->
                s.setInt(1, limit)
                s.executeQuery().use { r ->
                    buildList {
                        while (r.next()) {
                            add(NGramKey(r.getString("prev1"), r.getString("prev2"), r.getString("word")) to r.getInt("count"))
                        }
                    }
                }
            }
        e.loadUserModel(
            rows(NGramSql.TOP_UNIGRAMS, FastEngine.MAX_PERSONAL_UNIGRAMS).map { (k, c) -> k.third to c },
            rows(NGramSql.TOP_BIGRAMS, FastEngine.MAX_PERSONAL_BIGRAMS).map { (k, c) -> Triple(k.first, k.third, c) },
            rows(NGramSql.TOP_TRIGRAMS, FastEngine.MAX_PERSONAL_TRIGRAMS).map { (k, c) -> Triple(k.second to k.first, k.third, c) },
        )
    }

    /** Simulated typing: Zipf-sampled words, focus resets, and invented words typed 12 times each,
     *  always mid-sentence. Counts must match order by order. */
    private fun restartRoundTrip(assets: File, clearEvery: Int, totalWords: Int, seed: Int) {
        val mem = freshEngine(assets)
        val queue = NGramQueue()
        val db = newStore()
        val committed = ArrayDeque<String>()
        var words = 0
        fun type(w: String) {
            val p1 = committed.lastOrNull() ?: ""
            val p2 = committed.dropLast(1).lastOrNull() ?: ""
            if (mem.learn(w, p1, p2)) {
                queue.addLearned(p1, p2, w)
                words++
                if (words % 500 == 0) db.flush(queue)
            }
            committed.addLast(w)
            while (committed.size > 40) committed.removeFirst()
        }
        val personal = listOf("zqorblax", "zqbruhh", "zqokayy", "zqkubectl", "zqmibby")
        val pool = mem.allWords()
            .filter { (w, _) -> w.all { it in 'a'..'z' } && w.length >= 2 }
            .sortedByDescending { it.second }
            .take(5000)
            .map { it.first }
        val rnd = Random(seed)
        val lnN = ln(pool.size + 1.0)
        fun zipf() = pool[((exp(rnd.nextDouble() * lnN) - 1).toInt()).coerceIn(0, pool.size - 1)]
        val placed = personal.flatMap { w -> List(12) { w } }.shuffled(rnd).toMutableList()
        var messages = 0
        while (words < totalWords) {
            if (++messages % clearEvery == 0) committed.clear()
            repeat(1 + rnd.nextInt(3)) {
                val len = 6 + rnd.nextInt(9)
                for (i in 0 until len) {
                    type(zipf())
                    if (i in 1..(len - 2) && placed.isNotEmpty() && rnd.nextInt(8) == 0) {
                        type(placed.removeAt(placed.size - 1))
                    }
                }
                if (committed.lastOrNull() != ".") committed.addLast(".")
            }
        }
        db.flush(queue)

        val back = freshEngine(assets)
        db.hydrate(back)
        for (name in listOf("userUnigram", "userBigram", "userTrigram")) {
            val before = field(mem, name)
            val after = field(back, name)
            val mismatches = before.count { (k, c) -> after[k] != c } + after.keys.count { it !in before }
            println("simulated restart, $name: $mismatches mismatches of ${before.size} (seed $seed, focus reset every $clearEvery messages)")
            check("$name survives a restart, seed $seed", mismatches == 0) { "$mismatches of ${before.size}" }
        }
        check("every invented word was typed in full", placed.isEmpty()) { placed.size }
        for (w in personal) {
            check("$w is known after a restart", known(back, w))
            check("$w completes after a restart", completes(back, w.take(4), w))
            check("$w is not corrected after a restart", back.correctionProbe(w, "the", "near", 2).first == null) {
                back.correctionProbe(w, "the", "near", 2).first
            }
        }
        db.close()
    }

    /** A word the old rules struck, with history only in context rows, through the real migration SQL
     *  and then what the one-shot does. The first half proves this check can fail. */
    private fun struckWord(e: FastEngine) {
        val db = newStore()
        db.insert(NGramKey("zqso", "zqwell", "zqstruck"), 9)
        db.insert(NGramKey("zqnow", "zqwell", "zqstruck"), 2)
        db.insert(NGramKey("zqso", "zqwell", "zqkept"), 9)
        db.createStatement().use { s -> NGramRows.MIGRATION_5_TO_6.forEach { s.execute(it) } }

        db.hydrate(e)
        check("left alone, the rebuilt rows would offer a struck word next", "zqstruck" in bar(e, "zqso"))
        check("and would count it as used", e.userFrequency("zqstruck") == 11)

        for (w in LegacyTypoVerdicts.struckWords(null, "zqstruck=1@5")) db.exec(NGramSql.DELETE_WORD, w)
        db.hydrate(e)
        check("a struck word with context rows is not offered after reload",
            "zqstruck" !in bar(e, "zqso") && "zqstruck" !in bar(e, "zqso", "zqwell") &&
                "zqstruck" !in bar(e, "zqnow"))
        check("it has no count after reload", e.userFrequency("zqstruck") == 0)
        check("it is not in the dictionary after reload", !known(e, "zqstruck"))
        check("a word that was not struck is still offered", "zqkept" in bar(e, "zqso"))
        db.close()
    }

    private fun reload(e: FastEngine) {
        e.loadUserModel(emptyList(), emptyList(), emptyList())
        repeat(10) { e.learn("zqreload", "", "") }
        check("ten uses put a word in the dictionary", known(e, "zqreload"))
        e.loadUserModel(listOf("zqother" to 10), emptyList(), emptyList())
        check("a reload without a promoted word takes it out", !known(e, "zqreload"))
        check("a reload promotes what it holds", known(e, "zqother"))
        e.loadUserModel(listOf("zqother" to 10), emptyList(), emptyList())
        check("a reload of the same rows keeps the word", known(e, "zqother") && completes(e, "zqoth", "zqother"))
    }

    /** Every count below the point a word enters the dictionary, typed and read back from the store. */
    private fun everyCountCompletes(e: FastEngine) {
        for (uses in 1..12) {
            e.loadUserModel(emptyList(), emptyList(), emptyList())
            val word = "zqcount" + ('a' + uses)
            repeat(uses) { e.learn(word, "", "") }
            check("a word used $uses times completes", completes(e, "zqcount", word))
            e.loadUserModel(listOf(word to uses), emptyList(), emptyList())
            check("a word used $uses times completes after a restart", completes(e, "zqcount", word))
        }

        e.loadUserModel(listOf("zqfour" to 4, "zqeight" to 8), emptyList(), emptyList())
        check("four uses alone stay out of the dictionary", !known(e, "zqfour"))
        check("eight uses alone put a word in it", known(e, "zqeight"))
        e.noteConfirmed("zqfour")
        check("four uses and a confirmation put a word in it", known(e, "zqfour"))
        check("and it still completes", completes(e, "zqfou", "zqfour"))

        e.loadUserModel(listOf("zqgone" to 5), emptyList(), emptyList())
        check("before it is removed it completes", completes(e, "zqgon", "zqgone"))
        e.removeWordEverywhere("zqgone")
        repeat(5) { e.learn("zqgone", "", "") }
        check("a word the person removed does not complete, even with new counts", !completes(e, "zqgon", "zqgone"))
    }

    /** What a start does with a typo verdict, in the order ContextEngine does it. "form" ships in the dictionary. */
    private fun verdictsAtLoad(e: FastEngine) {
        val now = System.currentTimeMillis()
        e.restoreSuppressions(setOf("form@$now", "zqverdict@$now"))
        e.loadUserModel(listOf("zqverdict" to 9), emptyList(), emptyList())
        check("a shipped word under a verdict stays in the dictionary", known(e, "form"))
        check("a learned word under a verdict does not enter it", !known(e, "zqverdict"))
        check("and is not offered", !completes(e, "zqverd", "zqverdict"))
        e.restoreSuppressions(emptySet())
    }

    /** A backup put back over a store holds counts the store may already have, so the higher stands. */
    private fun restoredCounts(e: FastEngine) {
        e.loadUserModel(listOf("zqtyped" to 200, "zqslight" to 2), emptyList(), emptyList())
        e.mergeRestoredCounts(listOf("zqtyped" to 3, "zqslight" to 6, "zqnew" to 5))
        check("a smaller restored count takes nothing away", e.userFrequency("zqtyped") == 200) { e.userFrequency("zqtyped") }
        check("a larger restored count stands", e.userFrequency("zqslight") == 6) { e.userFrequency("zqslight") }
        check("a restored word arrives at its count", e.userFrequency("zqnew") == 5) { e.userFrequency("zqnew") }
        check("and completes", completes(e, "zqne", "zqnew"))

        e.mergeRestoredCounts(listOf("zqslight" to 6, "zqnew" to 5))
        check("restoring the same file again changes nothing",
            e.userFrequency("zqslight") == 6 && e.userFrequency("zqnew") == 5)

        e.mergeRestoredCounts(listOf("zqnew" to 9))
        check("a count that reaches the strong level enters the dictionary", known(e, "zqnew"))
    }

    private fun erase(e: FastEngine) {
        e.loadUserModel(emptyList(), emptyList(), emptyList())
        val shipped = frequency(e, "people")
        repeat(10) { e.learn("zqpromoted", "zqbefore", "zqolder") }
        check("before the erase the word completes", completes(e, "zqprom", "zqpromoted"))
        check("before the erase it is offered next", "zqpromoted" in bar(e, "zqbefore"))
        repeat(60) { e.learn("people", "", "") }
        check("use ranks a dictionary word higher", frequency(e, "people") > shipped) { frequency(e, "people") }
        e.setAppVocab("zq.app", mapOf("zqappword" to 9))
        e.noteConfirmed("zqconfirmed")
        e.noteTypo("zqslip")
        e.removeWordEverywhere("zqremoved")

        val trieVersion = e.trieVersion
        e.forgetEverythingLearned()

        check("an erased word no longer completes", !completes(e, "zqprom", "zqpromoted"))
        check("an erased word is out of the dictionary", !known(e, "zqpromoted"))
        check("an erased word is no longer offered next", "zqpromoted" !in bar(e, "zqbefore"))
        check("an erased word has no count", e.userFrequency("zqpromoted") == 0)
        check("the dictionary word is still there", known(e, "people"))
        check("at the frequency it shipped with", frequency(e, "people") == shipped) { frequency(e, "people") }
        check("nothing learned is counted", e.learnedWordCount == 0)
        check("no verdict or confirmation is left",
            e.confirmedWordsSnapshot().isEmpty() && e.strikesSnapshot().isEmpty() &&
                e.suppressionsSnapshot().isEmpty())
        check("no app vocabulary is left", field(e, "appVocab").isEmpty())
        check("the glide table will notice", e.trieVersion != trieVersion)
    }

    fun run(assets: File, e: FastEngine, check: (String, Boolean, () -> Any?) -> Unit) {
        checkFn = check
        reload(e)
        everyCountCompletes(e)
        verdictsAtLoad(e)
        restoredCounts(e)
        erase(e)
        struckWord(e)
        restartRoundTrip(assets, clearEvery = 20, totalWords = 20_000, seed = 1)
        restartRoundTrip(assets, clearEvery = 1, totalWords = 20_000, seed = 2)
    }
}
