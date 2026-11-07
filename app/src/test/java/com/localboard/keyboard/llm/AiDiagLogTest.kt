package com.localboard.keyboard.llm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDiagLogTest {
    private val chat = "Them: are you coming tonight"

    @After
    fun tidy() {
        AiDiagLog.lastPrompt = ""
        AiDiagLog.lastPromptPurpose = ""
        AiDiagLog.lastPromptAt = 0L
    }

    @Test
    fun aPromptIsKeptWordForWord() {
        AiDiagLog.recordPrompt("draft", chat)

        assertEquals(chat, AiDiagLog.lastPrompt)
        assertTrue(AiDiagLog.report("model", "screen").contains(chat))
    }

    /** Copy puts the report on the clipboard, for a bug report that could be pasted anywhere. */
    @Test
    fun aPromptNotToBeRememberedLeavesOnlyAPlaceholder() {
        AiDiagLog.recordPrompt("draft", chat, keep = false)

        assertEquals(AiDiagLog.PROMPT_NOT_KEPT, AiDiagLog.lastPrompt)
        val report = AiDiagLog.report("model", "screen")
        assertFalse(report.contains(chat))
        assertTrue(report.contains(AiDiagLog.PROMPT_NOT_KEPT))
    }

    /** The diagnostics must not go on showing the prompt before it, which is not what this draft saw. */
    @Test
    fun aPlaceholderReplacesTheLastPrompt() {
        AiDiagLog.recordPrompt("draft", "an earlier prompt")

        AiDiagLog.recordPrompt("draft", chat, keep = false)

        assertEquals(AiDiagLog.PROMPT_NOT_KEPT, AiDiagLog.lastPrompt)
    }
}
