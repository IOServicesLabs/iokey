package com.localboard.keyboard.memory

import java.text.Normalizer

enum class Speaker { ME, THEM, UNKNOWN }

/** One message in a conversation. Lists of these always run oldest to newest. */
data class Turn(
    val speaker: Speaker,
    val text: String,
    /** The sender's display name in a group chat; empty in a one-to-one chat. */
    val sender: String = "",
    val ts: Long = 0L
)

/** The one definition of "the same text", so a capture that differs only in case or
 *  spacing lines up with what is stored. For comparing only, never for showing. */
internal object TextNorm {
    private val WS = Regex("\\s+")
    fun cmp(s: String): String =
        WS.replace(Normalizer.normalize(s, Normalizer.Form.NFKC).trim().lowercase(), " ")
}
