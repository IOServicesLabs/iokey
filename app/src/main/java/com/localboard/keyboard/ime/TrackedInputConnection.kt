package com.localboard.keyboard.ime

import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.localboard.keyboard.engine.TrackedEditor

/**
 * The editor's connection with every write reported to [TrackedEditor]. One for each real
 * connection. It only forwards, and it starts no call of its own, so a key costs the same
 * binder calls as without it. Reads are not overridden, so they always reach the editor.
 * The logic is all in [TrackedEditor], because a subclass of an android.jar class cannot
 * run in a JVM test here.
 */
internal class TrackedInputConnection(
    raw: InputConnection,
    private val editor: TrackedEditor,
    private val connectionId: Int,
) : InputConnectionWrapper(raw, false) {

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        editor.setComposingText(connectionId, text?.length ?: 0) { super.setComposingText(text, newCursorPosition) }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
        editor.commitText(connectionId, text?.length ?: 0) { super.commitText(text, newCursorPosition) }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        editor.deleteSurroundingText(connectionId, beforeLength) { super.deleteSurroundingText(beforeLength, afterLength) }

    override fun finishComposingText(): Boolean =
        editor.finishComposingText(connectionId) { super.finishComposingText() }

    override fun setSelection(start: Int, end: Int): Boolean =
        editor.setSelection(connectionId, start, end) { super.setSelection(start, end) }

    override fun beginBatchEdit(): Boolean =
        editor.beginBatchEdit(connectionId) { super.beginBatchEdit() }

    override fun endBatchEdit(): Boolean =
        editor.endBatchEdit(connectionId) { super.endBatchEdit() }

    override fun sendKeyEvent(event: KeyEvent?): Boolean =
        editor.unpredictable(connectionId) { super.sendKeyEvent(event) }

    override fun performEditorAction(editorAction: Int): Boolean =
        editor.unpredictable(connectionId) { super.performEditorAction(editorAction) }

    override fun performContextMenuAction(id: Int): Boolean =
        editor.unpredictable(connectionId) { super.performContextMenuAction(id) }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        editor.unpredictable(connectionId) { super.setComposingRegion(start, end) }

    override fun commitCompletion(text: CompletionInfo?): Boolean =
        editor.unpredictable(connectionId) { super.commitCompletion(text) }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean =
        editor.unpredictable(connectionId) { super.commitCorrection(correctionInfo) }
}
