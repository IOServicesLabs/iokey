package com.localboard.keyboard.memory

import kotlin.math.abs

/**
 * Turns one walk of a screen's view tree into lines of a chat. It knows nothing about
 * Android, so what it decides can be checked without a device or a real app.
 */
object ChatCapture {
    /** A list-like node on screen. [parent] is the nearest list-like node around it, or -1. */
    data class ScrollBox(
        val parent: Int,
        val depth: Int,
        val area: Long,
        val top: Int,
        val canScrollForward: Boolean
    )

    /** One piece of visible text. [box] is the nearest list-like node around it, or -1. */
    data class TextNode(
        val text: String,
        val top: Int,
        val left: Int,
        val right: Int,
        /** What the view id said about the side, when it said anything. */
        val idVerdict: Speaker?,
        val box: Int
    )

    data class Result(
        val turns: List<Turn>,
        val listFound: Boolean,
        /** False while the list can still scroll forward: the user is reading history. */
        val atBottom: Boolean,
        /** Text that might be the chat's title, likeliest first. */
        val titleCandidates: List<String>
    )

    /** Below this share of the screen a scrollable is a detail of the page, such as a
     *  dropdown, and not where the conversation is. */
    private const val MIN_LIST_SCREEN_SHARE = 0.15f

    /** A wrapper counts as the same place as a list inside it that fills at least half of it. */
    private const val INNER_LIST_SHARE = 0.5f

    /** With no list to go on, a capture shorter than this is more likely stray chrome. */
    private const val MIN_UNLISTED_CHARS = 24

    /** Without a list, it takes this many changes of side before lines are taken to be a chat:
     *  a form or a settings page must not gain speakers it does not have. */
    private const val MIN_UNLISTED_ALTERNATIONS = 3

    private const val TITLE_MAX_CHARS = 42
    private const val DATE_CHIP_MAX_CHARS = 40

    private val WS = Regex("\\s+")
    private val DIGITS = Regex("\\d+")

    /** The chips a chat puts between days. Only taken as one when centered as well, so a
     *  message that just says "today" stays a message. */
    private val DATE_RE = Regex(
        "(today|yesterday|" +
            "(monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\\.?(, .*| \\d.*)?|" +
            "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? \\d{1,2}(,? \\d{4})?(,? .*)?|" +
            "\\d{1,2} (jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?(,? \\d{4})?(,? .*)?|" +
            "\\d{1,4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,4})",
        RegexOption.IGNORE_CASE
    )

    /** The conversation is in the largest list on screen. An outer scrolling wrapper can be
     *  larger still, so a list that fills most of it is the better answer. */
    fun chooseList(boxes: List<ScrollBox>, screenArea: Long): Int {
        val largest = boxes.indices.maxByOrNull { boxes[it].area } ?: return -1
        if (boxes[largest].area < screenArea * MIN_LIST_SCREEN_SHARE) return -1
        var best = largest
        for (i in boxes.indices) {
            val b = boxes[i]
            if (i != largest && b.depth > boxes[best].depth &&
                b.area >= boxes[largest].area * INNER_LIST_SHARE && isWithin(i, largest, boxes)
            ) best = i
        }
        return best
    }

    private fun isWithin(box: Int, list: Int, boxes: List<ScrollBox>): Boolean {
        var b = box
        while (b >= 0) {
            if (b == list) return true
            b = boxes[b].parent
        }
        return false
    }

    fun shape(texts: List<TextNode>, boxes: List<ScrollBox>, screenW: Int, screenH: Int): Result {
        val list = chooseList(boxes, screenW.toLong() * screenH)
        val inList = if (list >= 0) texts.filter { isWithin(it.box, list, boxes) } else emptyList()
        // A list with no text in it, such as a strip of photos, says nothing about messages.
        val listFound = inList.isNotEmpty()
        val sorted = (if (listFound) inList else texts)
            .filterNot { isDateChip(it, screenW) }
            .sortedWith(compareBy({ it.top }, { it.left }))
        val sides = BubbleClassifier.classify(
            sorted.map { BubbleClassifier.Box(it.left, it.right, it.idVerdict) }, screenW
        )
        val trustSides = if (listFound) hasBothSides(sides) else alternations(sides) >= MIN_UNLISTED_ALTERNATIONS
        val turns = sorted.mapIndexed { i, n ->
            Turn(if (trustSides) sides[i] else n.idVerdict ?: Speaker.UNKNOWN, WS.replace(n.text, " ").trim())
        }
        val keep = listFound || turns.sumOf { it.text.length } >= MIN_UNLISTED_CHARS
        return Result(
            turns = if (keep) turns else emptyList(),
            listFound = listFound,
            atBottom = !listFound || !boxes[list].canScrollForward,
            titleCandidates = titleCandidates(texts, if (listFound) boxes[list].top else null, inList, screenH)
        )
    }

    /** One-sided by geometry could as easily be a settings page as a chat, so that gets no
     *  speakers from geometry. Both sides at once is a conversation, however short. */
    private fun hasBothSides(sides: List<Speaker>): Boolean =
        Speaker.ME in sides && Speaker.THEM in sides

    private fun alternations(sides: List<Speaker>): Int {
        val known = sides.filter { it != Speaker.UNKNOWN }
        return (1 until known.size).count { known[it] != known[it - 1] }
    }

    /** A bubble that fills the row is centered too, and may well open with a date, so a chip
     *  also has to be chip-sized. */
    private fun isDateChip(n: TextNode, screenW: Int): Boolean {
        val centered = abs(n.left + n.right - screenW) / 2 < screenW * 0.08f
        val small = n.right - n.left < screenW * 0.7f && n.text.length <= DATE_CHIP_MAX_CHARS
        return centered && small && DATE_RE.matches(n.text.trim())
    }

    /** With a list, the title is in the region above it, topmost first, or in the top band
     *  outside it when the bar is drawn over the list. Without a list, the top band of the
     *  screen, longest first, because a name outlives a status line. */
    private fun titleCandidates(
        texts: List<TextNode>,
        listTop: Int?,
        inList: List<TextNode>,
        screenH: Int
    ): List<String> {
        fun fits(n: TextNode) = n.text.trim().length in 2..TITLE_MAX_CHARS && !DIGITS.matches(n.text.trim())
        val bandTop = (screenH * 0.03f).toInt()
        val bandBottom = (screenH * 0.16f).toInt()
        return if (listTop != null) {
            val held = inList.toSet()
            texts.filter { it !in held && (it.top < listTop || it.top <= bandBottom) && fits(it) }
                .sortedWith(compareBy({ it.top }, { -it.text.length }))
                .map { it.text.trim() }
        } else {
            texts.filter { it.top in bandTop..bandBottom && fits(it) }
                .sortedByDescending { it.text.length }
                .map { it.text.trim() }
        }
    }
}
