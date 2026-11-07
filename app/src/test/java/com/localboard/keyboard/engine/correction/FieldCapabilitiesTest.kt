package com.localboard.keyboard.engine.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldCapabilitiesTest {
    private val text = 0x1
    private val number = 0x2
    private val phone = 0x3
    private val datetime = 0x4

    private val uri = 0x10
    private val email = 0x20
    private val shortMessage = 0x40
    private val longMessage = 0x50
    private val name = 0x60
    private val postal = 0x70
    private val password = 0x80
    private val visiblePassword = 0x90
    private val webEdit = 0xa0
    private val filter = 0xb0
    private val phonetic = 0xc0
    private val webEmail = 0xd0
    private val webPassword = 0xe0

    private val numberPassword = 0x10
    private val dateVariation = 0x10
    private val timeVariation = 0x20

    private val capSentences = 0x4000
    private val autoCorrect = 0x8000
    private val autoComplete = 0x10000
    private val multiLine = 0x20000
    private val noSuggestions = 0x80000

    private val noPersonalizedLearning = 0x1000000

    private fun from(inputType: Int, imeOptions: Int = 0, mayLearn: Boolean = true) =
        FieldPolicy.from(inputType, imeOptions, mayLearn)

    private val nothing = FieldCapabilities(
        composing = false, suggestions = false, correction = FieldCorrection.OFF,
        autoSpace = false, smartPunct = false, doubleSpace = false, autoCaps = false,
        learn = false, glide = false, ai = false, voice = false, snippets = false,
        rawKeys = false, email = false,
    )
    private val everything = FieldCapabilities(
        composing = true, suggestions = true, correction = FieldCorrection.AUTO,
        autoSpace = true, smartPunct = true, doubleSpace = true, autoCaps = true,
        learn = true, glide = true, ai = true, voice = true, snippets = true,
        rawKeys = false, email = false,
    )
    private val emailField = nothing.copy(
        composing = true, suggestions = true, autoCaps = true, learn = true, snippets = true, email = true,
    )
    private val urlField = nothing.copy(composing = true, suggestions = true)
    private val noSuggestionsField = everything.copy(
        composing = false, suggestions = false, correction = FieldCorrection.OFF,
        learn = false, glide = false, snippets = false,
    )
    private val filterField = everything.copy(
        correction = FieldCorrection.SUGGEST_ONLY, smartPunct = false, doubleSpace = false,
        learn = false, ai = false, voice = false, snippets = false,
    )

    @Test
    fun chatFieldsCanDoEverything() {
        assertEquals(everything, from(text or multiLine))
        assertEquals(everything, from(text or autoCorrect))
        assertEquals(everything, from(text or longMessage or multiLine or capSentences))
    }

    @Test
    fun passwordsTurnEverythingOff() {
        for (t in listOf(text or password, text or visiblePassword, text or webPassword, number or numberPassword)) {
            assertEquals("inputType $t", nothing, from(t))
        }
    }

    @Test
    fun aPasswordStaysOffWhateverElseItsFlagsSay() {
        assertEquals(nothing, from(text or password or multiLine or autoCorrect))
    }

    @Test
    fun emailFieldsAssembleAddressesAndNothingElse() {
        assertEquals(emailField, from(text or email))
        assertEquals(emailField, from(text or webEmail))
    }

    @Test
    fun urlFieldsAreLeftAlone() {
        assertEquals(urlField, from(text or uri))
        assertEquals(urlField, from(text or uri or multiLine))
    }

    @Test
    fun noSuggestionsHidesTheBarButKeepsDictationAndDrafts() {
        assertEquals(noSuggestionsField, from(text or noSuggestions))
        assertEquals(noSuggestionsField, from(text or noSuggestions or multiLine))
        assertTrue(from(text or noSuggestions).ai)
        assertTrue(from(text or noSuggestions).voice)
    }

    @Test
    fun filterFieldsSuggestWithoutRewriting() {
        assertEquals(filterField, from(text or filter))
    }

    @Test
    fun fieldsThatAreNotTextGetNothing() {
        val others = listOf(
            number, number or 0x1000, number or 0x2000, phone, datetime,
            datetime or dateVariation, datetime or timeVariation,
        )
        for (t in others) assertEquals("inputType $t", nothing, from(t))
    }

    @Test
    fun typeNullIsTheOnlyFieldThatWantsRawKeys() {
        assertEquals(nothing.copy(rawKeys = true), from(0))
        for (t in listOf(text, number, phone, datetime, text or password)) assertFalse(from(t).rawKeys)
    }

    @Test
    fun aFieldCombiningAttributesGetsTheIntersection() {
        assertEquals(
            emailField.copy(composing = false, suggestions = false, learn = false, snippets = false),
            from(text or email or noSuggestions),
        )
        assertEquals(urlField.copy(composing = false, suggestions = false), from(text or uri or noSuggestions))
    }

    @Test
    fun aTimeFieldIsNotAnEmailFieldDespiteSharingItsVariationNumber() {
        assertFalse(from(datetime or timeVariation).email)
        assertTrue(from(text or email).email)
    }

    @Test
    fun onlyLearningAnswersToTheLearningRules() {
        val chat = text or multiLine
        assertEquals(everything.copy(learn = false), from(chat, imeOptions = noPersonalizedLearning))
        assertEquals(everything.copy(learn = false), from(chat, mayLearn = false))
        assertTrue(from(chat).learn)
    }

    // The single-line rule is held off until the flags real apps send have been read.

    @Test
    fun theSwitchIsOff() {
        assertFalse(FieldPolicy.SINGLE_LINE_NEEDS_AUTO_CORRECT)
    }

    @Test
    fun singleLineFieldsKeepAutocorrectWhileTheSwitchIsOff() {
        for (t in listOf(text, text or capSentences, text or shortMessage)) {
            assertEquals("inputType $t", FieldCorrection.AUTO, FieldPolicy.classify(t))
            assertEquals("inputType $t", everything, from(t))
        }
    }

    @Test
    fun theSwitchTurnsThoseSameFieldsToSuggestionsOnly() {
        for (t in listOf(text, text or capSentences, text or shortMessage)) {
            assertEquals(FieldCorrection.SUGGEST_ONLY, FieldPolicy.classify(t, singleLineNeedsAutoCorrect = true))
            val caps = FieldPolicy.from(t, 0, true, singleLineNeedsAutoCorrect = true)
            assertEquals(everything.copy(correction = FieldCorrection.SUGGEST_ONLY), caps)
            assertFalse(caps.autocorrect)
        }
    }

    @Test
    fun theSwitchMovesNoOtherField() {
        val others = listOf(
            text or autoCorrect, text or multiLine, text or longMessage or multiLine,
            text or webEdit, text or webEdit or autoCorrect, text or name, text or postal, text or phonetic,
            text or filter, text or autoComplete or multiLine, text or uri, text or email, text or webEmail,
            text or password, text or noSuggestions, number, phone, datetime, 0,
        )
        for (t in others) {
            assertEquals(
                "inputType $t",
                FieldPolicy.classify(t, singleLineNeedsAutoCorrect = true),
                FieldPolicy.classify(t, singleLineNeedsAutoCorrect = false),
            )
        }
    }

    @Test
    fun aBrowserFieldWithoutAutoCorrectStaysSuggestionsOnlyEitherWay() {
        assertEquals(FieldCorrection.SUGGEST_ONLY, FieldPolicy.classify(text or webEdit))
        assertEquals(FieldCorrection.AUTO, FieldPolicy.classify(text or webEdit or autoCorrect))
    }

    @Test
    fun theAutocorrectFlagReadsTheCorrectionVerdict() {
        assertTrue(everything.autocorrect)
        assertFalse(filterField.autocorrect)
        assertFalse(nothing.autocorrect)
    }

    // The password check itself, which the AI gate uses on its own.

    @Test
    fun everyPasswordVariationIsAPassword() {
        for (t in listOf(text or password, text or visiblePassword, text or webPassword, number or numberPassword)) {
            assertTrue("inputType $t", FieldPolicy.isPassword(t))
        }
    }

    @Test
    fun aVariationNumberCountsOnlyInItsOwnClass() {
        assertFalse(FieldPolicy.isPassword(text or uri))
        assertFalse(FieldPolicy.isPassword(datetime or dateVariation))
        assertFalse(FieldPolicy.isPassword(number))
        assertFalse(FieldPolicy.isPassword(number or 0x2000))
        assertFalse(FieldPolicy.isPassword(0))
    }

    @Test
    fun capabilitiesNeverDisagreeWithTheFunctionsTheyAreBuiltFrom() {
        val variations = (0..0xf).map { it shl 4 }
        val flags = listOf(0, capSentences, autoCorrect, autoComplete, multiLine, noSuggestions, autoCorrect or multiLine)
        for (cls in listOf(0, text, number, phone, datetime)) for (v in variations) for (f in flags) {
            val t = cls or v or f
            val caps = from(t)
            assertEquals("inputType $t", FieldPolicy.showsSuggestions(t), caps.suggestions)
            assertEquals("inputType $t", FieldPolicy.classify(t), caps.correction)
            if (FieldPolicy.isPassword(t)) assertEquals("inputType $t", nothing, caps)
            if (caps.autocorrect) assertTrue("inputType $t", caps.composing && caps.suggestions)
        }
    }
}
