package com.localboard.keyboard.engine.correction

/** A text field with a caret and a composing region. No Android, no engine. */
class FakeEditor : EditorPort {
    val text = StringBuilder()
    var cursor = 0
    var compStart = -1
    var compEnd = -1
    override fun textBefore(n: Int): String = text.substring((cursor - n).coerceAtLeast(0), cursor)
    fun textAfter(n: Int): String = text.substring(cursor, (cursor + n).coerceAtMost(text.length))
    override fun setComposing(text: String) {
        if (compStart >= 0) { this.text.delete(compStart, compEnd); cursor = compStart }
        compStart = cursor
        this.text.insert(cursor, text)
        cursor += text.length
        compEnd = cursor
        if (text.isEmpty()) { compStart = -1; compEnd = -1 }
    }
    fun finishComposing() { compStart = -1; compEnd = -1 }
    override fun deleteBefore(n: Int) {
        val s = (cursor - n).coerceAtLeast(0)
        text.delete(s, cursor)
        cursor = s
    }
    override fun commit(text: String) {
        if (compStart >= 0) { this.text.delete(compStart, compEnd); cursor = compStart; finishComposing() }
        this.text.insert(cursor, text)
        cursor += text.length
    }
    override fun batch(block: () -> Unit) = block()
    fun moveCursor(to: Int) { finishComposing(); cursor = to }
    override fun toString() = text.toString()
}

/** What the engine would report for a word. The candidate table is built by hand. */
class Ev(
    val candidates: List<Candidate> = emptyList(),
    val closed: String? = null,
    val split: String? = null,
)

/**
 * How the count of a person's own words moves. The real rules live with the service's glide code, which the simulator
 * sharing this file does not compile, so a test holding them hands them in. With none handed in, nothing is counted.
 */
interface TrailCountRules {
    fun added(count: Int): Int
    fun removed(count: Int): Int
    fun sentence(own: List<String>, count: Int): String?
}

/**
 * A hand port of how the service drives [CorrectionSession], event for event, because the service
 * itself cannot run on a JVM. It proves the design, not the wiring: it can drift from the service,
 * so read one against the other whenever either changes.
 */
class FakeIme(val clock: () -> Long, val incognito: Boolean = false) {
    val log = ArrayList<String>()
    val ed = FakeEditor()
    val committedWords = ArrayDeque<String>()
    val messageWords = ArrayList<String>()
    val confirmed = HashSet<String>()
    var persisted: String? = null
    var refusals = 0
    /** What the typing log got, one entry per message. */
    val sentMessages = ArrayList<String>()
    /** The field takes only an address, as an email field does. */
    var addressField = false
    /** A URL field, which a browser's bar also uses for searches, so what lands there depends on what it holds. */
    var urlField = false
    var autoSpace = true
    /** The words the decode of the current glide reads as the ones before it. */
    var glidePrev1: String? = null
    var glidePrev2: String? = null
    var countRules: TrailCountRules? = null
    var typedSinceSeed = 0
    /** What the trail holds from an accepted draft, by identity as the service keeps it. */
    val draftWords = ArrayList<String>()
    /** What the service's onSentenceCommitted would get. */
    val sentencesLearned = ArrayList<String>()
    /** The run touching the caret that a read of the field left off the trail, as the service's LearningState keeps it. */
    var keptRun: String? = null

    private fun countOneAdded() { countRules?.let { typedSinceSeed = it.added(typedSinceSeed) } }
    private fun countOneRemoved() { countRules?.let { typedSinceSeed = it.removed(typedSinceSeed) } }

