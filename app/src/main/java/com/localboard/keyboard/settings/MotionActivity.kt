package com.localboard.keyboard.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.localboard.keyboard.R
import com.localboard.keyboard.ui.LiveBackground
import com.localboard.keyboard.util.FrameStats
import com.localboard.keyboard.util.Prefs

/**
 * Animated background and the render-efficiency controls.
 *
 * These belong on one screen because they are the same tradeoff seen from two
 * sides: every one of them is a knob on how many pixels the keyboard lights up
 * and how many frames it draws to do it. Putting the counters here too means
 * the effect of a change is visible in the same place it was made.
 */
class MotionActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = getString(R.string.settings_motion)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, p, p, dp(32))
        }
        setContentView(ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        build()
    }

    private fun build() {
        root.removeAllViews()

        // ------------------------------------------------------ live background
        header("Live background")
        hint(
            "Drawn behind the keys. All of these render at reduced resolution " +
                "and reuse their paint objects, so the cost is a fraction of a " +
                "normal animation — but the cheapest option is still \"Off\"."
        )
        // A wallpaper replaces the background outright, so an animated style
        // chosen underneath it would do nothing at all — with no way to tell
        // that from the feature being broken. Say so.
        if (prefs.wallpaperPath.isNotEmpty()) {
            hint(
                "⚠ A keyboard wallpaper is set, and it replaces the background — " +
                    "no animated style will be visible until you clear it in " +
                    "Settings → Appearance."
            )
        }
        val current = LiveBackground.Style.fromId(prefs.liveBgStyle)
        for (style in LiveBackground.Style.entries) {
            val chosen = style == current
            val locked = style != LiveBackground.Style.NONE &&
                !com.localboard.keyboard.billing.Pro.isUnlocked
            button((if (chosen) "● " else "○ ") + style.label + (if (locked) "   (Pro)" else "")) {
                if (locked && !com.localboard.keyboard.billing.Pro.require(
                        this, com.localboard.keyboard.billing.ProFeature.ANIMATED_BACKGROUND
                    )
                ) return@button
                prefs.liveBgStyle = style.id
                build()
            }
            if (style == LiveBackground.Style.PULSE) {
                hint("Blooms on each keystroke and then stops — zero frames when idle.")
            }
        }

        if (current != LiveBackground.Style.NONE) {
            switchRow(
                "Animate only while typing",
                "Runs during a typing burst, then stops drawing completely. " +
                    "An idle keyboard should cost nothing.",
                prefs.liveBgWhileTyping
            ) { prefs.liveBgWhileTyping = it }

            slider(
                label = { "Frame rate: ${prefs.liveBgFps} fps" },
                hintText = "These animations move over tens of seconds. 15 fps is " +
                    "visually identical to 60 and draws a quarter as many frames.",
                max = 56,
                value = prefs.liveBgFps - 4
            ) { p -> prefs.liveBgFps = p + 4 }

            slider(
                label = { "Strength: ${(prefs.liveBgIntensity * 100).toInt()}%" },
                hintText = "Lower is dimmer, which on an OLED panel is also less power.",
                max = 100,
                value = (prefs.liveBgIntensity * 100).toInt()
            ) { p -> prefs.liveBgIntensity = p / 100f }
        }

        // ------------------------------------------------- navigation strip
        header("Behind the navigation bar")
        hint(
            "Android 15 lets the keyboard window extend behind the system " +
                "navigation bar. The keys are held clear of it so the bar still " +
                "works — this is what fills the space that leaves. Left empty " +
                "it is see-through, and the app behind shows through the bottom " +
                "of the keyboard."
        )
        val stripModes = listOf(
            0 to "Match the keyboard",
            1 to "A colour I pick",
            2 to "Animated",
            3 to "Off — give the space back"
        )
        for ((value, label) in stripModes) {
            val chosen = prefs.navStripMode == value
            val locked = value == 2 && !com.localboard.keyboard.billing.Pro.isUnlocked
            button((if (chosen) "● " else "○ ") + label + (if (locked) "   (Pro)" else "")) {
                if (locked && !com.localboard.keyboard.billing.Pro.require(
                        this, com.localboard.keyboard.billing.ProFeature.ANIMATED_BACKGROUND
                    )
                ) return@button
                prefs.navStripMode = value
                build()
            }
        }

        if (prefs.navStripMode == 3) {
            hint(
                "⚠ The keyboard gets shorter and the app above gets that space " +
                    "back — but the bottom key row then sits under the navigation " +
                    "bar, and the bar takes those taps first, so the arrow that " +
                    "hides the keyboard may stop responding. Swipe down to " +
                    "dismiss instead. Worth it on a small screen; annoying on a " +
                    "large one."
            )
        }

        if (prefs.navStripMode == 1) {
            colorRow("Strip colour", prefs.navStripColor) { c ->
                prefs.navStripColor = c
                build()
            }
        }

        if (prefs.navStripMode == 2) {
            hint("Style for the strip. It can differ from the keyboard's own background.")
            val stripStyle = LiveBackground.Style.fromId(prefs.navStripStyle)
            for (style in LiveBackground.Style.entries) {
                if (style == LiveBackground.Style.NONE) continue
                val chosen = style == stripStyle
                button((if (chosen) "● " else "○ ") + style.label) {
                    prefs.navStripStyle = style.id
                    build()
                }
            }
            hint(
                "Frame rate and strength are shared with the keyboard background " +
                    "above — one animation budget, not two."
            )
        }

        // -------------------------------------------------------------- pixels
        header("Pixels")
        switchRow(
            "Outline keys instead of filling them",
            "On an OLED screen power follows lit pixels, and 40 filled key faces " +
                "are most of what the keyboard emits. Pressed keys still fill, so " +
                "feedback stays obvious.",
            prefs.outlineKeys
        ) { prefs.outlineKeys = it }

        // -------------------------------------------------------------- frames
        header("Frames")
        switchRow(
            "Cap the refresh rate while typing",
            "A 120 Hz panel composites this window 120 times a second even when " +
                "one key changed. Typing asks for 60; a glide still gets the " +
                "panel's full rate, because the trail actually needs it.",
            prefs.throttleRefreshRate
        ) { prefs.throttleRefreshRate = it }

        switchRow(
            "Merge haptics during fast typing",
            "The vibration motor is the most power-hungry thing a keypress can " +
                "start. Two keys within 40 ms share one pulse — the motor is " +
                "still ringing from the first anyway.",
            prefs.coalesceHaptics
        ) { prefs.coalesceHaptics = it }

        // --------------------------------------------------------------- meters
        header("Counters")
        switchRow(
            "Track render counters",
            "Frames drawn, how many of them repainted the whole keyboard, and how " +
                "many frames each keystroke costs. Under about 4 frames per " +
                "keystroke is healthy; 40 means every keypress is repainting the " +
                "entire grid.",
            prefs.showFrameStats
        ) { prefs.showFrameStats = it; build() }

        if (prefs.showFrameStats) {
            mono(FrameStats.summary())
            button("Reset counters") { FrameStats.reset(); build() }
            button("Refresh") { build() }
            hint(
                "Cross-check externally with:\n" +
                    "adb shell dumpsys gfxinfo com.localboard.keyboard framestats"
            )
        }
    }

    // ------------------------------------------------------------- widgets

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String) {
        root.addView(TextView(this).apply {
            setText(text)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MotionActivity, R.color.app_text))
            setPadding(0, dp(20), 0, dp(4))
        })
    }

    private fun hint(text: String) {
        root.addView(TextView(this).apply {
            setText(text)
            textSize = 12f
            alpha = 0.7f
            setTextColor(ContextCompat.getColor(this@MotionActivity, R.color.app_text))
            setPadding(0, dp(2), 0, dp(6))
        })
    }

    private fun mono(text: String) {
        root.addView(TextView(this).apply {
            setText(text)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(ContextCompat.getColor(this@MotionActivity, R.color.app_text))
            setPadding(0, dp(6), 0, dp(8))
        })
    }

    private fun switchRow(
        label: String,
        hintText: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        root.addView(MaterialSwitch(this).apply {
            text = label
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        hint(hintText)
    }

    /**
     * A button that shows the current colour and opens a picker.
     *
     * The swatch is drawn as the button's own background rather than described
     * in words, because "what colour is it currently" is the entire question
     * being asked here.
     */
    private fun colorRow(label: String, current: Int, onPick: (Int) -> Unit) {
        val btn = MaterialButton(this).apply {
            text = label
            setBackgroundColor(current)
            // Keep the label readable whatever the user picks.
            setTextColor(
                if (androidx.core.graphics.ColorUtils.calculateLuminance(current) > 0.5)
                    android.graphics.Color.BLACK else android.graphics.Color.WHITE
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        btn.setOnClickListener {
            val picker = com.localboard.keyboard.ui.ColorPickerView(this).apply {
                color = current
            }
            var chosen = current
            picker.onColorChanged = { c -> chosen = c }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = dp(20)
                setPadding(p, p, p, 0)
                addView(picker)
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(label)
                .setView(container)
                .setPositiveButton(R.string.theme_done) { _, _ -> onPick(chosen) }
                .show()
        }
        root.addView(btn)
    }

    private fun button(label: String, onClick: () -> Unit) {
        root.addView(MaterialButton(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        })
    }

    private fun slider(
        label: () -> String,
        hintText: String,
        max: Int,
        value: Int,
        onChange: (Int) -> Unit
    ) {
        val text = TextView(this).apply {
            setText(label())
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MotionActivity, R.color.app_text))
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(text)
        root.addView(SeekBar(this).apply {
            this.max = max
            progress = value.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    onChange(p)
                    text.text = label()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        hint(hintText)
    }
}
