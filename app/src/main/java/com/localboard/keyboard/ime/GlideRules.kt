package com.localboard.keyboard.ime

import com.localboard.keyboard.engine.ContextTokens
import com.localboard.keyboard.engine.Suggestion
import com.localboard.keyboard.engine.correction.FieldPolicy
import com.localboard.keyboard.engine.correction.WordShape
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * What a glide decides from the words and the field around it, apart from the service so that it can run on a JVM.
 * Nothing here touches the editor. The service does the reads and hands over what it found.
 */
internal object GlideRules {

    // ------------------------------------------------------------------
    // The words before a glide
    // ------------------------------------------------------------------

    data class WordsBefore(val prev1: String?, val prev2: String?)

    /**
     * The two words a glide follows. One still composing is finished only when the glide's own word lands, so it is
     * not on the trail yet, and the trail's last entry is then the word before it.
     */
    fun wordsBefore(composing: CharSequence, trail: List<String>): WordsBefore =
        if (composing.isNotEmpty()) {
            WordsBefore(WordShape.normalized(WordShape.stem(composing.toString())).lowercase(), trail.lastOrNull())
        } else {
            WordsBefore(trail.lastOrNull(), trail.getOrNull(trail.size - 2))
        }

    // ------------------------------------------------------------------
    // Words the person has typed since the trail was read from the field
    // ------------------------------------------------------------------

    fun countAfterAdding(count: Int, words: Int = 1): Int = count.coerceAtLeast(0) + words.coerceAtLeast(0)

    /** Backspace can take off a word the field gave the trail, which nobody counted, so this stops at 0. */
    fun countAfterRemoving(count: Int): Int = (count - 1).coerceAtLeast(0)

    /**
     * What may teach how this person writes when a sentence ends, or null for nothing. A trail read from the field may
     * hold a draft or quoted text, so only the last words they typed count, and a sentence with none of those is no sentence.
     */
    fun sentenceToLearn(own: List<String>, typedSinceSeed: Int): String? {
        val slice = own.takeLast(minOf(MOST_WORDS_IN_A_SENTENCE, typedSinceSeed.coerceAtLeast(0)))
        return if (slice.any { it != ContextTokens.SENTENCE_MARK }) slice.joinToString(" ") else null
    }

    // ------------------------------------------------------------------
    // Reading the field
    // ------------------------------------------------------------------

    /**
     * Whether earlier text may be read out of this field for context at all. Everything that decides this has to come
     * before the read: a password, a private field, a URL or an address, or an app that asked for no suggestions.
     */
    fun mayReadField(inputType: Int, imeOptions: Int, mayLearnInApp: Boolean, ownPackage: Boolean): Boolean {
        if (ownPackage) return false
        val field = FieldPolicy.from(inputType, imeOptions, mayLearnInApp)
        return field.suggestions && field.glide && field.learn
    }

    /**
     * Whether what was read replaces the trail at a show. A new field always takes it, as the trail was just emptied. A
     * restart keeps a trail holding words, so that a rotation stays whole, unless the field was left empty with the caret
     * at its start, which is how an app clearing its field by setText looks.
     */
    fun showReplacesTrail(restarting: Boolean, read: CharSequence?, trail: List<String>, initialSelStart: Int): Boolean = when {
        read == null -> false
        !restarting -> true
        trail.none { it != ContextTokens.SENTENCE_MARK } -> true
        else -> read.isEmpty() && initialSelStart <= 0
    }

    /** What the caret moved past when the check was scheduled, or what is there now. */
    data class MoveState(val packageName: String, val fieldId: Int, val typedSinceSeed: Int)

    /**
     * Whether a debounced caret move may still act. Nothing cached says which field this is by the time it fires: the
     * connection can already be the next field's, so [inputType] must be the one the editor reports now.
     */
    fun moveMayAct(scheduled: MoveState, now: MoveState, composing: CharSequence, inputType: Int): Boolean =
        composing.isEmpty() && scheduled == now && !FieldPolicy.isPassword(inputType)

    // ------------------------------------------------------------------
    // Capitals and spaces
    // ------------------------------------------------------------------

    enum class Shift { OFF, ON, CAPS_LOCK }