    val port = object : LearningPort {
        override val canLearn get() = !incognito
        override fun pushContext(word: String) {
            keptRun = null
            for (w in word.split(' ')) if (w.isNotBlank()) {
                committedWords.addLast(w.lowercase())
                messageWords.add(w.lowercase())
                countOneAdded()
            }
        }
        override fun popContext(word: String) {
            keptRun = null
            for (w in word.split(' ').reversed()) {
                if (committedWords.lastOrNull() == w.lowercase()) {
                    committedWords.removeLast()
                    countOneRemoved()
                }
                if (messageWords.lastOrNull() == w.lowercase()) messageWords.removeAt(messageWords.size - 1)
            }
        }
        override fun swapContext(fix: String, raw: String) {
            val marked = committedWords.lastOrNull() == MARK
            if (marked) committedWords.removeLast()
            popContext(fix)
            pushContext(raw)
            if (marked) committedWords.addLast(MARK)
        }
        override fun learn(word: String, prev1: String, prev2: String, selected: Boolean) {
            log += "learn($word,prev=$prev1,selected=$selected)"
        }
        override fun unlearn(word: String) { log += "unlearn($word)" }
        override fun unconfirm(word: String) { confirmed -= word; log += "unconfirm($word)" }
        override fun noteTypo(word: String) { log += "noteTypo($word)" }
        override fun clearTypoVerdict(word: String) { log += "clearTypo($word)" }
        override fun tapSignal(raw: String, fix: String) { log += "tap($raw,$fix)" }
        override fun persistRejections() { persisted = memory.serialize(); memory.markPersisted(); log += "persist" }
        override fun correctionRefused() { refusals++ }
    }

    var memory = RejectionMemory(clock)
    val volatile = RejectionMemory(clock)
    var session = CorrectionSession(ed, port, memory, volatile)
    val composing = StringBuilder()
    var evidence: Map<String, Ev> = emptyMap()
    var known: Set<String> = emptySet()
    var level = 2
    var field = FieldCorrection.AUTO
    var charBefore: Char? = null
    var charAfter: Char? = null
    var lastGesture: String? = null
    private var lastGestureHadSpace = true
    private var lastGestureLearned = false
    /** The service takes a word's count back when Backspace re-enters it soon after it was learned. */
    var reentryUnlearns = false

    fun input(raw: String, sep: Char?): CorrectionInput {
        val lower = raw.lowercase()
        val ev = evidence[lower] ?: Ev()
        val prev1 = committedWords.lastOrNull() ?: ""
        return CorrectionInput(
            typed = raw,
            provenance = session.effectiveProvenance(lower, prev1),
            field = field, charBefore = charBefore, charAfter = charAfter, separator = sep, level = level,
            known = lower in known, confirmed = lower in confirmed, strongUsage = false,
            rejected = session.isProtected(lower), banned = session.bannedFixes(lower),
            closedFix = ev.closed, candidates = ev.candidates, spatialWinner = null, hasTrace = false,
            contextOverride = null, split = ev.split,
        )
    }

    fun type(s: String) { for (c in s) letter(c) }

    fun letter(c: Char) {
        lastGesture = null
        keptRun = null
        if (composing.isEmpty()) {
            charBefore = ed.textBefore(1).lastOrNull()
            charAfter = ed.textAfter(1).firstOrNull()
            session.onWordStart(charBefore, charAfter, committedWords.lastOrNull() ?: "")
        }
        composing.append(c)
        ed.setComposing(composing.toString())
    }

    /** The apostrophe key. After a letter it stays in the word, anywhere else it ends one uncorrected. */
    fun apostrophe(c: Char = '\'') {
        if (WordShape.isConnector(c.toString(), composing)) {
            lastGesture = null
            composing.append(c)
            ed.setComposing(composing.toString())
        } else {
            separator(c.toString(), correct = false)
        }
    }

    fun separator(s: String, correct: Boolean = true) {
        val raw = composing.toString()
        if (raw.isEmpty()) session.onTypedPastFix()
        if (raw.isNotEmpty() && correct && WordShape.stem(raw) == raw) {
            val v = CorrectionPolicy.decide(input(raw, s[0]))
            if (v is Verdict.AutoCommit) applyFix(raw, v.word, s)
        }
        finishComposingWord()
        ed.commit(s)
        lastGesture = null
        settleKeptRun()
        if (s in SENTENCE_ENDS) endSentence()
    }

    /** The service's settleKeptRun, for a separator, which always ends the run: what the field says afterwards. */
    private fun settleKeptRun() {
        val run = keptRun ?: return
        keptRun = null
        if (run in known) committedWords.addLast(run) else forgetTrailAcrossRun()
    }

