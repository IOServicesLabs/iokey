"""Rewrites a scratch copy of FastEngine.kt, given as the only argument, in place.

Holds the engine changes the correction policy was measured with, so the gates in src/Gates.kt can
run before the engine itself carries them. Every edit must match exactly once, so an engine that has
moved on fails here, loudly, rather than measuring something else. Never point it at the repo's copy.
"""
import io, sys

p = sys.argv[1]
s = io.open(p, encoding='utf-8', newline='').read()
s = s.replace('\r\n', '\n')

def sub(old, new, count=1):
    global s
    assert s.count(old) == count, (old, s.count(old))
    s = s.replace(old, new)

# --- Edit costs: a missing apostrophe and a doubled letter are cheap, in the right direction
sub("""                val insCost = if (letter == '\\'') APOSTROPHE_EDIT_COST else 1.0
                val delCost = if (w[i - 1] == '\\'') APOSTROPHE_EDIT_COST else 1.0
                var v = minOf(prevRow[i] + delCost, row[i - 1] + insCost, prevRow[i - 1] + subCost)
""", """                val candExtra = when {               // candidate has a letter the typed word lacks
                    letter == '\\'' -> APOSTROPHE_EDIT_COST
                    letter == prevLetter -> DOUBLED_LETTER_COST
                    else -> 1.0
                }
                val typedExtra = when {              // typed word has a character the candidate lacks
                    w[i - 1] == '\\'' -> APOSTROPHE_EDIT_COST
                    i >= 2 && w[i - 1] == w[i - 2] -> DOUBLED_LETTER_COST
                    else -> 1.0
                }
                var v = minOf(prevRow[i] + candExtra, row[i - 1] + typedExtra, prevRow[i - 1] + subCost)
""")
sub("        private const val APOSTROPHE_EDIT_COST = 0.3\n",
    "        private const val APOSTROPHE_EDIT_COST = 0.3\n        private const val DOUBLED_LETTER_COST = 0.5\n        private const val SPATIAL_RAW_BONUS = 2.0\n")

# --- Tap evidence must beat the raw string by more before it can rewrite it
sub("var rawScore = 1.0", "var rawScore = SPATIAL_RAW_BONUS")

# --- Closed table: misspellings and junk run-ons the dictionary carries as if they were words
sub("        for (raw in CONTRACTION_FIXES.keys) trie.remove(raw)\n",
    "        for (raw in CONTRACTION_FIXES.keys) trie.remove(raw)\n        for (raw in COMMON_MISSPELLINGS.keys) trie.remove(raw)\n")
