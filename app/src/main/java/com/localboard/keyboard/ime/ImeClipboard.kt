package com.localboard.keyboard.ime

import android.app.AlertDialog
import android.content.ClipDescription
import android.content.Context.CLIPBOARD_SERVICE
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.localboard.keyboard.R
import kotlinx.coroutines.cancel

/**
 * The clipboard chip, its history sheet, and dialogs raised over the IME.
 *
 * Fields only this file needs belong here. The service holds one instance, so
 * adding a field leaves it untouched.
 */
internal class ClipboardState {
    /** The sheet up over the keyboard. It is a child of the keyboard's window, so it would hide with
     *  that window and come back with it, over whatever field opens the keyboard next. */
    var openSheet: AlertDialog? = null

    /** The field that sheet was opened over. */
    var sheetField: EditorInfo? = null

    /** Whoever copied the current clip asked for it to be kept out of sight, as a password manager does.
     *  Clip history has no way to take out one row, so such a clip stays out of it. */
    var clipIsSensitive = false
}

internal fun LLMKeyboardService.startClipboardWatch() {
    // Clipboard reads are binder transactions; the prediction refresh used
    // to make two of them per keystroke lull. The change listener keeps a
    // local cache hot instead, and the refresh path only ever reads that.
    clipboardManager =
        getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    try {
        clipboardManager?.addPrimaryClipChangedListener(clipChangeListener)
        refreshClipCache()
    } catch (_: Throwable) { }
}

internal fun LLMKeyboardService.createClipChangeListener() =
    android.content.ClipboardManager.OnPrimaryClipChangedListener {
        refreshClipCache()
        // A fresh copy while the keyboard is up should surface the chip
        // without waiting for the next keystroke.
        if (predictionBarInitialized) updateClipboardChip()
    }

internal fun LLMKeyboardService.refreshClipCache() {
    val cm = clipboardManager ?: return
    cachedClipText = try {
        cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()?.trim()
    } catch (_: Throwable) { null }
    val description = try { cm.primaryClipDescription } catch (_: Throwable) { null }
    cachedClipTs = description?.timestamp ?: 0L
    // Extras another app wrote are unpacked here, and may not unpack. That clip stays out of history.
    clipboardState.clipIsSensitive = try {
        description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
    } catch (_: Throwable) { true }
}

/** Re-evaluate the paste chip: only when the field is safe (predictions
 *  on — never over passwords), the user isn't mid-word, and the clip is
 *  real and fresh (≤ 2 min old, Gboard-style). Reads only the listener-
 *  maintained cache — no binder call on the prediction path. */
internal fun LLMKeyboardService.updateClipboardChip() {
    if (!predictionBarInitialized) return
    if (!predictionsEnabled || composing.isNotEmpty()) {
        clipboardFullText = ""
        predictionBar.setClipboardChip(null)
        return
    }
    val text = cachedClipText
    val ts = cachedClipTs
    val fresh = !text.isNullOrEmpty() && text.length >= 2 &&
        (ts <= 0L || System.currentTimeMillis() - ts < 120_000)
    // Feed the history whenever a NEW clip appears, regardless of chip
    // freshness — the chip is a 2-minute affordance, the history is the
    // durable one.
    if (!incognito && !clipboardState.clipIsSensitive && !text.isNullOrEmpty() && text != lastRecordedClip) {
        lastRecordedClip = text
        clipHistory.record(text)
    }
    clipboardFullText = if (fresh) text!! else ""
    predictionBar.setClipboardChip(
        if (fresh) text!!.replace('\n', ' ').take(24) else null
    )
}

/**
 * The clipboard history sheet: everything copied recently, newest first,
 * pinned entries on top. Long-press pins or deletes. Opened by
 * long-pressing the paste chip, so the common case (paste the last thing)
 * stays a single tap.
 */
internal fun LLMKeyboardService.showClipboardHistory() {
    if (!prefs.clipboardHistory) {
        showNotice(R.string.clip_history_off)
        return
    }
    val clips = clipHistory.entries()
    if (clips.isEmpty()) {
        showNotice(R.string.clip_history_empty)
        return
    }
    val labels = clips.map { c ->
        (if (c.pinned) "📌  " else "") + c.text.replace('\n', ' ').take(60)
    }.toTypedArray()
    val ctx = ContextThemeWrapper(this, R.style.Theme_LocalBoard)
    val openedOver = currentInputEditorInfo
    val dialog = AlertDialog.Builder(ctx)
        .setTitle(R.string.clip_history_title)
        .setItems(labels) { _, i ->
            // A clip only ever goes into the field the sheet was opened over, whatever else closing
            // the sheet missed. It could be a password field by now.
            if (!isSameField(openedOver, currentInputEditorInfo)) return@setItems
            val ic = currentInputConnection ?: return@setItems
            beginSelfEdit()
            ic.commitText(clips[i].text, 1)
            endSelfEdit()
            learningOnExternalCursorMove()
            updateAutoCaps()
            refreshPredictions()
        }
        .setNeutralButton(R.string.clip_history_manage) { _, _ -> showClipManage(clips) }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    showOverIme(dialog)
}