    private fun forgetTrailAcrossRun() {
        committedWords.clear()
        draftWords.clear()
    }

    /** The service's endKeptRunBeforeWord: a word is about to land after the run. */
    private fun endKeptRunBeforeWord(spaced: Boolean) {
        val run = keptRun ?: return
        keptRun = null
        if (spaced && run in known && !WordShape.continuesWord(ed.textAfter(1).firstOrNull())) {
            committedWords.addLast(run)
        } else {
            forgetTrailAcrossRun()
        }
    }

    private fun endSentence() {
        if (!incognito && committedWords.isNotEmpty()) {
            val own = committedWords.filter { w -> draftWords.none { it === w } }
            countRules?.sentence(own, typedSinceSeed)?.let { sentencesLearned += it }
        }
        if (committedWords.lastOrNull() != MARK) committedWords.addLast(MARK)
    }

    /** The service's lead and tail space, by hand, for a word landing with nothing composing. */
    private fun wantsLeadSpace(glued: Boolean): Boolean {
        val before = ed.textBefore(1).lastOrNull() ?: return false
        return autoSpace && !glued && (before.isLetterOrDigit() || before in ".,;:!?)]}")
    }

    private fun wantsTailSpace(glued: Boolean): Boolean {
        if (!autoSpace || glued) return false
        val after = ed.textAfter(1).firstOrNull() ?: return true
        return !(after.isWhitespace() || after in ".,;:!?)]}\"'’”")
    }

    /** The service's landsGluedInAddress and GlideRules.uriRunIsAddress. */
    private fun landsGluedInAddress(wasComposing: Boolean): Boolean {
        if (addressField) return true
        if (!urlField) return false
        return wasComposing || ed.textBefore(128).takeLastWhile { !it.isWhitespace() }.any { it in "./:@" }
    }

    private fun applyFix(raw: String, word: String, sep: String) {
        val out = if (raw[0].isUpperCase() && word != "I") word.replaceFirstChar { it.uppercase() } else word
        val p1 = committedWords.lastOrNull() ?: ""
        val p2 = committedWords.dropLast(1).lastOrNull() ?: ""
        composing.clear()
        ed.finishComposing()
        ed.deleteBefore(raw.length)
        ed.commit(out)
        session.onAutoCorrected(raw, out, sep, p1, p2)
    }

    /** Part of an address, not of a sentence. The service's isPartOfAddress. */
    private fun isPartOfAddress(text: CharSequence) =
        addressField || urlField || text.any { it == '@' || it == '.' || it == '/' || it == ':' }

    fun finishComposingWord(beforeGlide: Boolean = false) {
        if (composing.isEmpty()) return
        val word = WordShape.normalized(WordShape.stem(composing.toString()))
        composing.clear()
        ed.finishComposing()
        val prev1 = committedWords.lastOrNull() ?: ""
        // The session says how the word counts. Whether it may is the service's own check.
        val counts = session.onWordCommitted(word, prev1)
        if (beforeGlide && isPartOfAddress(word)) return
        when (counts) {
            CommitLearning.NORMAL ->
                if (beforeGlide && word.lowercase() !in known) port.pushContext(word)
                else { if (port.canLearn) log += "learn($word,prev=$prev1,selected=false)"; port.pushContext(word) }
            CommitLearning.SELECTED -> { if (port.canLearn) log += "learn($word,prev=$prev1,selected=true)"; port.pushContext(word) }
            CommitLearning.CONTEXT_ONLY -> port.pushContext(word)
            CommitLearning.NOTHING -> {}
        }
    }

