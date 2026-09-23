package com.localboard.keyboard.util

import android.content.Context
import android.provider.ContactsContract

/**
 * Reads contact names into the personal dictionary.
 *
 * Proper nouns are the single worst autocorrect failure: a name is, by
 * definition, absent from a general word list, so every correction pass sees
 * it as a misspelling of something else and "helpfully" destroys it. Gboard
 * solves this by knowing the user's contacts. So does this — locally.
 *
 * PRIVACY: names are read on demand, tokenised, and handed to the encrypted
 * dictionary. Nothing is uploaded, no other contact field is touched (no
 * numbers, no emails, no photos), and the read only happens when the user
 * asks for it. The permission is optional and the feature is off by default.
 */
object ContactNames {

    /** Names longer than this are almost certainly a company or a note. */
    private const val MAX_TOKEN = 24

    /**
     * Distinct name tokens from the contacts list. "Mary-Jane O'Connell"
     * yields Mary, Jane, O'Connell — each is separately typeable, and the
     * hyphen/apostrophe forms are what actually get typed.
     */
    fun read(context: Context, max: Int = 4000): List<String> {
        val out = LinkedHashSet<String>()
        val projection = arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, projection, null, null, null
            )?.use { c ->
                val idx = c.getColumnIndex(projection[0])
                if (idx < 0) return emptyList()
                while (c.moveToNext() && out.size < max) {
                    val display = c.getString(idx) ?: continue
                    for (raw in display.split(' ', '-', ' ', ',')) {
                        // Normalize the curly apostrophe (U+2019) to the typed
                        // form (U+0027): "O’Connell" in contacts must match the
                        // "O'Connell" the keyboard actually produces.
                        val tok = raw.trim().trim('.', '(', ')', '"').replace('’', '\'')
                        if (tok.length !in 2..MAX_TOKEN) continue
                        // Letters and apostrophes only: filters out phone
                        // numbers saved as names, emoji, and "Work (2)".
                        if (!tok.all { it.isLetter() || it == '\'' || it == '’' }) continue
                        if (tok.none { it.isLetter() }) continue
                        out.add(tok)
                    }
                }
            }
        } catch (_: Throwable) {
            // Revoked permission mid-read, or an OEM provider that refuses the
            // query: an empty list is the correct answer, not a crash.
            return emptyList()
        }
        return out.toList()
    }
}