sub("""        fun contractionFixFor(rawLower: String, userCount: Int = 0): String? {
            if (userCount >= 3) return null
            return CONTRACTION_FIXES[rawLower]
        }
""", """        fun contractionFixFor(rawLower: String, userCount: Int = 0): String? {
            if (userCount >= 3) return null
            return CONTRACTION_FIXES[rawLower]
        }

        /** Fixes a margin cannot find: the typed form is removed from the trie at load. */
        private val COMMON_MISSPELLINGS: Map<String, String> = mapOf(
            "agressive" to "aggressive", "alot" to "a lot", "apparantly" to "apparently", "belive" to "believe",
            "bizzare" to "bizarre", "buisness" to "business", "cemetary" to "cemetery", "commited" to "committed",
            "concious" to "conscious", "curiousity" to "curiosity", "dissapear" to "disappear",
            "dissapoint" to "disappoint", "dosent" to "doesn't", "dosnt" to "doesn't", "ecstacy" to "ecstasy",
            "embarass" to "embarrass", "familar" to "familiar", "florescent" to "fluorescent", "fourty" to "forty",
            "grammer" to "grammar", "gaurd" to "guard", "happend" to "happened", "harrass" to "harass",
            "independant" to "independent", "irresistable" to "irresistible", "jewelery" to "jewelry",
            "knowlege" to "knowledge", "liason" to "liaison", "maintainance" to "maintenance",
            "millenium" to "millennium", "noticable" to "noticeable", "occured" to "occurred",
            "oppurtunity" to "opportunity", "potatos" to "potatoes", "prefered" to "preferred",
            "recomend" to "recommend", "refered" to "referred", "relevent" to "relevant", "religous" to "religious",
            "remeber" to "remember", "resistence" to "resistance", "rythm" to "rhythm", "seige" to "siege",
            "sentance" to "sentence", "similiar" to "similar", "speach" to "speech", "succesful" to "successful",
            "suprise" to "surprise", "tatoo" to "tattoo", "tounge" to "tongue", "truely" to "truly",
            "vaccum" to "vacuum",
            // Not dictionary entries, but the margin gate leaves them without context.
            "plase" to "please", "helo" to "hello", "thnks" to "thanks", "becuz" to "because",
            // Missed spaces the corpus carries as junk entries, so they read as known words.
            "ofthe" to "of the", "inthe" to "in the", "tothe" to "to the", "onthe" to "on the",
            "forthe" to "for the", "andthe" to "and the", "tobe" to "to be", "withthe" to "with the",
            "fromthe" to "from the", "bythe" to "by the", "atthe" to "at the", "willbe" to "will be",
            "donot" to "do not", "isthe" to "is the", "ofthis" to "of this", "thesame" to "the same",
            "youcan" to "you can", "ifyou" to "if you", "outof" to "out of", "aswell" to "as well",
            "upto" to "up to", "toget" to "to get", "itwas" to "it was", "theworld" to "the world",
            "thisis" to "this is", "howto" to "how to", "atleast" to "at least", "ifthe" to "if the",
            "inorder" to "in order", "kindof" to "kind of", "ofcourse" to "of course",
            "eachother" to "each other", "rightnow" to "right now", "sofar" to "so far", "noone" to "no one",
            "thankyou" to "thank you", "iam" to "I am", "highschool" to "high school",
            "realestate" to "real estate", "doyou" to "do you",
        )

        /** Contractions and curated misspellings. Same rule as the stock contraction map. */
        fun closedFixFor(rawLower: String, userCount: Int = 0): String? {
            if (userCount >= 3) return null
            return CONTRACTION_FIXES[rawLower] ?: COMMON_MISSPELLINGS[rawLower]
        }
""")

# --- "a" and "i" are not in the word list, so the split loop skipped them
sub("""    private fun partFrequency(p: String): Long {
        val dict = trie.frequency(p)          // 0 when absent, never null
""", """    private fun partFrequency(p: String): Long {
        if (p == "a" || p == "i") return ONE_LETTER_WORD_FREQUENCY
        val dict = trie.frequency(p)          // 0 when absent, never null
""")
sub("        private const val SPLIT_MIN_SCORE = 8.0\n",
    "        private const val SPLIT_MIN_SCORE = 8.0\n        private const val ONE_LETTER_WORD_FREQUENCY = 3_000_000L\n")

# --- A real pair is evidence whichever table it is in
sub("            if (hasBigram(a, b)) s += 3.0\n",
    "            if (isRealPair(a, b)) s += 3.0\n")
sub("""    /** Halves that are allowed to appear in a split.
""", """    /** The user has written this pair, or English has. [hasBigram] reads the user's table only. */
    private fun isRealPair(a: String, b: String): Boolean =
        hasBigram(a, b) || (languageModel?.prob(a, b) ?: 0.0) > 0.0

    /** Halves that are allowed to appear in a split.
""")

# --- A learned word stays reachable at every count, in the trie or through the per-keystroke scan
sub("            if (c < DICTIONARY_PROMOTION_THRESHOLD) unpromotedWords.add(w)\n            if (isEmailToken(w)) emailTokens[w] = c\n",
    "            syncPromotion(w)\n            if (isEmailToken(w)) emailTokens[w] = c\n")
sub("            if (n < DICTIONARY_PROMOTION_THRESHOLD) unpromotedWords.add(w)\n        }\n        topUnigramDirty = Int.MAX_VALUE\n",
    "            syncPromotion(w)\n        }\n        topUnigramDirty = Int.MAX_VALUE\n")
