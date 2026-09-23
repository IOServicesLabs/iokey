package com.localboard.keyboard.llm

import com.localboard.keyboard.context.ContextEngine
import com.localboard.keyboard.util.Prefs

/**
 * A scripted back-and-forth used to baseline drafting quality: the model
 * plays the USER's side of a realistic multi-turn chat, turn after turn,
 * through the exact production generation path (same prompts, same
 * sampling, same sanitizers). The A/B pass runs the script twice — with
 * the auto-learned style profile and without — so its real effect on
 * drafts is visible instead of guessed.
 *
 * 1.6.1 redesign: the conversation's "You:" side is now SCRIPTED human
 * replies, not the model's own accepted drafts. The old auto-accept loop
 * made every model poison itself: one mediocre draft entered the
 * transcript and the model parroted it forever (a 0.6B can't recover —
 * verified offline across prompt structures and sampling recipes).
 * Production mostly sees HUMAN-typed history on screen, so the scripted
 * side measures what drafting actually faces. Each turn shows the AI's
 * draft next to the scripted human line for a vibe comparison.
 */
object AiTestLab {

    private val SCRIPT = listOf(
        "yo you alive? haven't heard from you all day",
        "we're doing dinner at that thai place at 7, you in?",
        "nice. can you grab drinks on the way? everyone's chipping in",
        "oh and sarah's bringing her new boyfriend lol",
        "what time can you realistically get there",
        "also did you ever finish that show we talked about?",
        "the ending was wild right??",
        "lol ok. how was your day though, you seemed stressed this morning",
        "fair. anyway — see you at 7, don't be late this time 😂",
        "one more thing, can you bring the charger i left at yours?"
    )

    /** The human the model is ghostwriting for. These keep the transcript
     *  realistic (what the screen would really show) and stop the model's
     *  own drafts from poisoning later turns. */
    private val SCRIPTED_REPLIES = listOf(
        "lol yeah sorry, was dead asleep. what's up",
        "ooh i'm in, been craving pad thai all week",
        "sure, i'll grab a case of that mango ipa, cool?",
        "oh boy lol. this should be interesting",
        "probably like 6:45, leaving work at 6",
        "dude yes, finished it at like 2am last night",
        "RIGHT?? still not over it tbh",
        "yeah morning was rough, meeting ran forever. better now though",
        "lol i'll show up early just to spite you",
        "oh yeah it's on my desk, i'll bring it"
    )

    data class TurnResult(
        val friend: String,
        val draft: String,
        val scripted: String,
        val stat: String
    )

    /** One pass over the script. [useProfile] toggles the auto-learned
     *  style layers exactly like the production switch does. [onTurn]
     *  fires after each turn for live rendering. Returns the transcript. */
    suspend fun runPass(
        provider: LlamaCppProvider,
        contextEngine: ContextEngine,
        prefs: Prefs,
        useProfile: Boolean,
        onTurn: suspend (TurnResult) -> Unit
    ): String {
        val transcript = StringBuilder()
        val rendered = StringBuilder()
        SCRIPT.forEachIndexed { i, friend ->
            // Mirrors what production actually sees from screen captures:
            // the other side tagged with a name, the user's side "You:".
            val context = "Chat on my screen:\n$transcript" + "Alex: $friend\n"
            val req = GenerateRequest(
                contextBefore = context,
                styleProfile = contextEngine.profile.takeIf { useProfile },
                maxWords = 36,
                temperature = 0.7f,
                appTone = "in a casual chat with a friend",
                styleInstructions = if (useProfile)
                    prefs.customStylePrompt.ifBlank { null } else null,
                personalityNotes = if (useProfile)
                    contextEngine.personalityNotes.ifBlank { null } else null
            )
            val draft = try {
                provider.generate(req) {}
            } catch (t: Throwable) {
                ""
            }
            val st = try { LlamaCppBridge.nativeLastStats() } catch (t: Throwable) { LongArray(0) }
            val stat = if (st.size >= 6) {
                val tokOut = st[3]
                val genMs = st[4]
                val tokPerSec = if (genMs > 0) "%.1f".format(tokOut * 1000f / genMs) else "—"
                val reused = if (st[1] > 0) ", ${st[1]} reused" else ""
                "$tokOut tok · ${"%.1f".format(genMs / 1000f)}s · $tokPerSec tok/s · " +
                    AiDiagLog.finishLabel(st[5].toInt()) + reused +
                    (if (draft.isBlank()) " — empty after checks" else "")
            } else "no stats"
            val shown = draft.ifBlank { "∅ (rejected — nothing committed)" }
            val scripted = SCRIPTED_REPLIES[i]
            // The HUMAN side goes into the running transcript — never the
            // model's draft. That is what production sees on screen, and it
            // keeps one bad draft from dragging every later turn down.
            transcript.append("Alex: ").append(friend).append('\n')
            transcript.append("You: ").append(scripted).append('\n')
            rendered.append("Alex: ").append(friend).append('\n')
            rendered.append("You (AI): ").append(shown).append('\n')
            rendered.append("You (script): ").append(scripted).append('\n')
            rendered.append("   · ").append(stat).append('\n')
            onTurn(TurnResult(friend, shown, scripted, stat))
        }
        return rendered.toString()
    }
}
