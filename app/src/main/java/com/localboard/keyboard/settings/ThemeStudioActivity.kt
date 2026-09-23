package com.localboard.keyboard.settings

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.localboard.keyboard.R
import com.localboard.keyboard.ui.ColorPickerView
import com.localboard.keyboard.ui.KeyboardPreviewView
import com.localboard.keyboard.ui.KeyboardTheme
import com.localboard.keyboard.ui.ThemeSpec
import com.localboard.keyboard.util.Prefs

/**
 * Theme studio.
 *
 * The old flow was four hex text boxes, an Apply button, and "reopen the
 * keyboard to see it". This replaces all of that:
 *
 *  - a live preview of the *actual* keyboard at the top, updating on drag;
 *  - **Simple** mode: pick a background and an accent, everything else is
 *    derived as matching pastels with contrast guaranteed;
 *  - **Every colour** mode: all ten surfaces individually;
 *  - save named themes, and share/import them as a short code.
 */
class ThemeStudioActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var preview: KeyboardPreviewView
    private lateinit var swatchRow: LinearLayout
    private lateinit var contrastNote: TextView
    private lateinit var savedRow: LinearLayout

    /** The theme being edited. */
    private lateinit var spec: ThemeSpec

    /** Simple mode derives everything from background + accent. */
    private var simpleMode = true

    private data class Slot(val label: String, val get: () -> Int, val set: (Int) -> Unit)

    /**
     * Import a .ttf/.otf as the key font.
     *
     * Copied into app storage rather than referenced by URI: the keyboard
     * loads its typeface on every process start, long after the picker's
     * permission grant has lapsed.
     */
    private val pickFont = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val dest = com.localboard.keyboard.util.FontCache.customFontFile(this)
        val ok = try {
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            // Prove it parses before committing the user to it.
            android.graphics.Typeface.createFromFile(dest) != null
        } catch (_: Throwable) {
            false
        }
        if (!ok) {
            dest.delete()
            android.widget.Toast.makeText(
                this, "That file is not a usable font", android.widget.Toast.LENGTH_LONG
            ).show()
            return@registerForActivityResult
        }
        com.localboard.keyboard.util.FontCache.invalidate(ThemeSpec.FONT_CUSTOM)
        spec = spec.copy(fontId = ThemeSpec.FONT_CUSTOM)
        renderPreview()
        android.widget.Toast.makeText(
            this, "Font imported", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = getString(R.string.theme_studio_title)

        spec = ThemeSpec.decode(prefs.activeThemeCode)
            ?: ThemeSpec.fromTheme("My theme", KeyboardTheme.load(this, prefs))

        val colText = ContextCompat.getColor(this, R.color.app_text)
        val colSecondary = ContextCompat.getColor(this, R.color.app_text_secondary)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@ThemeStudioActivity, R.color.app_bg))
            val p = dp(16)
            setPadding(p, dp(12), p, dp(20))
        }
        setContentView(ScrollView(this).apply { addView(root); isFillViewport = true })

        // ── Live preview ─────────────────────────────────────────────────
        preview = KeyboardPreviewView(this)
        root.addView(preview)
        root.addView(TextView(this).apply {
            text = getString(R.string.theme_preview_hint)
            textSize = 11.5f
            setTextColor(colSecondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        })

        // ── Mode switch: Simple vs Every colour ──────────────────────────
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var simpleBtn: MaterialButton
        lateinit var fullBtn: MaterialButton
        fun renderMode() {
            simpleBtn.alpha = if (simpleMode) 1f else 0.45f
            fullBtn.alpha = if (simpleMode) 0.45f else 1f
            buildSwatches()
        }
        simpleBtn = MaterialButton(this).apply {
            text = getString(R.string.theme_mode_simple)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { simpleMode = true; renderMode() }
        }
        fullBtn = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.theme_mode_full)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) }
            setOnClickListener { simpleMode = false; renderMode() }
        }
        modeRow.addView(simpleBtn)
        modeRow.addView(fullBtn)
        root.addView(modeRow)

        // ── Swatches ─────────────────────────────────────────────────────
        swatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(swatchRow)

        contrastNote = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(contrastNote)

        // ── Corner radius ────────────────────────────────────────────────
        root.addView(sectionLabel(getString(R.string.theme_radius), colText))
        val radiusSeek = android.widget.SeekBar(this).apply {
            max = 20
            progress = spec.radiusDp.toInt().coerceIn(0, 20)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, user: Boolean) {
                    if (!user) return
                    spec = spec.copy(radiusDp = p.toFloat())
                    renderPreview()
                }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
            })
        }
        root.addView(radiusSeek)

        // ── Key font ─────────────────────────────────────────────────────
        root.addView(sectionLabel(getString(R.string.theme_font), colText))
        val fontScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val fontRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // availableFonts(), not ThemeSpec.FONTS: several candidates alias to
        // the same face on any given device, and listing them all is what made
        // the picker look like seven copies of Roboto and one serif.
        for ((id, label) in com.localboard.keyboard.util.FontCache.availableFonts()) {
            fontRow.addView(MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                typeface = KeyboardTheme(0, 0, 0, 0, 0, 0, 0, 0, 0, false, fontId = id).typeface()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    if (id == ThemeSpec.FONT_CUSTOM &&
                        !com.localboard.keyboard.billing.Pro.require(
                            this@ThemeStudioActivity,
                            com.localboard.keyboard.billing.ProFeature.CUSTOM_FONT
                        )
                    ) return@setOnClickListener
                    if (id == ThemeSpec.FONT_CUSTOM &&
                        !com.localboard.keyboard.util.FontCache.hasCustomFont(
                            this@ThemeStudioActivity
                        )
                    ) {
                        // Nothing imported yet — go get one instead of
                        // selecting a font that would silently be the default.
                        pickFont.launch(arrayOf("font/*", "application/octet-stream", "*/*"))
                        return@setOnClickListener
                    }
                    spec = spec.copy(fontId = id)
                    renderPreview()
                }
            })
        }
        fontScroll.addView(fontRow)
        root.addView(fontScroll)

        // ── Saved themes ─────────────────────────────────────────────────
        root.addView(sectionLabel(getString(R.string.theme_saved), colText))
        savedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(savedRow)
        })

        // ── Actions ──────────────────────────────────────────────────────
        root.addView(MaterialButton(this).apply {
            text = getString(R.string.theme_apply)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
            setOnClickListener { applyTheme() }
        })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actionRow.addView(outlined(getString(R.string.theme_save)) { promptSave() })
        actionRow.addView(outlined(getString(R.string.theme_share)) { shareTheme() })
        actionRow.addView(outlined(getString(R.string.theme_import)) { promptImport() })
        root.addView(actionRow)

        renderMode()
        renderSaved()
        renderPreview()
    }

    // ── rendering ────────────────────────────────────────────────────────

    private fun renderPreview() {
        preview.refresh(prefs.heightScale, prefs.keyTextScale, spec.toTheme())
        preview.setBottomRow(
            prefs.bottomEmojiKey, prefs.bottomCommaKey, prefs.bottomApostropheKey
        )
        // Honest, immediate readability feedback — the reason a theme fails is
        // almost always contrast, and users can't eyeball a 4.5:1 ratio.
        val onKey = ThemeSpec.contrast(spec.keyText, spec.key)
        val onBar = ThemeSpec.contrast(spec.text, spec.background)
        val worst = minOf(onKey, onBar)
        contrastNote.text = getString(
            R.string.theme_contrast_fmt, String.format("%.1f", worst)
        ) + when {
            worst >= 4.5f -> "  ✓ ${getString(R.string.theme_contrast_good)}"
            worst >= 3.0f -> "  ⚠ ${getString(R.string.theme_contrast_low)}"
            else -> "  ✕ ${getString(R.string.theme_contrast_bad)}"
        }
        contrastNote.setTextColor(
            when {
                worst >= 4.5f -> 0xFF2E7D32.toInt()
                worst >= 3.0f -> 0xFFEF6C00.toInt()
                else -> 0xFFC62828.toInt()
            }
        )
    }

    private fun slots(): List<Slot> = if (simpleMode) listOf(
        Slot(getString(R.string.theme_c_background), { spec.background }) { c ->
            // Simple mode: one colour drives the whole family.
            spec = ThemeSpec.auto(spec.name, c, spec.accent, spec.radiusDp, spec.fontId)
        },
        Slot(getString(R.string.theme_c_accent), { spec.accent }) { c ->
            spec = ThemeSpec.auto(spec.name, spec.background, c, spec.radiusDp, spec.fontId)
        }
    ) else listOf(
        Slot(getString(R.string.theme_c_background), { spec.background }) { spec = spec.copy(background = it) },
        Slot(getString(R.string.theme_c_key), { spec.key }) { spec = spec.copy(key = it) },
        Slot(getString(R.string.theme_c_keyfunc), { spec.keyFunctional }) { spec = spec.copy(keyFunctional = it) },
        Slot(getString(R.string.theme_c_keytext), { spec.keyText }) { spec = spec.copy(keyText = it) },
        Slot(getString(R.string.theme_c_text), { spec.text }) { spec = spec.copy(text = it) },
        Slot(getString(R.string.theme_c_text2), { spec.textSecondary }) { spec = spec.copy(textSecondary = it) },
        Slot(getString(R.string.theme_c_accent), { spec.accent }) { spec = spec.copy(accent = it) },
        Slot(getString(R.string.theme_c_divider), { spec.divider }) { spec = spec.copy(divider = it) },
        Slot(getString(R.string.theme_c_popup), { spec.popupBackground }) { spec = spec.copy(popupBackground = it) },
        Slot(getString(R.string.theme_c_trail), { spec.trail }) { spec = spec.copy(trail = it) }
    )

    private fun buildSwatches() {
        swatchRow.removeAllViews()
        if (simpleMode) {
            swatchRow.addView(TextView(this).apply {
                text = getString(R.string.theme_simple_hint)
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@ThemeStudioActivity, R.color.app_text_secondary))
                setPadding(0, 0, 0, dp(10))
            })
        }
        // Two per row keeps the tap targets big on a phone.
        var row: LinearLayout? = null
        slots().forEachIndexed { i, slot ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(8))
                }
                swatchRow.addView(row)
            }
            row?.addView(swatchChip(slot))
        }
    }

    private fun swatchChip(slot: Slot): View {
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_settings_inner)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8) }
        }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(slot.get())
                setStroke(dp(1), 0x33000000)
            }
        }
        chip.addView(dot)
        chip.addView(TextView(this).apply {
            text = slot.label
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ThemeStudioActivity, R.color.app_text))
        })
        chip.setOnClickListener { openPicker(slot) }
        return chip
    }

    private fun openPicker(slot: Slot) {
        val picker = ColorPickerView(this).apply { color = slot.get() }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20)
            setPadding(p, p, p, 0)
            addView(picker)
        }
        picker.onColorChanged = { c ->
            slot.set(c)
            renderPreview()
            buildSwatches()
        }
        AlertDialog.Builder(this)
            .setTitle(slot.label)
            .setView(container)
            .setPositiveButton(R.string.theme_done, null)
            .show()
    }

    private fun renderSaved() {
        savedRow.removeAllViews()
        val codes = prefs.savedThemes
        if (codes.isEmpty()) {
            savedRow.addView(TextView(this).apply {
                text = getString(R.string.theme_none_saved)
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@ThemeStudioActivity, R.color.app_text_secondary))
            })
            return
        }
        for (code in codes) {
            val s = ThemeSpec.decode(code) ?: continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = ContextCompat.getDrawable(context, R.drawable.bg_settings_inner)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
            // A three-dot smear of the theme's key colours as its thumbnail.
            val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in listOf(s.background, s.key, s.accent)) {
                dots.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                        .apply { marginEnd = dp(3) }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(c)
                        setStroke(dp(1), 0x33000000)
                    }
                })
            }
            card.addView(dots)
            card.addView(TextView(this).apply {
                text = s.name
                textSize = 11.5f
                setPadding(0, dp(6), 0, 0)
                setTextColor(ContextCompat.getColor(this@ThemeStudioActivity, R.color.app_text))
            })
            card.setOnClickListener {
                spec = s
                simpleMode = false
                buildSwatches()
                renderPreview()
                Toast.makeText(this, getString(R.string.theme_loaded_fmt, s.name), Toast.LENGTH_SHORT).show()
            }
            card.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle(s.name)
                    .setMessage(R.string.theme_delete_confirm)
                    .setPositiveButton(R.string.theme_delete) { _, _ ->
                        prefs.deleteTheme(code)
                        renderSaved()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            savedRow.addView(card)
        }
    }

    // ── actions ──────────────────────────────────────────────────────────

    private fun applyTheme() {
        prefs.activeThemeCode = spec.encode()
        prefs.paletteId = "custom"
        // Clear the legacy 4-colour overrides so they can't fight the spec.
        prefs.customBg = Int.MIN_VALUE
        prefs.customKey = Int.MIN_VALUE
        prefs.customText = Int.MIN_VALUE
        prefs.customAccent = Int.MIN_VALUE
        prefs.keyFontId = spec.fontId
        prefs.keyRadiusDp = spec.radiusDp
        KeyboardTheme.invalidateCache()
        Toast.makeText(this, R.string.theme_applied, Toast.LENGTH_SHORT).show()
    }

    private fun promptSave() {
        val input = EditText(this).apply {
            setText(spec.name)
            setSingleLine()
            val p = dp(20)
            setPadding(p, dp(10), p, 0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.theme_save)
            .setView(input)
            .setPositiveButton(R.string.theme_save) { _, _ ->
                val name = input.text.toString().trim().ifBlank { "My theme" }
                spec = spec.copy(name = name)
                prefs.saveTheme(spec.encode())
                renderSaved()
                Toast.makeText(this, getString(R.string.theme_saved_fmt, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareTheme() {
        val code = spec.encode()
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        getString(R.string.theme_share_body_fmt, spec.name, code)
                    )
                },
                getString(R.string.theme_share)
            )
        )
    }

    private fun promptImport() {
        val input = EditText(this).apply {
            hint = "LB1…"
            setSingleLine()
            val p = dp(20)
            setPadding(p, dp(10), p, 0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.theme_import)
            .setMessage(R.string.theme_import_hint)
            .setView(input)
            .setPositiveButton(R.string.theme_import) { _, _ ->
                // Accept a pasted message containing the code, not just the
                // bare token — people share these inside a sentence.
                val raw = input.text.toString()
                val token = Regex("LB1[A-Za-z0-9_\\-]+").find(raw)?.value ?: raw.trim()
                val s = ThemeSpec.decode(token)
                if (s == null) {
                    Toast.makeText(this, R.string.theme_import_bad, Toast.LENGTH_LONG).show()
                } else {
                    spec = s
                    simpleMode = false
                    buildSwatches()
                    renderPreview()
                    Toast.makeText(this, getString(R.string.theme_loaded_fmt, s.name), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun sectionLabel(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(color)
        alpha = 0.8f
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun outlined(label: String, onClick: () -> Unit) = MaterialButton(
        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
