package com.localboard.keyboard.engine.correction

/**
 * The behaviour of the correction logic as a table of named checks, run on [FakeIme]. Each name
 * says which scenario failed. Plain Kotlin, so tools/correction-sim can run it without JUnit too.
 */
class Scenarios {
    class Check(val name: String, val ok: Boolean, val detail: String) {
        // A few names hold the separator under test, and a line break would split the test report.
        override fun toString() = name.replace("\n", "\\n")
    }

    val checks = ArrayList<Check>()
    var nowMs = 1_000_000_000L
    val clock = { nowMs }

    fun check(name: String, ok: Boolean, detail: String = "") {
        checks += Check(name, ok, if (ok) "" else detail)
    }
    fun eq(name: String, got: Any?, want: Any?) = check(name, got == want, "got=[$got] want=[$want]")

    fun cand(w: String, d: Double, s: Double, f: Long = 1_000_000) = Candidate(w, d, s, f)

    val EV = mapOf(
        "aight" to Ev(listOf(cand("right", 1.0, 3.0), cand("light", 1.0, 1.0), cand("eight", 1.0, 0.5))),
        "aighz" to Ev(listOf(cand("right", 1.5, 2.0), cand("light", 1.5, 0.5))),
        "teh" to Ev(listOf(cand("the", 0.5, 3.7), cand("ten", 1.0, 2.2))),
        "jhon" to Ev(listOf(cand("john", 0.5, 3.0), cand("jon", 1.0, 2.0))),
        "dont" to Ev(listOf(cand("don't", 0.3, 3.3)), closed = "don't"),
        "thisis" to Ev(emptyList(), split = "this is"),
        "cashapp" to Ev(emptyList(), split = null),
        "tge" to Ev(listOf(cand("the", 0.5, 3.7), cand("age", 1.0, 2.0))),
    )
    val KNOWN = setOf("the", "john", "right", "next", "hello", "world", "light", "i", "ok", "don't", "well")

    fun ime(incognito: Boolean = false, level: Int = 2): FakeIme =
        FakeIme(clock, incognito).also { it.evidence = EV; it.known = KNOWN; it.level = level }

