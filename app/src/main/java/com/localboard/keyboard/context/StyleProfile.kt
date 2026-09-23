package com.localboard.keyboard.context

import org.json.JSONArray
import org.json.JSONObject

/**
 * A compact statistical fingerprint of how the user writes.
 * Computed and refined entirely on-device.
 */
data class StyleProfile(
    val avgSentenceLength: Float = 14f,
    val emojiPerSentence: Float = 0f,
    /** 0 = standard capitalization, 1 = mostly lowercase */
    val capitalizationStyle: Int = 0,
    /** 0f = very casual, 1f = very formal */
    val formality: Float = 0.5f,
    val usesContractions: Boolean = true,
    val topWords: List<Pair<String, Int>> = emptyList(),
    val topEmojis: List<String> = emptyList(),
    val sentencesAnalyzed: Int = 0
) {
    /** Word -> ranking boost in [1.0, 1.6], used by the FastEngine. */
    fun toBoostMap(): Map<String, Float> {
        if (topWords.isEmpty()) return emptyMap()
        val max = topWords.maxOf { it.second }.coerceAtLeast(1)
        return topWords.associate { (w, c) ->
            w to (1.0f + 0.6f * (c.toFloat() / max))
        }
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("avgSentenceLength", avgSentenceLength.toDouble())
        o.put("emojiPerSentence", emojiPerSentence.toDouble())
        o.put("capitalizationStyle", capitalizationStyle)
        o.put("formality", formality.toDouble())
        o.put("usesContractions", usesContractions)
        o.put("sentencesAnalyzed", sentencesAnalyzed)
        o.put("topWords", JSONArray(topWords.map { JSONArray().put(it.first).put(it.second) }))
        o.put("topEmojis", JSONArray(topEmojis))
        return o.toString()
    }

    companion object {
        val EMPTY = StyleProfile()

        fun fromJson(json: String): StyleProfile {
            return try {
                val o = JSONObject(json)
                val wordsArr = o.optJSONArray("topWords") ?: JSONArray()
                val words = buildList {
                    for (i in 0 until wordsArr.length()) {
                        // One malformed entry must not throw away the whole
                        // profile — skip it and keep the rest.
                        try {
                            val pair = wordsArr.getJSONArray(i)
                            add(pair.getString(0) to pair.getInt(1))
                        } catch (_: Exception) { }
                    }
                }
                val emojisArr = o.optJSONArray("topEmojis") ?: JSONArray()
                val emojis = buildList {
                    for (i in 0 until emojisArr.length()) {
                        try {
                            add(emojisArr.getString(i))
                        } catch (_: Exception) { }
                    }
                }
                StyleProfile(
                    avgSentenceLength = o.optDouble("avgSentenceLength", 14.0).toFloat(),
                    emojiPerSentence = o.optDouble("emojiPerSentence", 0.0).toFloat(),
                    capitalizationStyle = o.optInt("capitalizationStyle", 0),
                    formality = o.optDouble("formality", 0.5).toFloat(),
                    usesContractions = o.optBoolean("usesContractions", true),
                    topWords = words,
                    topEmojis = emojis,
                    sentencesAnalyzed = o.optInt("sentencesAnalyzed", 0)
                )
            } catch (e: Exception) {
                EMPTY
            }
        }
    }
}
