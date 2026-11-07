package io.ioservices.iokey.e2e

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import java.util.concurrent.atomic.AtomicInteger

/**
 * The app under the keyboard: one text field of each kind real apps present, and a record of every touch
 * that reached this window. Tests read the fields directly, which is far faster and more reliable than
 * dumping the view tree, and they read the touch record to tell a tap the keyboard took from one that
 * fell through it to the app.
 *
 * All view access must happen on the main thread. Tests go through the harness's Host wrapper for that.
 */
open class HostActivity : Activity() {

    private val fields = LinkedHashMap<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), 0)
            // Whatever part of the window has no field is still this window: a touch that falls through
            // the keyboard's touchable area must be counted, never swallowed by a gap in the layout.
            isClickable = true
        }

        fun addField(tag: String, type: Int, heightDp: Int, ime: Int = EditorInfo.IME_ACTION_NONE) {
            val field = EditText(this).apply {
                // A stable id so the framework restores the text if the window is ever recreated.
                id = fields.size + 1
                this.tag = tag
                contentDescription = tag
                hint = tag
                inputType = type
                imeOptions = ime
                gravity = Gravity.TOP or Gravity.START
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                textSize = 15f
                setOnEditorActionListener { _, _, _ ->
                    editorActions.incrementAndGet()
                    true
                }
                // Counted and left to the framework, so the harness can tell a tap that landed as a long press.
                setOnLongClickListener {
                    fieldLongClicks.incrementAndGet()
                    false
                }
            }
            fields[tag] = field
            root.addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)))
        }

        val autoCorrected = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        addField(CHAT, autoCorrected or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 96)
        addField(SINGLE, autoCorrected, 44)
        addField(SEND, autoCorrected or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 44, EditorInfo.IME_ACTION_SEND)
        addField(URL, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 44, EditorInfo.IME_ACTION_GO)
        addField(EMAIL, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 44)
        addField(PASSWORD, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 44)
        addField(NO_SUGGESTIONS, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 44)
        addField(PRIVATE, autoCorrected, 44, EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        addField(WEB, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, 44)
        setContentView(root)
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /** Back with no keyboard up would otherwise end the test's window. No test wants that. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) appTouchDowns.incrementAndGet()
        return super.dispatchTouchEvent(ev)
    }

    fun field(tag: String): EditText =
        fields[tag] ?: throw IllegalArgumentException("No field called $tag")

    fun fieldRectOnScreen(tag: String): Rect {
        val v = field(tag)
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        return Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
    }

    /** The composing region, or null when there is none. */
    fun composingRange(tag: String): IntRange? {
        val text = field(tag).text
        val start = BaseInputConnection.getComposingSpanStart(text)
        val end = BaseInputConnection.getComposingSpanEnd(text)
        return if (start < 0 || end < 0) null else start until end
    }

    /** What the navigation or gesture bar occupies at the bottom of the screen right now. */
    fun navigationBarBottomPx(): Int =
        window.decorView.rootWindowInsets
            ?.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())?.bottom ?: 0

    fun windowSizePx(): Pair<Int, Int> {
        val bounds = windowManager.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    companion object {
        const val CHAT = "chat"
        const val SINGLE = "single"
        const val SEND = "send"
        const val URL = "url"
        const val EMAIL = "email"
        const val PASSWORD = "password"
        const val NO_SUGGESTIONS = "nosuggest"
        const val PRIVATE = "private"
        const val WEB = "web"

        /** The live instance. It changes only if the framework recreates the window after all. */
        @Volatile
        var current: HostActivity? = null
            private set

        /** ACTION_DOWNs that reached this window, whether or not a field consumed them. */
        val appTouchDowns = AtomicInteger(0)

        /** Editor actions (send, go, done) the keyboard performed on any field. */
        val editorActions = AtomicInteger(0)

        /** Long clicks the framework gave any field. A tap on a stalled CPU can land as one. */
        val fieldLongClicks = AtomicInteger(0)
    }
}

/**
 * The same window for an app that lets the framework recreate it on a configuration change, as most apps
 * do. A recreated window starts input afresh, which reaches the keyboard by a different route than a window
 * that handles the change itself, and the keyboard has to come up right after both.
 */
class RecreatingHostActivity : HostActivity()

/** The same window for an app that hides the system's bars, as a video player does. */
class ImmersiveHostActivity : HostActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
    }
}
