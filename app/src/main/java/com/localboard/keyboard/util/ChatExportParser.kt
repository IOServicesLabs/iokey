package com.localboard.keyboard.util

/**
 * Parser for messaging-app chat exports — WhatsApp's "Export chat" .txt is
 * the flagship case (WhatsApp's own message database is encrypted and
 * private to WhatsApp; the export file is the legitimate, user-initiated
 * way in). Also handles generic "Name: message" dumps.
 *
 * Supported line shapes (WhatsApp varies by platform and locale):
 *   [25/12/2024, 14:22:31] Jane Doe: hey are we still on
 *   25/12/2024, 14:22 - Jane Doe: hey are we still on
 *   12/25/2024, 2:22 PM - Jane Doe: hey
 *   [25.12.2024, 14:22:31] Jane Doe: hey
 * Lines without a timestamp prefix continue the previous message.
 * Timestamped lines without a "Sender:" part are system messages
 * (encryption notices, joins, deletions) and are dropped.
 *
 * Everything runs locally; the file text never leaves the device.
 */
object ChatExportParser {

    /** sender name -> that sender's messages, in file order */
    data class Parsed(
        val bySender: Map<String, List<String>>,
        val totalLines: Int
    )

    // Date: 1-4 digits, separator . / or -, 1-2 digits, separator, 1-4 digits.
    // Time: H:MM or HH:MM with optional :SS and optional AM/PM (also a.m.).
    private val PREFIX = Regex(
        "^\\[?\\d{1,4}[./\\-]\\d{1,2}[./\\-]\\d{1,4},?\\s+" +
            "\\d{1,2}[:.]\\d{2}(?:[:.]\\d{2})?(?:\\s*(?:[AaPp][.?\\s]?[Mm][.?]?))?\\s*\\]?\\s*[-–—]?\\s+"
    )

    /** Placeholders WhatsApp writes instead of content. */
    private val JUNK_LINES = setOf(
        "<media omitted>", "media omitted", "this message was deleted",
        "you deleted this message", "missed voice call", "missed video call",
        "<this message was edited>", "message deleted", "null"
    )

    private val URL_RE = Regex(
        "(https?://|www\\.)\\S+|\\b\\S+?\\.(com|net|org|io|co|app|dev|me|gg|tv|info|biz|ly|link|site)(/\\S*)?\\b",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String, maxMessages: Int = 6000): Parsed {
        val bySender = LinkedHashMap<String, MutableList<String>>()
        var total = 0
        var lastSender: String? = null
        var kept = 0
        for (rawLine in text.lineSequence()) {
            if (kept >= maxMessages) break
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            total++
            val m = PREFIX.find(line)
            if (m != null) {
                val rest = line.substring(m.range.last + 1)
                // Sender is the text before the FIRST ": " — capped: a colon
                // deep into the line means this is a system message whose
                // body merely contains one.
                val colon = rest.indexOf(": ").takeIf { it >= 0 }
                    ?: rest.indexOf(':').takeIf { it in 1..48 } ?: -1
                if (colon in 1..48) {
                    val sender = rest.substring(0, colon).trim()
                    val msg = cleanMessage(rest.substring(colon + 1))
                    if (sender.isNotEmpty() && msg != null) {
                        bySender.getOrPut(sender) { ArrayList() }.add(msg)
                        kept++
                        lastSender = sender
                        continue
                    }
                    // Recognized sender shape but empty/junk content: keep the
                    // sender context so a following continuation line still
                    // attaches correctly, but store nothing.
                    lastSender = if (sender.isNotEmpty()) sender else null
                } else {
                    // Timestamped system line — breaks any continuation.
                    lastSender = null
                }
            } else {
                // No timestamp: continuation of a multi-line message.
                val s = lastSender ?: continue
                val list = bySender[s] ?: continue
                if (list.isNotEmpty()) {
                    val extra = cleanMessage(line) ?: continue
                    list[list.size - 1] = (list.last() + " " + extra).take(240)
                }
            }
        }
        return Parsed(bySender, total)
    }

    /** Clean one message for learning: drop placeholders, strip URLs.
     *  Returns null when nothing worth learning remains. */
    private fun cleanMessage(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        val low = s.lowercase()
        if (low in JUNK_LINES) return null
        if (low.contains("<media omitted>")) s = s.replace("<Media omitted>", " ")
        s = URL_RE.replace(s, " ").replace('\n', ' ')
            .replace(Regex("\\s+"), " ").trim()
        if (s.length < 2) return null
        return s.take(200)
    }

    /** Senders sorted by message count, most talkative first — the user's own
     *  name is almost always near the top in a 1:1 chat export. */
    fun sendersByActivity(parsed: Parsed): List<Pair<String, Int>> =
        parsed.bySender.entries
            .map { it.key to it.value.size }
            .sortedByDescending { it.second }
}