    fun table() {
        // 1 Immediate backspace after a space-fired fix
        run {
            val k = ime(); k.type("aight"); k.separator(" ")
            eq("1 corrected", k.ed.toString(), "right ")
            eq("1 not learned yet", k.log.filter { it.startsWith("learn") || it.startsWith("noteTypo") }, emptyList<String>())
            eq("1 context pushed", k.committedWords.toList(), listOf("right"))
            k.backspace()
            eq("1 raw composing", k.ed.toString(), "aight")
            eq("1 composing buffer", k.composing.toString(), "aight")
            eq("1 provenance", k.session.provenance, Provenance.REVERTED)
            eq("1 context popped", k.committedWords.toList(), emptyList<String>())
            eq("1 message popped", k.messageWords.toList(), emptyList<String>())
            eq("1 nothing learned or typo'd", k.log, emptyList<String>())
        }
        // 2 Then space, keyboard hide, type again, restart, type again
        run {
            var k = ime(); k.type("hey"); k.separator(" ")
            k.log.clear(); k.type("aight"); k.separator(" "); k.backspace(); k.separator(" ")
            eq("2 kept", k.ed.toString(), "hey aight ")
            eq("2 learned with real prev", k.log.first { it.startsWith("learn") }, "learn(aight,prev=hey,selected=true)")
            check("2 no right|aight bigram", k.log.none { it.contains("prev=right") })
            eq("2 kept count", k.memory.keptCount("aight"), 1)
            eq("2 banned", k.memory.bannedFixes("aight"), setOf("right"))
            check("2 persisted", k.persisted?.contains("aight=1/0") == true, "${k.persisted}")
            check("2 a keep protects, it does not confirm", "aight" !in k.confirmed)
            k.hide(); k.showNewField()
            k.type("aight"); k.separator(" ")
            eq("2 after hide stays", k.ed.toString(), "hey aight aight ")
            k = k.restart()
            k.type("aight"); k.separator(" ")
            eq("2 after restart stays", k.ed.toString(), "aight ")
        }
        // 3a Backspace N times, retype the same word, undo ran
        run {
            val k = ime(); k.type("aight"); k.separator(" ")
            repeat(6) { k.backspace() }
            eq("3a erased", k.ed.toString(), "")
            k.type("aight"); k.separator(" ")
            eq("3a stays", k.ed.toString(), "aight ")
            eq("3a explicit keep", k.memory.keptCount("aight"), 1)
        }
        // 3b Fight with no undo: word delete, retype, twice
        run {
            val k = ime(); k.type("aight"); k.separator(" ")
            k.wordDelete()
            eq("3b erased", k.ed.toString(), "")
            k.type("aight"); k.separator(" ")
            eq("3b first retype stays", k.ed.toString(), "aight ")
            eq("3b soft", k.memory.softCount("aight"), 1)
            check("3b no strike", k.log.none { it.startsWith("noteTypo") })
            nowMs += 600_000   // outside the ring window
            k.wordDelete(); k.type("aight"); k.separator(" ")
            // Ring expired. "right" stays banned; "light" is the next candidate but changes the first
            // letter with a margin under .75, so the word is left alone and "light" is only a hint.
            eq("3b ring expired, ban holds, next candidate gated", k.ed.toString(), "aight ")
            check("3b still no strike", k.log.none { it.startsWith("noteTypo") })
        }
        // 4 Backspace, change one letter, space
        run {
            val k = ime(); k.type("aight"); k.separator(" ")
            k.backspace(); k.backspace(); k.letter('z'); k.separator(" ")
            eq("4 variant stays", k.ed.toString(), "aighz ")
            eq("4 variant not kept", k.memory.keptCount("aighz"), 0)
            eq("4 variant soft", k.memory.softCount("aighz"), 1)
            eq("4 variant not protected", k.memory.isProtected("aighz"), false)
            check("4 variant learned normally", k.log.contains("learn(aighz,prev=,selected=false)"), "${k.log}")
            eq("4 variant bans fix", k.memory.bannedFixes("aighz"), setOf("right"))
            eq("4 raw banned", k.memory.bannedFixes("aight"), setOf("right"))
            eq("4 raw not protected", k.memory.isProtected("aight"), false)
        }
        // 5 Erase everything, type a different word
        run {
            val k = ime(); k.type("aight"); k.separator(" ")
            repeat(6) { k.backspace() }
            k.type("teh"); k.separator(" ")
            eq("5 normal correction", k.ed.toString(), "the ")
        }
        // 6a Keep typing, later backspace into the fix, leave it
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.type("next"); k.separator(" ")
            eq("6a settled", k.log.filter { it.startsWith("noteTypo") || it.startsWith("learn(right") }.size, 2)
            k.log.clear()
            k.backspace()                      // deletes " ", re-enters next
            eq("6a next resumed", k.session.provenance, Provenance.RESUMED)
            repeat(4) { k.backspace() }        // erases next
            k.backspace()                      // deletes " ", re-enters right
            eq("6a right resumed", k.composing.toString(), "right")
            eq("6a chip raw", k.session.refusedRaw, "aight")
            k.separator(" ")
            eq("6a unchanged", k.ed.toString(), "right ")
            eq("6a learned nothing", k.log, emptyList<String>())
            eq("6a context restored", k.committedWords.toList(), listOf("right"))
            eq("6a typing log holds it once, the erased word not at all", k.messageWords.toList(), listOf("right"))
        }
        // 6b Later backspace into the fix, restore the spelling via edits
        run {
            val k = ime(); k.type("teh"); k.separator(" "); k.type("next"); k.separator(" "); k.log.clear()
            k.backspace(); repeat(4) { k.backspace() }; k.backspace()
            eq("6b the resumed", k.composing.toString(), "the")
            k.backspace(); k.backspace(); k.type("eh"); k.separator(" ")
            eq("6b raw stays", k.ed.toString(), "teh ")
            check("6b fix unlearned", k.log.contains("unlearn(the)"), "${k.log}")
            check("6b strike cleared", k.log.contains("clearTypo(teh)"), "${k.log}")
            eq("6b kept", k.memory.keptCount("teh"), 1)
        }
        // 6c Later backspace into the fix before the next word commits: pending never settled
        run {
            val k = ime(); k.type("teh"); k.separator(" ")
            k.letter('n'); k.backspace()       // next word started, abandoned: undo is gone
            k.backspace()                      // deletes " ", re-enters the
            eq("6c the resumed", k.composing.toString(), "the")
            k.backspace(); k.backspace(); k.type("eh"); k.separator(" ")
            eq("6c raw stays", k.ed.toString(), "teh ")
            check("6c no unlearn needed", k.log.none { it.startsWith("unlearn") || it.startsWith("noteTypo") }, "${k.log}")
            eq("6c kept", k.memory.keptCount("teh"), 1)
        }
        // 6d Later backspace into the fix, tap the chip offering the original spelling
        run {
            val k = ime(); k.type("teh"); k.separator(" "); k.type("next"); k.separator(" "); k.log.clear()
            k.backspace(); repeat(4) { k.backspace() }; k.backspace()
            eq("6d chip offers raw", k.session.refusedRaw, "teh")
            k.chip("teh")
            eq("6d raw restored", k.ed.toString(), "teh ")
            eq("6d kept", k.memory.keptCount("teh"), 1)
            check("6d fix unlearned", k.log.contains("unlearn(the)"), "${k.log}")
            check("6d strike cleared", k.log.contains("clearTypo(teh)"), "${k.log}")
            k.backspace()
            eq("6d accidental tap wiped", k.ed.toString(), "")
            eq("6d keep taken back", k.memory.keptCount("teh"), 0)
        }
        // 7 Caret inside a word
        run {
            val k = ime(); k.type("world"); k.separator(" "); k.log.clear()
            k.externalCursor(3)
            k.type("teh"); k.separator(" ")
            eq("7 no correction inside a word", k.ed.toString(), "worteh ld ")
            eq("7 fragment not learned", k.log, emptyList<String>())
        }
        // 8 Punctuation-fired correction
        run {
            val k = ime(); k.type("teh"); k.separator(",")
            eq("8 corrected", k.ed.toString(), "the,")
            k.backspace()
            eq("8 restored exactly", k.ed.toString(), "teh,")
            eq("8 no composing", k.composing.toString(), "")
            eq("8 kept", k.memory.keptCount("teh"), 1)
            eq("8 context", k.committedWords.toList(), listOf("teh"))
            check("8 learned once", k.log.count { it.startsWith("learn(teh") } == 1, "${k.log}")
            k.backspace()
            eq("8 second backspace deletes the comma and re-enters", k.ed.toString(), "teh")
            eq("8 resumed", k.session.provenance, Provenance.RESUMED)
            k.separator(" ")
            eq("8 never re-corrected", k.ed.toString(), "teh ")
        }
        // 9 Hold delete
        run {
            val k = ime(); k.type("aight"); k.separator(" ")
            repeat(6) { k.backspace() }
            eq("9 erased", k.ed.toString(), "")
            eq("9 nothing learned", k.log, emptyList<String>())
            eq("9 nothing kept", k.memory.keptCount("aight"), 0)
        }
        // 11a Revert chip after a space fix
        run {
            val k = ime(); k.type("aight"); k.separator(" ")
            k.chip("aight")
            eq("11a restored with separator", k.ed.toString(), "aight ")
            eq("11a kept", k.memory.keptCount("aight"), 1)
            k.type("next")
            eq("11a next word does not run on", k.ed.toString(), "aight next")
        }
        // 11b Revert chip after a punctuation fix
        run {
            val k = ime(); k.type("teh"); k.separator(",")
            k.chip("teh")
            eq("11b exact text", k.ed.toString(), "teh,")
            check("11b nothing confirmed by accident", "teh" !in k.confirmed)
        }
        // 11c A chip whose fix can no longer be located does nothing
        run {
            val k = ime(); k.type("teh"); k.separator(" ")
            k.ed.deleteBefore(1); k.ed.commit("x")    // external edit, no selection callback yet
            k.chip("teh")
            eq("11c no append", k.ed.toString(), "the" + "x")
        }
        // 12 Raw chip before space, then an immediate backspace
        run {
            val k = ime(); k.type("aight"); k.chip("aight")
            eq("12 committed", k.ed.toString(), "aight ")
            eq("12 kept", k.memory.keptCount("aight"), 1)
            check("12 confirmed", "aight" in k.confirmed)
            k.backspace()
            eq("12 wiped", k.ed.toString(), "")
            eq("12 keep taken back", k.memory.keptCount("aight"), 0)
            check("12 unconfirmed", "aight" !in k.confirmed)
        }
        // 12b A slip on the chip takes back the tap, and what the word had before still stands
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.separator(" ")
            eq("12b earlier keep", k.memory.keptCount("aight"), 1)
            k.type("aight"); k.chip("aight")
            eq("12b the tap counts", k.memory.keptCount("aight"), 2)
            k.backspace()
            eq("12b slip wiped", k.ed.toString(), "aight ")
            eq("12b earlier keep stands", k.memory.keptCount("aight"), 1)
            eq("12b its ban stands", k.memory.bannedFixes("aight"), setOf("right"))
            check("12b stored that way", k.persisted?.contains("aight=1/0") == true, "${k.persisted}")
            check("12b unconfirmed", "aight" !in k.confirmed)
            nowMs += 300_000   // outside the ring window, so only the memory can keep it
            k.type("aight"); k.separator(" ")
            eq("12b refused correction stays away", k.ed.toString(), "aight aight ")
            val r = k.restart(); r.type("aight"); r.separator(" ")
            eq("12b and after a restart", r.ed.toString(), "aight ")
        }
        // 12c A slip takes back the ban that tap added, not an older one
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.wordDelete(); k.type("aight"); k.separator(" ")
            eq("12c soft", k.memory.softCount("aight"), 1)
            nowMs += 600_000   // the ring has let go, so the chip is refusing the next candidate
            k.type("aight"); k.chip("aight")
            eq("12c the tap bans what it refused", k.memory.bannedFixes("aight"), setOf("right", "light"))
            k.backspace()
            eq("12c its ban goes", k.memory.bannedFixes("aight"), setOf("right"))
            eq("12c its keep goes", k.memory.keptCount("aight"), 0)
            eq("12c the soft refusal stands", k.memory.softCount("aight"), 1)
        }
        // 12d A slip on a word confirmed long ago does not unconfirm it
        run {
            val k = ime(); k.confirmed += "aight"; k.type("aight"); k.chip("aight")
            k.backspace()
            eq("12d wiped", k.ed.toString(), "")
            check("12d stays confirmed", "aight" in k.confirmed)
            check("12d no unconfirm", k.log.none { it.startsWith("unconfirm") }, "${k.log}")
        }
        // 13 Tap the centre fix
        run {
            val k = ime(); k.type("aight"); k.chip("right")
            eq("13 committed", k.ed.toString(), "right ")
            check("13 typo noted", k.log.contains("noteTypo(aight)"))
            eq("13 not kept", k.memory.keptCount("aight"), 0)
        }
        // 14 Silent acceptance
        run {
            val k = ime(); k.type("aight"); k.separator(" ")
            eq("14 nothing yet", k.log, emptyList<String>())
            k.type("next"); k.separator(" ")
            check("14 settled by next word", k.log.contains("learn(right,prev=,selected=false)") && k.log.contains("noteTypo(aight)") && k.log.contains("tap(aight,right)"), "${k.log}")
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.hide()
            check("14b settled by hide", k.log.contains("noteTypo(aight)"), "${k.log}")
        }
        // 16 Incognito
        run {
            val k = ime(incognito = true); k.type("aight"); k.separator(" ")
            k.backspace(); k.separator(" ")
            eq("16 stays", k.ed.toString(), "aight ")
            eq("16 nothing persisted", k.persisted, null)
            eq("16 durable memory untouched", k.memory.keptCount("aight"), 0)
            k.type("aight"); k.separator(" ")
            eq("16 protected in this field", k.ed.toString(), "aight aight ")
            check("16 no learning calls", k.log.none { it.startsWith("noteTypo") || it.startsWith("tap") || it.startsWith("confirm") }, "${k.log}")
            eq("16 nothing taught at all", k.log, emptyList<String>())
            k.showNewField()
            k.ed.text.setLength(0); k.ed.cursor = 0
            k.type("aight"); k.separator(" ")
            eq("16 next field forgets", k.ed.toString(), "right ")
        }
        // 16b A correction that stands teaches nothing in incognito
        run {
            val k = ime(incognito = true); k.type("aight"); k.separator(" "); k.type("ok"); k.separator(" ")
            eq("16b the fix stood", k.ed.toString(), "right ok ")
            check("16b no typo, tap or learning from the fix", k.log.none { it.startsWith("noteTypo") || it.startsWith("tap") || it.startsWith("learn(right") }, "${k.log}")
            eq("16b nothing taught at all", k.log, emptyList<String>())
            k.hide()
            eq("16b hide teaches nothing", k.log, emptyList<String>())
            eq("16b nothing persisted", k.persisted, null)
        }
        run {
            val k = ime(incognito = true); k.type("aight"); k.separator(" "); k.hide()
            eq("16b settled by hide teaches nothing", k.log, emptyList<String>())
        }
        // 16c A fix that stood and is then refused has nothing to roll back in incognito
        run {
            val k = ime(incognito = true); k.type("teh"); k.separator(" "); k.type("next"); k.separator(" ")
            k.backspace(); repeat(4) { k.backspace() }; k.backspace()
            eq("16c the resumed", k.composing.toString(), "the")
            k.backspace(); k.backspace(); k.type("eh"); k.separator(" ")
            eq("16c raw stays", k.ed.toString(), "teh ")
            check("16c no unlearn", k.log.none { it.startsWith("unlearn") || it.startsWith("clearTypo") }, "${k.log}")
            eq("16c nothing taught at all", k.log, emptyList<String>())
            eq("16c held for this field", k.volatile.keptCount("teh"), 1)
            eq("16c durable memory untouched", k.memory.keptCount("teh"), 0)
            eq("16c nothing persisted", k.persisted, null)
        }
        // 16d The same by erasing and retyping
        run {
            val k = ime(incognito = true); k.type("aight"); k.separator(" "); k.type("next"); k.separator(" ")
            k.wordDelete(); k.wordDelete(); k.type("aight"); k.separator(" ")
            eq("16d stays", k.ed.toString(), "aight ")
            eq("16d nothing rolled back", k.log, emptyList<String>())
            eq("16d soft held for this field", k.volatile.softCount("aight"), 1)
            eq("16d durable memory untouched", k.memory.softCount("aight"), 0)
        }
        // 16e A slip on the chip in incognito stays out of the durable memory and the model
        run {
            val k = ime(incognito = true); k.type("aight"); k.chip("aight")
            eq("16e held for this field", k.volatile.keptCount("aight"), 1)
            k.backspace()
            eq("16e wiped", k.ed.toString(), "")
            eq("16e taken back", k.volatile.keptCount("aight"), 0)
            check("16e no unconfirm", k.log.none { it.startsWith("unconfirm") }, "${k.log}")
            eq("16e nothing persisted", k.persisted, null)
        }
        // 18 Enter
        run {
            val k = ime(); k.type("teh"); k.enter(actionKey = false)
            eq("18 newline corrects", k.ed.toString(), "the\n")
            k.backspace()
            eq("18 undo restores newline", k.ed.toString(), "teh\n")
            val s = ime(); s.type("teh"); s.enter(actionKey = true)
            eq("18b send does not correct", s.ed.toString(), "teh")
        }
        // 19 Field policy reaches the verdict
        run {
            val k = ime(); k.field = FieldCorrection.OFF; k.type("teh"); k.separator(" ")
            eq("19 off", k.ed.toString(), "teh ")
            val s = ime(); s.field = FieldCorrection.SUGGEST_ONLY; s.type("teh"); s.separator(" ")
            eq("19 suggest only", s.ed.toString(), "teh ")
        }
        // Stale undo
        run {
            val k = ime(); k.type("hello"); k.separator(" "); k.type("teh"); k.separator(" ")
            k.type("the"); k.separator(" ")
            eq("stale setup", k.ed.toString(), "hello the the ")
            val k2 = ime(); k2.type("teh"); k2.separator(" ")
            k2.ed.commit("the ")                      // field now "the the ", as if from elsewhere
            k2.externalCursor(4)                      // caret after first "the "
            k2.backspace()
            eq("stale undo cannot fire", k2.ed.toString(), "the" + "the ")
        }
        // Retype after moving on changes nothing for an unrelated later word
        run {
            val k = ime(); k.type("teh"); k.separator(" "); k.type("next"); k.separator(" "); k.type("teh"); k.separator(" ")
            eq("21 same typo elsewhere still fixes", k.ed.toString(), "the next the ")
            eq("21 no refusal", k.memory.keptCount("teh") + k.memory.softCount("teh"), 0)
        }
        // Ring match then keep typing must not record a refusal for the longer word
        run {
            val k = ime(); k.type("teh"); k.separator(" "); repeat(4) { k.backspace() }
            k.type("teh"); CorrectionPolicy.decide(k.input("teh", null)); k.type("r"); k.separator(" ")
            eq("ring miss stays clean", k.ed.toString(), "tehr ")
            eq("ring miss no kept", k.memory.keptCount("tehr"), 0)
            eq("ring miss no soft", k.memory.softCount("tehr"), 0)
        }
        // Run-on split: only Space
        run {
            val k = ime(); k.type("thisis"); k.separator(" ")
            eq("split on space", k.ed.toString(), "this is ")
            eq("split context", k.committedWords.toList(), listOf("this", "is"))
            k.backspace()
            eq("split undo", k.ed.toString(), "thisis")
            eq("split context popped", k.committedWords.toList(), emptyList<String>())
            k.separator(" ")
            eq("split kept", k.memory.keptCount("thisis"), 1)
            val p = ime(); p.type("thisis"); p.separator(".")
            eq("no split on period", p.ed.toString(), "thisis.")
        }
        run {
            val k = ime(); k.type("thisis"); k.separator(" "); k.type("next"); k.separator(" ")
            check("split learned word by word, no typo", k.log.contains("learn(this,prev=,selected=false)") && k.log.contains("learn(is,prev=this,selected=false)") && k.log.none { it.startsWith("noteTypo") }, "${k.log}")
        }
        // A cased fix is refused like any other, through the lowercase memory
        run {
            val k = FakeIme(clock); k.evidence = mapOf("im" to Ev(closed = "I'm")); k.type("im"); k.separator(" ")
            eq("cased closed fix", k.ed.toString(), "I'm ")
            k.wordDelete(); k.type("im"); k.separator(" ")
            eq("retype stays", k.ed.toString(), "im ")
            eq("soft", k.memory.softCount("im"), 1)
            nowMs += 600_000
            k.wordDelete(); k.type("im"); k.separator(" ")
            eq("ban holds outside the ring window", k.ed.toString(), "im ")
        }
        run {
            val k = FakeIme(clock); k.evidence = mapOf("ithink" to Ev(split = "I think")); k.type("ithink"); k.separator(" ")
            eq("cased split", k.ed.toString(), "I think ")
            eq("cased split context", k.committedWords.toList(), listOf("i", "think"))
            k.backspace()
            eq("cased split undo", k.ed.toString(), "ithink")
            eq("cased split context popped", k.committedWords.toList(), emptyList<String>())
            k.separator(" ")
            eq("cased split banned lowercase", k.memory.bannedFixes("ithink"), setOf("i think"))
        }
        // Closed table honours a refusal
        run {
            val k = ime(); k.type("dont"); k.separator(" ")
            eq("closed fix", k.ed.toString(), "don't ")
            k.backspace(); k.separator(" ")
            eq("closed kept", k.ed.toString(), "dont ")
            eq("closed banned", k.memory.bannedFixes("dont"), setOf("don't"))
            k.type("dont"); k.separator(" ")
            eq("closed stays", k.ed.toString(), "dont dont ")
        }
        run {
            val k = ime(); k.type("teh"); k.separator(" "); k.backspace(); k.separator(" ")
            check("one keep does not confirm", "teh" !in k.confirmed)
            k.memory.forget("teh"); k.memory.noteKept("teh", "the")
            k.type("teh"); k.separator(" ")
            k.backspace()                                   // nothing to undo: word was never corrected
            eq("no fix to undo", k.ed.toString(), "teh teh")
        }
        // An ordinary edit of a fix is not a refusal
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.type("next"); k.separator(" "); k.log.clear()
            k.backspace(); repeat(4) { k.backspace() }; k.backspace()
            eq("rights re-entered", k.composing.toString(), "right")
            k.letter('s'); k.separator(" ")
            eq("rights stays", k.ed.toString(), "rights ")
            eq("rights no keep", k.memory.keptCount("rights"), 0)
            eq("rights no refusal of raw", k.memory.keptCount("aight"), 0)
            check("rights learned normally", k.log.contains("learn(rights,prev=,selected=false)"), "${k.log}")
            check("rights no unlearn", k.log.none { it.startsWith("unlearn") }, "${k.log}")
        }
        // A word fixed by hand while composing is the user's text
        run {
            val k = ime(); k.type("tehh"); k.backspace()
            eq("edited provenance", k.session.provenance, Provenance.EDITED)
            k.separator(" ")
            eq("edited never corrected", k.ed.toString(), "teh ")
            eq("edited no keep", k.memory.keptCount("teh"), 0)
            k.type("teh"); k.separator(" ")
            eq("next fresh typing corrects again", k.ed.toString(), "teh the ")
        }
        // A fix that stood, then erased and retyped: what it taught is taken back
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.type("next"); k.separator(" "); k.log.clear()
            k.wordDelete(); k.wordDelete()
            eq("settled retype erased", k.ed.toString(), "")
            k.type("aight"); k.separator(" ")
            eq("settled retype stays", k.ed.toString(), "aight ")
            check("settled retype unlearns the fix", k.log.contains("unlearn(right)"), "${k.log}")
            check("settled retype clears the strike", k.log.contains("clearTypo(aight)"), "${k.log}")
            eq("settled retype is one soft refusal", k.memory.softCount("aight"), 1)
        }
        // The second soft refusal protects the word, and only then is its typo verdict cleared
        run {
            val k = ime(); k.type("aight"); k.separator(" ")
            k.wordDelete(); k.type("aight"); k.separator(" ")
            eq("two soft, first", k.memory.softCount("aight"), 1)
            check("two soft, the first clears nothing", k.log.none { it.startsWith("clearTypo") }, "${k.log}")
            k.wordDelete(); k.type("aight"); k.separator(" ")
            eq("two soft, second", k.memory.softCount("aight"), 2)
            check("two soft, protected", k.memory.isProtected("aight"))
            check("two soft, protection clears the verdict", k.log.contains("clearTypo(aight)"), "${k.log}")
        }
        // Hide is what carries the sliding expiry across a restart
        run {
            var k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.separator(" ")
            k.type("aight"); k.chip("aight")
            eq("kept twice", k.memory.keptCount("aight"), 2)
            val day = 86_400_000L
            nowMs += 80 * day
            k.type("aight"); k.separator(" ")
            k.log.clear(); k.hide()
            eq("hide writes what a touch changed", k.log, listOf("persist"))
            k.log.clear(); k.hide()
            eq("hide with nothing new writes nothing", k.log, emptyList<String>())
            k = k.restart()
            nowMs += 80 * day
            k.type("aight"); k.separator(" ")
            eq("the slide survives a restart", k.ed.toString(), "aight ")
        }
        // A re-entered word counts again only if its count was taken back on the way in
        run {
            val k = ime(); k.reentryUnlearns = true; k.type("world"); k.separator(" "); k.log.clear()
            k.backspace()
            eq("re-entry takes the count back", k.log, listOf("unlearn(world)"))
            k.separator(" ")
            eq("re-entry counts it again", k.log, listOf("unlearn(world)", "learn(world,prev=,selected=false)"))
            eq("re-entry context", k.committedWords.toList(), listOf("world"))
        }
        run {
            val k = ime(); k.type("world"); k.separator(" "); k.log.clear(); k.backspace(); k.separator(" ")
            eq("re-entry with its count intact counts nothing", k.log, emptyList<String>())
            eq("re-entry with its count intact, context", k.committedWords.toList(), listOf("world"))
        }
        // Case
        run {
            val k = ime(); k.type("Teh"); k.separator(" ")
            eq("first cap kept", k.ed.toString(), "The ")
            k.backspace()
            eq("undo keeps case", k.ed.toString(), "Teh")
        }
        run {
            val k = ime(); k.type("TEH"); k.separator(" ")
            eq("all caps untouched", k.ed.toString(), "TEH ")
            val d = ime(); d.type("DONT"); d.separator(" ")
            eq("all caps contraction untouched", d.ed.toString(), "DONT ")
            val i = ime(); i.type("i"); i.separator(" ")
            eq("i becomes I", i.ed.toString(), "I ")
            val c = ime(); c.type("I"); c.separator(" ")
            eq("I stays", c.ed.toString(), "I ")
            check("I is not a correction", c.session.lastFix == null)
        }
    }

    private fun inp(
        typed: String, prov: Provenance = Provenance.TYPED, field: FieldCorrection = FieldCorrection.AUTO,
        before: Char? = null, after: Char? = null, sep: Char? = ' ', level: Int = 2, known: Boolean = false,
        confirmed: Boolean = false, strong: Boolean = false, rejected: Boolean = false, banned: Set<String> = emptySet(),
        closed: String? = null, cands: List<Candidate> = emptyList(), spatial: Candidate? = null, trace: Boolean = false,
        over: String? = null, split: String? = null,
    ) = CorrectionInput(typed, prov, field, before, after, sep, level, known, confirmed, strong, rejected, banned, closed, cands, spatial, trace, over, split)

    fun gates() {
        val d = CorrectionPolicy::decide
        val teh = listOf(cand("the", 0.5, 3.7), cand("ten", 1.0, 2.2))
        eq("g off", d(inp("teh", field = FieldCorrection.OFF, cands = teh)), Verdict.Keep)
        eq("g auto", d(inp("teh", cands = teh)), Verdict.AutoCommit("the"))
        eq("g suggest", d(inp("teh", field = FieldCorrection.SUGGEST_ONLY, cands = teh)), Verdict.Hint("the"))
        for (p in listOf(Provenance.EDITED, Provenance.RESUMED, Provenance.REVERTED))
            eq("g provenance $p", d(inp("teh", prov = p, cands = teh)), Verdict.Hint("the"))
        eq("g rejected", d(inp("teh", rejected = true, cands = teh)), Verdict.Keep)
        eq("g confirmed", d(inp("teh", confirmed = true, cands = teh)), Verdict.Keep)
        eq("g strong", d(inp("teh", strong = true, cands = teh)), Verdict.Keep)
        for (c in "5a@#_/\\.:=&+-'’") eq("g before $c", d(inp("teh", before = c, cands = teh)), Verdict.Keep)
        for (c in " (\"\n,") eq("g before ok [$c]", d(inp("teh", before = c, cands = teh)), Verdict.AutoCommit("the"))
        for (c in "5a_-'’") eq("g after $c", d(inp("teh", after = c, cands = teh)), Verdict.Keep)
        for (c in " .,)") eq("g after ok [$c]", d(inp("teh", after = c, cands = teh)), Verdict.AutoCommit("the"))
        eq("g caps", d(inp("TEH", cands = teh)), Verdict.Keep)
        eq("g inner cap", d(inp("tEh", cands = teh)), Verdict.Keep)
        eq("g first cap", d(inp("Teh", cands = teh)), Verdict.AutoCommit("the"))
        eq("g digit", d(inp("te5", cands = teh)), Verdict.Keep)
        eq("g i", d(inp("i")), Verdict.AutoCommit("I"))
        eq("g closed", d(inp("dont", closed = "don't")), Verdict.AutoCommit("don't"))
        eq("g closed banned", d(inp("dont", closed = "don't", banned = setOf("don't"))), Verdict.Keep)
        eq("g cased closed fix banned", d(inp("im", closed = "I'm", banned = setOf("i'm"))), Verdict.Keep)
        eq("g cased split banned", d(inp("ithink", split = "I think", banned = setOf("i think"))), Verdict.Keep)
        eq("g closed resumed hints", d(inp("dont", closed = "don't", prov = Provenance.RESUMED)), Verdict.Hint("don't"))
        eq("g known", d(inp("form", known = true, cands = listOf(cand("from", 0.5, 4.0)))), Verdict.Keep)
        eq("g known bold no override", d(inp("form", level = 3, known = true, cands = listOf(cand("from", 0.5, 4.0)))), Verdict.Keep)
        eq("g known bold override", d(inp("form", level = 3, known = true, over = "from")), Verdict.AutoCommit("from"))
        eq("g known bold override banned", d(inp("form", level = 3, known = true, over = "from", banned = setOf("from"))), Verdict.Keep)
        eq("g two letters override banned", d(inp("ot", cands = listOf(cand("it", 0.5, 3.0)), over = "it", banned = setOf("it"))), Verdict.Keep)
        eq("g two letters override banned, next hints", d(inp("ot", cands = listOf(cand("it", 0.5, 3.0), cand("at", 1.0, 2.0)), over = "it", banned = setOf("it"))), Verdict.Hint("at"))
        eq("g curly apostrophe", d(inp("dosn’t", cands = listOf(cand("doesn't", 0.5, 3.0)))), Verdict.AutoCommit("doesn't"))
        eq("g straight apostrophe", d(inp("dosn't", cands = listOf(cand("doesn't", 0.5, 3.0)))), Verdict.AutoCommit("doesn't"))
        eq("g two letters", d(inp("ot", cands = listOf(cand("it", 0.5, 3.0)))), Verdict.Hint("it"))
        eq("g two letters override", d(inp("ot", cands = listOf(cand("it", 0.5, 3.0)), over = "it")), Verdict.AutoCommit("it"))
        eq("g light short", d(inp("teh", level = 1, cands = teh)), Verdict.Hint("the"))
        eq("g run3 keep", d(inp("hiii", cands = listOf(cand("hill", 1.0, 3.0)))), Verdict.Keep)
        eq("g run3 base hint", d(inp("yesss", cands = listOf(cand("yes", 1.0, 3.0)))), Verdict.Hint("yes"))
        eq("g run2 collapse", d(inp("heyy", cands = listOf(cand("hey", 0.5, 3.0)))), Verdict.AutoCommit("hey"))
        eq("g run2 other", d(inp("noo", cands = listOf(cand("not", 0.5, 4.0), cand("no", 1.0, 3.0)))), Verdict.Hint("not"))
        eq("g no shrink", d(inp("gon", cands = listOf(cand("on", 1.0, 3.0)))), Verdict.Hint("on"))
        eq("g shrink long ok", d(inp("woulda", cands = listOf(cand("would", 1.0, 3.0)))), Verdict.AutoCommit("would"))
        eq("g floor", d(inp("zzab", cands = listOf(cand("zzap", 0.5, 3.0, 100)))), Verdict.Hint("zzap"))
        eq("g floor user", d(inp("zzab", cands = listOf(Candidate("zzap", 0.5, 3.0, 100, 2)))), Verdict.AutoCommit("zzap"))
        eq("g floor 8 letters at it", d(inp("zzabzzab", cands = listOf(cand("zzapzzab", 0.5, 3.0, 100)))), Verdict.AutoCommit("zzapzzab"))
        eq("g floor 8 letters under it", d(inp("zzabzzab", cands = listOf(cand("zzapzzab", 0.5, 3.0, 99)))), Verdict.Hint("zzapzzab"))
        eq("g floor 7 letters is the 6 letter floor", d(inp("zzabzab", cands = listOf(cand("zzapzab", 0.5, 3.0, 100)))), Verdict.Hint("zzapzab"))
        eq("g floor 6 letters at it", d(inp("zzabzb", cands = listOf(cand("zzapzb", 0.5, 3.0, 600)))), Verdict.AutoCommit("zzapzb"))
        eq("g floor 6 letters under it", d(inp("zzabzb", cands = listOf(cand("zzapzb", 0.5, 3.0, 599)))), Verdict.Hint("zzapzb"))
        eq("g floor 5 letters at it", d(inp("zzabz", cands = listOf(cand("zzapz", 0.5, 3.0, 2000)))), Verdict.AutoCommit("zzapz"))
        eq("g floor 5 letters under it", d(inp("zzabz", cands = listOf(cand("zzapz", 0.5, 3.0, 1999)))), Verdict.Hint("zzapz"))
        eq("g margin short", d(inp("wan", cands = listOf(cand("was", 0.5, 3.0), cand("want", 1.0, 2.8)))), Verdict.Hint("was"))
        eq("g margin long", d(inp("wxnt", cands = listOf(cand("want", 0.5, 3.0), cand("went", 1.0, 2.8)))), Verdict.Hint("want"))
        eq("g margin long ok", d(inp("wxnt", cands = listOf(cand("want", 0.5, 3.0), cand("went", 1.0, 2.6)))), Verdict.AutoCommit("want"))
        eq("g first letter", d(inp("aight", cands = listOf(cand("right", 1.0, 3.0), cand("light", 1.0, 2.5)))), Verdict.Hint("right"))
        eq("g first swap allowed", d(inp("hte", cands = listOf(cand("the", 0.5, 3.7), cand("he", 1.0, 3.0)))), Verdict.AutoCommit("the"))
        eq("g two edits margin", d(inp("recieev", cands = listOf(cand("receive", 1.5, 2.0), cand("relieve", 2.0, 1.5)))), Verdict.Hint("receive"))
        eq("g two edits decisive", d(inp("recieev", cands = listOf(cand("receive", 1.5, 2.0), cand("relieve", 2.0, 0.9)))), Verdict.AutoCommit("receive"))
        eq("g light maxdist", d(inp("recieev", level = 1, cands = listOf(cand("receive", 1.5, 3.0), cand("x", 2.0, 0.5)))), Verdict.Hint("receive"))
        eq("g bold margin short", d(inp("wan", level = 3, cands = listOf(cand("was", 0.5, 3.0), cand("want", 1.0, 2.6)))), Verdict.AutoCommit("was"))
        eq("g bold margin short under", d(inp("wan", level = 3, cands = listOf(cand("was", 0.5, 3.0), cand("want", 1.0, 2.8)))), Verdict.Hint("was"))
        eq("g bold margin long", d(inp("wxnt", level = 3, cands = listOf(cand("want", 0.5, 3.0), cand("went", 1.0, 2.8)))), Verdict.AutoCommit("want"))
        eq("g bold margin long under", d(inp("wxnt", level = 3, cands = listOf(cand("want", 0.5, 3.0), cand("went", 1.0, 2.95)))), Verdict.Hint("want"))
        eq("g bold first letter", d(inp("aight", level = 3, cands = listOf(cand("right", 1.0, 3.0), cand("light", 1.0, 2.4)))), Verdict.AutoCommit("right"))
        eq("g bold first letter under", d(inp("aight", level = 3, cands = listOf(cand("right", 1.0, 3.0), cand("light", 1.0, 2.6)))), Verdict.Hint("right"))
        eq("g light first letter never", d(inp("aight", level = 1, cands = listOf(cand("right", 1.0, 9.0), cand("light", 1.0, 1.0)))), Verdict.Hint("right"))
        eq("g light first letter never, sole candidate", d(inp("aight", level = 1, cands = listOf(cand("right", 1.0, 3.0)))), Verdict.Hint("right"))
        eq("g light same first letter", d(inp("wxnt", level = 1, cands = listOf(cand("want", 0.5, 3.0), cand("went", 1.0, 1.5)))), Verdict.AutoCommit("want"))
        eq("g veto", d(inp("morn", trace = true, cands = listOf(cand("more", 0.5, 3.0), cand("corn", 1.0, 1.0)))), Verdict.Hint("more"))
        eq("g veto spares transposition", d(inp("teh", trace = true, cands = teh)), Verdict.AutoCommit("the"))
        eq("g veto spares length change", d(inp("woulda", trace = true, cands = listOf(cand("would", 1.0, 3.0)))), Verdict.AutoCommit("would"))
        eq("g bold no veto", d(inp("morn", level = 3, trace = true, cands = listOf(cand("more", 0.5, 3.0), cand("corn", 1.0, 1.0)))), Verdict.AutoCommit("more"))
        eq("g spatial", d(inp("qhen", trace = true, spatial = cand("when", 0.0, 1.0), cands = listOf(cand("then", 0.5, 3.0)))), Verdict.AutoCommit("when"))
        eq("g spatial banned", d(inp("qhen", trace = true, spatial = cand("when", 0.0, 1.0), banned = setOf("when"), cands = listOf(cand("then", 0.5, 3.0)))), Verdict.Hint("then"))
        eq("g spatial floor", d(inp("qhen", trace = true, spatial = cand("when", 0.0, 1.0, 10))), Verdict.Keep)
        eq("g spatial must collapse", d(inp("heyy", trace = true, spatial = cand("heya", 0.0, 1.0), cands = listOf(cand("hey", 0.5, 3.0)))), Verdict.AutoCommit("hey"))
        eq("g spatial collapse", d(inp("heyy", trace = true, spatial = cand("hey", 0.0, 1.0))), Verdict.AutoCommit("hey"))
        eq("g ban moves on", d(inp("aight", banned = setOf("right"), cands = listOf(cand("right", 1.0, 3.0), cand("light", 1.0, 1.0)))), Verdict.AutoCommit("light"))
        eq("g split space", d(inp("thisis", split = "this is")), Verdict.AutoCommit("this is"))
        eq("g split preview", d(inp("thisis", sep = null, split = "this is")), Verdict.AutoCommit("this is"))
        eq("g split period", d(inp("thisis", sep = '.', split = "this is")), Verdict.Hint("this is"))
        eq("g split light", d(inp("thisis", level = 1, split = "this is")), Verdict.Hint("this is"))
        eq("g split banned", d(inp("thisis", split = "this is", banned = setOf("this is"))), Verdict.Keep)
        eq("g split beats a whole-key edit", d(inp("inthis", split = "in this", cands = listOf(cand("this", 2.0, 3.0)))), Verdict.AutoCommit("in this"))
        eq("g split beats edit of exactly 1", d(inp("theyare", split = "they are", cands = listOf(cand("they're", 1.0, 3.0)))), Verdict.AutoCommit("they are"))
        eq("g close edit beats split", d(inp("anout", split = "an out", cands = listOf(cand("about", 0.5, 3.0), cand("mount", 1.0, 2.0)))), Verdict.AutoCommit("about"))
        eq("g split after the close edit fails its gates", d(inp("gotit", split = "got it", cands = listOf(cand("gotti", 0.5, 1.0, 50)))), Verdict.AutoCommit("got it"))
        eq("g split not on period, edit still hints", d(inp("inthis", sep = '.', split = "in this", cands = listOf(cand("this", 2.0, 3.0), cand("thin", 2.0, 2.5)))), Verdict.Hint("this"))
        eq("g light never splits", d(inp("inthis", level = 1, split = "in this", cands = listOf(cand("this", 2.0, 3.0)))), Verdict.Hint("this"))
    }

    fun memory() {
        val base = 5_000_000_000L
        var t = base
        val m = RejectionMemory({ t }, maxEntries = 3)
        check("m empty", !m.isProtected("x"))
        m.noteKept("foo", "for"); m.noteKept("foo", "fog")
        eq("m fixes", m.bannedFixes("FOO"), setOf("for", "fog"))
        check("m protected", m.isProtected("foo"))
        m.noteSoft("bar", "bat")
        check("m one soft does not protect", !m.isProtected("bar"))
        m.noteSoft("bar", null)
        check("m two soft protect", m.isProtected("bar"))
        val day = 86_400_000L
        t += 89 * day; m.touch("foo"); t += 89 * day
        check("m touch slides", m.isProtected("foo"))
        check("m expiry", !m.isProtected("bar"))
        eq("m expired fixes gone", m.bannedFixes("bar"), emptySet<String>())
        val slide = RejectionMemory({ t })
        slide.noteKept("foo", null); slide.noteSoft("baz", null); t += 89 * day
        slide.noteKept("foo", null); slide.noteSoft("baz", null); t += 89 * day
        check("m a keep slides", slide.isProtected("foo"))
        eq("m a soft refusal slides", slide.softCount("baz"), 2)
        val rec = RejectionMemory({ t })
        rec.noteKept("foo", "for")
        val before = rec.recordOf("FOO")
        val stamp = rec.serialize()
        t += day
        rec.noteKept("foo", "fog"); rec.noteSoft("foo", "fob")
        rec.restoreWord("FOO", before)
        eq("m restored exactly, stamp and all", rec.serialize(), stamp)
        rec.restoreWord("foo", null)
        check("m restored to nothing is gone", rec.recordOf("foo") == null && !rec.isProtected("foo"))
        check("m nothing known has no record", rec.recordOf("never") == null)
        t = base
        val lru = RejectionMemory({ t }, maxEntries = 3)
        lru.noteKept("a1", null); lru.noteKept("a2", null); lru.noteKept("a3", null)
        lru.isProtected("a1"); lru.noteKept("a4", null)
        check("m lru keeps recent", lru.isProtected("a1") && lru.isProtected("a4"))
        check("m lru evicts oldest", !lru.isProtected("a2"))
        val rt = RejectionMemory({ t })
        rt.noteKept("don't", "do not"); rt.noteSoft("zork", "work"); rt.banPair("zork", "cork")
        val s = rt.serialize()
        val back = RejectionMemory({ t }); back.restore(s)
        eq("m round trip kept", back.keptCount("don't"), 1)
        eq("m round trip fix", back.bannedFixes("don't"), setOf("do not"))
        eq("m round trip soft", back.softCount("zork"), 1)
        eq("m round trip fixes", back.bannedFixes("zork"), setOf("work", "cork"))
        val bad = RejectionMemory({ t }); bad.restore("garbage,,=,a=b/c@d|e,ok=1/0@$t|fix,x=1/0|nope")
        check("m malformed skipped", bad.isProtected("ok"))
        eq("m malformed count", bad.serialize().count { it == '=' }, 1)
        val old = RejectionMemory({ t + 100 * day }); old.restore(s)
        check("m restore honours ttl", !old.isProtected("don't"))
        val unsafe = RejectionMemory({ t }); unsafe.noteKept("a,b", null); unsafe.noteKept("fine", null)
        eq("m unsafe not serialized", unsafe.serialize().count { it == '=' }, 1)
        var rtime = base
        val r = RejectionMemory({ rtime })
        r.noteAutoCorrected("teh", "the", "so")
        check("m ring no hit while the fix stands", r.matchRetype("teh", "so") == null)
        r.noteWordErased("the", "and")
        check("m ring erase needs same prev", r.matchRetype("teh", "so") == null)
        r.noteWordErased("the", "so")
        check("m ring hit", r.matchRetype("teh", "so") != null)
        check("m ring needs same prev", r.matchRetype("teh", "and") == null)
        check("m ring fragment", r.matchFragment("th", "so")?.raw == "teh")
        check("m ring fragment needs same prev", r.matchFragment("th", "and") == null)
        rtime += 121_000
        check("m ring window", r.matchRetype("teh", "so") == null)
        check("m fragment has no window", r.matchFragment("th", "so") != null)
        r.clearRecent()
        check("m ring cleared", r.matchFragment("th", "so") == null)
        repeat(9) { r.noteAutoCorrected("a$it", "b$it", "") }
        check("m ring capped", r.matchFragment("b0", "") == null && r.matchFragment("b8", "") != null)
    }

    /** The audited table, so with the single-line rule on. FieldCapabilitiesTest covers the switch. */
    fun fields() {
        val TEXT = 0x1
        val c = { t: Int -> FieldPolicy.classify(t, singleLineNeedsAutoCorrect = true) }
        eq("f plain single line", c(TEXT), FieldCorrection.SUGGEST_ONLY)
        eq("f sentences single line", c(TEXT or 0x4000), FieldCorrection.SUGGEST_ONLY)
        eq("f autocorrect", c(TEXT or 0x8000), FieldCorrection.AUTO)
        eq("f multi line", c(TEXT or 0x20000), FieldCorrection.AUTO)
        eq("f long message", c(TEXT or 0x40 or 0x20000 or 0x4000), FieldCorrection.AUTO)
        eq("f short message single line", c(TEXT or 0x40), FieldCorrection.SUGGEST_ONLY)
        eq("f no suggestions", c(TEXT or 0x80000 or 0x20000), FieldCorrection.OFF)
        eq("f uri", c(TEXT or 0x10), FieldCorrection.OFF)
        eq("f email", c(TEXT or 0x20), FieldCorrection.OFF)
        eq("f web email", c(TEXT or 0xd0), FieldCorrection.OFF)
        eq("f password", c(TEXT or 0x80), FieldCorrection.OFF)
        eq("f visible password", c(TEXT or 0x90), FieldCorrection.OFF)
        eq("f web password", c(TEXT or 0xe0), FieldCorrection.OFF)
        eq("f filter", c(TEXT or 0xb0 or 0x20000), FieldCorrection.SUGGEST_ONLY)
        eq("f name", c(TEXT or 0x60 or 0x20000), FieldCorrection.SUGGEST_ONLY)
        eq("f postal", c(TEXT or 0x70 or 0x20000), FieldCorrection.SUGGEST_ONLY)
        eq("f phonetic", c(TEXT or 0xc0 or 0x20000), FieldCorrection.SUGGEST_ONLY)
        eq("f autocomplete", c(TEXT or 0x10000 or 0x20000), FieldCorrection.SUGGEST_ONLY)
        eq("f web edit", c(TEXT or 0xa0 or 0x20000), FieldCorrection.SUGGEST_ONLY)
        eq("f web edit autocorrect", c(TEXT or 0xa0 or 0x8000), FieldCorrection.AUTO)
        eq("f null", c(0), FieldCorrection.OFF)
        eq("f number", c(0x2), FieldCorrection.OFF)
        eq("f phone", c(0x3), FieldCorrection.OFF)
        val v = FieldPolicy::showsSuggestions
        check("f show plain", v(TEXT))
        check("f show email", v(TEXT or 0x20))
        check("f show uri", v(TEXT or 0x10))
        check("f hide nosug", !v(TEXT or 0x80000))
        check("f hide password", !v(TEXT or 0x80))
        check("f hide null", !v(0))
    }

    /** Words with an apostrophe, where a word starts and stops, and what an abandoned word leaves behind. */
    fun wordShape() {
        run {
            val k = ime(); k.type("they"); k.apostrophe(); k.type("re"); k.separator(" ")
            eq("connector one word in the field", k.ed.toString(), "they're ")
            eq("connector one word learned", k.log.filter { it.startsWith("learn") }, listOf("learn(they're,prev=,selected=false)"))
            eq("connector one word of context", k.committedWords.toList(), listOf("they're"))
        }
        run {
            val k = ime(); k.type("they"); k.apostrophe('’'); k.type("re"); k.separator(" ")
            eq("curly apostrophe stays in the field", k.ed.toString(), "they’re ")
            eq("curly apostrophe learned straight", k.committedWords.toList(), listOf("they're"))
        }
        run {
            val k = ime(); k.type("aight"); k.apostrophe(); k.separator(" ")
            eq("closing quote not corrected", k.ed.toString(), "aight' ")
            eq("closing quote not learned", k.committedWords.toList(), listOf("aight"))
        }
        run {
            val k = ime(); k.apostrophe(); k.type("hello")
            eq("opening quote is a separator", k.ed.toString(), "'hello")
            eq("opening quote leaves the word composing", k.composing.toString(), "hello")
        }
        run {
            val k = ime(); k.type("don"); k.apostrophe(); k.type("t"); k.separator(" "); k.backspace()
            eq("back into a contraction", k.composing.toString(), "don't")
            eq("back into a contraction pops it", k.committedWords.toList(), emptyList<String>())
            eq("back into a contraction provenance", k.session.provenance, Provenance.RESUMED)
        }
        run {
            val k = ime(); k.ed.commit("hello\n"); k.ed.commit("well "); k.backspace()
            eq("word after a line break re-enters", k.composing.toString(), "well")
        }
        run {
            val k = ime(); k.ed.commit("well hello "); k.ed.cursor = 8; k.backspace()
            eq("text after the caret stays committed", k.composing.toString(), "")
            eq("text after the caret loses one letter only", k.ed.toString(), "well helo ")
        }
        run {
            val k = ime(); k.ed.commit("hello '"); k.ed.commit("well "); k.backspace()
            eq("opening quote keeps the word out", k.composing.toString(), "")
            eq("opening quote keeps the word", k.ed.toString(), "hello 'well")
        }
        eq("stem of a word", WordShape.stem("dogs'"), "dogs")
        eq("stem keeps inner apostrophes", WordShape.stem("don't"), "don't")
        eq("context form drops punctuation", WordShape.contextForm("don’t,"), "don't")
        check("a digit continues a word", WordShape.continuesWord('1'))
        check("a space does not continue a word", !WordShape.continuesWord(' '))
        check("nothing does not continue a word", !WordShape.continuesWord(null))
    }

    /** The word left composing with no commit, the send button being the common case. */
    fun abandoned() {
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.log.clear(); k.hide()
            check("undo left standing at hide is kept", k.memory.isProtected("aight"))
            eq("undo left standing at hide bans the fix", k.memory.bannedFixes("aight"), setOf("right"))
            check("undo left standing at hide teaches nothing", k.log.none { it.startsWith("learn") }, "${k.log}")
            eq("undo left standing at hide counts once", k.refusals, 1)
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.externalCursor(0)
            check("undo left standing at a tap away is kept", k.memory.isProtected("aight"))
        }
        run {
            val k = ime(); k.type("aight"); k.hide()
            check("a plain word left composing keeps nothing", !k.memory.isProtected("aight"))
            eq("a plain word left composing leaves no record", k.memory.keptCount("aight") + k.memory.softCount("aight"), 0)
            eq("a plain word left composing counts nothing", k.refusals, 0)
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.wordDelete(); k.hide()
            check("undo then erase by hold keeps nothing", !k.memory.isProtected("aight"))
        }
        run {
            val k = ime(); k.type("hey"); k.separator(" "); k.type("aight"); k.separator(" "); k.log.clear()
            k.backspace(); k.backspace(); k.hide()
            check("undo then one more letter gone keeps nothing", !k.memory.isProtected("aigh"))
            check("undo then one more letter gone leaves the word alone", !k.memory.isProtected("aight"))
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.type("hello"); k.separator(" "); k.backspace(); k.hide()
            check("a plain word re-entered at hide keeps nothing", !k.memory.isProtected("hello"))
        }
    }

    /** Erasing what the keyboard has learned, and the revert counter. */
    fun eraseAndCount() {
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.separator(" ")
            check("before the erase the word is protected", k.memory.isProtected("aight"))
            k.erase()
            check("the erase forgets the refusal", !k.memory.isProtected("aight"))
            eq("the erase forgets the ban", k.memory.bannedFixes("aight"), emptySet<String>())
            check("the erase leaves nothing to write back", !k.memory.dirty)
            k.type("aight"); k.separator(" ")
            eq("after the erase the word is corrected again", k.ed.toString(), "aight right ")
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.log.clear(); k.erase(); k.hide()
            check("the erase stops a pending fix teaching", k.log.none { it.startsWith("learn") || it.startsWith("noteTypo") }, "${k.log}")
            k.backspace()
            eq("the erase ends the undo", k.composing.toString(), "right")
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.type("hello"); k.separator(" ")
            eq("a correction that stands counts no refusal", k.refusals, 0)
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.separator(" ")
            eq("undo then commit counts once", k.refusals, 1)
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" ")
            k.chip("aight")
            eq("the chip counts once", k.refusals, 1)
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.wordDelete(); k.type("aight"); k.separator(" ")
            eq("erase and retype counts once", k.refusals, 1)
        }
        run {
            val k = ime(); k.type("hey"); k.separator(" "); k.type("aight"); k.separator(" "); k.type("hello"); k.separator(" ")
            k.backspace(); k.wordDelete(); k.wordDelete(); k.type("aight"); k.separator(" ")
            eq("erase after it stood and retype counts once", k.refusals, 1)
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.backspace(); k.type("t"); k.separator(" ")
            eq("undo, one letter off and back counts once", k.refusals, 1)
        }
        run {
            val k = ime(); k.type("aight"); k.separator(","); k.separator(" "); k.backspace()
            eq("past a typed separator there is no undo", k.ed.toString(), "right,")
            eq("past a typed separator nothing is refused", k.refusals, 0)
        }
        run {
            val k = ime(); k.type("aight"); k.separator(","); k.separator(" ")
            check("past a typed separator there is no chip", k.session.lastFix == null)
        }
    }

    private fun words(k: FakeIme, vararg ws: String) {
        for (w in ws) { k.type(w); k.separator(" ") }
    }

    /** The same typo again after the same word, with the first fix left standing, refuses nothing. */
    fun repeatedTypos() {
        run {
            val k = ime(); words(k, "for", "teh", "cat", "for", "teh")
            eq("repeat corrected like the first", k.ed.toString(), "for the cat for the ")
            eq("repeat counts no refusal", k.refusals, 0)
            eq("repeat bans nothing", k.memory.bannedFixes("teh"), emptySet<String>())
            eq("repeat leaves no soft refusal", k.memory.softCount("teh"), 0)
            check("repeat takes nothing back", k.log.none { it.startsWith("unlearn") || it.startsWith("clearTypo") }, "${k.log}")
            check("repeat does not learn the typo", k.log.none { it.startsWith("learn(teh") }, "${k.log}")
        }
        run {
            val k = ime(); words(k, "i", "dont", "know", "and", "i", "dont", "care", "if", "i", "dont")
            eq("repeat many times", k.ed.toString(), "I don't know and I don't care if I don't ")
            check("repeat never protects", !k.memory.isProtected("dont"))
        }
        run {
            val k = ime(); words(k, "for", "teh", "cat", "for", "teh"); k.wordDelete()
            eq("second fix erased", k.ed.toString(), "for the cat for ")
            k.type("teh"); k.separator(" ")
            eq("retyped after its fix was erased stays", k.ed.toString(), "for the cat for teh ")
            eq("retyped after its fix was erased is one soft refusal", k.memory.softCount("teh"), 1)
            eq("retyped after its fix was erased counts once", k.refusals, 1)
        }
        run {
            val k = ime(); words(k, "for", "teh"); k.letter('x'); k.backspace()
            k.backspace()
            eq("gone back into the fix", k.composing.toString(), "the")
            k.separator(" ")
            words(k, "for", "teh")
            eq("a fix gone back into and kept was not erased", k.ed.toString(), "for the for the ")
            eq("a fix gone back into and kept refuses nothing", k.refusals, 0)
        }
        run {
            val k = ime(); words(k, "for", "teh"); k.letter('x'); k.backspace()
            k.backspace(); repeat(3) { k.backspace() }
            eq("gone back into the fix and erased it", k.ed.toString(), "for ")
            k.type("teh"); k.separator(" ")
            eq("a fix erased letter by letter is refused when retyped", k.ed.toString(), "for teh ")
            eq("a fix erased letter by letter is one soft refusal", k.memory.softCount("teh"), 1)
        }
        run {
            val k = ime(); words(k, "thisis"); k.wordDelete()
            eq("a split fix goes last word first", k.ed.toString(), "this ")
            k.wordDelete()
            eq("a split fix gone", k.ed.toString(), "")
            k.type("thisis"); k.separator(" ")
            eq("a split fix erased is refused when retyped", k.ed.toString(), "thisis ")
            eq("a split fix erased is one soft refusal", k.memory.softCount("thisis"), 1)
            check("a split fix erased teaches nothing", k.log.none { it.startsWith("learn(this,") || it.startsWith("learn(is,") }, "${k.log}")
        }
    }

    /** What the user changes by hand is theirs, whatever it started as. */
    fun handEdits() {
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.wordDelete(); k.type("aight")
            eq("retype is a ring hit", k.input("aight", ' ').provenance, Provenance.REVERTED)
            k.backspace()
            eq("a backspace in it makes it an edit", k.session.provenance, Provenance.EDITED)
            eq("an edit offers no refused spelling", k.session.refusedRaw, null)
            k.letter('z')
            eq("and it stays an edit", k.input("aighz", ' ').provenance, Provenance.EDITED)
            k.separator(" ")
            eq("an edited retype is not corrected", k.ed.toString(), "aighz ")
            eq("an edited retype refuses nothing", k.memory.softCount("aighz") + k.memory.softCount("aight"), 0)
        }
        // A slip made while changing a word after an undo stays this once, and is not chosen.
        run {
            val k = ime(); k.evidence = EV + ("tez" to Ev(listOf(cand("ten", 0.5, 3.0))))
            k.type("teh"); k.separator(" "); k.backspace(); k.backspace(); k.letter('z'); k.separator(" ")
            eq("a slip after an undo stays", k.ed.toString(), "tez ")
            check("a slip is learned normally", k.log.contains("learn(tez,prev=,selected=false)"), "${k.log}")
            check("a slip is not protected", !k.memory.isProtected("tez"))
            eq("a slip keeps the ban on what was refused", k.memory.bannedFixes("teh"), setOf("the"))
            k.type("tez"); k.separator(" ")
            eq("a different fix may come next time", k.ed.toString(), "tez ten ")
            k.backspace(); k.separator(" ")
            eq("one more undo", k.ed.toString(), "tez tez ")
            check("one more undo protects it", k.memory.isProtected("tez"))
        }
    }

    /** The app calls setText with the keyboard up, the way many chat apps clear the field after Send. */
    fun appRestarts() {
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.appSetsText("")
            eq("send after an undo clears the field", k.ed.toString(), "")
            check("send after an undo is kept", k.memory.isProtected("aight"))
            eq("send after an undo bans the fix", k.memory.bannedFixes("aight"), setOf("right"))
            check("send after an undo is stored", k.persisted?.contains("aight=1/0") == true, "${k.persisted}")
            eq("send after an undo counts once", k.refusals, 1)
            nowMs += 600_000
            words(k, "ok", "aight")
            eq("send after an undo holds in the next message", k.ed.toString(), "ok aight ")
        }
        run {
            val k = ime(); k.type("aight"); k.appSetsText("")
            check("send of a plain word keeps nothing", !k.memory.isProtected("aight"))
            eq("send of a plain word is logged", k.sentMessages.toList(), listOf("aight"))
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" ")
            check("the chip's correction is there", k.session.lastFix != null)
            k.appSetsText("")
            check("send takes the chip's correction away", k.session.lastFix == null)
            k.backspace()
            eq("nothing to undo in the empty field", k.ed.toString(), "")
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" ")
            k.appSetsText("right ")
            k.backspace()
            eq("a restart that leaves the text keeps the undo", k.ed.toString(), "aight")
        }
        run {
            val k = ime(); words(k, "hello", "world"); k.appSetsText("")
            words(k, "next", "one"); k.type("more"); k.appSetsText("")
            eq("messages stay apart, tail included", k.sentMessages.toList(), listOf("hello world", "next one more"))
            eq("nothing left over", k.messageWords.toList(), emptyList<String>())
        }
        run {
            val k = ime(); words(k, "hello"); k.appSetsText("hello ")
            eq("a restart that leaves the text is not a message", k.sentMessages.toList(), emptyList<String>())
        }
    }

    /** A field holding [text] when the keyboard comes up on it. Its last run stays off the trail, and the rest is the context. */
    private fun cameBackTo(text: String): FakeIme {
        val k = ime()
        k.known = KNOWN + setOf("see", "you", "tomorrow", "there")
        val words = text.split(' ')
        k.appSetsText(text)
        k.reseed(listOf(".") + words.dropLast(1), run = words.last())
        return k
    }

    /** A glide is a word of its own. A bar tap, row 13, still replaces what is composing. */
    fun glides() {
        run {
            val k = ime(); words(k, "for", "teh"); k.backspace(); k.glide("cat")
            eq("glide after an undo keeps the word", k.ed.toString(), "for teh cat ")
            eq("glide after an undo keeps it once", k.memory.keptCount("teh"), 1)
            eq("glide after an undo bans the fix", k.memory.bannedFixes("teh"), setOf("the"))
            check("glide after an undo follows the kept word", k.log.contains("learn(cat,prev=teh,selected=false)"), "${k.log}")
            eq("glide after an undo, context", k.committedWords.toList(), listOf("for", "teh", "cat"))
            nowMs += 600_000
            words(k, "so", "teh")
            eq("glide after an undo holds", k.ed.toString(), "for teh cat so teh ")
        }
        run {
            val k = ime(); k.known = KNOWN + "hi"; k.type("hi"); k.glide("there")
            eq("glide after a tapped word keeps it", k.ed.toString(), "hi there ")
            check("a tapped word the dictionary knows is learned", k.log.contains("learn(hi,prev=,selected=false)"), "${k.log}")
            check("the glide's word follows it", k.log.contains("learn(there,prev=hi,selected=false)"), "${k.log}")
            eq("glide after a tapped word, context", k.committedWords.toList(), listOf("hi", "there"))
            eq("glide after a tapped word, the message", k.messageWords.toList(), listOf("hi", "there"))
            k.backspace()
            eq("one backspace takes the glide back", k.ed.toString(), "hi ")
            eq("one backspace takes it off the trail", k.committedWords.toList(), listOf("hi"))
        }
        run {
            val k = ime(); k.type("zorp"); k.glide("there")
            eq("a tapped typo before a glide stays in the field", k.ed.toString(), "zorp there ")
            check("a tapped typo before a glide is not learned", k.log.none { it.startsWith("learn(zorp") }, "${k.log}")
            eq("a tapped typo before a glide is still context", k.committedWords.toList(), listOf("zorp", "there"))
            check("the glide's word follows the typo", k.log.contains("learn(there,prev=zorp,selected=false)"), "${k.log}")
        }
        run {
            val k = ime(); k.type("hel"); k.glideStarts()
            eq("while the finger moves the word stays composing", k.composing.toString(), "hel")
            eq("while the finger moves the field keeps the word", k.ed.toString(), "hel")
            eq("the decode reads the composing word as the one before", k.glidePrev1, "hel")
            eq("while the finger moves nothing is learned", k.log, emptyList<String>())
            eq("while the finger moves nothing reaches the trail", k.committedWords.toList(), emptyList<String>())
            k.glideFallsBackToTap('l')
            eq("a glide falling back to a tap leaves the letters together", k.ed.toString(), "hell")
            eq("a glide falling back to a tap leaves one word", k.composing.toString(), "hell")
            eq("a glide falling back to a tap learns no fragment", k.log, emptyList<String>())
            k.type("o"); k.separator(" ")
            eq("the word goes on after the fallback", k.ed.toString(), "hello ")
            eq("the whole word is the context", k.committedWords.toList(), listOf("hello"))
            eq("the whole word is what is learned", k.log.filter { it.startsWith("learn") }, listOf("learn(hello,prev=,selected=false)"))
        }
        run {
            val k = ime(); k.type("for"); k.separator(" "); k.glide("cat")
            eq("glide after a space adds no second one", k.ed.toString(), "for cat ")
        }
        // A slide up from the keys onto the bar.
        run {
            val k = ime(); k.type("hel"); k.slideUpPick("hello")
            eq("a slide up onto the bar completes the word", k.ed.toString(), "hello ")
            eq("a slide up onto the bar leaves one word on the trail", k.committedWords.toList(), listOf("hello"))
            check("a slide up onto the bar learns no fragment", k.log.none { it.startsWith("learn(hel,") }, "${k.log}")
            check("a slide up onto the bar counts as a bar tap", k.log.contains("learn(hello,prev=,selected=true)"), "${k.log}")
        }
        run {
            val k = ime(); words(k, "for"); k.slideUpPick("cat")
            eq("a slide up with nothing composing adds the word", k.ed.toString(), "for cat ")
        }
        // An address in the making is not a sentence.
        run {
            val k = ime(); k.addressField = true; k.type("exam"); k.glide("ple")
            eq("no space inside an address", k.ed.toString(), "example")
            eq("nothing in an address field is learned", k.log.filter { it.startsWith("learn") }, emptyList<String>())
            eq("nothing in an address field reaches the trail", k.committedWords.toList(), emptyList<String>())
        }
        run {
            val k = ime(); k.type("john@gm"); k.glide("mail")
            eq("no space after text holding @", k.ed.toString(), "john@gmmail ")
            eq("text holding @ teaches nothing", k.log.filter { it.startsWith("learn") }, emptyList<String>())
        }
        // A browser's bar is a URL field that takes searches too.
        run {
            val k = ime(); k.urlField = true; k.glide("weather"); k.glide("tomorrow")
            eq("two glides in a URL field stay apart", k.ed.toString(), "weather tomorrow ")
            eq("nothing in a URL field is learned", k.log.filter { it.startsWith("learn") }, emptyList<String>())
            eq("nothing in a URL field reaches the trail", k.committedWords.toList(), emptyList<String>())
        }
        run {
            val k = ime(); k.urlField = true; k.glide("weather"); k.type("to")
            eq("a glide then typed letters stay apart in a URL field", k.ed.toString(), "weather to")
        }
        run {
            val k = ime(); k.urlField = true; k.type("exam"); k.glide("ple")
            eq("a word composing in a URL field is glued", k.ed.toString(), "example")
        }
        run {
            val k = ime(); k.urlField = true; k.ed.commit("example."); k.glide("com")
            eq("after a dot in the run a URL field glues", k.ed.toString(), "example.com")
            k.chip("au")
            eq("after a dot in the run a bar word glues too", k.ed.toString(), "example.comau")
        }
        run {
            val k = ime(); k.urlField = true; k.ed.commit("example.com weather"); k.glide("today")
            eq("a dot behind a space is not in the run", k.ed.toString(), "example.com weather today ")
        }
        run {
            val k = ime(); k.urlField = true; k.glide("weather"); k.chip("today")
            eq("a bar word after a glide in a URL field stays apart", k.ed.toString(), "weather today ")
            k.chip("tomorrow")
            eq("a second bar word in a URL field stays apart", k.ed.toString(), "weather today tomorrow ")
        }
        run {
            val k = ime(); k.urlField = true; k.ed.commit("weather"); k.chip("today")
            eq("a bar word after a lone word in a URL field gets its lead", k.ed.toString(), "weather today ")
        }
        run {
            val k = ime(); k.addressField = true; k.glide("john"); k.glide("smith")
            eq("two glides in an email field stay glued", k.ed.toString(), "johnsmith")
        }
        run {
            val k = ime(); k.urlField = true; k.glide("weather"); k.backspace()
            eq("a glide wiped in a URL field goes", k.ed.toString(), "")
            check("a glide never learned gives nothing back", k.log.none { it.startsWith("wipeUnlearn") }, "${k.log}")
        }
        // A message left with no space after its last word: the read of the field passes over that run, and the trail
        // ends a word early, so a word landing there would be taught as following the wrong one.
        run {
            val k = cameBackTo("see you tomorrow")
            k.glideStarts()
            eq("a glide follows the word the caret touches", listOf(k.glidePrev1, k.glidePrev2), listOf<String?>("tomorrow", "you"))
            k.glideLands("there")
            eq("the glide lands apart from that word", k.ed.toString(), "see you tomorrow there ")
            check("the glide is taught after that word", k.log.contains("learn(there,prev=tomorrow,selected=false)"), "${k.log}")
            check("the false pair is gone", k.log.none { it.startsWith("learn(there,prev=you") }, "${k.log}")
            eq("the word it ended joins the trail", k.committedWords.toList(), listOf(".", "see", "you", "tomorrow", "there"))
            eq("the word it ended was not typed here", k.messageWords.toList(), listOf("there"))
            eq("nothing is kept once it has ended", k.keptRun, null)
        }
        run {
            val k = cameBackTo("see you tomor")
            k.glide("there")
            eq("a glide lands apart from a half word", k.ed.toString(), "see you tomor there ")
            eq("nothing is taught across a half word", k.log.filter { it.startsWith("learn") }, listOf("learn(there,prev=,selected=false)"))
            eq("a half word does not join the trail", k.committedWords.toList(), listOf("there"))
        }
        run {
            val k = cameBackTo("see you tomorrow")
            k.chip("there")
            eq("a bar word lands apart from that word", k.ed.toString(), "see you tomorrow there ")
            check("a bar word is taught after that word", k.log.contains("learn(there,prev=tomorrow,selected=true)"), "${k.log}")
        }
        run {
            val k = cameBackTo("see you tomor")
            k.chip("there")
            check("nothing is taught across a half word by a bar word", k.log.contains("learn(there,prev=,selected=true)"), "${k.log}")
        }
        run {
            val k = cameBackTo("see you tomorrow")
            k.separator(" ")
            k.glide("there")
            check("a Space ends the word, and the next follows it", k.log.contains("learn(there,prev=tomorrow,selected=false)"), "${k.log}")
        }
        run {
            val k = cameBackTo("see you tomor")
            k.separator(" ")
            k.glide("there")
            check("a Space ends a half word, and nothing is taught across it", k.log.contains("learn(there,prev=,selected=false)"), "${k.log}")
        }
        run {
            val k = cameBackTo("see you tomorrow")
            k.separator(".")
            eq("a full stop goes on after the word it ended", k.committedWords.toList(), listOf(".", "see", "you", "tomorrow", "."))
        }
        run {
            val k = cameBackTo("see you tomorrow")
            k.autoSpace = false
            k.glide("there")
            eq("with auto-space off a glide glues to the run", k.ed.toString(), "see you tomorrowthere")
            check("glued, it does not end the run, and follows nothing", k.log.contains("learn(there,prev=,selected=false)"), "${k.log}")
            check("the run does not join the trail", "tomorrow" !in k.committedWords, "${k.committedWords}")
        }
        run {
            val k = cameBackTo("see you tomorrow")
            k.externalCursor(3)
            eq("a moved caret drops the run", k.keptRun, null)
            k.glideStarts()
            eq("a moved caret follows the trail", k.glidePrev1, "you")
        }
        run {
            val k = cameBackTo("see you tomorrow")
            k.type("s")
            eq("a typed letter drops the run", k.keptRun, null)
        }
        run {
            val k = cameBackTo("see you tomorrow")
            k.showNewField()
            eq("a show drops the run", k.keptRun, null)
        }
        run {
            val k = cameBackTo("see you tomorrow")
            k.selectThenBackspace(8, 16)
            eq("a selection deleted drops the run", k.keptRun, null)
            eq("a selection deleted leaves the field", k.ed.toString(), "see you ")
        }
        run {
            val k = ime(); k.autoSpace = false; k.known = KNOWN + "hi"; k.type("hi"); k.glide("there")
            eq("with auto-space off a tapped word and a glide get no space", k.ed.toString(), "hithere")
            k.glide("again")
            eq("with auto-space off two glides get no space either", k.ed.toString(), "hithereagain")
        }
        // What the decode is told, and where the spaces go.
        run {
            val k = ime(); words(k, "so", "then"); k.glideStarts()
            eq("a glide reads the last two words of the trail", listOf(k.glidePrev1, k.glidePrev2), listOf<String?>("then", "so"))
        }
        run {
            val k = ime(); words(k, "so"); k.type("then"); k.glideStarts()
            eq("a word composing comes first, then the trail's last", listOf(k.glidePrev1, k.glidePrev2), listOf<String?>("then", "so"))
        }
        run {
            val k = ime(); k.type("hello"); k.separator(","); k.glide("world")
            eq("a glide after a comma gets a space before it", k.ed.toString(), "hello, world ")
        }
        run {
            val k = ime(); k.type("hello"); k.separator(","); k.chip("how")
            eq("a bar word after a comma gets a space before it", k.ed.toString(), "hello, how ")
        }
        run {
            val k = ime(); words(k, "see", "soon"); k.externalCursor(4); k.glide("you")
            eq("a glide before a word gets a space after it, and none before", k.ed.toString(), "see you soon ")
        }
        // Taking back a wrong glide.
        run {
            val k = ime(); words(k, "so", "a"); k.glide("have"); k.backspace()
            check("a wiped glide takes back the pair it was learned in", k.log.contains("wipeUnlearn(have,prev=a,prev2=so)"), "${k.log}")
        }
        // Backspace over what ended a sentence.
        run {
            val k = ime(); k.type("word"); k.enter(actionKey = false); k.backspace()
            eq("Backspace over a line break goes back into the word", k.composing.toString(), "word")
            eq("Backspace over a line break takes the sentence mark", k.committedWords.toList(), emptyList<String>())
            k.separator(" ")
            eq("the word comes back once", k.committedWords.toList(), listOf("word"))
        }
        run {
            val k = ime(); k.type("fine"); k.separator("."); k.enter(actionKey = false); k.backspace()
            eq("a full stop still standing keeps the sentence mark", k.committedWords.toList(), listOf("fine", FakeIme.MARK))
        }
        // Backspace with text selected removes exactly that text.
        run {
            val k = ime(); words(k, "hello", "world"); k.selectThenBackspace(0, 12)
            eq("select all then Backspace empties the field", k.ed.toString(), "")
            eq("select all then Backspace clears the trail", k.committedWords.toList(), emptyList<String>())
            eq("select all then Backspace clears the message", k.messageWords.toList(), emptyList<String>())
            eq("a message selected and taken back is never logged", k.sentMessages.toList(), emptyList<String>())
        }
        run {
            val k = ime(); words(k, "hello", "world"); k.selectionReachesTheService(0, 12)
            eq("select all alone sends nothing", k.sentMessages.toList(), emptyList<String>())
            eq("select all alone leaves the message as it was", k.messageWords.toList(), listOf("hello", "world"))
        }
        run {
            val k = ime(); k.type("hello"); k.selectThenBackspace(0, 5)
            eq("one word selected and taken back is never logged", k.sentMessages.toList(), emptyList<String>())
        }
        run {
            val k = ime(); words(k, "hello", "world"); k.selectThenBackspace(6, 11)
            eq("a selected word goes and the space before it stays", k.ed.toString(), "hello  ")
            eq("a selected word leaves the trail", k.committedWords.toList(), listOf("hello"))
        }
        run {
            val k = ime(); k.type("hello"); k.separator(","); k.separator(" "); words(k, "world"); k.selectThenBackspace(7, 12)
            eq("a selection after punctuation goes alone", k.ed.toString(), "hello,  ")
        }
        run {
            val k = ime(); k.type("hello"); k.separator("."); k.selectThenBackspace(0, 6)
            eq("a selected sentence end takes its mark", k.committedWords.toList(), emptyList<String>())
        }
        run {
            val k = ime(); words(k, "for", "teh")
            eq("the fix to be selected", k.ed.toString(), "for the ")
            k.selectThenBackspace(4, 7)
            eq("the selected fix goes", k.ed.toString(), "for  ")
            k.log.clear()
            k.type("teh"); k.separator(" ")
            eq("retyping a fix removed by selecting it stays", k.ed.toString(), "for teh  ")
            check("a fix removed by selecting it never stood", k.log.none { it.startsWith("learn(the") || it.startsWith("noteTypo") }, "${k.log}")
        }
        run {
            val k = ime(); words(k, "for", "teh"); k.selectThenBackspace(0, 8); k.log.clear()
            k.type("cat"); k.separator(" ")
            check("a fix taken out with what is around it teaches nothing", k.log.none { it.startsWith("learn(the") || it.startsWith("noteTypo") }, "${k.log}")
        }
        run {
            val k = ime(); k.type("ok"); k.appClearsFieldInPlace()
            eq("a one-word reply sent with no restart is logged", k.sentMessages.toList(), listOf("ok"))
        }
    }

    /** Removing a word, or picking the fix that was refused, takes the refusal back. */
    fun liftingARefusal() {
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.separator(" ")
            check("kept before the removal", k.memory.isProtected("aight"))
            k.session.onWordForgotten("Aight")
            check("a removed word is not protected", !k.memory.isProtected("aight"))
            eq("a removed word has no bans", k.memory.bannedFixes("aight"), emptySet<String>())
            check("a removed word leaves the store", k.persisted?.contains("aight") == false, "${k.persisted}")
            nowMs += 600_000
            k.type("aight"); k.separator(" ")
            eq("a removed word is corrected again", k.ed.toString(), "aight right ")
        }
        run {
            val k = ime(incognito = true); k.type("aight"); k.separator(" "); k.backspace(); k.separator(" ")
            check("kept in a private field", k.session.isProtected("aight"))
            k.session.onWordForgotten("aight")
            check("a removed word goes from a private field's memory too", !k.session.isProtected("aight"))
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.wordDelete(); k.type("aight"); k.separator(" ")
            eq("banned before the pick", k.memory.bannedFixes("aight"), setOf("right"))
            nowMs += 600_000
            k.log.clear(); k.type("aight"); k.chip("right")
            eq("picking the refused fix lifts its ban", k.memory.bannedFixes("aight"), emptySet<String>())
            eq("picking the refused fix leaves the soft refusal", k.memory.softCount("aight"), 1)
            check("picking the refused fix is stored", k.log.contains("persist"), "${k.log}")
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.wordDelete(); k.type("aight"); k.separator(" ")
            nowMs += 600_000
            k.log.clear(); k.type("aight"); k.chip("light")
            eq("picking another word leaves the ban", k.memory.bannedFixes("aight"), setOf("right"))
            check("picking another word writes nothing", !k.log.contains("persist"), "${k.log}")
        }
    }

    /** One keep can be a slip, so it ages out. Two keeps slide while the word is in use. */
    fun expiry() {
        val day = 86_400_000L
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.separator(" ")
            nowMs += 80 * day
            k.type("aight"); k.separator(" ")
            eq("one keep holds inside its 90 days", k.ed.toString(), "aight aight ")
            nowMs += 11 * day
            k.type("aight"); k.separator(" ")
            eq("one keep ages out even in use", k.ed.toString(), "aight aight right ")
            k.backspace(); k.separator(" ")
            check("one more undo protects it again", k.memory.isProtected("aight"))
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.separator(" ")
            k.type("aight"); k.chip("aight")
            repeat(3) {
                nowMs += 80 * day
                k.type("aight"); k.separator(" ")
            }
            check("two keeps slide while in use", k.memory.isProtected("aight"))
        }
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.wordDelete(); k.type("aight"); k.separator(" ")
            k.wordDelete(); k.type("aight"); k.separator(" ")
            check("two soft refusals protect", k.memory.isProtected("aight"))
            nowMs += 80 * day
            k.type("aight"); k.separator(" ")
            nowMs += 11 * day
            check("two soft refusals do not slide", !k.memory.isProtected("aight"))
        }
    }

    /** The emoji panel commits what was composing, and a sentence mark on the trail stays in its place. */
    fun emojiAndSentenceMarks() {
        run {
            val k = ime(); k.type("aight"); k.separator(" "); k.backspace(); k.emoji("\uD83D\uDE00")
            eq("an emoji after an undo keeps the word", k.ed.toString(), "aight\uD83D\uDE00")
            check("an emoji after an undo records the refusal", k.memory.isProtected("aight"))
            check("an emoji after an undo learns the word", k.log.contains("learn(aight,prev=,selected=true)"), "${k.log}")
            eq("an emoji after an undo, context", k.committedWords.toList(), listOf("aight"))
        }
        run {
            val k = ime(); k.type("teh"); k.separator("."); eq("full stop fix", k.ed.toString(), "the.")
            eq("full stop fix, context", k.committedWords.toList(), listOf("the", FakeIme.MARK))
            k.backspace()
            eq("full stop undo restores exactly", k.ed.toString(), "teh.")
            eq("full stop undo keeps the mark last", k.committedWords.toList(), listOf("teh", FakeIme.MARK))
            eq("full stop undo, typing log", k.messageWords.toList(), listOf("teh"))
            k.separator(" "); k.log.clear(); words(k, "next")
            check("the next word follows the mark", k.log.contains("learn(next,prev=${FakeIme.MARK},selected=false)"), "${k.log}")
        }
        run {
            val k = ime(); k.type("for"); k.separator(" "); k.type("teh"); k.separator("."); k.chip("teh")
            eq("full stop chip restores exactly", k.ed.toString(), "for teh.")
            eq("full stop chip keeps the mark last", k.committedWords.toList(), listOf("for", "teh", FakeIme.MARK))
        }
        run {
            val k = ime(); k.type("for"); k.separator(" "); k.type("teh"); k.separator("."); k.wordDelete()
            eq("a word delete takes the mark with the word", k.committedWords.toList(), listOf("for"))
            k.type("teh"); k.separator(" ")
            eq("and a retype there is a refusal", k.ed.toString(), "for teh ")
            eq("and a retype there is one soft refusal", k.memory.softCount("teh"), 1)
        }
    }

    /**
     * The typo typed again where its fix was erased, with no undo. A fix that never stood is refused at
     * once. One that stood had been moved on from, so the first time is only counted.
     */
    fun retypes() {
        // A hold of Delete to redo the sentence, and the same thumb makes the same typo.
        run {
            val k = ime(); words(k, "for", "teh", "cat"); k.log.clear()
            k.wordDelete(); k.wordDelete()
            eq("habit, erased", k.ed.toString(), "for ")
            words(k, "teh")
            eq("habit stays this once", k.ed.toString(), "for teh ")
            eq("habit bans nothing", k.memory.bannedFixes("teh"), emptySet<String>())
            check("habit is not learned", k.log.none { it.startsWith("learn(teh") }, "${k.log}")
            eq("habit is counted", k.memory.softCount("teh"), 1)
            check("habit does not protect", !k.memory.isProtected("teh"))
            check("habit takes back what the fix taught", k.log.contains("unlearn(the)"), "${k.log}")
            eq("habit stays on the trail", k.committedWords.toList(), listOf("for", "teh"))
            eq("habit counts one refusal", k.refusals, 1)
            check("habit count is stored", k.persisted?.contains("teh=0/1") == true, "${k.persisted}")
            words(k, "dog", "and", "teh")
            eq("habit, the fix still fires elsewhere", k.ed.toString(), "for teh dog and the ")
            words(k, "cat"); k.log.clear()
            k.wordDelete(); k.wordDelete(); words(k, "teh")
            eq("habit again stays", k.ed.toString(), "for teh dog and teh ")
            eq("habit again bans the fix", k.memory.bannedFixes("teh"), setOf("the"))
            eq("habit again is the second count", k.memory.softCount("teh"), 2)
            check("habit again protects", k.memory.isProtected("teh"))
            check("habit again is learned", k.log.contains("learn(teh,prev=and,selected=false)"), "${k.log}")
            eq("habit again counts its own refusal", k.refusals, 2)
            nowMs += 600_000
            words(k, "so", "teh")
            eq("habit again holds elsewhere", k.ed.toString(), "for teh dog and teh so teh ")
        }
        // The same again, after the retyped word itself was erased and typed once more.
        run {
            val k = ime(); words(k, "for", "teh", "cat"); k.wordDelete(); k.wordDelete(); words(k, "teh"); k.log.clear()
            k.wordDelete(); words(k, "teh")
            eq("retyped twice stays", k.ed.toString(), "for teh ")
            eq("retyped twice bans the fix", k.memory.bannedFixes("teh"), setOf("the"))
            eq("retyped twice is two counts", k.memory.softCount("teh"), 2)
            check("retyped twice is learned", k.log.contains("learn(teh,prev=for,selected=false)"), "${k.log}")
            eq("retyped twice is one correction refused", k.refusals, 1)
            check("what a fix taught goes back once", k.log.none { it.startsWith("unlearn") }, "${k.log}")
        }
        // Held Delete that goes letter by letter before it speeds up.
        run {
            val k = ime(); words(k, "for", "teh", "cat"); repeat(8) { k.backspace() }
            eq("letter by letter, erased", k.ed.toString(), "for ")
            words(k, "teh")
            eq("letter by letter stays this once", k.ed.toString(), "for teh ")
            eq("letter by letter bans nothing", k.memory.bannedFixes("teh"), emptySet<String>())
            eq("letter by letter is counted", k.memory.softCount("teh"), 1)
        }
        // A word delete straight after the fix is the owner's complaint: never fight that person.
        run {
            val k = ime(); words(k, "for", "aight"); k.log.clear(); k.wordDelete(); words(k, "aight")
            eq("instant retype stays", k.ed.toString(), "for aight ")
            eq("instant retype bans at once", k.memory.bannedFixes("aight"), setOf("right"))
            eq("instant retype is one soft refusal", k.memory.softCount("aight"), 1)
            check("instant retype is learned", k.log.contains("learn(aight,prev=for,selected=false)"), "${k.log}")
            check("instant retype has nothing to take back", k.log.none { it.startsWith("unlearn") || it.startsWith("clearTypo") }, "${k.log}")
            nowMs += 600_000
            words(k, "so", "aight")
            eq("instant retype holds elsewhere", k.ed.toString(), "for aight so aight ")
        }
        // A hand-typed word erased after an older fix of the same spelling, which is left standing.
        run {
            val k = ime(); words(k, "for", "teh", "cat", "for", "the", "dog")
            k.wordDelete(); k.wordDelete(); words(k, "teh")
            eq("older fix standing, retype stays", k.ed.toString(), "for the cat for teh ")
            eq("older fix standing bans nothing", k.memory.bannedFixes("teh"), emptySet<String>())
        }
        // A private field counts and bans the same way, and only for as long as the person is there.
        run {
            val k = ime(incognito = true); words(k, "for", "teh", "cat"); k.wordDelete(); k.wordDelete(); words(k, "teh")
            eq("private, first bans nothing", k.session.bannedFixes("teh"), emptySet<String>())
            k.wordDelete(); words(k, "teh")
            eq("private, second bans in the field", k.volatile.bannedFixes("teh"), setOf("the"))
            eq("private, durable memory untouched", k.memory.softCount("teh"), 0)
            eq("private, nothing persisted", k.persisted, null)
            eq("private, nothing taught at all", k.log, emptyList<String>())
        }
        // Send, hide or a tap instead of Space.
        run {
            val k = ime(); words(k, "for", "aight"); k.wordDelete(); k.type("aight"); k.log.clear(); k.appSetsText("")
            eq("send after a retype clears the field", k.ed.toString(), "")
            eq("send after a retype bans the fix", k.memory.bannedFixes("aight"), setOf("right"))
            eq("send after a retype is one soft refusal", k.memory.softCount("aight"), 1)
            eq("send after a retype is not a keep", k.memory.keptCount("aight"), 0)
            eq("send after a retype counts once", k.refusals, 1)
            check("send after a retype is stored", k.persisted?.contains("aight=0/1") == true, "${k.persisted}")
            check("send after a retype teaches nothing", k.log.none { it.startsWith("learn") }, "${k.log}")
            nowMs += 600_000
            words(k, "ok", "aight")
            eq("send after a retype holds in the next message", k.ed.toString(), "ok aight ")
        }
        run {
            val k = ime(); words(k, "for", "teh", "cat"); k.wordDelete(); k.wordDelete(); k.type("teh"); k.log.clear(); k.hide()
            eq("hide after a retype of a fix that stood bans nothing", k.memory.bannedFixes("teh"), emptySet<String>())
            eq("hide after a retype of a fix that stood is counted", k.memory.softCount("teh"), 1)
            check("hide after a retype takes back what the fix taught", k.log.contains("unlearn(the)"), "${k.log}")
            check("hide after a retype teaches nothing", k.log.none { it.startsWith("learn") }, "${k.log}")
            k.hide()
            eq("a word reported at hide and again at show counts once", k.memory.softCount("teh"), 1)
        }
        run {
            val k = ime(); words(k, "for", "aight"); k.wordDelete(); k.type("aight"); k.externalCursor(0)
            eq("a tap away after a retype bans the fix", k.memory.bannedFixes("aight"), setOf("right"))
        }
        run {
            val k = ime(); words(k, "for", "aight"); k.backspace(); k.wordDelete(); k.type("aight"); k.hide()
            eq("undo, erase and retype left at hide is a keep", k.memory.keptCount("aight"), 1)
            eq("undo, erase and retype left at hide counts once", k.refusals, 1)
        }
        run {
            val k = ime(); words(k, "for", "aight"); k.wordDelete(); k.type("aighty"); k.hide()
            eq("a longer word left at hide bans nothing", k.memory.bannedFixes("aight"), emptySet<String>())
            eq("a longer word left at hide counts nothing", k.refusals, 0)
        }
        run {
            val k = ime(); words(k, "for", "teh", "cat", "for"); k.type("teh"); k.hide()
            eq("the same typo left at hide with its fix standing refuses nothing", k.memory.softCount("teh"), 0)
        }
    }

    /** The person selects the last [selected] in the field, and the next letter or Backspace takes it out, as the service reports that. */
    private fun removeSelection(k: FakeIme, selected: String) {
        k.externalCursor(k.ed.text.lastIndexOf(selected) + selected.length)
        k.port.popContext(WordShape.contextForm(selected.trim()))
        k.session.onSelectionRemoved(selected)
        k.ed.deleteBefore(selected.length)
    }

    /** Selecting a fix and typing over it takes it out as surely as erasing it does. */
    fun selections() {
        run {
            val k = ime(); words(k, "for", "aight"); k.log.clear()
            removeSelection(k, "right"); words(k, "aight")
            eq("typed over at once stays", k.ed.toString(), "for aight  ")
            eq("typed over at once bans the fix", k.memory.bannedFixes("aight"), setOf("right"))
            eq("typed over at once is one soft refusal", k.memory.softCount("aight"), 1)
            eq("typed over at once counts once", k.refusals, 1)
            check("typed over at once, the fix teaches nothing", k.log.none { it.startsWith("learn(right") || it.startsWith("noteTypo") }, "${k.log}")
            eq("typed over at once, trail", k.committedWords.toList(), listOf("for", "aight"))
        }
        // Back in the sentence: the trail ends in a later word, so only the selection says where this is.
        run {
            val k = ime(); words(k, "for", "teh", "cat"); k.log.clear()
            removeSelection(k, "the"); words(k, "teh")
            eq("typed over once it stood stays", k.ed.toString(), "for teh  cat ")
            eq("typed over once it stood bans nothing", k.memory.bannedFixes("teh"), emptySet<String>())
            eq("typed over once it stood is counted", k.memory.softCount("teh"), 1)
            check("typed over once it stood takes back what it taught", k.log.contains("unlearn(the)"), "${k.log}")
            check("typed over once it stood is not learned", k.log.none { it.startsWith("learn(teh") }, "${k.log}")
        }
        run {
            val k = ime(); words(k, "for", "aight"); removeSelection(k, "right "); words(k, "aight")
            eq("a selection with its space, the retype stays", k.ed.toString(), "for aight ")
            eq("a selection with its space, the retype bans", k.memory.bannedFixes("aight"), setOf("right"))
        }
        run {
            val k = FakeIme(clock); k.evidence = mapOf("im" to Ev(closed = "I'm")); words(k, "im")
            eq("typed over a cased fix, setup", k.ed.toString(), "I'm ")
            removeSelection(k, "I'm"); words(k, "im")
            eq("typed over a cased fix stays", k.ed.toString(), "im  ")
            eq("typed over a cased fix bans it", k.memory.bannedFixes("im"), setOf("i'm"))
        }
        run {
            val k = ime(); words(k, "thisis"); removeSelection(k, "this is"); words(k, "thisis")
            eq("typed over a split fix stays", k.ed.toString(), "thisis  ")
            eq("typed over a split fix is one soft refusal", k.memory.softCount("thisis"), 1)
            eq("typed over a split fix bans it", k.memory.bannedFixes("thisis"), setOf("this is"))
            eq("typed over a split fix, trail", k.committedWords.toList(), listOf("thisis"))
        }
        // Backspace on the selection, then the typo: the same two cases.
        run {
            val k = ime(); words(k, "for", "aight"); k.selectThenBackspace(4, 9); words(k, "aight")
            eq("selected and deleted at once, the retype stays", k.ed.toString(), "for aight  ")
            eq("selected and deleted at once bans the fix", k.memory.bannedFixes("aight"), setOf("right"))
        }
        run {
            val k = ime(); words(k, "for", "teh", "cat"); k.selectThenBackspace(4, 7); words(k, "teh")
            eq("selected and deleted once it stood, the retype stays", k.ed.toString(), "for teh  cat ")
            eq("selected and deleted once it stood bans nothing", k.memory.bannedFixes("teh"), emptySet<String>())
            eq("selected and deleted once it stood is counted", k.memory.softCount("teh"), 1)
        }
        // Another word over the fix: it is gone, but no typo came back, so nothing is refused.
        run {
            val k = ime(); words(k, "for", "aight"); k.log.clear(); removeSelection(k, "right"); words(k, "well")
            eq("another word over the fix stays", k.ed.toString(), "for well  ")
            eq("another word over the fix refuses nothing", k.refusals, 0)
            eq("another word over the fix bans nothing", k.memory.bannedFixes("aight"), emptySet<String>())
            check("another word over the fix, the fix teaches nothing", k.log.none { it.startsWith("learn(right") || it.startsWith("noteTypo") }, "${k.log}")
        }
        // Only the word typed right there, and only over a fix.
        run {
            val k = ime(); words(k, "for", "teh", "cat"); removeSelection(k, "cat"); words(k, "teh")
            eq("a selection holding something else changes nothing", k.ed.toString(), "for the the  ")
            eq("a selection holding something else refuses nothing", k.refusals, 0)
        }
        run {
            val k = ime(); words(k, "for", "teh", "cat"); removeSelection(k, "the"); words(k, "well"); words(k, "teh")
            eq("the word after the one typed there is corrected", k.ed.toString(), "for well the  cat ")
        }
        run {
            val k = ime(); words(k, "for", "teh", "cat"); removeSelection(k, "the")
            k.externalCursor(k.ed.text.length); words(k, "teh")
            eq("moving away first, the typo is corrected", k.ed.toString(), "for  cat the ")
        }
    }

    fun runAll(): List<Check> {
        table(); gates(); memory(); fields(); wordShape(); abandoned(); eraseAndCount()
        retypes(); selections()
        repeatedTypos(); handEdits(); appRestarts(); glides(); liftingARefusal(); expiry(); emojiAndSentenceMarks()
        return checks
    }

    companion object {
        /** How many checks the suite held when its numbers were last proven. */
        const val EXPECTED_CHECKS = 719

        @JvmStatic
        fun main(args: Array<String>) {
            val all = Scenarios().runAll()
            val failed = all.filterNot { it.ok }
            failed.forEach { println("FAIL  ${it.name}  ${it.detail}") }
            println("checks=${all.size} failures=${failed.size}")
            if (failed.isNotEmpty() || all.size != EXPECTED_CHECKS) kotlin.system.exitProcess(1)
        }
    }
}
