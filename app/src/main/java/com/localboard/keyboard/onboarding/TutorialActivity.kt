package com.localboard.keyboard.onboarding

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.localboard.keyboard.R
import com.localboard.keyboard.util.Prefs

/**
 * The 60-second tour.
 *
 * IOKey has a lot of behaviour that is invisible until someone tells you
 * it exists — glide typing, the swipe-up word picker, backspace-to-undo an
 * autocorrection, long-press ✨ for a prompt, space-bar cursor dragging.
 * Settings can't teach that; a wall of switches is where discoverability goes
 * to die. So: one card at a time, one idea each, skippable forever.
 *
 * Deliberately dependency-free (no ViewPager2, no new layout XML) so it stays
 * a single file that can't drift out of sync with the rest of the app.
 */
class TutorialActivity : AppCompatActivity() {

    private data class Card(
        val glyph: String,
        val title: String,
        val body: String,
        /** Optional "try it" line rendered in the accent colour. */
        val tryIt: String? = null
    )

    private lateinit var prefs: Prefs
    private var index = 0

    private lateinit var glyphView: TextView
    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var tryView: TextView
    private lateinit var dots: LinearLayout
    private lateinit var nextButton: MaterialButton
    private lateinit var backButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = getString(R.string.tutorial_title)

        // Named colorX, not text/bg: a local `val text` would shadow
        // TextView.text inside the apply blocks below (Kotlin resolves the
        // local before the implicit receiver's member).
        val colorBg = ContextCompat.getColor(this, R.color.app_bg)
        val colorText = ContextCompat.getColor(this, R.color.app_text)
        val colorSecondary = ContextCompat.getColor(this, R.color.app_text_secondary)
        val colorAccent = ContextCompat.getColor(this, R.color.app_primary)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            val pad = dp(24)
            setPadding(pad, dp(16), pad, dp(16))
        }
        setContentView(root)

        // ── Skip, top-right ──────────────────────────────────────────────
        root.addView(TextView(this).apply {
            text = getString(R.string.tutorial_skip)
            textSize = 14f
            setTextColor(colorSecondary)
            gravity = Gravity.END
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { finishTutorial() }
        })

        // ── The card ─────────────────────────────────────────────────────
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_settings_card)
            val pad = dp(24)
            setPadding(pad, dp(32), pad, dp(32))
        }

        glyphView = TextView(this).apply {
            textSize = 46f
            gravity = Gravity.CENTER
        }
        card.addView(glyphView)

        titleView = TextView(this).apply {
            textSize = 21f
            setTextColor(colorText)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14), 0, 0)
        }
        card.addView(titleView)

        bodyView = TextView(this).apply {
            textSize = 15f
            setTextColor(colorSecondary)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(bodyView)

        tryView = TextView(this).apply {
            textSize = 14f
            setTextColor(colorAccent)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, 0)
        }
        card.addView(tryView)

        // The card scrolls on small screens / large font sizes rather than
        // clipping the body text.
        root.addView(
            ScrollView(this).apply {
                addView(card)
                isFillViewport = true
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        // ── Progress dots ────────────────────────────────────────────────
        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(16))
        }
        root.addView(dots)

        // ── Back / Next ──────────────────────────────────────────────────
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        backButton = MaterialButton(
            this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.tutorial_back)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { if (index > 0) show(index - 1) }
        }
        buttons.addView(backButton)

        nextButton = MaterialButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f
            ).apply { marginStart = dp(10) }
            setOnClickListener {
                if (index < CARDS.lastIndex) show(index + 1) else finishTutorial()
            }
        }
        buttons.addView(nextButton)
        root.addView(buttons)

        buildDots(colorSecondary, colorAccent)
        show(0)
    }

    private fun buildDots(idle: Int, active: Int) {
        dotIdle = idle
        dotActive = active
        dots.removeAllViews()
        repeat(CARDS.size) {
            dots.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
            })
        }
    }

    private var dotIdle = 0
    private var dotActive = 0

    private fun show(i: Int) {
        index = i.coerceIn(0, CARDS.lastIndex)
        val c = CARDS[index]
        glyphView.text = c.glyph
        titleView.text = c.title
        bodyView.text = c.body
        tryView.text = c.tryIt ?: ""
        tryView.visibility = if (c.tryIt == null) View.GONE else View.VISIBLE

        for (d in 0 until dots.childCount) {
            val dot = dots.getChildAt(d)
            val on = d == index
            dot.setBackgroundColor(if (on) dotActive else dotIdle)
            dot.alpha = if (on) 1f else 0.3f
        }

        backButton.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        nextButton.text = getString(
            if (index == CARDS.lastIndex) R.string.tutorial_done else R.string.tutorial_next
        )

        // A small rise-and-fade so moving between cards reads as movement
        // rather than a hard swap.
        for (v in listOf(glyphView, titleView, bodyView, tryView)) {
            v.alpha = 0f
            v.translationY = dp(10).toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
    }

    private fun finishTutorial() {
        prefs.tutorialSeen = true
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Six cards, each teaching exactly one thing that isn't discoverable
         *  by looking at the keyboard. Order is "you'll use this today" first. */
        private val CARDS = listOf(
            Card(
                "⌨️", "Everything stays on your phone",
                "IOKey learns how you write and drafts replies using a model " +
                    "that runs on this device. Your typing is never uploaded — there " +
                    "is no analytics, no telemetry, no account.",
                "Privacy dashboard lives in Settings"
            ),
            Card(
                "👆", "Glide to type",
                "Slide your finger across the letters without lifting it and " +
                    "IOKey works out the word. Lift anywhere to commit it; " +
                    "the alternatives appear in the bar above.",
                "Try gliding h-e-l-l-o in one stroke"
            ),
            Card(
                "↩️", "Fix a wrong autocorrect instantly",
                "When a word gets corrected and you didn't want it, press " +
                    "backspace once. Your original comes straight back, and " +
                    "IOKey stops correcting that word.",
                "Backspace right after = undo"
            ),
            Card(
                "✨", "Let it write the reply",
                "Tap the sparkle and IOKey drafts a reply in your own voice, " +
                    "using the conversation on screen. Hold the sparkle instead to " +
                    "tell it what to say — \"say I'm running 10 minutes late\".",
                "Tap to draft · hold to instruct"
            ),
            Card(
                "⇔", "The space bar is a trackpad",
                "Slide left or right along the space bar to move the cursor one " +
                    "character at a time — much easier than aiming at the text. " +
                    "Hold it to switch to another keyboard.",
                "Slide space to move the cursor"
            ),
            Card(
                "🎛️", "Make it fit your thumbs",
                "Keyboard height, key spacing, which keys appear in the bottom " +
                    "row, themes and the number row are all adjustable. If the " +
                    "keys feel cramped, start with Layout in Settings.",
                "Settings → Keyboard layout"
            )
        )
    }
}