sub("""            if ((userUnigram[w] ?: 0) < DICTIONARY_PROMOTION_THRESHOLD)
                unpromotedWords.add(w) else unpromotedWords.remove(w)
""", "            syncPromotion(w)\n")
sub("""        if (newCount < DICTIONARY_PROMOTION_THRESHOLD) unpromotedWords.add(w)
        else unpromotedWords.remove(w)
        if (newCount >= DICTIONARY_PROMOTION_THRESHOLD) injectPersonal(w)
""", "        syncPromotion(w)\n")
sub("""        clearStrikes(w)
        if ((userUnigram[w] ?: 0) >= DICTIONARY_PROMOTION_THRESHOLD) injectPersonal(w)
    }

    fun confirmedWordsSnapshot()""", """        clearStrikes(w)
        syncPromotion(w)
    }

    /** One place that decides how a counted word is reachable: through the trie
     *  once it has earned promotion, through the per-keystroke scan until then. */
    private fun syncPromotion(w: String) {
        val c = userUnigram[w] ?: return
        val promoted = c >= DICTIONARY_PROMOTION_THRESHOLD && (w in confirmedWords || c >= STRONG_USAGE_THRESHOLD)
        if (promoted) { unpromotedWords.remove(w); injectPersonal(w) } else unpromotedWords.add(w)
    }

    /** A bar tap that confirmed [word] was a slip. Never touches a word confirmed earlier: the caller checks. */
    fun unconfirm(word: String) {
        val w = word.lowercase().trim()
        confirmedWords.remove(w)
        if ((userUnigram[w] ?: 0) < STRONG_USAGE_THRESHOLD && injectedPersonal.remove(w)) {
            trie.remove(w)
            bumpTrie()
        }
        syncPromotion(w)
    }

    fun isConfirmed(word: String): Boolean = word.lowercase().trim() in confirmedWords

    fun hasStrike(word: String): Boolean =
        synchronized(typoStrikes) { typoStrikes.containsKey(word.lowercase().trim()) }

    /** The user said [word] is not a typo: drop any strike and any blacklist entry. */
    fun clearTypoVerdict(word: String) {
        val w = word.lowercase().trim()
        clearStrikes(w)
        if (typoSuppressions.remove(w)) {
            suppressionStamps.remove(w)
            predictionCache.evictAll()
        }
    }

    fun confirmedWordsSnapshot()""")

# --- noteTypo: strike one is only a note, and only a promoted personal word can leave the trie
sub("""        val w = word.lowercase().trim()
        if (w.length < 2 || w.any { it.isDigit() }) return
        val now = System.currentTimeMillis()
        val strikes: Int
""", """        val w = word.lowercase().trim()
        if (w.length < 2 || w.any { it.isDigit() }) return
        if (w in confirmedWords) return
        val now = System.currentTimeMillis()
        val strikes: Int
""")
sub("""            typoStrikes[w] = strikes to now
        }
        userUnigram.remove(w)
        unpromotedWords.remove(w)
        emailTokens.remove(w)
        // Strike one already demotes the trie entry — a deliberately re-typed
        // word is restored by injectPersonal on the next learn, so this costs
        // a deliberate word nothing but immediately stops suggesting a typo
        // that had reached promotion strength before the verdict arrived.
        trie.remove(w)
        bumpTrie()
        if (strikes >= 2) {
            // Confirmed typo: scrub it from the n-gram model too, not just
            // the unigram map — otherwise "prev|typo" entries keep
            // recommending the very word the user has now rejected twice.
            scrubNgrams(w)
            typoSuppressions.add(w)
            suppressionStamps[w] = now
            synchronized(typoStrikes) { typoStrikes.remove(w) }
        }
        topUnigramDirty = Int.MAX_VALUE
""", """            typoStrikes[w] = strikes to now
        }
        // One accepted correction is only a note. It removes nothing.
        if (strikes < 2) { modelVersion++; return }
        userUnigram.remove(w)
        unpromotedWords.remove(w)
        emailTokens.remove(w)
        // Only a word personal promotion added may leave the trie: never a shipped word.
        if (injectedPersonal.remove(w)) { trie.remove(w); bumpTrie() }
        scrubNgrams(w)
        typoSuppressions.add(w)
        suppressionStamps[w] = now
        synchronized(typoStrikes) { typoStrikes.remove(w) }
        topUnigramDirty = Int.MAX_VALUE
""")

