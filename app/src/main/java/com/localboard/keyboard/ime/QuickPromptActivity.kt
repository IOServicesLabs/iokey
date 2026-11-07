package com.localboard.keyboard.ime

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.localboard.keyboard.R

/** What the quick prompt's box shows, and where its answer goes. All of it runs on the main thread. */
internal class QuickPromptRequest(
    val message: String,
    val hint: String,
    val canRewrite: Boolean,
    val tooLongToRewrite: Boolean,
    val onWrite: (String) -> Unit,
    val onRewrite: (String) -> Unit,
    val onCancel: () -> Unit
)

/**
 * The quick prompt's box, in a window of its own.
 *
 * A dialog owned by the keyboard's window cannot do this job. Such a window can never be what the
 * keyboard types into: the first key closed the dialog, and the rest went into the app's field
 * behind it. This is a see-through activity with a real field in it, so the keyboard treats the
 * box like any other. The price is that the app's field loses its connection while the box is
 * open, and a draft has to wait for it to come back: see startWifmWhenReady.
 */
class QuickPromptActivity : Activity() {
    private var request: QuickPromptRequest? = null
    private var dialog: AlertDialog? = null

    /** A button has said what happens next. Any other ending is a cancel. */
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = pending
        if (req == null) {
            // Brought back by the system with nothing left to ask.
            finish()
            return
        }
        request = req
        val ctx = ContextThemeWrapper(this, R.style.Theme_LocalBoard)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(ctx).apply {
            hint = req.hint
            setPadding(pad, pad / 2, pad, pad / 2)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // Landscape's full-screen editor would put the box out of sight.
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            privateImeOptions = BOX_OPTION
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle("✨ Quick prompt")
            .setMessage(req.message)
            .setView(input)
            // Wired after show: a button given here closes the dialog, and a box with a
            // problem has to stay open to say so.
            .setPositiveButton("Write", null)
            .setNegativeButton("Cancel", null)
        // A rewrite replaces what is before the cursor, and committing it would take a selection too.
        if (req.canRewrite) builder.setNeutralButton("Rewrite this text", null)
        val shown = builder.create()
        shown.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        // A thumb landing beside the box must not cost a long instruction. Back and Cancel still close it.
        shown.setCanceledOnTouchOutside(false)
        shown.setOnDismissListener {
            endWithoutAnAnswer()
            finish()
        }
        shown.show()
        dialog = shown
        input.requestFocus()
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val instruction = input.text.toString().trim()
            if (instruction.isEmpty()) shown.setMessage("Say what to write first.")
            else answer { req.onWrite(instruction) }
        }
        shown.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { shown.dismiss() }
        if (req.canRewrite) {
            shown.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (req.tooLongToRewrite) {
                    shown.setMessage("That text is too long to rewrite here. Nothing changed. You can still say what to write.")
                } else {
                    answer { req.onRewrite(input.text.toString().trim()) }
                }
            }
        }
    }

    private fun answer(action: () -> Unit) {
        answered = true
        action()
        dialog?.dismiss()
    }

    /** Called as the box closes and not left to onDestroy, which can come seconds later. */
    private fun endWithoutAnAnswer() {
        if (answered) return
        answered = true
        request?.onCancel?.invoke()
    }

    override fun onDestroy() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        endWithoutAnAnswer()
        if (pending === request) pending = null
        super.onDestroy()
    }

    companion object {
        /** Tells the keyboard this field is the box, so it knows the app's field has not come back. */
        internal const val BOX_OPTION = "com.localboard.keyboard.quickPromptBox"

        /** Set just before this activity starts. Same process, and a closure will not fit in an intent. */
        internal var pending: QuickPromptRequest? = null
    }
}
