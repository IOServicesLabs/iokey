package com.localboard.keyboard.llm

/**
 * Turns the cumulative text the native callback emits into real deltas.
 *
 * nativeGenerate hands its callback the whole text so far on every token, while
 * [LLMProvider.generate] promises its listener successive chunks. Passing the native
 * value straight through makes "hey", " what" arrive as "hey", "hey what", and anything
 * appending them shows "heyhey what". LEAP's chunks are already deltas and need no
 * wrapping.
 *
 * One per generation, called from the decode thread only.
 */
class DeltaStream(private val sink: (String) -> Unit) {
    private var emitted = 0

    fun onCumulative(full: String) {
        if (full.length > emitted) {
            sink(full.substring(emitted))
            emitted = full.length
        }
    }
}
