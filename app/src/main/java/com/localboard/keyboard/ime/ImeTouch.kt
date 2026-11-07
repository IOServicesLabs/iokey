package com.localboard.keyboard.ime

/**
 * Where fingers land, and what the keyboard does about it. Nothing learned about a
 * finger ever moves a key: the keys are where the layout puts them.
 *
 * Fields only this file needs belong here. The service holds one instance, so
 * adding a field leaves it untouched.
 */
internal class TouchState

// Editing events, called from ImeText. Empty until this file needs them.
internal fun LLMKeyboardService.touchOnExternalCursorMove() {}
internal fun LLMKeyboardService.touchOnWordStart() {}
internal fun LLMKeyboardService.touchOnWordAboutToFinish(word: String) {}
internal fun LLMKeyboardService.touchOnComposingDelete() {}
internal fun LLMKeyboardService.touchOnDeleteWithEmptyComposing() {}
