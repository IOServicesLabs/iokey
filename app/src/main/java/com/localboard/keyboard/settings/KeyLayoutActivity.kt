package com.localboard.keyboard.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.localboard.keyboard.R
import com.localboard.keyboard.ui.KeyboardLayouts
import com.localboard.keyboard.ui.KeyboardPreviewView
import com.localboard.keyboard.ui.KeyboardTheme
import com.localboard.keyboard.util.Prefs

/**
 * Pick exactly which keys the keyboard has.
 *
 * Every optional key already existed as a scattered switch under "Keyboard
 * layout"; the problem was that you could not see what any of them did without
 * closing settings and opening the keyboard. This screen puts the same choices
 * next to a live preview and leads with presets, so the common answer is one
 * tap and the fiddly answer is still available underneath.
 *
 * The presets are named after what they are for, not after which switches they
 * flip. "Gboard" is the reference layout most people already have muscle
 * memory for.
 */
class KeyLayoutActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var preview: KeyboardPreviewView

    /** A named set of key choices. [layoutId] is null for presets that leave
     *  the letter arrangement (QWERTY/AZERTY/…) alone. */
    private data class Preset(
        val name: String,
        val blurb: String,
        val emoji: Boolean,
        val comma: Boolean,
        val apostrophe: Boolean,
        val selection: Boolean,
        val numberRow: Boolean,
        val symbolsDensity: Int,
        val bracketPos: Int,
        val layoutId: String? = null
    )

    private val presets = listOf(
        Preset(
            "Gboard", "What most thumbs already know: Gboard's letters, symbols pages, " +
                "emoji key, comma, period — every key where Gboard puts it.",
            emoji = true, comma = true, apostrophe = false, selection = false,
            numberRow = false, symbolsDensity = 0, bracketPos = KeyboardLayouts.BRACKET_HIDDEN,
            layoutId = "qwerty"
        ),
        Preset(
            "Roomy", "Drops the emoji key so the space bar is as wide as it gets.",
            emoji = false, comma = true, apostrophe = false, selection = false,
            numberRow = false, symbolsDensity = 0, bracketPos = KeyboardLayouts.BRACKET_HIDDEN
        ),
        Preset(
            "Writer", "Apostrophe gets its own key — English types more of those than commas.",
            emoji = false, comma = true, apostrophe = true, selection = false,
            numberRow = false, symbolsDensity = 0, bracketPos = KeyboardLayouts.BRACKET_HIDDEN
        ),
        Preset(
            "Coder", "Number row always up, every symbol on page one, brackets on the left.",
            emoji = false, comma = true, apostrophe = true, selection = true,
            numberRow = true, symbolsDensity = 1, bracketPos = KeyboardLayouts.BRACKET_LEFT
        ),
        Preset(
            "Everything", "All optional keys on. The most keys, the narrowest space bar.",
            emoji = true, comma = true, apostrophe = true, selection = true,
            numberRow = true, symbolsDensity = 0, bracketPos = KeyboardLayouts.BRACKET_LEFT
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = getString(R.string.settings_keys)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, p, p, dp(32))
        }
        preview = KeyboardPreviewView(this)
        setContentView(ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        build()
    }

    private fun matches(p: Preset): Boolean =
        p.emoji == prefs.bottomEmojiKey &&
            p.comma == prefs.bottomCommaKey &&
            p.apostrophe == prefs.bottomApostropheKey &&
            p.selection == prefs.selectionKey &&
            p.numberRow == prefs.numberRow &&
            p.symbolsDensity == prefs.symbolsDensity &&
            p.bracketPos == prefs.symbolsBracketPos &&
            (p.layoutId == null || p.layoutId == prefs.layoutId)

    private fun apply(p: Preset) {
        prefs.bottomEmojiKey = p.emoji
        prefs.bottomCommaKey = p.comma
        prefs.bottomApostropheKey = p.apostrophe
        prefs.selectionKey = p.selection
        prefs.numberRow = p.numberRow
        prefs.symbolsDensity = p.symbolsDensity
        prefs.symbolsBracketPos = p.bracketPos
        if (p.layoutId != null) {
            prefs.layoutId = p.layoutId
            // Keep autocorrect's fat-finger model in step, same as the layout
            // picker in SettingsActivity does.
            com.localboard.keyboard.engine.FastEngine.setKeyboardRows(
                KeyboardLayouts.letterRows(p.layoutId)
            )
        }
        build()
    }

    private fun build() {
        root.removeAllViews()

        // ------------------------------------------------------------ preview
        root.addView(
            preview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        refreshPreview()
        hint("Live preview — every change below shows up here immediately.")

        // ------------------------------------------------------------ presets
        header("Start from a preset")
        for (p in presets) {
            val chosen = matches(p)
            button((if (chosen) "● " else "○ ") + p.name) { apply(p) }
            hint(p.blurb)
        }

        // ------------------------------------------------------------ keys
        header("Or pick keys individually")
        hint(
            "Every key you switch off hands its width to the space bar, so fewer " +
                "keys means bigger keys — not gaps."
        )

        switchRow("Emoji key", "Off: emoji are still one long-press away on the comma.",
            prefs.bottomEmojiKey) { prefs.bottomEmojiKey = it; refreshPreview() }

        switchRow("Comma key", "Its long-press carries ! ? ; : and the apostrophe.",
            prefs.bottomCommaKey) { prefs.bottomCommaKey = it; refreshPreview() }

        switchRow("Apostrophe key", "A dedicated key rather than a long-press.",
            prefs.bottomApostropheKey) { prefs.bottomApostropheKey = it; refreshPreview() }

        switchRow(
            "Cursor and selection key",
            "Opens arrows, select, cut, copy and paste. Off by default — it is " +
                "one more key competing for the bottom row.",
            prefs.selectionKey
        ) { prefs.selectionKey = it; refreshPreview() }

        switchRow("Number row", "A permanent row of digits above the letters.",
            prefs.numberRow) { prefs.numberRow = it; refreshPreview() }

        switchRow(
            "Long-press for numbers and symbols",
            "Gboard's hints: hold the top row for 1-0, the other letters for " +
                "@ # \$ _ & - + ( ) * \" ' : ; ! ?. Independent of the number row.",
            prefs.digitPopups
        ) { prefs.digitPopups = it }

        // ------------------------------------------------------------ symbols
        header("Symbols page")
        switchRow(
            "Fit every symbol on page one",
            "Off (roomy): symbol keys are exactly as wide as letter keys. " +
                "On: more symbols, all of them narrower.",
            prefs.symbolsDensity == 1
        ) { prefs.symbolsDensity = if (it) 1 else 0 }

        val bracketName = KeyboardLayouts.BRACKET_CHOICES
            .firstOrNull { it.first == prefs.symbolsBracketPos }?.second ?: "Left"
        button("Bracket key: $bracketName") {
            val choices = KeyboardLayouts.BRACKET_CHOICES
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.layout_brackets)
                .setSingleChoiceItems(
                    choices.map { it.second }.toTypedArray(),
                    choices.indexOfFirst { it.first == prefs.symbolsBracketPos }.coerceAtLeast(0)
                ) { dlg, which ->
                    prefs.symbolsBracketPos = choices[which].first
                    dlg.dismiss()
                    build()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        hint("Gboard has no bracket key on ?123 (hold ( or ) instead, or use =\\<); " +
            "add one here if you want it.")
    }

    private fun refreshPreview() {
        preview.refresh(
            prefs.heightScale, prefs.keyTextScale,
            KeyboardTheme.load(this, prefs)
        )
        preview.setKeys(
            emoji = prefs.bottomEmojiKey,
            comma = prefs.bottomCommaKey,
            apostrophe = prefs.bottomApostropheKey,
            selection = prefs.selectionKey,
            numberRow = prefs.numberRow
        )
    }

    // ------------------------------------------------------------- widgets

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String) {
        root.addView(TextView(this).apply {
            setText(text)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@KeyLayoutActivity, R.color.app_text))
            setPadding(0, dp(20), 0, dp(4))
        })
    }

    private fun hint(text: String) {
        root.addView(TextView(this).apply {
            setText(text)
            textSize = 12f
            alpha = 0.7f
            setTextColor(ContextCompat.getColor(this@KeyLayoutActivity, R.color.app_text))
            setPadding(0, dp(2), 0, dp(6))
        })
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
}