internal fun LLMKeyboardService.showClipManage(clips: List<com.localboard.keyboard.util.ClipHistory.Clip>) {
    val labels = clips.map { c ->
        (if (c.pinned) "📌 " else "☆ ") + c.text.replace('\n', ' ').take(50)
    }.toTypedArray()
    val ctx = ContextThemeWrapper(this, R.style.Theme_LocalBoard)
    val dialog = AlertDialog.Builder(ctx)
        .setTitle(R.string.clip_history_manage)
        .setItems(labels) { _, i ->
            val c = clips[i]
            clipHistory.setPinned(c.text, !c.pinned)
            showClipboardHistory()
        }
        .setPositiveButton(R.string.clip_history_clear) { _, _ ->
            clipHistory.clear()
            showNoticeOnceDialogHasClosed(getString(R.string.clip_history_cleared))
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    showOverIme(dialog)
}

/**
 * Every sheet the keyboard raises goes through here, as a child window of the keyboard's own. The
 * window type made for input method dialogs is not ours to use: it takes a system permission, and the
 * window manager refused every dialog with "permission denied for window type 2012". The flag keeps
 * the keyboard attached to the app's field while the dialog is up. Closing it can still start the
 * keyboard over on that field, so a button that goes on to do something has to allow for that.
 *
 * False when it could not open, for a caller that must then carry on without an answer.
 */
internal fun LLMKeyboardService.showOverIme(dialog: AlertDialog): Boolean {
    // The decor view is there whichever panel is showing. The emoji panel takes the keys away.
    val token = window?.window?.decorView?.windowToken
        ?: (if (keyboardViewInitialized) keyboardView.windowToken else null)
        ?: return false
    return try {
        dialog.window?.let { w ->
            val lp = w.attributes
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            lp.token = token
            w.attributes = lp
            w.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        dialog.show()
        // A button can open the next sheet before its own has closed. That one is on its way out.
        closeKeyboardSheet()
        clipboardState.openSheet = dialog
        clipboardState.sheetField = currentInputEditorInfo
        dialog.setOnDismissListener {
            if (clipboardState.openSheet === dialog) {
                clipboardState.openSheet = null
                clipboardState.sheetField = null
            }
        }
        true
    } catch (t: Throwable) {
        recordCaughtError(t, "dialog over the keyboard")
        showNotice(R.string.clip_history_no_window)
        false
    }
}

/** Closes the sheet over the keyboard, if there is one. Nothing listens for this to carry on from it. */
internal fun LLMKeyboardService.closeKeyboardSheet() {
    val sheet = clipboardState.openSheet ?: return
    clipboardState.openSheet = null
    clipboardState.sheetField = null
    try {
        sheet.dismiss()
    } catch (t: Throwable) {
        recordCaughtError(t, "closing a dialog over the keyboard")
    }
}

/** The keyboard has started on [info]. Closing a sheet can start it over on the same field, and that
 *  must leave the next sheet alone, so only a different field counts. */
internal fun LLMKeyboardService.closeSheetLeftOnAnotherField(info: EditorInfo) {
    val opened = clipboardState.sheetField ?: return
    if (!isSameField(opened, info)) closeKeyboardSheet()
}

private fun isSameField(a: EditorInfo?, b: EditorInfo?): Boolean =
    a != null && b != null && a.packageName == b.packageName && a.fieldId == b.fieldId

internal fun LLMKeyboardService.pasteClipboardChip() {
    val ic = currentInputConnection ?: return
    val text = clipboardFullText
    if (text.isNotEmpty()) {
        beginSelfEdit()
        ic.commitText(text, 1)
        endSelfEdit()
        learningOnExternalCursorMove()
        // Pasted text is not necessarily the user's own writing — it
        // never enters recordWords / the learning streams.
    }
    clipboardFullText = ""
    predictionBar.setClipboardChip(null)
    updateAutoCaps()
    refreshPredictions()
}
