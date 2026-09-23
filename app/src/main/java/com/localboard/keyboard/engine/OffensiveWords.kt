package com.localboard.keyboard.engine

/**
 * Words the keyboard never VOLUNTEERS: they are not offered as glide
 * decodes, next-word predictions, completions or corrections unless the
 * user has already typed them deliberately (see [allowedFor]). Typing them
 * letter by letter is always possible — this is a "don't put words in my
 * mouth" list, not a censor.
 *
 * The base dictionary is a raw web frequency list, so racial slurs sit in
 * it a few thousand counts above ordinary words, and a sloppy glide for
 * "bigger" or "never" could land on one. Gboard applies the same rule.
 */
object OffensiveWords {
    private val words: HashSet<String> = hashSetOf(
        "nigger", "niggers", "nigga", "niggas", "nigger's",
        "faggot", "faggots", "fag", "fags",
        "retard", "retards", "retarded",
        "cunt", "cunts",
        "kike", "kikes", "spic", "spics", "chink", "chinks", "gook", "gooks",
        "wetback", "wetbacks", "tranny", "trannies", "dyke", "dykes",
        "raghead", "towelhead", "beaner", "beaners", "coon", "coons",
        "fuck", "fucks", "fucked", "fucker", "fuckers", "fucking", "motherfucker",
        "motherfuckers", "motherfucking",
        "shit", "shits", "shitty", "bullshit",
        "cocksucker", "pussy", "pussies",
        "whore", "whores", "slut", "sluts"
    )

    fun contains(lower: String): Boolean = lower in words

    /**
     * True when [lower] may be offered given how often this user has typed
     * it themselves. Two deliberate uses is the bar: one could be a slip,
     * and a user who swears in their own messages should get their own
     * vocabulary predicted like anyone else.
     */
    fun allowedFor(lower: String, userCount: Int): Boolean =
        userCount >= 2 || lower !in words
}
