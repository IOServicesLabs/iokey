import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.correction.CommitLearning
import com.localboard.keyboard.engine.correction.CorrectionInput
import com.localboard.keyboard.engine.correction.CorrectionPolicy
import com.localboard.keyboard.engine.correction.CorrectionSession
import com.localboard.keyboard.engine.correction.FakeEditor
import com.localboard.keyboard.engine.correction.FieldCorrection
import com.localboard.keyboard.engine.correction.LearningPort
import com.localboard.keyboard.engine.correction.RejectionMemory
import com.localboard.keyboard.engine.correction.Verdict

class Clock(var ms: Long = 2_000_000_000_000L)

/** The session's learning calls, going straight to the engine. Logged, so a gate can read their order. */
open class EnginePort(protected val e: FastEngine) : LearningPort {
    val committed = ArrayList<String>()
    val calls = ArrayList<String>()
    var memory: RejectionMemory? = null
    var savedRejections: String? = null

    override val canLearn = true
    override fun pushContext(word: String) {
        for (w in word.split(' ')) if (w.isNotBlank()) committed += w.lowercase()
    }
    override fun popContext(word: String) {
        for (w in word.split(' ').reversed()) if (committed.lastOrNull() == w.lowercase()) committed.removeAt(committed.size - 1)
    }
    override fun learn(word: String, prev1: String, prev2: String, selected: Boolean) {
        e.learn(word, prev1, prev2, selected)
        calls += "learn($word,$selected)"
    }
    override fun unlearn(word: String) {
        e.unlearnRecent(word)
        calls += "unlearn($word)"
    }
    override fun unconfirm(word: String) = e.unconfirm(word)
    override fun noteTypo(word: String) {
        e.noteTypo(word)
        calls += "noteTypo($word)"
    }
    override fun clearTypoVerdict(word: String) {
        e.clearTypoVerdict(word)
        calls += "clearTypo($word)"
    }
    override fun tapSignal(raw: String, fix: String) {
        calls += "tap($raw,$fix)"
    }
    override fun persistRejections() {
        savedRejections = memory?.serialize()
        memory?.markPersisted()
    }
}

/**
 * Types into a fake field through the session, one word and a Space at a time, at Balanced with no
 * trace. FakeIme in the test sources covers every event with scripted evidence; this covers the few
 * events a plain typist needs, with the evidence the real engine reports.
 */
class Typist(val e: FastEngine, val port: EnginePort, val clock: Clock = Clock(), saved: String? = null) {
    val ed = FakeEditor()
    lateinit var mem: RejectionMemory; private set
    private lateinit var session: CorrectionSession
    private var charBefore: Char? = null

    init { start(saved) }

    private fun start(saved: String?) {
        mem = RejectionMemory({ clock.ms }).also { m -> saved?.let { m.restore(it) }; port.memory = m }
        session = CorrectionSession(ed, port, mem, RejectionMemory({ clock.ms }))
        port.committed.clear()
    }

    private fun prev1() = port.committed.lastOrNull() ?: ""
    private fun prev2() = port.committed.dropLast(1).lastOrNull() ?: ""

    fun verdict(word: String): Verdict {
        val ev = e.correctionEvidence(word, prev1(), prev2(), false)
        return CorrectionPolicy.decide(CorrectionInput(
            word, session.effectiveProvenance(word, prev1()), FieldCorrection.AUTO, charBefore, null, ' ', 2,
            ev.known, ev.confirmed, ev.strongUsage, session.isProtected(word), session.bannedFixes(word),
            ev.closedFix, ev.candidates, null, false, ev.contextOverride, ev.split,
        ))
    }

    /** Returns what landed in the field. */
    fun typeSpace(word: String): String {
        charBefore = ed.textBefore(1).lastOrNull()   // read before composing, as the word-start wiring does
        session.onWordStart(charBefore, null, prev1())
        ed.setComposing(word)
        val v = verdict(word)
        if (v is Verdict.AutoCommit) {
            ed.setComposing("")
            ed.commit(v.word)
            session.onAutoCorrected(word, v.word, " ", prev1(), prev2())
            ed.commit(" ")
            return v.word
        }
        ed.finishComposing()
        commitLearning(word)
        ed.commit(" ")
        return word
    }

    private fun commitLearning(word: String) {
        val p1 = prev1()
        val p2 = prev2()
        when (session.onWordCommitted(word, p1)) {
            CommitLearning.NORMAL -> { port.learn(word, p1, p2, false); port.pushContext(word) }
            CommitLearning.SELECTED -> { port.learn(word, p1, p2, true); port.pushContext(word) }
            CommitLearning.CONTEXT_ONLY -> port.committed += word
            CommitLearning.NOTHING -> {}
        }
    }

    /** Backspace right after the correction, then Space. */
    fun undoThenSpace(): String {
        val u = session.tryUndo(false) ?: return "NO UNDO"
        val word = u.composing!!
        ed.finishComposing()
        commitLearning(word)
        ed.commit(" ")
        return word
    }

    fun wordDelete(word: String) {
        ed.deleteBefore(word.length + 1)
        if (port.committed.lastOrNull() == word) port.committed.removeAt(port.committed.size - 1)
        session.onWordDeleted(word, prev1())
    }

    fun hide() = session.onHide()

    /** The keyboard goes away and comes back: new session, same engine, rejections as last saved. */
    fun hideAndRestart() {
        session.onHide()
        start(port.savedRejections)
    }

    /** Mid-sentence, as nearly every real word is. */
    fun midSentence() {
        port.committed.clear()
        port.committed += listOf("well", "so")
    }
}
