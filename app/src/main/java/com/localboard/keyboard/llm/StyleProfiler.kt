package com.localboard.keyboard.llm

import kotlin.math.roundToInt

/**
 * Deterministic style profiler — the answer to the hallucinating-distillation
 * failure. A 0.6B model asked to "list observable writing habits" does NOT
 * read the sample: it answers from its prior of what such lists look like
 * (proven offline: it claimed "always capitalizes" over an all-lowercase
 * sample, invented emoji over a zero-emoji sample, and repeated the same
 * generic paragraph regardless of content).
 *
 * So the facts are MEASURED here, never guessed. The model's only job (see
 * StyleActivity / NightlyDistillWorker) is to rewrite these facts into one
 * fluent paragraph — a task it does faithfully — and anything it returns is
 * checked against the facts before it can be saved. If it fails, the
 * deterministic fallback rendering is used instead. Either way, the saved
 * profile is always true to the sample.
 */
object StyleProfiler {

    data class Facts(
        val messageCount: Int,
        val totalWords: Int,
        val avgWords: Float,
        val capStartPct: Int,          // % of messages starting with a capital
        val periodRate: Float,         // sentence-ending periods per message
        val ellipsisRate: Float,       // "..." per message
        val questionRate: Float,       // "?" per message
        val exclaimRate: Float,        // "!" per message
        val emojiPerMsg: Float,
        val topEmojis: List<String>,
        val slang: List<String>,       // signature words/phrases, by frequency
        val topics: List<String>,      // recurring content words + bigrams
        val polished: Boolean,         // derived formality read
        val greetingPct: Int,          // % of messages opening with hey/hi/yo…
        val lowercaseI: Boolean        // writes the pronoun "i" lowercase
    )