    /**
     * How a decoded word is written. [leadAfterSentenceEnd] is a space going in after . ! or ?, which starts a sentence
     * though Shift cannot know it, since the check behind Shift only sees that sentence end once a space has followed it.
     */
    fun caseWord(word: String, shift: Shift, addressField: Boolean, leadAfterSentenceEnd: Boolean = false): String = when {
        shift == Shift.CAPS_LOCK -> word.uppercase()
        addressField -> word
        shift == Shift.ON || leadAfterSentenceEnd -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    fun endsSentence(c: Char?): Boolean = c == '.' || c == '!' || c == '?'

    /** A word needs a space between it and what is before the cursor. */
    fun wantsLeadSpace(autoSpace: Boolean, addressField: Boolean, gluedToAddress: Boolean, before: Char?): Boolean {
        if (!autoSpace || addressField || gluedToAddress || before == null) return false
        return before.isLetterOrDigit() || before in AFTER_THESE_A_WORD_NEEDS_A_SPACE || endsAnEmoji(before)
    }

    /** A word gets a space after it, unless what follows the cursor already keeps it apart. */
    fun wantsTailSpace(autoSpace: Boolean, addressField: Boolean, after: Char?): Boolean {
        if (!autoSpace || addressField) return false
        return after == null || !(after.isWhitespace() || after in CLOSING_PUNCTUATION)
    }

    /**
     * Whether the text before the caret in a URI field reads as an address. A browser's bar takes searches as well, so
     * words there stay apart unless the run touching the caret, back to the last whitespace, holds . / : or @.
     * A read that failed gives no reason to glue anything.
     */
    fun uriRunIsAddress(beforeCaret: CharSequence?): Boolean {
        if (beforeCaret == null) return false
        val runStart = beforeCaret.indexOfLast { it.isWhitespace() } + 1
        return (runStart until beforeCaret.length).any { beforeCaret[it] in ADDRESS_MARKS }
    }

    private fun endsAnEmoji(c: Char): Boolean =
        c.isLowSurrogate() || c in '\u2600'..'\u27BF' || c == '\uFE0F' || c == '\u20E3'

    // ------------------------------------------------------------------
    // The bar after a glide
    // ------------------------------------------------------------------

    /**
     * What the bar offers once a glide has landed: what comes next, with one way back. By the bar's slot order the list
     * [next1, alternate, next2] draws next1 in the centre, where a thumb goes for the next word, so that a tap there can
     * never replace the glide. A next word equal to the alternate or to the glided word would be a tap that means
     * something else, so those go. Alone, the alternate takes the score that puts one word on the left.
     */
    fun barAfterGlide(display: List<Suggestion>, alternate: Suggestion?, glidedWord: String?): List<Suggestion> {
        if (alternate == null) return display
        val next = display.filter {
            !it.text.equals(alternate.text, ignoreCase = true) && !it.text.equals(glidedWord, ignoreCase = true)
        }
        if (next.isEmpty()) return listOf(alternate.copy(score = LONE_WORD_SCORE))
        val kept = if (alternate.score in MARKER_SCORES) alternate.copy(score = 1.0) else alternate
        return listOfNotNull(next[0], kept, next.getOrNull(1))
    }

    /**
     * Whether a finger that came onto a chip at [enteredAt] and lifted at [liftAt] stayed there. A chip that takes something
     * back asks for this, because a stroke overshooting the top edge can end over one with no such wish.
     */
    fun stayedOnChip(enteredAt: Long, liftAt: Long): Boolean = liftAt - enteredAt >= CHIP_STAY_MS

    /**
     * What the bar shows while a stroke is under way: how the stroke reads so far. A chip that takes something back keeps
     * its place among those words, since a finger sliding up to it has to find it there. [revertWord] is the word as
     * typed before a correction, and that chip is always last.
     */
    fun barDuringStroke(preview: List<Suggestion>, alternate: Suggestion?, revertWord: String?): List<Suggestion> = when {
        revertWord != null ->
            preview.filter { !it.text.equals(revertWord, ignoreCase = true) }.take(2) + Suggestion(revertWord, REVERT_CHIP_SCORE)
        alternate != null -> barAfterGlide(preview, alternate, null)
        else -> preview
    }

    // ------------------------------------------------------------------
    // A glide read before its table is ready
    // ------------------------------------------------------------------

    /** Under this share of the view's width a stroke that cannot be read may still be a slip on a key. */
    private const val LONG_PATH_SHARE = 0.30f

    fun isLongPath(pathLength: Float, viewWidth: Int): Boolean = pathLength >= viewWidth * LONG_PATH_SHARE

    /** Everything a held glide's word depends on, as it was when that glide's turn came. */
    data class HoldSnapshot(
        val prev1: String?,
        val prev2: String?,
        val shift: Shift,
        val lastKeystrokeAt: Long,
        val packageName: String,
        val fieldId: Int,
        val composing: String,
    )

    /**
     * A held decode outlives the lift. Any change since its turn began means a key, a Backspace or another field has come
     * between the finger and the word, which would land in the wrong place, so the word is dropped.
     */
    fun holdMayCommit(atTurn: HoldSnapshot, now: HoldSnapshot, keyboardShown: Boolean): Boolean =
        keyboardShown && atTurn == now

    // ------------------------------------------------------------------
    // What glide has learned, with the day it was learned
    // ------------------------------------------------------------------

    /** One word's bias: [value] and [day] as stored, and [worth], what that came to when it was loaded. */
    data class BiasEntry(val value: Int, val day: Long, val worth: Int)

    /**
     * Reads the stored form: word, tab, value, tab, day in days since the epoch, one per line. A line with no day is from
     * a build before days were kept, and counts as today. A line that cannot be read goes alone, so that one bad line
     * costs one word. A value worth nothing by now is left out.
     */
    fun loadBias(stored: String, today: Long): Map<String, BiasEntry> {
        val out = LinkedHashMap<String, BiasEntry>()
        for (line in stored.split('\n')) {
            val parts = line.split('\t')
            if (parts.size !in 2..3 || parts[0].isEmpty()) continue
            val value = parts[1].toIntOrNull() ?: continue
            val day = if (parts.size == 3) parts[2].toLongOrNull() ?: continue else today
            val worth = decayedBias(value, today - day)
            if (worth != 0) out[parts[0]] = BiasEntry(value, day, worth)
        }
        return out
    }

    /**
     * What a stored value is worth [ageDays] on: half every [BIAS_HALF_LIFE_DAYS], rounded, and 0 once under a half.
     * Only a load works this out, from the value as stored, or each load would age it again.
     */
    fun decayedBias(value: Int, ageDays: Long): Int {
        val worth = abs(value) * 0.5.pow(ageDays.coerceAtLeast(0) / BIAS_HALF_LIFE_DAYS)
        if (worth < 0.5) return 0
        val rounded = floor(worth + 0.5).toInt()
        return if (value < 0) -rounded else rounded
    }

    /**
     * What to store for what a decoder holds. A word it has not changed since the load goes back as it was, with its day,
     * so that a save makes no old learning new. One it has changed is new learning, dated [today].
     */
    fun biasToStore(current: Map<String, Int>, loaded: Map<String, BiasEntry>, today: Long): Map<String, BiasEntry> {
        val out = LinkedHashMap<String, BiasEntry>()
        for ((word, value) in current) {
            val was = loaded[word]
            out[word] = if (was != null && was.worth == value) was else BiasEntry(value, today, value)
        }
        return out
    }

    fun formatBias(entries: Map<String, BiasEntry>): String =
        entries.entries.joinToString("\n") { (word, entry) -> "$word\t${entry.value}\t${entry.day}" }

    private const val MOST_WORDS_IN_A_SENTENCE = 24
    private const val BIAS_HALF_LIFE_DAYS = 30.0

    /** The bar's marker for one word and nothing else, which draws it on the left. */
    private const val LONE_WORD_SCORE = 0.0

    /** The bar's marker for the chip that undoes a correction. */
    private const val REVERT_CHIP_SCORE = -1.0

    /** How long a finger stays on a chip that takes something back to mean it. A proposal, not measured on real fingers. */
    const val CHIP_STAY_MS = 150L

    /** Scores the bar reads as the word as typed and the revert chip, which an alternate is neither of. */
    private val MARKER_SCORES = setOf(LONE_WORD_SCORE, REVERT_CHIP_SCORE)

    private const val AFTER_THESE_A_WORD_NEEDS_A_SPACE = ".,;:!?)]}"
    private const val CLOSING_PUNCTUATION = ".,;:!?)]}\"'\u2019\u201D"
    private const val ADDRESS_MARKS = "./:@"
}
