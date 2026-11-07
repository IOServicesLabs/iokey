package com.localboard.keyboard.memory

import kotlin.math.abs

/**
 * Chat layouts share one left edge for incoming bubbles and one right edge for
 * outgoing ones, whatever the avatar width. Fixed bands at 15% and 85% of the screen
 * misread avatar layouts, where an incoming bubble starts past the band and a long
 * one runs past the other.
 */
object BubbleClassifier {
    /** [idVerdict] is what the node's view id said, when it said anything. */
    data class Box(val left: Int, val right: Int, val idVerdict: Speaker? = null)

    private fun modeOf(values: List<Int>, tol: Int): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        for (v in values) {
            val c = values.count { abs(it - v) <= tol }
            if (best == null || c > best.second) best = v to c
        }
        return best
    }

    fun classify(boxes: List<Box>, screenW: Int): List<Speaker> {
        val tol = (screenW * 0.015f).toInt().coerceAtLeast(6)
        val lefts = boxes.map { it.left }.filter { it < screenW * 0.40f }
        val rights = boxes.map { it.right }.filter { it > screenW * 0.60f }
        val leftEdge = modeOf(lefts, tol)?.takeIf { it.second >= 2 }?.first
        val rightEdge = modeOf(rights, tol)?.takeIf { it.second >= 2 }?.first
        return boxes.map { b ->
            b.idVerdict ?: run {
                val onLeft = leftEdge != null && abs(b.left - leftEdge) <= tol
                val onRight = rightEdge != null && abs(b.right - rightEdge) <= tol
                when {
                    onLeft && !onRight -> Speaker.THEM
                    onRight && !onLeft -> Speaker.ME
                    else -> {
                        val leftGap = b.left
                        val rightGap = screenW - b.right
                        val margin = screenW * 0.08f
                        when {
                            leftGap + margin < rightGap -> Speaker.THEM
                            rightGap + margin < leftGap -> Speaker.ME
                            else -> Speaker.UNKNOWN
                        }
                    }
                }
            }
        }
    }
}
