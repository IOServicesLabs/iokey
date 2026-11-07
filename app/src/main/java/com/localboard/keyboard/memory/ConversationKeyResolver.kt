package com.localboard.keyboard.memory

import java.text.Normalizer

enum class KeyConfidence { HIGH, MEDIUM, LOW }

/** Everything that might say which chat is on screen. All of it is optional, and any of
 *  it can be missing or wrong depending on the app. */
data class ConversationSignals(
    val pkg: String,
    val appLabel: String? = null,
    /** Text of the toolbar title node in the capture (view id or top band). */
    val toolbarTitle: String? = null,
    /** TYPE_WINDOW_STATE_CHANGED text for the current window, cleared on each change. */
    val windowTitle: String? = null,
    /** Address of the matched SMS thread; only used when nothing names the chat. */
    val smsAddress: String? = null,
    /** MessagingStyle conversation title recently seen for this package and shown on screen. */
    val notificationTitle: String? = null,
    /** EditorInfo.hintText */
    val hint: String? = null
)

data class ConversationKey(
    val pkg: String,
    val threadId: String,
    val title: String,
    val confidence: KeyConfidence
) {
    val storageKey: String get() = "$pkg|$threadId"
}

/**
 * Which chat is this, and how sure are we.
 *
 * A wrong answer is far worse than no answer. LOW costs some memory for this one session,
 * but a key that is really the app's own wording puts every thread of that app in one
 * bucket and feeds one person's messages into a draft for someone else. So anything that
 * looks like chrome is refused, and the caller decides what LOW may do.
 */
object ConversationKeyResolver {
    /** Hints and screen titles that name a feature rather than a person. Compared with
     *  trailing dots removed, because apps also write these as "Message…". */
    private val GENERIC = setOf(
        "message", "messages", "enter message", "type a message", "type message",
        "text message", "rcs message", "sms message", "mms message", "chat message",
        "send a message", "write a message", "start a message", "compose message",
        "add a message", "chat", "aa", "reply", "type here", "say something", "here",
        "new message", "new conversation", "new chat", "conversations", "chats", "inbox",
        "search", "settings"
    )

    /** What can follow a hint's prefix, capital and all, and still name nobody: a crowd, a
     *  role or a part of the screen. It would be the same "name" in every thread of that app. */
    private val HINT_NOBODY = setOf(
        "everyone", "everybody", "group", "group chat", "groups", "thread", "threads", "channel",
        "channels", "room", "team", "members", "friends", "contact", "contacts", "participants",
        "people", "recipient", "recipients", "request", "requests", "message request", "sender",
        "author", "owner", "seller", "buyer", "host", "driver", "support", "admin", "us", "them",
        "body", "box", "field", "input", "area", "content", "subject", "preview", "editor",
        "composer", "comment", "comments", "post", "story", "status", "tweet", "review",
        "question", "answer", "topic", "discussion", "conversation", "email", "mail", "text", "sms",
        "mms", "rcs", "someone", "somebody", "anyone", "anybody", "me", "you", "now", "later"
    )

    /** A first word that makes what follows a description: "This Thread", "All Members". */
    private val HINT_NOBODY_OPENERS = setOf(
        "a", "an", "the", "this", "that", "these", "those", "your", "my", "our", "all", "every",
        "each", "any", "some", "new", "everyone", "everybody"
    )

    /** Lines that sit in the title's band without being a title: presence, and the date
     *  chips and clock times that scroll through it. */
    private val STATUS_RE = Regex(
        "(online|offline|(.* is )?typing(…|\\.\\.\\.)?|last seen.*|active now|" +
            "active (today|yesterday|\\d+.*)|away|busy|today|yesterday|" +
            "(monday|tuesday|wednesday|thursday|friday|saturday|sunday)(,.*)?|" +
            "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? \\d{1,2}(,? \\d{4})?|" +
            "\\d{1,2}:\\d{2}( ?[ap]\\.?m\\.?)?)",
        RegexOption.IGNORE_CASE
    )
    private val COUNTER_RE = Regex("\\s*\\(\\d+\\)\\s*$")
    private val HINT_PREFIX_RE =
        Regex("^(message to|send message to|reply to|message|text)\\s+", RegexOption.IGNORE_CASE)