# --- Evidence for the pure policy
sub("""    /** The shared candidate search behind [autoCorrectTop] and
""", """    /** Everything the engine knows about a typed word. It decides nothing: see CorrectionPolicy. */
    fun correctionEvidence(lower: String, p1: String, p2: String, capitalized: Boolean): CorrectionEvidence {
        val userCount = userUnigram[lower] ?: 0
        val known = trie.contains(lower) || lower in SHORT_WORDS
        val ranked = autoCorrectScored(
            lower, minLen = 2,
            forceMaxDist = if (lower.length == 2) SHORT_WORD_MAX_DIST else 0,
            prev1 = p1, prev2 = p2, correctionBudget = 30_000,
            allowKnownWord = true
        )
        val pool = if (lower.length != 2) ranked else
            ranked.filter { it.first in SHORT_WORDS || (userUnigram[it.first] ?: 0) >= 2 }
        val winner = pool.firstOrNull()
        var override: String? = null
        if (winner != null && winner.first != lower && (known || lower.length == 2)) {
            val lp1 = p1.lowercase()
            val lp2 = p2.lowercase()
            val typedFreq = max(trie.frequency(lower), userCount.toLong() * 8)
            val baselineCtx = contextBonusFor(lp1, lp2, lower)
            val baseline = ln((typedFreq + 1).toDouble()) * FREQ_BLEND_W + baselineCtx
            val winnerCtx = contextBonusFor(lp1, lp2, winner.first)
            val contextDecisive = winnerCtx > baselineCtx &&
                (winner.third - winnerCtx) < baseline + CONTEXT_OVERRIDE_MARGIN
            if (winner.third - baseline >= CONTEXT_OVERRIDE_MARGIN && contextDecisive) override = winner.first
        }
        val cut = if (known) null else splitRunOn(lower, p1, capitalized)
        return CorrectionEvidence(
            known = known,
            confirmed = lower in confirmedWords,
            strongUsage = userCount >= STRONG_USAGE_THRESHOLD,
            closedFix = closedFixFor(lower, userCount),
            candidates = pool.take(3).map {
                Candidate(it.first, it.second, it.third, trie.frequency(it.first), userUnigram[it.first] ?: 0)
            },
            contextOverride = override,
            split = cut?.takeIf { isRealPair(it.first, it.second) }?.let { (a, b) ->
                (if (a == "i") "I" else a) + " " + (if (b == "i") "I" else b)
            },
        )
    }

    /** The word tap positions favour over the raw string, or null. Conservative may change only one edit. */
    fun spatialEvidence(
        lower: String, trace: List<android.graphics.PointF>, geometry: KeyboardGeometry,
        p1: String, p2: String, level: Int,
    ): Candidate? = spatialCorrect(lower, trace, geometry, p1, p2, 3, if (level == 1) 1 else 0)
        .firstOrNull()
        ?.let { (w, s) -> Candidate(w, 0.0, s, trie.frequency(w), userUnigram[w] ?: 0) }

    /** The shared candidate search behind [autoCorrectTop] and
""")

# --- Imports and the evidence type
sub("class FastEngine", """data class CorrectionEvidence(
    val known: Boolean,
    val confirmed: Boolean,
    val strongUsage: Boolean,
    val closedFix: String?,
    val candidates: List<Candidate>,
    val contextOverride: String?,
    val split: String?,
)

class FastEngine""")
idx = s.index('\nimport ')
s = s[:idx] + '\nimport com.localboard.keyboard.engine.correction.Candidate' + s[idx:]

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
