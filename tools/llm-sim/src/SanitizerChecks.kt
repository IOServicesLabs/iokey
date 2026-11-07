import com.localboard.keyboard.llm.OutputSanitizer
import com.localboard.keyboard.llm.OutputSanitizer.Accepted
import com.localboard.keyboard.llm.OutputSanitizer.Reason
import com.localboard.keyboard.llm.OutputSanitizer.Rejected

/** Real short replies must come through; repeats of the chat and prompt scaffolding must not. */
internal fun sanitizerChecks() {
    val chat = listOf("are you coming tonight", "yeah what time", "7 at marios")
    fun review(raw: String, turns: List<String> = chat, voice: List<String>? = null) =
        OutputSanitizer.review(raw, 36, turns, voice)
    fun accepted(raw: String, turns: List<String> = chat, voice: List<String>? = null): String? =
        (review(raw, turns, voice) as? Accepted)?.text
    fun reason(raw: String, turns: List<String> = chat, voice: List<String>? = null): Reason? =
        (review(raw, turns, voice) as? Rejected)?.reason

    // ---- short replies are replies
    for (short in listOf("sounds good", "ok", "k", "yep", "lol ok", "see you there")) {
        check("short reply passes: $short", accepted(short) == short, review(short))
    }
    check("sounds good passes when the chat just said it",
        accepted("sounds good", chat + "sounds good") == "sounds good", review("sounds good", chat + "sounds good"))
    check("ok passes when the chat just said it", accepted("ok", chat + "ok") == "ok")
    check("short repeat of an older turn passes", accepted("yeah what time") == "yeah what time")

    // ---- echoes are not
    check("whole message echo fails", reason("are you coming tonight") == Reason.ECHO, review("are you coming tonight"))
    check("echo ignores case and spacing", reason("Are you  coming TONIGHT") == Reason.ECHO)
    val last = chat + "did you book the flight yet"
    check("echo of the very last message fails", reason("did you book the flight yet", last) == Reason.ECHO)
    check("echo running two turns together fails",
        reason("are you coming tonight yeah what time") == Reason.ECHO, review("are you coming tonight yeah what time"))
    check("reply that is mostly a slice fails", reason("so did you book the flight yet or what", last) == Reason.ECHO)
    check("real reply sharing a few words passes",
        accepted("yeah i can be at marios by 7 tonight") == "yeah i can be at marios by 7 tonight")
    val old = listOf("did you book the flight yet") + (1..6).map { "filler message number $it" }
    check("only the last six turns count", accepted("did you book the flight yet", old) == "did you book the flight yet")
    check("same turn inside the six fails", reason("did you book the flight yet", old.drop(0).takeLast(5) + "did you book the flight yet") == Reason.ECHO)

    // ---- which lines count as turns
    val block = "Chat on my screen:\nThem: are you coming tonight\nYou: yeah what time\nThem: 7 at marios\n" +
        "Messages I sent recently:\nyeah what time sounds good lol ok omw see you there at the usual place"
    check("run-on is not a turn", OutputSanitizer.recentTurnsOf(block) == chat, OutputSanitizer.recentTurnsOf(block))
    val nearRunOn = "lol ok omw see you there at the usual place"
    check("saying what you said before is not an echo",
        OutputSanitizer.review(nearRunOn, 36, OutputSanitizer.recentTurnsOf(block), null, "") == Accepted(nearRunOn))
    val blind = "My recent messages in this chat (only my side is visible):\nyeah what time sounds good"
    check("only my side visible means no turns", OutputSanitizer.recentTurnsOf(blind).isEmpty())
    val named = "Chat on my screen:\nAlex: yo you alive?\nYou: lol yeah sorry\nAlex: we're doing dinner at 7, you in?\n"
    check("sender names are labels too",
        OutputSanitizer.recentTurnsOf(named) == listOf("yo you alive?", "lol yeah sorry", "we're doing dinner at 7, you in?"),
        OutputSanitizer.recentTurnsOf(named))
    val many = (1..9).joinToString("\n") { "Them: line $it" }
    check("newest six kept", OutputSanitizer.recentTurnsOf(many) == (4..9).map { "line $it" })
    check("a bare line is not mistaken for labelled",
        OutputSanitizer.recentTurnsOf("see you at 7") == listOf("see you at 7"))

    // ---- nothing, or nothing but scaffolding
    check("empty output", reason("") == Reason.EMPTY)
    check("blank output", reason("  \n ") == Reason.EMPTY)
    check("raw kept for blank", (review(" \n") as Rejected).raw == " \n")
    check("all scaffolding", reason("<|im_start|>assistant") == Reason.SCAFFOLDING)
    check("role line first", reason("assistant\nhello there") == Reason.SCAFFOLDING)
    check("fake turn first", reason("Them: hello there") == Reason.SCAFFOLDING)
    check("new prompt placeholder", reason("(no reply yet)") == Reason.SCAFFOLDING)
    check("new prompt system text", reason("How you text: short and lowercase") == Reason.SCAFFOLDING)
    check("old prompt label", reason("Write the owner's next reply, one short casual text.") == Reason.SCAFFOLDING)
    check("raw kept for show what it wrote",
        (review("<|im_start|>assistant") as Rejected).raw == "<|im_start|>assistant")

    // ---- trimming what follows a real reply
    check("cut at a template token", accepted("sure thing<|im_end|>\n<|im_start|>user") == "sure thing")
    check("cut at a Phi-3 end token", accepted("sure thing<|end|>") == "sure thing")
    check("cut at a fake turn", accepted("on my way\nThem: ok cool\nYou: see you") == "on my way")
    check("owner label stripped", accepted("you: yeah i'm in") == "yeah i'm in")
    check("carriage returns dropped", accepted("sure\r\nthing") == "sure\nthing")
    check("echoed prefix cut", accepted("7 at marios works for me") == "works for me", review("7 at marios works for me"))
    val ramble = "Sounds good to me. " + (1..80).joinToString(" ") { "word$it" }
    val trimmed = OutputSanitizer.sanitize(ramble, 10)
    check("ramble capped to the budget", trimmed.split(" ").size == 16, trimmed)
    val rambleSentences = (1..20).joinToString(" ") { "this is sentence number $it." }
    val cutAtEnd = OutputSanitizer.sanitize(rambleSentences, 10)
    check("ramble cut at a sentence end", cutAtEnd.endsWith(".") && cutAtEnd.split(" ").size <= 16, cutAtEnd)

    // ---- a cut that leaves a fragment leaves no reply
    val agreed = chat + "sounds good to me"
    check("fragment after a long echo is refused", reason("sounds good to me too", agreed) == Reason.ECHO, review("sounds good to me too", agreed))
    check("fragment refused keeps the raw", (review("sounds good to me too", agreed) as? Rejected)?.raw == "sounds good to me too")
    val time = chat + "see you at 7"
    check("punctuated fragment is refused too", reason("see you at 7 then!", time) == Reason.ECHO, review("see you at 7 then!", time))
    check("short reply opening on the last turn stays whole", accepted("7 at marios ok") == "7 at marios ok", review("7 at marios ok"))
    check("two words left are a fragment", accepted("7 at marios i guess") == null, review("7 at marios i guess"))
    check("three words left are a reply", accepted("7 at marios i guess so") == "i guess so", review("7 at marios i guess so"))
    check("a long parrot with a word on the end fails", reason("are you coming tonight\nyeah what time\n7 at marios lol") == Reason.ECHO)
    check("nothing cut, nothing counted", accepted("ok") == "ok" && accepted("on it") == "on it")

    // ---- words a person might also write only count at the start of a line
    for (natural in listOf(
        "can you do this: call me when you land",
        "sounds like my next message should be to him",
        "great conversation: thanks for today",
        "i could ghostwrite it for you",
        "i found it and the phone's owner called me",
        "can you write your next message in caps lol",
        "so now the real one is at 9",
        "wait how you text: with one thumb?"
    )) {
        check("said in passing: $natural", accepted(natural) == natural, review(natural))
    }
    check("said in passing on a later line", accepted("sure\nbut can you do this: call me") == "sure\nbut can you do this: call me")
    check("said in passing, then parroted", accepted("can you do this: call me\nDo this: reply") == "can you do this: call me", review("can you do this: call me\nDo this: reply"))
    check("prose marker opening the text", reason("Do this: reply to the chat") == Reason.SCAFFOLDING)
    check("few-shot label opening the text", reason("Conversation: \"you still coming over tonight?\"") == Reason.SCAFFOLDING)
    check("old prompt's last line", reason("My next message: hey") == Reason.SCAFFOLDING)
    check("prose marker opening a later line", accepted("on my way\nDo this: reply to the chat") == "on my way")
    check("prose marker opening an indented line", accepted("on my way\n  conversation: hey") == "on my way")
    check("a short answer before a parroted line stands", accepted("sure\nDo this: reply to the chat") == "sure")

    // ---- what no person writes still goes, wherever it is
    check("our system text, whole", reason(com.localboard.keyboard.llm.PromptAssembler.BASE) == Reason.SCAFFOLDING)
    check("old prompt's opening, whole", reason("You ghostwrite the phone owner's text messages.") == Reason.SCAFFOLDING)
    check("our system text after a reply", accepted("yeah i can be there. You are the phone's owner, texting") == "yeah i can be there.")
    check("cut inside a line leaving a sentence", accepted("on my way (no reply yet)") == "on my way")
    check("cut inside a line leaving a fragment", reason("sure thing (no reply yet)") == Reason.SCAFFOLDING, review("sure thing (no reply yet)"))
    check("markup leaves a short answer alone, inside a line", accepted("sure<|im_end|>") == "sure")

    // ---- what the prompt ended on, when that is not the turns
    val tail = "My recent messages in this chat (only my side is visible):\nlol ok omw see you there at the usual place"
    check("whole draft repeating a long prompt tail fails",
        (OutputSanitizer.review("omw see you there at the usual place", 36, emptyList(), null, tail) as? Rejected)?.reason == Reason.ECHO)
    check("whole draft repeating a short prompt tail passes",
        OutputSanitizer.review("the usual place", 36, emptyList(), null, tail) == Accepted("the usual place"))
    check("rewrite with no turns may match its source",
        OutputSanitizer.review("are you coming tonight", 36) == Accepted("are you coming tonight"))

    // ---- continuing a draft, where what follows the repeat is what gets added
    check("repeat of the draft with enough after it is cut",
        OutputSanitizer.review("so about tonight i think we should", 36, emptyList(), null, "so about tonight") == Accepted("i think we should"))
    check("repeat of the draft with a fragment after it is refused",
        (OutputSanitizer.review("so about tonight i think", 36, emptyList(), null, "so about tonight") as? Rejected)?.reason == Reason.ECHO)

    // ---- another chat's words
    val voice = listOf("my sister is flying in on the ninth", "lol yeah omw")
    check("voice leak refused", reason("hey my sister is flying in on the ninth btw", chat, voice) == Reason.VOICE_LEAK)
    check("voice leak ignores punctuation", reason("My sister, is flying in: on the ninth!", chat, voice) == Reason.VOICE_LEAK)
    check("voice raw kept",
        (review("hey my sister is flying in on the ninth btw", chat, voice) as Rejected).raw == "hey my sister is flying in on the ninth btw")
    check("few words in common is fine", accepted("my sister says hi", chat, voice) == "my sister says hi")
    check("short voice line never matches", accepted("lol yeah omw", chat, voice) == "lol yeah omw")
    check("no examples no leak", !OutputSanitizer.leaksVoiceExample("anything at all goes here today", null))

    // ---- the profile path still uses the plain context form
    check("isEchoOf on a plain context", OutputSanitizer.isEchoOf("these notes describe the writer well", "x these notes describe the writer well y"))
    check("isEchoOf below the floor", !OutputSanitizer.isEchoOf("short one", "short one"))
}
