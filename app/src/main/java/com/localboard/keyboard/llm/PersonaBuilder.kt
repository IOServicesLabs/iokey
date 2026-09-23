package com.localboard.keyboard.llm

import com.localboard.keyboard.data.db.TypingLogEntity

/**
 * The other half of "who is this person".
 *
 * [StyleProfiler] measures HOW someone writes — sentence length, emoji,
 * formality. It says nothing about WHAT they write about or WHO they write
 * to, which is most of what makes a reply sound informed rather than generic.
 * A model told "casual, short sentences" still has no idea that the thread it
 * is drafting into is the five-a-side group, or that this person's week is
 * mostly deploys and physio appointments.
 *
 * This builds that second half from the encrypted typing log: the recurring
 * subjects, and the conversations they recur in. It is deliberately
 * DETERMINISTIC — counted, not summarised. The same reasoning as the nightly
 * style distillation: a small local model cannot be trusted to analyse raw
 * text without inventing, and a persona blob that quietly invents facts about
 * the user would poison every draft that follows. Counting cannot hallucinate.
 *
 * Everything here is derived from data already on the device and stays on it.
 */
object PersonaBuilder {

    /** Topics need to be recurring, not one-offs. */
    private const val MIN_TOPIC_COUNT = 3
    private const val MAX_TOPICS = 14
    private const val MAX_THREADS = 5

    /**
     * Words that are common enough to say nothing about a person.
     *
     * Deliberately blunt: this is a stop list, not a language model. A word
     * that slips through and is genuinely common shows up as a boring topic;
     * a real topic wrongly filtered is invisible. The list therefore covers
     * function words and chat filler heavily and content words not at all.
     */
    private val STOP = hashSetOf(
        "the", "and", "you", "that", "this", "have", "for", "not", "with", "but",
        "was", "are", "they", "what", "when", "your", "will", "there", "their",
        "would", "could", "should", "been", "just", "like", "know", "get", "got",
        "make", "made", "want", "need", "think", "thought", "going", "gonna",
        "yeah", "yea", "yes", "okay", "ok", "lol", "haha", "hey", "hi", "hello",
        "thanks", "thank", "please", "sorry", "sure", "good", "great", "nice",
        "well", "really", "very", "much", "many", "some", "any", "all", "one",
        "two", "now", "then", "today", "tomorrow", "yesterday", "time", "day",
        "week", "back", "come", "coming", "here", "out", "about", "from", "can",
        "cant", "dont", "didnt", "doesnt", "wont", "its", "im", "ive", "ill",
        "you're", "youre", "were", "isnt", "wasnt", "how", "why", "who", "which",
        "than", "them", "then", "these", "those", "into", "over", "also", "still",
        "even", "does", "did", "has", "had", "her", "his", "him", "she", "our",
        "let", "lets", "see", "look", "take", "give", "tell", "said", "say",
        "way", "thing", "things", "stuff", "bit", "lot", "little", "big"
    )

    /**
     * A ≤[maxChars] note describing what this person talks about and with whom.
     * Empty when there is not enough history to say anything honest.
     *
     * [appLabel] resolves a package name to something a human recognises; it
     * is a parameter rather than a PackageManager call because this runs on a
     * worker thread and the caller already caches those lookups.
     */
    fun build(
        fragments: List<TypingLogEntity>,
        appLabel: (String) -> String,
        maxChars: Int = 1200
    ): String {
        // AI-originated lines are excluded everywhere else for the same reason
        // they are excluded here: summarising the model's own output would let
        // the persona drift toward whatever the model already likes to say.
        val mine = fragments.filter { it.origin == 0 }
        if (mine.size < 15) return ""

        val topics = topTopics(mine)
        val threads = topThreads(mine, appLabel)
        if (topics.isEmpty() && threads.isEmpty()) return ""

        val sb = StringBuilder(maxChars)
        if (topics.isNotEmpty()) {
            sb.append("Recurring subjects: ").append(topics.joinToString(", ")).append(".")
        }
        if (threads.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("Usually writing to: ").append(threads.joinToString("; ")).append(".")
        }
        return sb.toString().take(maxChars)
    }

    /**
     * Recurring content words, most frequent first.
     *
     * Scored by raw count with a length preference rather than anything
     * cleverer: with a stop list doing the heavy lifting, what survives at 3+
     * occurrences over a fortnight is already the person's actual vocabulary.
     */
    private fun topTopics(fragments: List<TypingLogEntity>): List<String> {
        val counts = HashMap<String, Int>(256)
        for (f in fragments) {
            // Seen-in-this-message set: repeating a word ten times in one rant
            // should not outrank a subject that comes up on ten separate days.
            val seen = HashSet<String>(16)
            for (raw in f.text.split(' ', '\n', '\t', ',', '.', '!', '?', ';', ':', '"', '(', ')')) {
                val w = raw.trim().lowercase().trim('\'', '’', '-', '_')
                if (w.length < 4 || w.length > 20) continue
                if (!w.all { it.isLetter() || it == '\'' }) continue
                if (w in STOP) continue
                if (seen.add(w)) counts[w] = (counts[w] ?: 0) + 1
            }
        }
        return counts.entries
            .filter { it.value >= MIN_TOPIC_COUNT }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { it.key.length })
            .take(MAX_TOPICS)
            .map { it.key }
    }

    /**
     * The conversations this person actually writes in, busiest first.
     *
     * A thread with no screen key is a field the capture never got a title
     * for; naming the app alone is still useful ("mostly writes in Gmail"),
     * but an unnamed thread is not, so those collapse into the app entry.
     */
    private fun topThreads(
        fragments: List<TypingLogEntity>,
        appLabel: (String) -> String
    ): List<String> {
        val counts = HashMap<String, Int>(32)
        for (f in fragments) {
            val thread = f.screenKey.trim()
            // Tab separator: chat titles routinely contain spaces ("Five a
            // side") while package names contain neither, so splitting on a
            // space would truncate every multi-word thread to its first word.
            val key = if (thread.isEmpty()) f.appPackage else "${f.appPackage}\t$thread"
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(MAX_THREADS)
            .mapNotNull { (key, n) ->
                if (n < 3) return@mapNotNull null
                val parts = key.split('\t', limit = 2)
                val app = appLabel(parts[0]).ifBlank { return@mapNotNull null }
                val thread = parts.getOrNull(1)?.take(40)
                if (thread.isNullOrBlank()) app else "$thread ($app)"
            }
    }
}
