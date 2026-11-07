package com.localboard.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextTokensTest {

    private val mark = ContextTokens.SENTENCE_MARK

    private fun tokens(text: String) = ContextTokens.tokenize(text)

    private fun field(text: String) = ContextTokens.fromFieldWindow(text)

    @Test
    fun `words are lowercase and keep their apostrophe and hyphen`() {
        assertEquals(listOf("hello", "world"), tokens("Hello, WORLD"))
        assertEquals(listOf("i", "don't", "know"), tokens("I don't know"))
        assertEquals(listOf("a", "well-known", "place"), tokens("a well-known place"))
    }

    @Test
    fun `a curly apostrophe comes out as the spelling the maps are keyed on`() {
        assertEquals(listOf("don't"), tokens("don’t"))
    }

    @Test
    fun `a connector with no letter after it does not stick to the word`() {
        assertEquals(listOf("the", "dogs", "bowl"), tokens("the dogs' bowl"))
        assertEquals(listOf("said", "quote"), tokens("said 'quote'"))
        assertEquals(listOf("wait", "what"), tokens("wait - what"))
        assertEquals(listOf("wait", "what"), tokens("wait- what"))
        assertEquals(listOf("wait", "what"), tokens("wait -what"))
    }

    @Test
    fun `a run holding a digit is dropped whole, not reduced to its letters`() {
        assertEquals(listOf("at", "today"), tokens("at 3pm today"))
        assertEquals(listOf("my", "player"), tokens("my mp3 player"))
        assertEquals(listOf("hi"), tokens("h3llo hi"))
        assertEquals(listOf("room"), tokens("room 42"))
        assertEquals(listOf("the", "were", "great"), tokens("the 90's were great"))
        assertEquals(listOf("the", "were", "great"), tokens("the 90’s were great"))
        assertEquals(listOf("a", "call"), tokens("a 3-way call"))
        assertEquals(listOf("season"), tokens("2020-21 season"))
    }

    @Test
    fun `an apostrophe or hyphen with nothing after it ends a run holding a digit`() {
        assertEquals(listOf("a", "way"), tokens("a 3- way"))
        assertEquals(listOf("the", "were"), tokens("the 90' were"))
    }

    @Test
    fun `sentence punctuation is a mark and so is a line break`() {
        assertEquals(listOf("hi", mark, "ok"), tokens("Hi. Ok"))
        assertEquals(listOf("hi", mark, "ok"), tokens("Hi! Ok"))
        assertEquals(listOf("hi", mark, "ok"), tokens("Hi? Ok"))
        assertEquals(listOf("hi", mark, "ok"), tokens("Hi… Ok"))
        assertEquals(listOf("hi", mark, "ok"), tokens("Hi\nOk"))
        assertEquals(listOf("hi", mark, "ok"), tokens("Hi\r\nOk"))
    }

    @Test
    fun `marks never repeat`() {
        assertEquals(listOf("hi", mark, "ok"), tokens("Hi!!! Ok"))
        assertEquals(listOf("hi", mark, "ok"), tokens("Hi?!\n\nOk"))
        assertEquals(listOf("hi", mark, "ok"), tokens("Hi... Ok"))
    }

    @Test
    fun `punctuation inside a token is not a sentence end`() {
        assertEquals(listOf("google", "com", "rocks"), tokens("google.com rocks"))
        assertEquals(listOf("cost", "dollars"), tokens("cost 3.5 dollars"))
        assertEquals(listOf("see", "q"), tokens("see ?q=1"))
    }

    @Test
    fun `an emoji closes the thought, and its own marks open nothing`() {
        assertEquals(listOf("hi", mark, "ok"), tokens("hi 😀 ok"))
        // A red heart: a symbol followed by the variation selector, itself a mark.
        assertEquals(listOf("hi", mark, "ok"), tokens("hi ❤️ ok"))
    }

    @Test
    fun `combining marks stay with their word`() {
        assertEquals(listOf("café", "open"), tokens("café open"))
    }

    @Test
    fun `other punctuation only separates`() {
        assertEquals(listOf("one", "two", "three"), tokens("one, two; three"))
        assertEquals(listOf("say", "what"), tokens("say (what)"))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(emptyList<String>(), tokens(""))
        assertEquals(emptyList<String>(), tokens(" , ; "))
        assertEquals(listOf(mark), tokens("..."))
    }

    @Test
    fun `an empty field is the start of a sentence`() {
        assertEquals(listOf(mark), field(""))
    }

    @Test
    fun `a short read reached the start of the field, so it opens with the mark`() {
        assertEquals(listOf(mark, "hello"), field("Hello, "))
        assertEquals(listOf(mark, "hello", "there"), field("Hello there "))
    }

    @Test
    fun `the fragment touching the cursor is not context`() {
        assertEquals(listOf(mark, "hello"), field("Hello, wor"))
        assertEquals(listOf(mark, "hello"), field("Hello wor"))
        assertEquals(listOf(mark), field("wor"))
        assertEquals("a whole run goes, connectors included", listOf(mark, "ok"), field("ok don't"))
        assertEquals(listOf(mark, "ok"), field("ok well-kno"))
        assertEquals("a digit is part of the run", listOf(mark, "ok"), field("ok abc3"))
    }

    @Test
    fun `a word finished by a space stays even when it is the last one`() {
        assertEquals(listOf(mark, "hello", "wor"), field("Hello wor "))
    }

    @Test
    fun `a sentence end right before the cursor is kept`() {
        assertEquals(listOf(mark, "hi", mark), field("Hi. "))
        assertEquals(listOf(mark, "hi", mark), field("Hi."))
    }

    @Test
    fun `a dot just before the fragment is judged by the fragment`() {
        assertEquals("still a web address, not a sentence end", listOf(mark, "google"), field("google.co"))
        assertEquals("a space after it is a sentence end", listOf(mark, "hi", mark), field("Hi. wor"))
    }

    @Test
    fun `a sentence start at the top of the field is not doubled`() {
        assertEquals(listOf(mark, "hi"), field("\nHi "))
        assertEquals(listOf(mark, "hi"), field("...Hi "))
    }

    @Test
    fun `an emoji right before the cursor closes the thought`() {
        assertEquals(listOf(mark, "hi", mark), field("hi 😀"))
    }

    @Test
    fun `a full read was cut off somewhere unknown, so no mark and no first run`() {
        val body = "hello there "
        val padded = "abc".padEnd(ContextTokens.FIELD_WINDOW - body.length, 'x') + " " + body
        val text = padded.takeLast(ContextTokens.FIELD_WINDOW)
        assertEquals(ContextTokens.FIELD_WINDOW, text.length)
        assertEquals(listOf("hello", "there"), field(text))
    }

    @Test
    fun `a full read that is one unbroken run is nothing at all`() {
        assertEquals(emptyList<String>(), field("x".repeat(ContextTokens.FIELD_WINDOW)))
    }

    @Test
    fun `the window that counts is the one that was asked for`() {
        assertEquals("shorter than asked for", listOf(mark, "ab"), ContextTokens.fromFieldWindow("ab cd", 6))
        assertEquals("exactly what was asked for", emptyList<String>(), ContextTokens.fromFieldWindow("ab cd", 5))
    }

    @Test
    fun `a surrogate pair at the cursor is not split`() {
        // U+1D5BA, a letter outside the 16 bit range.
        val letter = String(Character.toChars(0x1D5BA))
        assertEquals(listOf(mark, "ok"), field("ok $letter$letter"))
        assertEquals(listOf(mark, "ok", "$letter$letter"), field("ok $letter$letter "))
    }
}