    fun backspace() {
        if (composing.isEmpty()) {
            val u = session.tryUndo(byChip = false)
            if (u != null) { composing.clear(); u.composing?.let { composing.append(it) }; return }
        }
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            ed.setComposing(composing.toString())
            session.onComposingBackspace(composing.isEmpty())
            if (composing.isEmpty()) ed.finishComposing()
            return
        }
        val g = lastGesture
        val hadSpace = lastGestureHadSpace
        if (g != null && (if (hadSpace) ed.textBefore(g.length + 1) == "$g " else ed.textBefore(g.length + 1).endsWith(g))) {
            ed.deleteBefore(if (hadSpace) g.length + 1 else g.length)
            port.popContext(g)
            // The tail after the pop names the pair the word was learned in.
            if (lastGestureLearned) log += "wipeUnlearn($g,prev=${committedWords.lastOrNull() ?: ""},prev2=${committedWords.getOrNull(committedWords.size - 2) ?: ""})"
            session.onGestureWiped(g)
            lastGesture = null
            return
        }
        val before = ed.textBefore(48)
        if (before.isEmpty()) return
        val fragment = WordShape.reentryFragment(before.dropLast(1))
            ?.takeUnless { WordShape.continuesWord(ed.textAfter(1).firstOrNull()) }
        ed.deleteBefore(1)
        if (endsSentence(before.last()) && before.dropLast(1).trimEnd(' ').lastOrNull()?.let { endsSentence(it) } != true &&
            committedWords.lastOrNull() == MARK
        ) {
            committedWords.removeLast()
        }
        if (fragment != null) {
            ed.deleteBefore(fragment.length)
            composing.append(fragment)
            ed.setComposing(composing.toString())
            val popped = committedWords.lastOrNull()
                ?.takeIf { it.startsWith(WordShape.normalized(WordShape.stem(fragment.lowercase()))) }
            if (popped != null) {
                committedWords.removeLast()
                countOneRemoved()
                if (messageWords.lastOrNull() == popped) messageWords.removeAt(messageWords.size - 1)
            }
            charBefore = ed.textBefore(fragment.length + 1).dropLast(fragment.length).lastOrNull()
            charAfter = ed.textAfter(1).firstOrNull()
            if (reentryUnlearns && popped != null) port.unlearn(popped)
            session.onFragmentReentered(fragment, committedWords.lastOrNull() ?: "", popped, unlearned = reentryUnlearns && popped != null)
        }
    }

    fun wordDelete() {
        if (composing.isNotEmpty()) {
            composing.clear(); ed.setComposing(""); session.onComposingBackspace(true); return
        }
        val before = ed.textBefore(48)
        val trimmed = before.trimEnd(' ')
        val cut = trimmed.lastIndexOf(' ')
        val n = before.length - (if (cut >= 0) cut + 1 else 0)
        if (n > 0) {
            ed.deleteBefore(n)
            val deleted = WordShape.contextForm(trimmed.substringAfterLast(' '))
            if (committedWords.lastOrNull() == MARK && trimmed.lastOrNull()?.let { endsSentence(it) } == true) committedWords.removeLast()
            port.popContext(deleted)
            session.onWordDeleted(deleted, committedWords.lastOrNull() ?: "")
        }
    }

    /** The bar. */
    fun chip(word: String) {
        val raw = composing.toString()
        val lf = session.lastFix
        if (lf != null && word.equals(lf.raw, ignoreCase = true)) {
            val u = session.tryUndo(byChip = true)
            composing.clear(); u?.composing?.let { composing.append(it) }
            return
        }
        val v = if (raw.isNotEmpty()) CorrectionPolicy.decide(input(raw, null)) else Verdict.Keep
        val candidate = (v as? Verdict.AutoCommit)?.word ?: (v as? Verdict.Hint)?.word
        val asTyped = raw.isNotEmpty() && word.equals(raw, ignoreCase = true)
        val refusedRaw = session.refusedRaw
        if (asTyped) session.onRawChipKept(raw, candidate, word.lowercase() in confirmed)
        else if (refusedRaw != null && word.equals(refusedRaw, ignoreCase = true)) session.onRefusedRawChosen(word.lowercase() in confirmed)
        else {
            if (raw.isNotEmpty() && v is Verdict.AutoCommit && v.word.equals(word, ignoreCase = true)) port.noteTypo(raw)
            if (raw.isNotEmpty()) session.onFixPicked(raw, word)
            session.onChipReplacedWord()
        }
        val glued = landsGluedInAddress(raw.isNotEmpty())
        if (raw.isNotEmpty()) {
            ed.setComposing(word)
        } else {
            val lead = wantsLeadSpace(glued)
            endKeptRunBeforeWord(spaced = lead)
            ed.commit(if (lead) " $word" else word)
        }
        composing.clear(); ed.finishComposing()
        val tail = if (raw.isNotEmpty()) autoSpace else wantsTailSpace(glued)
        if (tail) ed.commit(" ")
        lastGesture = word
        lastGestureHadSpace = tail
        lastGestureLearned = true
        log += "learn($word,prev=${committedWords.lastOrNull() ?: ""},selected=true)"
        port.pushContext(word)
        confirmed += word.lowercase(); log += "confirm(${word.lowercase()})"   // a bar tap always confirms
    }

    /** A glide begins. What is composing stays there, and the decode reads it as the word before. */
    fun glideStarts() {
        if (composing.isNotEmpty()) {
            glidePrev1 = WordShape.normalized(WordShape.stem(composing.toString())).lowercase()
            glidePrev2 = committedWords.lastOrNull()
        } else if (keptRun != null) {
            glidePrev1 = keptRun
            glidePrev2 = committedWords.lastOrNull()
        } else {
            glidePrev1 = committedWords.lastOrNull()
            glidePrev2 = committedWords.getOrNull(committedWords.size - 2)
        }
    }

    /** The glide decoded, and its word lands. Only now is what was composing finished as typed. */
    fun glideLands(word: String) {
        val notAWord = isPartOfAddress(composing)
        val wasComposing = composing.isNotEmpty()
        if (wasComposing) {
            finishComposingWord(beforeGlide = true)
            if (!notAWord && autoSpace) ed.commit(" ")
        }
        session.onChipReplacedWord()
        val glued = landsGluedInAddress(wasComposing)
        val lead = wantsLeadSpace(glued || (wasComposing && notAWord))
        endKeptRunBeforeWord(spaced = lead)
        ed.commit(if (lead) " $word" else word)
        val tail = wantsTailSpace(glued)
        if (tail) ed.commit(" ")
        lastGesture = word
        lastGestureHadSpace = tail
        lastGestureLearned = !notAWord
        if (notAWord) return
        log += "learn($word,prev=${committedWords.lastOrNull() ?: ""},selected=false)"
        port.pushContext(word)
    }

    fun glide(word: String) { glideStarts(); glideLands(word) }

    /** The decode failed, so the gesture falls back to a tap of the key it began on. */
    fun glideFallsBackToTap(c: Char) = letter(c)

    /** A slide from the keys up onto a word on the bar. With a word composing that is a bar tap by another route. */
    fun slideUpPick(word: String) {
        glideStarts()
        if (composing.isNotEmpty()) chip(word) else glideLands(word)
    }

    /**
     * The selection reaches the service as a change it did not make. It looks for a field the app has just
     * emptied, and a selection is not one, though with all of the field selected nothing is left to either side.
     */
    fun selectionReachesTheService(start: Int, end: Int) {
        val emptied = start == end && ed.text.substring(0, start).isEmpty() && ed.text.substring(end).isEmpty()
        if (emptied && (messageWords.isNotEmpty() || composing.isNotEmpty())) {
            if (composing.isNotEmpty()) messageWords.add(composing.toString())
            sentMessages += messageWords.joinToString(" ")
            messageWords.clear()
        }
        if (composing.isNotEmpty()) session.onComposingAbandoned(composing.toString())
        composing.clear()
        ed.finishComposing()
        session.onExternalCursorMove()
        keptRun = null
    }

    /** Backspace with [start] to [end] selected: the selection's own update, then the service's deleteSelectionIfAny. */
    fun selectThenBackspace(start: Int, end: Int) {
        selectionReachesTheService(start, end)
        val selected = ed.text.substring(start, end)
        ed.cursor = end
        ed.deleteBefore(end - start)
        lastGesture = null
        if (ed.text.isEmpty()) {
            committedWords.clear()
            messageWords.clear()
            typedSinceSeed = 0
        }
        if (selected.trimEnd(' ').lastOrNull()?.let { endsSentence(it) } == true && committedWords.lastOrNull() == MARK) {
            committedWords.removeLast()
        }
        val deleted = WordShape.contextForm(selected.trim())
        port.popContext(deleted)
        session.onWordDeleted(deleted, committedWords.lastOrNull() ?: "")
        session.onSelectionRemoved(selected)
    }

    /** An emoji from the panel. */
    fun emoji(e: String) {
        finishComposingWord()
        ed.commit(e)
        lastGesture = null
    }

    /**
     * The app calls setText with the keyboard up: input restarts, with no hide and no selection update.
     * Clearing the field this way is what a send button does in many chat apps.
     */
    fun appSetsText(value: String) {
        val empty = value.isEmpty()
        if (empty && (messageWords.isNotEmpty() || composing.isNotEmpty())) {
            if (composing.isNotEmpty()) messageWords.add(composing.toString())
            sentMessages += messageWords.joinToString(" ")
            messageWords.clear()
        }
        if (composing.isNotEmpty()) session.onComposingAbandoned(composing.toString())
        if (empty) session.onExternalCursorMove()
        composing.clear()
        ed.finishComposing()
        ed.text.setLength(0)
        ed.text.append(value)
        ed.cursor = value.length
    }

    /** The app empties the field in place, with no restart. The service hears of it as a selection update. */
    fun appClearsFieldInPlace() {
        if (messageWords.isNotEmpty() || composing.isNotEmpty()) {
            if (composing.isNotEmpty()) messageWords.add(composing.toString())
            sentMessages += messageWords.joinToString(" ")
            messageWords.clear()
        }
        if (composing.isNotEmpty()) session.onComposingAbandoned(composing.toString())
        session.onExternalCursorMove()
        composing.clear()
        ed.finishComposing()
        ed.text.setLength(0)
        ed.cursor = 0
    }

    fun hide() {
        if (composing.isNotEmpty()) session.onComposingAbandoned(composing.toString())
        session.onHide()
    }
    /** The user erased everything learned, in settings. */
    fun erase() { session.onEverythingErased(); persisted = null }
    fun showNewField() { session.onFieldChanged(); committedWords.clear(); messageWords.clear(); composing.clear(); typedSinceSeed = 0; keptRun = null }

    /** The words before the caret, as a read of the field gave them, take the trail's place, and [run] is the one left out. None of them was typed here. */
    fun reseed(tokens: List<String>, run: String? = null) {
        committedWords.clear()
        committedWords.addAll(tokens)
        draftWords.clear()
        keptRun = run
        typedSinceSeed = 0
    }

    /** An accepted draft is context for what comes next, and says nothing of how this person writes. */
    fun draftAccepted(text: String) {
        ed.commit(text)
        for (w in text.split(' ')) if (w.isNotBlank()) {
            val clean = w.lowercase()
            committedWords.addLast(clean)
            draftWords.add(clean)
        }
    }

    fun externalCursor(to: Int) {
        if (composing.isNotEmpty()) session.onComposingAbandoned(composing.toString())
        composing.clear()
        ed.moveCursor(to)
        session.onExternalCursorMove()
        keptRun = null
    }
    fun enter(actionKey: Boolean) {
        val raw = composing.toString()
        if (!actionKey && raw.isNotEmpty()) {
            val v = CorrectionPolicy.decide(input(raw, '\n'))
            if (v is Verdict.AutoCommit) applyFix(raw, v.word, "\n")
        }
        finishComposingWord()
        if (!actionKey) {
            ed.commit("\n")
            settleKeptRun()
        }
        keptRun = null
        endSentence()
    }

    /** Simulate a process restart: only what was persisted survives. */
    fun restart(): FakeIme {
        val fresh = FakeIme(clock, incognito)
        fresh.evidence = evidence; fresh.known = known; fresh.level = level; fresh.field = field
        fresh.confirmed += confirmed
        persisted?.let { fresh.memory.restore(it) }
        fresh.persisted = persisted
        return fresh
    }

    companion object {
        /** What the service's context trail holds where a sentence ended. */
        const val MARK = "."
        val SENTENCE_ENDS = setOf(".", "!", "?")

        fun endsSentence(c: Char) = c == '\n' || c.toString() in SENTENCE_ENDS
    }
}