    /** Marks stay because many scripts write their vowels with them: without them Devanagari
     *  मीना and मोना are one name. Emoji stay because two groups can differ by nothing else,
     *  and the caller still stores what MEDIUM resolves, so only a different key keeps them apart. */
    private val NOT_NAME_RE = Regex("[^\\p{L}\\p{M}\\p{N}\\p{So}\\p{Sk} .'+\\-]")
    private val EMOJI_RE = Regex("[\\p{So}\\p{Sk}]")

    /** Only pick how a character is drawn, and apps differ on writing them. */
    private val VARIATION_RE = Regex("[\\uFE00-\\uFE0F]")

    fun threadIdOf(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return TextNorm.cmp(NOT_NAME_RE.replace(VARIATION_RE.replace(COUNTER_RE.replace(nfkc, ""), ""), " "))
    }

    /** What has to stand as a name on its own: "Message 💬" is still the app's wording, and an
     *  emoji alone could be any button on the screen. */
    private fun withoutEmoji(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return EMOJI_RE.replace(VARIATION_RE.replace(nfkc, ""), " ").trim()
    }

    private fun usable(raw: String?, appLabel: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.length !in 2..60) return null
        val bare = withoutEmoji(t)
        if (STATUS_RE.matches(bare)) return null
        val id = threadIdOf(bare)
        if (id.length < 2 || id.trimEnd('.', ' ') in GENERIC) return null
        if (appLabel != null && id == threadIdOf(withoutEmoji(appLabel))) return null
        return COUNTER_RE.replace(t, "").trim()
    }

    /** A name keeps the capital its owner gave it, or an @ or a #, while a lowercase word after
     *  the prefix is more of the app's own sentence ("Reply to all", "Message group"). A script
     *  with no capitals has nothing to check. A capital alone proves little in a hint written
     *  in title case, hence the lists. */
    private fun namesSomeone(named: String): Boolean {
        val bare = withoutEmoji(named)
        if (bare.isEmpty()) return false
        val first = bare.codePointAt(0)
        val opensLikeName = first == '@'.code || first == '#'.code ||
            (Character.isLetter(first) && !Character.isLowerCase(first))
        if (!opensLikeName) return false
        val id = threadIdOf(bare).trimEnd('.', ' ')
        return id !in HINT_NOBODY && id.substringBefore(' ') !in HINT_NOBODY_OPENERS
    }

    fun resolve(s: ConversationSignals): ConversationKey {
        fun key(title: String, c: KeyConfidence) =
            ConversationKey(s.pkg, threadIdOf(title), title, c)
        usable(s.toolbarTitle, s.appLabel)?.let { return key(it, KeyConfidence.HIGH) }
        usable(s.windowTitle, s.appLabel)?.let { return key(it, KeyConfidence.HIGH) }
        s.smsAddress?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 3 }?.let {
            return ConversationKey(s.pkg, "sms:$it", it, KeyConfidence.HIGH)
        }
        usable(s.notificationTitle, s.appLabel)?.let { return key(it, KeyConfidence.MEDIUM) }
        // A hint only names someone through a prefix like "Message to John". A hint with
        // no prefix is the app's own wording, in whatever language the phone uses, and
        // would be the same "name" for every thread in that app.
        val hint = s.hint?.trim().orEmpty()
        val named = HINT_PREFIX_RE.replace(hint, "").trim()
        if (named != hint && namesSomeone(named)) {
            usable(named, s.appLabel)?.let { return key(it, KeyConfidence.MEDIUM) }
        }
        return ConversationKey(s.pkg, "", "", KeyConfidence.LOW)
    }
}