    private val EMOJI_RE = Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}]")
    private val WORD_SPLIT = Regex("[^a-z0-9']+")

    /** Texting shorthand worth calling out — the signature of a voice. */
    private val SLANG_LEXICON = listOf(
        "lol", "lmao", "lmfao", "haha", "hahaha", "hehe", "tbh", "ngl", "fr",
        "nah", "smh", "bet", "omw", "otw", "gtg", "g2g", "brb", "idk", "idc",
        "rn", "imma", "gonna", "wanna", "gotta", "kinda", "sorta", "yeah",
        "yep", "nope", "kk", "omg", "wtf", "fml", "lowkey", "highkey",
        "deadass", "no cap", "cap", "lit", "goat", "sus", "salty", "hype",
        "chill", "vibes", "irl", "asap", "ttyl", "cya", "thx", "thnx", "ty",
        "pls", "plz", "rly", "def", "probs", "obvi", "totes", "legit", "bro",
        "dude", "mate", "fam", "yall", "ya'll", "wyd", "wdym", "hmu", "ily",
        "idc", "imo", "imho", "jk", "np", "sry", "srry", "pls", "u", "ur"
    )

    /** Common words that must never become "topics". */
    private val STOPWORDS = setOf(
        "the", "and", "for", "are", "was", "were", "you", "your", "yours",
        "that", "this", "these", "those", "with", "have", "has", "had", "not",
        "but", "all", "can", "cant", "could", "should", "would", "will", "wont",
        "just", "like", "know", "want", "still", "now", "then", "there", "here",
        "did", "does", "done", "dont", "didnt", "doesnt", "isnt", "arent",
        "off", "over", "only", "been", "being", "from", "into", "than", "too",
        "very", "really", "say", "said", "see", "let", "lets", "his", "her",
        "their", "our", "him", "she", "hes", "shes", "youre", "youve", "ill",
        "ive", "thats", "theres", "whats", "any", "some", "more", "much",
        "many", "back", "even", "also", "yet", "already", "always", "never",
        "ever", "again", "once", "tonight", "tomorrow", "today", "yesterday",
        "about", "what", "when", "who", "how", "why", "where", "which", "out",
        "get", "got", "getting", "going", "gone", "gonna", "wanna", "come",
        "came", "coming", "take", "took", "make", "made", "give", "gave",
        "tell", "told", "ask", "asked", "need", "needed", "think", "thought",
        "yeah", "yes", "okay", "ok", "well", "right", "sure", "maybe",
        "probably", "actually", "basically", "literally", "though", "tho",
        "lol", "lmao", "haha", "one", "two", "three", "first", "last",
        "good", "great", "nice", "cool", "bad", "new", "old", "big", "little",
        "long", "way", "time", "day", "night", "morning", "week", "weekend",
        "thing", "things", "stuff", "lot", "lots", "bit", "hey", "hi", "hello",
        "thanks", "thank", "please", "sorry", "love", "hate", "hope", "wish",
        "feel", "felt", "feeling", "mean", "meant", "guess", "wonder",
        "remember", "forget", "forgot", "wait", "waiting", "call", "called",
        "text", "texting", "message", "send", "sent", "sending", "talk",
        "talking", "meet", "meeting", "home", "house", "work", "working",
        "worked", "im", "ive", "id", "ill", "we", "us", "our", "ours", "they",
        "them", "their", "theirs", "he", "she", "it", "its", "itself", "is",
        "am", "be", "been", "being", "do", "doing", "if", "or", "as", "at",
        "by", "in", "on", "of", "to", "up", "down", "so", "no", "my", "mine",
        "me", "i", "a", "an"
    )

    fun analyze(messages: List<String>): Facts {
        val msgs = messages.map { it.trim() }.filter { it.length >= 2 }
        val n = msgs.size.coerceAtLeast(1)
        var totalWords = 0
        var capStarts = 0
        var letterStarts = 0
        var periods = 0
        var ellipses = 0
        var questions = 0
        var exclaims = 0
        var greetings = 0
        var lowerI = 0
        var upperI = 0
        val emojiCounts = HashMap<String, Int>()
        val wordCounts = HashMap<String, Int>()
        val bigramCounts = HashMap<String, Int>()
        val slangCounts = HashMap<String, Int>()
        val slangRes = SLANG_LEXICON.distinct()
            .map { it to Regex("\\b" + Regex.escape(it) + "\\b") }

        for (m in msgs) {
            val words = m.lowercase().split(WORD_SPLIT).filter { it.isNotBlank() }
            totalWords += words.size
            val first = m.firstOrNull { it.isLetter() }
            if (first != null && m.indexOf(first) <= 1) {
                letterStarts++
                if (first.isUpperCase()) capStarts++
            }
            val ell = Regex("\\.\\.\\.").findAll(m).count()
            ellipses += ell
            periods += (Regex("(?<!\\.)\\.(?!\\.)").findAll(m).count())
            questions += m.count { it == '?' }
            exclaims += m.count { it == '!' }
            val firstTok = m.lowercase().split(WORD_SPLIT).firstOrNull { it.isNotBlank() } ?: ""
            if (firstTok in GREETINGS) greetings++
            lowerI += Regex("\\bi\\b").findAll(m).count()
            upperI += Regex("\\bI\\b").findAll(m).count()
            for (em in EMOJI_RE.findAll(m)) {
                val k = em.value
                emojiCounts[k] = (emojiCounts[k] ?: 0) + 1
            }
            val low = m.lowercase()
            for ((term, re) in slangRes) {
                val c = re.findAll(low).count()
                if (c > 0) slangCounts[term] = (slangCounts[term] ?: 0) + c
            }
            // Topic candidates: content words + adjacent content bigrams.
            val content = words.map { it.trim('\'') }
                .filter { it.length >= 3 && it !in STOPWORDS && it.all { ch -> ch.isLetter() } }
            for (w in content) wordCounts[w] = (wordCounts[w] ?: 0) + 1
            for (i in 0 until content.size - 1) {
                val bg = content[i] + " " + content[i + 1]
                bigramCounts[bg] = (bigramCounts[bg] ?: 0) + 1
            }
        }

        val emojiTotal = emojiCounts.values.sum()
        val slangTop = slangCounts.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(6)
        val slangSet = slangTop.toSet()
        val topBigrams = bigramCounts.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(3)
        val inBigram = topBigrams.flatMap { it.split(' ') }.toSet()
        val topWords = wordCounts.entries
            .filter { it.value >= 2 && it.key !in slangSet && it.key !in inBigram }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(6 - topBigrams.size.coerceAtMost(6))
        val topics = (topBigrams + topWords).take(6)

        val avgWords = totalWords.toFloat() / n
        val capPct = if (letterStarts > 0) (capStarts * 100) / letterStarts else 50
        val periodRate = periods.toFloat() / n
        val slangHits = slangCounts.values.sum()
        var toneScore = 0.0
        if (capPct >= 70) toneScore += 0.35 else if (capPct <= 20) toneScore -= 0.15
        if (periodRate >= 0.6f) toneScore += 0.25
        if (avgWords >= 10f) toneScore += 0.2 else if (avgWords <= 5f) toneScore -= 0.1
        if (totalWords > 0 && slangHits.toFloat() / totalWords >= 0.02f) toneScore -= 0.2
        else toneScore += 0.1
        if (emojiTotal.toFloat() / n >= 0.5f) toneScore -= 0.1

        return Facts(
            messageCount = msgs.size,
            totalWords = totalWords,
            avgWords = (avgWords * 10).roundToInt() / 10f,
            capStartPct = capPct,
            periodRate = periodRate,
            ellipsisRate = ellipses.toFloat() / n,
            questionRate = questions.toFloat() / n,
            exclaimRate = exclaims.toFloat() / n,
            emojiPerMsg = emojiTotal.toFloat() / n,
            topEmojis = emojiCounts.entries.sortedByDescending { it.value }
                .map { it.key }.take(3),
            slang = slangTop,
            topics = topics,
            polished = toneScore >= 0.5,
            greetingPct = greetings * 100 / n,
            lowercaseI = lowerI >= 3 && lowerI > upperI * 2
        )
    }

    private val GREETINGS = setOf(
        "hey", "heyy", "heyyy", "hi", "hii", "hiya", "yo", "sup", "morning",
        "gm", "howdy", "heya", "hai", "hello"
    )

    /** The measured notes handed to the model for its rewrite-only pass. */
    fun factsBlock(f: Facts): String = buildString {
        append("Measurements from ").append(f.messageCount).append(" sent text messages:\n")
        append("- average ").append(f.avgWords).append(" words per message\n")
        when {
            f.capStartPct <= 15 ->
                if (f.capStartPct <= 3)
                    append("- writes in lowercase (no message starts with a capital letter)\n")
                else
                    append("- writes in lowercase (").append(f.capStartPct)
                        .append("% of messages start with a capital letter)\n")
            f.capStartPct >= 85 ->
                append("- almost always capitalizes message starts (")
                    .append(f.capStartPct).append("%)\n")
            else ->
                append("- capitalizes message starts about ").append(f.capStartPct)
                    .append("% of the time\n")
        }
        when {
            f.periodRate < 0.15f -> append("- almost never ends messages with periods\n")
            f.periodRate < 0.6f -> append("- uses periods sometimes\n")
            else -> append("- ends most sentences with periods\n")
        }
        if (f.ellipsisRate >= 0.3f) append("- uses ellipses (...) often\n")
        else if (f.ellipsisRate >= 0.05f) append("- uses ellipses (...) sometimes\n")
        if (f.questionRate >= 0.3f) append("- asks questions often\n")
        else if (f.questionRate < 0.08f) append("- rarely asks questions in texts\n")
        if (f.exclaimRate >= 0.3f) append("- uses exclamation marks often\n")
        when {
            f.emojiPerMsg <= 0.01f -> append("- uses no emoji at all\n")
            f.emojiPerMsg < 0.25f -> append("- rarely uses emoji\n")
            else -> {
                append("- uses emoji often")
                if (f.topEmojis.isNotEmpty())
                    append(", favorites: ").append(f.topEmojis.joinToString(" "))
                append('\n')
            }
        }
        if (f.lowercaseI) append("- writes the pronoun \"i\" lowercase\n")
        if (f.greetingPct >= 20)
            append("- often opens messages with a casual greeting (hey/hi/yo — ")
                .append(f.greetingPct).append("% of messages)\n")
        if (f.slang.isNotEmpty())
            append("- signature words: ").append(f.slang.joinToString(", ")).append('\n')
        if (f.topics.isNotEmpty())
            append("- recurring topics: ").append(f.topics.joinToString(", ")).append('\n')
        append(if (f.polished) "- tone reads polished and deliberate"
               else "- tone reads casual and quick")
    }

    /** Guaranteed-true rendering of the facts — used whenever the model's
     *  rewrite is missing, blank, or contradicts a measurement. */
    fun fallbackProfile(f: Facts): String {
        val parts = ArrayList<String>()
        parts += if (f.polished)
            "Writes polished, deliberate messages — about ${f.avgWords} words each."
        else
            "Writes casual, quick texts — about ${f.avgWords} words each."
        val capsPunct = buildString {
            append(
                when {
                    f.capStartPct <= 15 -> "All lowercase"
                    f.capStartPct >= 85 -> "Standard capitalization"
                    else -> "Mixed capitalization"
                }
            )
            append(
                when {
                    f.periodRate < 0.15f -> ", minimal end punctuation"
                    f.periodRate < 0.6f -> ", occasional periods"
                    else -> ", standard punctuation"
                }
            )
            if (f.ellipsisRate >= 0.05f) append(", ellipses (...) show up")
            append('.')
        }
        parts += capsPunct
        parts += when {
            f.emojiPerMsg <= 0.01f -> "No emoji."
            f.emojiPerMsg < 0.25f -> "Emoji are rare."
            else -> "Emoji often" + if (f.topEmojis.isNotEmpty())
                " — favorites ${f.topEmojis.joinToString(" ")}." else "."
        }
        if (f.lowercaseI) parts += "Writes \"i\" lowercase."
        if (f.greetingPct >= 20) parts += "Often opens with \"hey\" or \"hi\"."
        if (f.slang.isNotEmpty())
            parts += "Signature words: ${f.slang.joinToString(", ")}."
        if (f.topics.isNotEmpty())
            parts += "Often texting about ${f.topics.joinToString(", ")}."
        return parts.joinToString(" ")
    }

    /** The anti-hallucination gate: a rewrite can only be saved if it does
     *  not contradict a hard measurement, and it stays anchored to the
     *  sample's own signature words or topics. */
    fun isConsistent(out: String, f: Facts): Boolean {
        val low = out.lowercase()
        if (f.emojiPerMsg <= 0.01f && Regex(
                "(frequent|often|loves?|heavy|lots of|regular)[^.\n]{0,25}emoji|" +
                    "emoji[^.\n]{0,25}(frequent|often|regular|in most|loves)"
            ).containsMatchIn(low)
        ) return false
        if (f.emojiPerMsg >= 0.5f && Regex(
                "no emoji|never uses? emoji|rarely[^.\n]{0,12}emoji|doesn.t use emoji|avoids? emoji"
            ).containsMatchIn(low)
        ) return false
        if (f.capStartPct <= 15 && Regex(
                "(always|usually|often|frequently|consistently|properly)[^.\n]{0,30}capital|" +
                    "capitalizes (every|each|her|his|their|sentences|words|messages)|" +
                    "standard capitalization|proper capitalization"
            ).containsMatchIn(low)
        ) return false
        if (f.capStartPct >= 85 && Regex(
                "writes in lowercase|all lowercase|never capitalizes|no capital letters"
            ).containsMatchIn(low)
        ) return false
        val anchors = (f.slang + f.topics).flatMap { it.split(' ') }.toSet()
        if (anchors.isNotEmpty() && anchors.none { it.isNotBlank() && low.contains(it) })
            return false
        return true
    }
}
