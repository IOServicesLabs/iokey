package com.localboard.keyboard.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.localboard.keyboard.R
import com.localboard.keyboard.context.ContextEngine
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.ui.KeyboardLayouts
import com.localboard.keyboard.llm.LlamaCppProvider
import com.localboard.keyboard.llm.LocalStyleComposer
import com.localboard.keyboard.llm.ModelManager
import com.localboard.keyboard.llm.ProviderRegistry
import com.localboard.keyboard.onboarding.OnboardingActivity
import com.localboard.keyboard.util.ChatExportParser
import com.localboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keyboard settings: appearance, typing behavior, personalization,
 * and the privacy dashboard (what's stored + one-tap erase).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val contextEngine by lazy {
        ContextEngine(applicationContext, FastEngine.shared(applicationContext))
    }

    /** Chat-export file picker (WhatsApp "Export chat" .txt and similar). */
    private val pickChatExport = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onChatExportPicked(it) } }

    /** READ_USER_DICTIONARY is a normal permission (install-time) on stock
     *  Android, but some skins mark it dangerous — request covers both. */
    private val reqUserDict = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) runDictionaryImport()
        else Toast.makeText(
            this, "Permission needed to read the system dictionary", Toast.LENGTH_LONG
        ).show()
    }
    private val registry by lazy {
        ProviderRegistry(prefs, LocalStyleComposer(FastEngine.shared(applicationContext))).also {
            it.register(LlamaCppProvider(applicationContext))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        bindSwitch(R.id.switch_number_row, prefs.numberRow) { prefs.numberRow = it }
        bindSwitch(R.id.switch_digit_popups, prefs.digitPopups) { prefs.digitPopups = it }
        bindSwitch(R.id.switch_swipe, prefs.swipeEnabled) { prefs.swipeEnabled = it }
        wireTypingFeel()
        wireContextWindow()
        wireImportSection()
        bindSwitch(R.id.switch_autospace, prefs.autoSpace) { prefs.autoSpace = it }
        bindSwitch(R.id.switch_autocorrect, prefs.autoCorrect) { prefs.autoCorrect = it }
        bindSwitch(R.id.switch_double_space, prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it }
        bindSwitch(R.id.switch_autocaps, prefs.autoCaps) { prefs.autoCaps = it }
        bindSwitch(R.id.switch_learn, prefs.learningEnabled) { prefs.learningEnabled = it }
        bindSwitch(R.id.switch_typing_log, prefs.typingLogEnabled) { prefs.typingLogEnabled = it }
        bindSwitch(R.id.switch_screen_stream, prefs.screenStreamEnabled) { prefs.screenStreamEnabled = it }
        bindSwitch(R.id.switch_field_text, prefs.fieldTextEnabled) { prefs.fieldTextEnabled = it }
        bindSwitch(R.id.switch_overnight_distill, prefs.overnightDistillation) {
            prefs.overnightDistillation = it
            if (it) com.localboard.keyboard.llm.NightlyDistillWorker.schedule(applicationContext)
            else com.localboard.keyboard.llm.NightlyDistillWorker.cancel(applicationContext)
        }
        addAiSuggestionsRow()
        bindSwitch(R.id.switch_use_style_profile, prefs.llmUseStyleProfile) {
            prefs.llmUseStyleProfile = it
        }
        findViewById<MaterialButton>(R.id.btn_ai_test_lab).setOnClickListener {
            startActivity(Intent(this, AiTestLabActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_blocked_apps).setOnClickListener {
            showBlockedAppsDialog()
        }
        findViewById<android.widget.LinearLayout>(R.id.row_screen_context).setOnClickListener {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.switch_screen_context
        ).setOnClickListener {
            // The switch only mirrors state — the real toggle lives in
            // system accessibility settings.
            startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
                bindSwitch(R.id.switch_swipe_trail, prefs.swipeTrail) { prefs.swipeTrail = it }
        bindSwitch(R.id.switch_live_preview, prefs.liveSwipePreview) { prefs.liveSwipePreview = it }
        wireTuningSection()

        findViewById<MaterialButton>(R.id.btn_retrain).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_privacy).setOnClickListener {
            showPrivacyDashboard()
        }
        findViewById<MaterialButton>(R.id.btn_data_controls).setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_clear).setOnClickListener {
            confirmErase()
        }

        wireModelSection()
        wireAppearanceSection()
        wireWallpaperSection()
        wireVoiceSection()
        wireAdvancedToggle()
        wireLayoutSection()
        wireTutorialButton()
        // Must run last: it walks the finished view tree to find the section
        // cards, so every section has to be inflated and wired by now.
        wireSectionCollapsing()
        wireSearch()
        startRelaxingAnimations()
        lifecycleScope.launch {
            contextEngine.initialize()
            refreshEngineStatus()
        }
    }

    // ------------------------------------------------------------------
    // Keyboard layout: bottom-row shape, key spacing, symbols density
    // ------------------------------------------------------------------

    private fun wireTutorialButton() {
        findViewById<MaterialButton>(R.id.btn_tutorial)?.setOnClickListener {
            startActivity(
                Intent(this, com.localboard.keyboard.onboarding.TutorialActivity::class.java)
            )
        }
        findViewById<MaterialButton>(R.id.btn_theme_studio)?.setOnClickListener {
            startActivity(Intent(this, ThemeStudioActivity::class.java))
        }
    }

    /**
     * Search across section names, their summaries, and the label of every
     * control inside them. A hit expands the section so the match is visible;
     * an empty query restores the user's own expand state.
     */
    private fun wireSearch() {
        val input = findViewById<android.widget.EditText>(R.id.input_settings_search) ?: return
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                if (q.isEmpty()) {
                    for (sec in sections) sec.card.visibility = View.VISIBLE
                    applySectionVisibility()
                    return
                }
                for (sec in sections) {
                    val hit = sec.key.lowercase().contains(q) ||
                        summaryFor(sec.key).lowercase().contains(q) ||
                        sec.body.any { v -> labelOf(v).contains(q) }
                    sec.card.visibility = if (hit) View.VISIBLE else View.GONE
                    if (hit) {
                        // Force the matching section open so the hit is on screen.
                        for (v in sec.body) {
                            if (v.id != View.NO_ID && advancedIds.contains(v.id)) continue
                            v.visibility = View.VISIBLE
                        }
                        sec.summary?.visibility = View.GONE
                        sec.header.text = "${iconFor(sec.key)}   ${sec.key}"
                    }
                }
                applyAdvancedVisibility(prefs.showAdvanced)
            }
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
        })
    }

    /** All the searchable text a control carries, lowercased. */
    private fun labelOf(v: View): String = when (v) {
        is TextView -> v.text?.toString()?.lowercase().orEmpty()
        is android.view.ViewGroup -> (0 until v.childCount)
            .joinToString(" ") { labelOf(v.getChildAt(it)) }
        else -> ""
    }

    /**
     * The "it feels cramped" controls. Each optional bottom-row key that is
     * switched off hands its width to the space bar, and tighter spacing grows
     * every key face without making the keyboard taller — so all of these are
     * about the same thing from different directions.
     */
    private fun wireLayoutSection() {
        val spacingLabel = findViewById<TextView>(R.id.label_key_spacing)
        val spacingSeek = findViewById<android.widget.SeekBar>(R.id.seek_key_spacing)

        fun spacingText(scale: Float): String {
            val name = when {
                scale <= 0.7f -> "Tight — biggest keys"
                scale <= 1.05f -> "Default"
                scale <= 1.4f -> "Airy"
                else -> "Very airy — smallest keys"
            }
            return "${getString(R.string.layout_spacing)}: $name (${(scale * 100).toInt()}%)"
        }

        // 0..100 maps to 0.4x..1.8x.
        spacingSeek.max = 100
        spacingSeek.progress = (((prefs.keySpacing - 0.4f) / 1.4f) * 100f).toInt().coerceIn(0, 100)
        spacingLabel.text = spacingText(prefs.keySpacing)
        spacingSeek.setOnSeekBarChangeListener(simpleSeek { p ->
            val scale = 0.4f + (p / 100f) * 1.4f
            prefs.keySpacing = scale
            spacingLabel.text = spacingText(prefs.keySpacing)
        })

        bindSwitch(R.id.switch_key_emoji, prefs.bottomEmojiKey) {
            prefs.bottomEmojiKey = it
            // Removing the emoji key would otherwise strand the emoji panel:
            // turn the quick emoji row on so there is still a way in.
            if (!it && !prefs.emojiRow) {
                prefs.emojiRow = true
                findViewById<MaterialSwitch>(R.id.switch_emoji_row)?.isChecked = true
                Toast.makeText(
                    this,
                    "Emoji row turned on so emoji stay one tap away",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        bindSwitch(R.id.switch_key_comma, prefs.bottomCommaKey) { prefs.bottomCommaKey = it }
        bindSwitch(R.id.switch_key_apostrophe, prefs.bottomApostropheKey) {
            prefs.bottomApostropheKey = it
        }
        bindSwitch(R.id.switch_key_selection, prefs.selectionKey) { prefs.selectionKey = it }

        val layoutBtn = findViewById<MaterialButton>(R.id.btn_letter_layout)
        fun layoutLabel() = getString(R.string.layout_letters) + ": " +
            (KeyboardLayouts.LAYOUT_CHOICES.firstOrNull { it.first == prefs.layoutId }?.second
                ?: "QWERTY")
        layoutBtn.text = layoutLabel()
        layoutBtn.setOnClickListener {
            val choices = KeyboardLayouts.LAYOUT_CHOICES
            val current = choices.indexOfFirst { it.first == prefs.layoutId }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(R.string.layout_letters)
                .setSingleChoiceItems(
                    choices.map { it.second }.toTypedArray(), current
                ) { dlg, which ->
                    prefs.layoutId = choices[which].first
                    // Keep autocorrect's fat-finger model in step with the keys
                    // that will actually be on screen.
                    FastEngine.setKeyboardRows(KeyboardLayouts.letterRows(prefs.layoutId))
                    layoutBtn.text = layoutLabel()
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        findViewById<MaterialButton>(R.id.btn_choose_keys).setOnClickListener {
            startActivity(Intent(this, KeyLayoutActivity::class.java))
        }

        val bracketBtn = findViewById<MaterialButton>(R.id.btn_bracket_pos)
        fun bracketLabel() = getString(R.string.layout_brackets) + ": " +
            (KeyboardLayouts.BRACKET_CHOICES
                .firstOrNull { it.first == prefs.symbolsBracketPos }?.second ?: "Left")
        bracketBtn.text = bracketLabel()
        bracketBtn.setOnClickListener {
            val choices = KeyboardLayouts.BRACKET_CHOICES
            val current = choices.indexOfFirst { it.first == prefs.symbolsBracketPos }
                .coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(R.string.layout_brackets)
                .setSingleChoiceItems(
                    choices.map { it.second }.toTypedArray(), current
                ) { dlg, which ->
                    prefs.symbolsBracketPos = choices[which].first
                    bracketBtn.text = bracketLabel()
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        val symbolsGroup = findViewById<RadioGroup>(R.id.symbols_density_group)
        symbolsGroup.check(
            if (prefs.symbolsDensity == 1) R.id.radio_symbols_complete
            else R.id.radio_symbols_roomy
        )
        symbolsGroup.setOnCheckedChangeListener { _, id ->
            prefs.symbolsDensity = if (id == R.id.radio_symbols_complete) 1 else 0
        }

        wireFeatureToggles()

        findViewById<MaterialButton>(R.id.btn_layout_reset).setOnClickListener {
            prefs.keySpacing = 1f
            prefs.bottomEmojiKey = true
            prefs.bottomCommaKey = true
            prefs.bottomApostropheKey = false
            prefs.symbolsDensity = 0
            spacingSeek.progress = (((1f - 0.4f) / 1.4f) * 100f).toInt()
            spacingLabel.text = spacingText(1f)
            findViewById<MaterialSwitch>(R.id.switch_key_emoji).isChecked = true
            findViewById<MaterialSwitch>(R.id.switch_key_comma).isChecked = true
            findViewById<MaterialSwitch>(R.id.switch_key_apostrophe).isChecked = false
            symbolsGroup.check(R.id.radio_symbols_roomy)
            Toast.makeText(this, "Layout reset", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Every new feature is a switch here. The defaults are chosen to be the
     * behaviour most people want without asking (glide learning and doubled
     * letters on, clipboard history on but expiring, per-app themes off
     * because a keyboard that changes colour unexpectedly is worse than one
     * that doesn't) — but nothing is mandatory.
     */
    private fun wireFeatureToggles() {
        bindSwitch(R.id.switch_swipe_learning, prefs.swipeLearning) { prefs.swipeLearning = it }
        bindSwitch(R.id.switch_swipe_double, prefs.swipeDoubleLetter) { prefs.swipeDoubleLetter = it }
        bindSwitch(R.id.switch_clip_history, prefs.clipboardHistory) { prefs.clipboardHistory = it }
        bindSwitch(R.id.switch_snippets, prefs.snippetsEnabled) { prefs.snippetsEnabled = it }
        bindSwitch(R.id.switch_per_app_theme, prefs.perAppThemes) {
            prefs.perAppThemes = it
            com.localboard.keyboard.ui.KeyboardTheme.invalidateCache()
        }

        // Clip expiry: 0..1440 min in coarse, human steps rather than a
        // meaningless continuous slider.
        val expirySteps = intArrayOf(5, 15, 30, 60, 120, 360, 720, 1440, 0)
        val expiryLabel = findViewById<TextView>(R.id.label_clip_expiry)
        val expirySeek = findViewById<android.widget.SeekBar>(R.id.seek_clip_expiry)
        fun expiryText(mins: Int) = getString(R.string.feat_clip_expiry) + ": " + when {
            mins == 0 -> "never"
            mins < 60 -> "$mins min"
            mins == 60 -> "1 hour"
            mins < 1440 -> "${mins / 60} hours"
            else -> "1 day"
        }
        expirySeek.max = expirySteps.size - 1
        expirySeek.progress = expirySteps.indexOf(prefs.clipboardExpiryMinutes)
            .let { if (it < 0) 3 else it }
        expiryLabel.text = expiryText(prefs.clipboardExpiryMinutes)
        expirySeek.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.clipboardExpiryMinutes = expirySteps[p.coerceIn(0, expirySteps.size - 1)]
            expiryLabel.text = expiryText(prefs.clipboardExpiryMinutes)
        })

        findViewById<MaterialButton>(R.id.btn_snippets).setOnClickListener { showSnippetEditor() }

        // One-handed mode.
        val ohGroup = findViewById<RadioGroup>(R.id.one_handed_group)
        ohGroup.check(
            when (prefs.oneHandedMode) {
                2 -> R.id.radio_oh_left
                1 -> R.id.radio_oh_right
                else -> R.id.radio_oh_off
            }
        )
        ohGroup.setOnCheckedChangeListener { _, id ->
            prefs.oneHandedMode = when (id) {
                R.id.radio_oh_left -> 2
                R.id.radio_oh_right -> 1
                else -> 0
            }
        }
        val ohLabel = findViewById<TextView>(R.id.label_oh_width)
        val ohSeek = findViewById<android.widget.SeekBar>(R.id.seek_oh_width)
        fun ohText(f: Float) =
            getString(R.string.feat_oh_width) + ": ${(f * 100).toInt()}%"
        ohSeek.max = 40   // 0.60 .. 1.00
        ohSeek.progress = ((prefs.oneHandedWidth - 0.6f) * 100f).toInt().coerceIn(0, 40)
        ohLabel.text = ohText(prefs.oneHandedWidth)
        ohSeek.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.oneHandedWidth = 0.6f + p / 100f
            ohLabel.text = ohText(prefs.oneHandedWidth)
        })

        // Emoji skin tone.
        val toneLabel = findViewById<TextView>(R.id.label_emoji_tone)
        val toneSeek = findViewById<android.widget.SeekBar>(R.id.seek_emoji_tone)
        fun toneText(t: Int) = getString(R.string.feat_emoji_tone) + "  " +
            com.localboard.keyboard.util.EmojiTone.toneSwatch(t)
        toneSeek.max = 5
        toneSeek.progress = prefs.emojiSkinTone
        toneLabel.text = toneText(prefs.emojiSkinTone)
        toneSeek.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.emojiSkinTone = p
            toneLabel.text = toneText(p)
        })
    }

    /** Simple list editor for text shortcuts. */
    private fun showSnippetEditor() {
        val snips = com.localboard.keyboard.util.Snippets(prefs)
        snips.seedIfEmpty()
        val items = snips.all()
        val labels = if (items.isEmpty()) arrayOf(getString(R.string.snippets_empty))
        else items.map { "${it.first}  →  ${it.second.take(40)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.snippets_title)
            .setItems(labels) { _, i ->
                if (items.isEmpty()) return@setItems
                val (k, _) = items[i]
                AlertDialog.Builder(this)
                    .setTitle(k)
                    .setMessage(items[i].second)
                    .setPositiveButton(R.string.theme_delete) { _, _ ->
                        snips.remove(k); showSnippetEditor()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setPositiveButton(R.string.snippets_add) { _, _ -> showSnippetAdd(snips) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSnippetAdd(snips: com.localboard.keyboard.util.Snippets) {
        val pad = dpi(20)
        val shortcut = android.widget.EditText(this).apply {
            hint = getString(R.string.snippets_shortcut_hint); setSingleLine()
        }
        val expansion = android.widget.EditText(this).apply {
            hint = getString(R.string.snippets_expansion_hint); minLines = 2
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, dpi(8), pad, 0)
            addView(shortcut); addView(expansion)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.snippets_add)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                snips.put(shortcut.text.toString(), expansion.text.toString())
                showSnippetEditor()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Collapsible sections
    // ------------------------------------------------------------------

    /** Section cards discovered in the view tree: header + its body views. */
    private class Section(
        val key: String,
        val card: android.widget.LinearLayout,
        val header: TextView,
        val body: List<View>,
        /** The one-line "currently set to" strip shown while collapsed. */
        var summary: TextView? = null
    )

    private val sections = ArrayList<Section>()

    /** Glyph per section. Emoji rather than vector drawables: they render at
     *  every density, need no assets, and survive a theme change. */
    private fun iconFor(key: String): String = when (key) {
        getString(R.string.sec_layout) -> "🎨"
        getString(R.string.sec_typing) -> "⌨️"
        getString(R.string.sec_keyboard) -> "🖌️"
        getString(R.string.sec_typing_feel) -> "✋"
        getString(R.string.sec_ai_model) -> "✨"
        getString(R.string.sec_your_style) -> "🖊️"
        getString(R.string.sec_learning_ai) -> "🧠"
        getString(R.string.sec_voice) -> "🎙️"
        getString(R.string.sec_privacy) -> "🔒"
        getString(R.string.sec_advanced) -> "🛠️"
        else -> "•"
    }

    /** What the section is set to right now — so a collapsed page still
     *  answers "how is this configured?" without opening anything. */
    private fun summaryFor(key: String): String = when (key) {
        getString(R.string.sec_layout) -> buildList {
            add("spacing ${(prefs.keySpacing * 100).toInt()}%")
            add(if (prefs.symbolsDensity == 1) "symbols: complete" else "symbols: roomy")
            if (!prefs.bottomEmojiKey) add("no emoji key")
            if (prefs.bottomApostropheKey) add("apostrophe key")
        }.joinToString(" · ")
        getString(R.string.sec_typing) -> buildList {
            add(if (prefs.autoCorrect) "autocorrect on" else "autocorrect off")
            if (prefs.swipeEnabled) add("glide")
            if (prefs.autoCaps) add("auto-caps")
        }.joinToString(" · ")
        getString(R.string.sec_keyboard) -> {
            val palette = when (prefs.paletteId) {
                "" -> "Mid-Century"
                "custom" -> com.localboard.keyboard.ui.ThemeSpec
                    .decode(prefs.activeThemeCode)?.name ?: "Custom"
                "system" -> "Follow system"
                "material" -> "Material You"
                else -> com.localboard.keyboard.ui.KeyboardTheme.EXTRA_PALETTES
                    .firstOrNull { it.id == prefs.paletteId }?.label
                    ?: prefs.paletteId.replaceFirstChar { it.uppercase() }
            }
            "$palette · height ${(prefs.heightScale * 100).toInt()}%"
        }
        getString(R.string.sec_typing_feel) -> buildList {
            add(when (prefs.vibLevel) {
                0 -> "no vibration"; 1 -> "light vibration"
                2 -> "medium vibration"; else -> "strong vibration"
            })
            if (prefs.soundPack > 0) add("sound on")
        }.joinToString(" · ")
        getString(R.string.sec_ai_model) ->
            com.localboard.keyboard.llm.ModelRegistry.activeName(prefs)
        getString(R.string.sec_voice) ->
            if (prefs.voiceTypingEnabled) "enabled" else "off"
        getString(R.string.sec_learning_ai) ->
            if (prefs.learningEnabled) "learning from your typing" else "learning paused"
        getString(R.string.sec_privacy) -> "on-device only · dashboard & erase"
        getString(R.string.sec_your_style) ->
            if (prefs.customStylePrompt.isBlank()) "auto-distilled" else "custom instructions"
        getString(R.string.sec_advanced) ->
            if (prefs.showAdvanced) "shown" else "hidden"
        else -> ""
    }

    /**
     * Turns the flat wall of ~50 controls into headings you open one at a
     * time. Done by walking the inflated tree rather than restructuring the
     * layout XML: every section card is a LinearLayout whose first child is
     * the SettingsSection header, so the header stays and the rest collapses.
     *
     * Also reorders the cards so the things people actually change (layout,
     * typing, appearance) come before the AI plumbing.
     */
    private fun wireSectionCollapsing() {
        val container = findViewById<View>(R.id.switch_settings_simple)
            .parent as? android.widget.LinearLayout ?: return
        sections.clear()

        for (i in 0 until container.childCount) {
            val card = container.getChildAt(i) as? android.widget.LinearLayout ?: continue
            val header = card.getChildAt(0) as? TextView ?: continue
            // Section headers are the only TextViews that lead a card and
            // carry a non-empty label; anything else is a plain container.
            val label = header.text?.toString()?.trim().orEmpty()
            if (label.isEmpty() || card.childCount < 2) continue
            val body = (1 until card.childCount).map { card.getChildAt(it) }
            sections += Section(label, card, header, body)
        }
        if (sections.isEmpty()) return

        // Preferred order, most-used first. Unlisted sections keep their
        // relative position at the end, so adding a section can't lose it.
        val order = listOf(
            R.string.sec_layout, R.string.sec_typing, R.string.sec_keyboard,
            R.string.sec_typing_feel, R.string.sec_ai_model, R.string.sec_your_style,
            R.string.sec_learning_ai, R.string.sec_voice, R.string.sec_privacy,
            R.string.sec_advanced
        ).map { getString(it).trim() }
        val ranked = sections.sortedBy { s ->
            val idx = order.indexOfFirst { it.equals(s.key, ignoreCase = true) }
            if (idx < 0) order.size + sections.indexOf(s) else idx
        }
        for (s in ranked) {
            container.removeView(s.card)
            container.addView(s.card)
        }

        for (s in sections) decorateSectionHeader(s)

        bindSwitch(R.id.switch_settings_simple, prefs.settingsSimple) { simple ->
            prefs.settingsSimple = simple
            applySectionVisibility()
        }
        applySectionVisibility()
    }

    private fun decorateSectionHeader(s: Section) {
        s.header.isClickable = true
        s.header.isFocusable = true
        // A comfortable tap target — the header is now a control, not a label.
        s.header.setPadding(
            s.header.paddingLeft, dpi(10), s.header.paddingRight, dpi(6)
        )
        s.header.setOnClickListener { toggleSection(s) }
        // Ripple feedback, so a heading looks tappable instead of decorative.
        val outValue = android.util.TypedValue()
        theme.resolveAttribute(
            android.R.attr.selectableItemBackground, outValue, true
        )
        s.header.setBackgroundResource(outValue.resourceId)

        if (s.summary == null) {
            val sum = TextView(this).apply {
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.app_text))
                textSize = 12.5f
                alpha = 0.55f
                setPadding(0, 0, 0, dpi(4))
            }
            s.card.addView(sum, 1)
            s.summary = sum
        }
    }

    private fun isExpanded(s: Section): Boolean =
        !prefs.settingsSimple || prefs.expandedSections.contains(s.key)

    private fun toggleSection(s: Section) {
        if (!prefs.settingsSimple) return // everything is already open
        val open = prefs.expandedSections.toMutableSet()
        if (!open.remove(s.key)) open.add(s.key)
        prefs.expandedSections = open
        applySectionVisibility()
    }

    private fun applySectionVisibility() {
        for (s in sections) {
            val expanded = isExpanded(s)
            for (v in s.body) {
                // The Advanced toggle owns its own rows — applyAdvancedVisibility
                // below has the final say on those.
                if (v.id != View.NO_ID && advancedIds.contains(v.id)) continue
                v.visibility = if (expanded) View.VISIBLE else View.GONE
            }
            val chevron = if (!prefs.settingsSimple) "" else if (expanded) "  ▾" else "  ▸"
            s.header.text = "${iconFor(s.key)}   ${s.key}$chevron"
            // The summary is the payoff of collapsing: the page still tells
            // you how everything is set without opening a single section.
            s.summary?.let { sum ->
                val text = summaryFor(s.key)
                if (!expanded && text.isNotBlank()) {
                    sum.text = text
                    sum.visibility = View.VISIBLE
                } else {
                    sum.visibility = View.GONE
                }
            }
        }
        // Re-apply the advanced filter: expanding a section above just made
        // its advanced rows visible again regardless of the advanced switch.
        applyAdvancedVisibility(prefs.showAdvanced)
    }

    private fun dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Keyboard wallpaper (picked image, copied into private storage)
    // ------------------------------------------------------------------

    private val wallpaperPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importWallpaper(it) }
    }

    private fun wireWallpaperSection() {
        findViewById<MaterialButton>(R.id.btn_motion).setOnClickListener {
            startActivity(Intent(this, MotionActivity::class.java))
        }

        val dimSeek = findViewById<android.widget.SeekBar>(R.id.seek_wallpaper_dim)
        val dimLabel = findViewById<TextView>(R.id.label_wallpaper_dim)
        fun renderDim(v: Float) {
            dimLabel.text = getString(R.string.settings_wallpaper_dim) +
                " — ${(v * 100).toInt()}%"
        }
        dimSeek.progress = (prefs.wallpaperDim * 100).toInt()
        renderDim(prefs.wallpaperDim)
        dimSeek.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.wallpaperDim = p / 100f
            renderDim(prefs.wallpaperDim)
        })

        renderWallpaperButton()
        findViewById<android.widget.Button>(R.id.btn_wallpaper).apply {
            setOnClickListener { wallpaperPicker.launch(arrayOf("image/*")) }
            setOnLongClickListener {
                if (prefs.wallpaperPath.isNotEmpty()) {
                    prefs.wallpaperPath = ""
                    java.io.File(filesDir, "keyboard_wallpaper.jpg").delete()
                    renderWallpaperButton()
                    android.widget.Toast.makeText(
                        this@SettingsActivity, "Wallpaper removed",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                true
            }
        }
    }

    private fun renderWallpaperButton() {
        findViewById<android.widget.Button>(R.id.btn_wallpaper).text = getString(
            if (prefs.wallpaperPath.isEmpty()) R.string.settings_wallpaper
            else R.string.settings_wallpaper_set
        )
    }

    private fun importWallpaper(uri: android.net.Uri) {
        try {
            val out = java.io.File(filesDir, "keyboard_wallpaper.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return
            prefs.wallpaperPath = out.absolutePath
            renderWallpaperButton()
            android.widget.Toast.makeText(
                this, "Wallpaper set — open the keyboard to see it",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                this, "Couldn't read that image", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ------------------------------------------------------------------
    // Relaxing ambience: staggered section entrances + a slow breathing tint
    // ------------------------------------------------------------------

    private var breathingAnimator: android.animation.ValueAnimator? = null

    private fun startRelaxingAnimations() {
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        val scroll = content.getChildAt(0) as? android.view.ViewGroup ?: return
        val column = scroll.getChildAt(0) as? android.view.ViewGroup ?: return

        // Sections rise and settle one after another.
        val dy = 14 * resources.displayMetrics.density
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            child.alpha = 0f
            child.translationY = dy
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(420)
                .setStartDelay(50L + i * 40L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        // The background slowly breathes between two warm cream tones.
        val from = androidx.core.content.ContextCompat.getColor(this, R.color.app_bg)
        val to = 0xFFE7DAC2.toInt()
        breathingAnimator = android.animation.ValueAnimator.ofArgb(from, to).apply {
            duration = 6000
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { scroll.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    override fun onDestroy() {
        breathingAnimator?.cancel()
        breathingAnimator = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Appearance & feel
    // ------------------------------------------------------------------

    private fun wireAppearanceSection() {
        // Sliders: height 0..90 → 0.60..1.50, text 0..70 → 0.70..1.40
        val heightSeek = findViewById<android.widget.SeekBar>(R.id.seek_height)
        val textSeek = findViewById<android.widget.SeekBar>(R.id.seek_textsize)
        val heightLabel = findViewById<TextView>(R.id.label_height)
        val textLabel = findViewById<TextView>(R.id.label_textsize)

        fun renderHeight(v: Float) {
            heightLabel.text = getString(R.string.settings_keyboard_height) +
                " — ${(v * 100).toInt()}%"
        }
        fun renderText(v: Float) {
            textLabel.text = getString(R.string.settings_key_text_size) +
                " — ${(v * 100).toInt()}%"
        }
        heightSeek.progress = (prefs.heightScale * 100).toInt() - 60
        textSeek.progress = (prefs.keyTextScale * 100).toInt() - 70
        renderHeight(prefs.heightScale)
        renderText(prefs.keyTextScale)
        heightSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                val v = (p + 60) / 100f
                prefs.heightScale = v
                renderHeight(v)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        textSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                val v = (p + 70) / 100f
                prefs.keyTextScale = v
                renderText(v)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })


        // Custom color overrides.
        val bgIn = findViewById<android.widget.EditText>(R.id.input_custom_bg)
        val keyIn = findViewById<android.widget.EditText>(R.id.input_custom_key)
        val textIn = findViewById<android.widget.EditText>(R.id.input_custom_text)
        val accentIn = findViewById<android.widget.EditText>(R.id.input_custom_accent)
        fun Int?.toHexOrEmpty() = if (this == null || this == Int.MIN_VALUE) "" else String.format("#%06X", this and 0xFFFFFF)
        bgIn.setText(prefs.customBg.toHexOrEmpty())
        keyIn.setText(prefs.customKey.toHexOrEmpty())
        textIn.setText(prefs.customText.toHexOrEmpty())
        accentIn.setText(prefs.customAccent.toHexOrEmpty())

        fun parseHex(raw: String): Int? {
            val s = raw.trim()
            if (s.isEmpty()) return Int.MIN_VALUE
            return try {
                android.graphics.Color.parseColor(if (s.startsWith("#")) s else "#$s")
            } catch (t: Throwable) {
                null
            }
        }
        findViewById<MaterialButton>(R.id.btn_apply_custom).setOnClickListener {
            var bad = false
            val bg = parseHex(bgIn.text.toString()).also { if (it == null) bad = true }
            val key = parseHex(keyIn.text.toString()).also { if (it == null) bad = true }
            val txt = parseHex(textIn.text.toString()).also { if (it == null) bad = true }
            val acc = parseHex(accentIn.text.toString()).also { if (it == null) bad = true }
            if (bad) {
                android.widget.Toast.makeText(this, "Invalid hex color — use #RRGGBB", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.customBg = bg!!
            prefs.customKey = key!!
            prefs.customText = txt!!
            prefs.customAccent = acc!!
            android.widget.Toast.makeText(this, "Applied — reopen the keyboard to see it", android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btn_clear_custom).setOnClickListener {
            prefs.customBg = Int.MIN_VALUE
            prefs.customKey = Int.MIN_VALUE
            prefs.customText = Int.MIN_VALUE
            prefs.customAccent = Int.MIN_VALUE
            bgIn.setText(""); keyIn.setText(""); textIn.setText(""); accentIn.setText("")
        }

        bindSwitch(R.id.switch_popup_preview, prefs.popupPreview) { prefs.popupPreview = it }
        bindSwitch(R.id.switch_emoji_row, prefs.emojiRow) { prefs.emojiRow = it }

        // Live keyboard preview: tracks sliders + palette in real time.
        val preview = findViewById<com.localboard.keyboard.ui.KeyboardPreviewView>(R.id.keyboard_preview)
        fun refreshPreview() {
            preview.refresh(
                prefs.heightScale,
                prefs.keyTextScale,
                com.localboard.keyboard.ui.KeyboardTheme.load(this, prefs)
            )
        }
        // Palette gallery: horizontal swatch cards (bg/key/accent chips) —
        // much easier to shop than a text radio list.
        val gallery = findViewById<android.widget.LinearLayout>(R.id.palette_gallery)
        data class Swatch(val id: String, val label: String, val bg: Int, val key: Int, val accent: Int)
        fun rc(id: Int) = ContextCompat.getColor(this, id)
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val swatches = mutableListOf(
            // P3: follow-system and Material You lead the gallery.
            Swatch("system", "Follow System",
                rc(if (night) R.color.kb_dark_bg else R.color.kb_light_bg),
                rc(if (night) R.color.kb_dark_key else R.color.kb_light_key),
                rc(if (night) R.color.kb_dark_accent else R.color.kb_light_accent)),
            Swatch("material", "Material You",
                rc(if (night) android.R.color.system_neutral1_900 else android.R.color.system_neutral2_100),
                rc(if (night) android.R.color.system_neutral2_800 else android.R.color.system_neutral1_0),
                rc(if (night) android.R.color.system_accent1_300 else android.R.color.system_accent1_600)),
            Swatch("dark", "Default Dark", rc(R.color.kb_dark_bg), rc(R.color.kb_dark_key), rc(R.color.kb_dark_accent)),
            Swatch("light", "Default Light", rc(R.color.kb_light_bg), rc(R.color.kb_light_key), rc(R.color.kb_light_accent))
        )
        com.localboard.keyboard.ui.KeyboardTheme.EXTRA_PALETTES.forEach {
            swatches += Swatch(it.id, it.label, it.bg, it.key, it.accent)
        }
        val density = resources.displayMetrics.density
        fun dpx(v: Float) = (v * density).toInt()
        lateinit var buildGallery: () -> Unit
        buildGallery = {
            gallery.removeAllViews()
            val current = prefs.paletteId.ifEmpty { "midcentury" }
            swatches.forEach { sw ->
                val card = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setPadding(dpx(10f), dpx(8f), dpx(10f), dpx(8f))
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.marginEnd = dpx(8f)
                    layoutParams = lp
                    val bgd = android.graphics.drawable.GradientDrawable()
                    bgd.cornerRadius = dpx(10f).toFloat()
                    if (sw.id == current) {
                        bgd.setColor(0x22FFFFFF)
                        bgd.setStroke(dpx(2f), com.localboard.keyboard.ui.KeyboardTheme
                            .EXTRA_PALETTES.firstOrNull { it.id == "midcentury" }?.accent
                            ?: 0xFFC96F4A.toInt())
                    } else {
                        bgd.setColor(0x00000000)
                        bgd.setStroke(dpx(1f), 0x33808080)
                    }
                    background = bgd
                }
                val chips = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                listOf(sw.bg, sw.key, sw.accent).forEach { col ->
                    val chip = View(this).apply {
                        val lp = android.widget.LinearLayout.LayoutParams(dpx(20f), dpx(20f))
                        lp.marginEnd = dpx(3f)
                        layoutParams = lp
                        val cd = android.graphics.drawable.GradientDrawable()
                        cd.cornerRadius = dpx(5f).toFloat()
                        cd.setColor(col)
                        cd.setStroke(dpx(0.5f), 0x55808080)
                        background = cd
                    }
                    chips.addView(chip)
                }
                val label = TextView(this).apply {
                    text = sw.label
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.app_text))
                    setPadding(0, dpx(5f), 0, 0)
                }
                card.addView(chips)
                card.addView(label)
                card.setOnClickListener {
                    prefs.paletteId = sw.id
                    buildGallery()
                    refreshPreview()
                }
                gallery.addView(card)
            }
        }

        refreshPreview()
        buildGallery()
        heightSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                val v = (p + 60) / 100f
                prefs.heightScale = v
                renderHeight(v)
                refreshPreview()
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        textSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                val v = (p + 70) / 100f
                prefs.keyTextScale = v
                renderText(v)
                refreshPreview()
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })

        // Corner radius: 0 on the seekbar = the palette's own roundness.
        val radiusSeek = findViewById<android.widget.SeekBar>(R.id.seek_radius)
        val radiusLabel = findViewById<TextView>(R.id.label_radius)
        fun renderRadius() {
            val v = prefs.keyRadiusDp
            radiusLabel.text = getString(R.string.settings_corner_radius) + ": " +
                if (v < 0f) getString(R.string.settings_radius_palette_default)
                else "${v.toInt()}dp"
        }
        radiusSeek.max = 20
        radiusSeek.progress = if (prefs.keyRadiusDp < 0f) 0 else prefs.keyRadiusDp.toInt()
        renderRadius()
        radiusSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                prefs.keyRadiusDp = if (p <= 0) -1f else p.toFloat()
                renderRadius()
                refreshPreview()
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })

        // Press effect picker.
        val peGroup = findViewById<RadioGroup>(R.id.press_effect_group)
        val peIds = intArrayOf(
            R.id.radio_pe_lift, R.id.radio_pe_bounce, R.id.radio_pe_glow, R.id.radio_pe_none
        )
        peGroup.check(peIds[prefs.pressEffect.coerceIn(0, 3)])
        peGroup.setOnCheckedChangeListener { _, id ->
            prefs.pressEffect = peIds.indexOf(id).coerceAtLeast(0)
        }

        findViewById<MaterialButton>(R.id.btn_my_style).setOnClickListener {
            startActivity(Intent(this, StyleActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_writing_profiles).setOnClickListener {
            showProfilesDialog()
        }
        findViewById<MaterialButton>(R.id.btn_calibrate).setOnClickListener {
            startActivity(Intent(this, CalibrateActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { refreshEngineStatus() }
        rebuildModelList()
        refreshQuickModelRow()
        refreshAiSuggestionsRow()
        refreshScreenContextRow()
        refreshDiagnostics()
    }

    /** Subtitle of the programmatically-added AI-suggestions row (null until
     *  [addAiSuggestionsRow] runs). */
    private var aiSuggestionsSubtitle: TextView? = null

    /** The AI-phrase-suggestions row is built in code, not XML: the feature
     *  is confined to a few files, so instead of touching the shared layout
     *  the row is inserted right before the model-instructions box at
     *  runtime. onCreate runs this BEFORE wireSectionCollapsing()/wireSearch(),
     *  so the new views join section collapsing and search like any XML child. */
    private fun addAiSuggestionsRow() {
        val anchor = findViewById<android.view.View>(R.id.model_instructions) ?: return
        val parent = anchor.parent as? android.view.ViewGroup ?: return
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        row.addView(MaterialSwitch(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = "AI next-word predictions (experimental)"
            isChecked = prefs.llmSuggestions
            setOnCheckedChangeListener { _, checked -> prefs.llmSuggestions = checked }
        })
        val subtitle = TextView(this).apply {
            setTextColor(resources.getColor(R.color.app_text_secondary, theme))
            textSize = 12f
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        aiSuggestionsSubtitle = subtitle
        row.addView(subtitle)
        parent.addView(row, parent.indexOfChild(anchor))
        refreshAiSuggestionsRow()
    }

    /** The switch always toggles, but the feature no-ops until the quick
     *  model is downloaded — the subtitle says which state we're in.
     *  ModelRegistry.isReady is cached, so calling this on every resume is
     *  cheap. */
    private fun refreshAiSuggestionsRow() {
        val ready = com.localboard.keyboard.llm.ModelRegistry.isReady(
            applicationContext, com.localboard.keyboard.llm.ModelRegistry.quick(prefs)
        )
        aiSuggestionsSubtitle?.text = if (ready) {
            "Off by default. Runs fully on-device with the quick model: after each " +
                "word it reads the whole message and puts its best next words first on " +
                "the bar. Small models guess badly often enough that the bar can get " +
                "noisier, not better — try it and judge. Uses more battery while typing."
        } else {
            "Quick model not downloaded yet — get it in the Models section below. " +
                "The switch still works; predictions start once the model is on the phone."
        }
    }

    /** Live AI diagnostics: model/window state, screen-capture status, and
     *  the last few generation runs with native timing (prompt tokens,
     *  reused-prefix tokens, tok/s, finish reason). This is what support
     *  asks for first — hence the copy button. */
    private fun refreshDiagnostics() {
        val body = findViewById<TextView>(R.id.diag_ai_body) ?: return
        val spec = com.localboard.keyboard.llm.ModelRegistry.active(prefs)
        val loaded = try {
            com.localboard.keyboard.llm.LlamaCppBridge.nativeIsLoaded(
                com.localboard.keyboard.llm.LlamaCppBridge.SLOT_DRAFT)
        } catch (t: Throwable) { false }
        val modelLine = "model: ${spec.displayName} · window ${prefs.llmCtxSize} tok" +
            " · ${if (loaded) "loaded" else "not loaded (loads on first AI tap)"}"
        val sb = com.localboard.keyboard.context.ScreenBuffer
        val ageMs = sb.freshestAgeMs()
        val screenLine = "screen context: " +
            (if (com.localboard.keyboard.context.ScreenBuffer.serviceRunning)
                "service on · ${sb.bufferedApps()} apps buffered" +
                    (if (ageMs >= 0) " · freshest ${ageMs / 1000}s ago" else " · empty — open a chat with the keyboard up")
            else "service OFF — enable in Accessibility for on-screen chat context")
        // Crash history: the keyboard dying in the background used to be
        // invisible ("doesn't pop up for a couple of seconds"). Now every
        // uncaught crash leaves its stack here for a copy-paste report.
        val crashLine = try {
            val f = java.io.File(filesDir, com.localboard.keyboard.LocalBoardApp.CRASH_FILE)
            if (f.exists() && f.length() > 0) {
                val crashes = f.readText().split("═══").filter { it.isNotBlank() }
                "crash log: ${crashes.size} crash(es) on record — copy to report"
            } else "crash log: clean — no crashes recorded"
        } catch (t: Throwable) { "crash log: unreadable" }
        val report = com.localboard.keyboard.llm.AiDiagLog.report(modelLine, screenLine) +
            "\n" + crashLine
        body.text = report
        findViewById<MaterialButton>(R.id.btn_diag_copy)?.setOnClickListener {
            val crashText = try {
                val f = java.io.File(filesDir, com.localboard.keyboard.LocalBoardApp.CRASH_FILE)
                if (f.exists()) "\n\n— crash log —\n" + f.readText().takeLast(4000) else ""
            } catch (t: Throwable) { "" }
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    "IOKey AI diagnostics",
                    "IOKey ${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (t: Throwable) { "" }}\n" +
                        com.localboard.keyboard.llm.AiDiagLog.report(modelLine, screenLine) +
                        "\n" + crashLine + crashText
                )
            )
            Toast.makeText(this, "Diagnostics copied — paste them anywhere", Toast.LENGTH_SHORT).show()
        }
    }

    /** Current + past distilled writing profiles — the user's evolving
     *  voice, kept as a look-back history (and future "year wrapped"). */
    private fun showProfilesDialog() {
        val density = resources.displayMetrics.density
        val padH = (18 * density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padH, (6 * density).toInt(), padH, 0)
        }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.profiles_title)
            .setView(android.widget.ScrollView(this).apply { addView(container) })
            .setPositiveButton("Close", null)
            .show()

        fun heading(text: String) {
            container.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
            })
        }
        fun body(text: String, dim: Boolean = false, onClick: (() -> Unit)? = null) {
            container.addView(TextView(this).apply {
                this.text = text
                textSize = 14f
                if (dim) alpha = 0.65f
                setPadding(0, 0, 0, (10 * density).toInt())
                if (onClick != null) {
                    // Tappable rows get a subtle affordance: profiles can be
                    // used or deleted individually.
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    setOnClickListener { onClick() }
                }
            })
        }

        lifecycleScope.launch {
            val current = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val custom = prefs.customStylePrompt
                    if (custom.isNotBlank()) custom
                    else com.localboard.keyboard.data.db.AppDatabase.get(this@SettingsActivity)
                        .styleStatDao().all()
                        .firstOrNull { it.key == "personality_notes" }?.value ?: ""
                } catch (_: Throwable) { "" }
            }
            val history = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.localboard.keyboard.data.db.AppDatabase.get(this@SettingsActivity)
                        .distillationDao().latest(30)
                } catch (_: Throwable) { emptyList() }
            }
            if (dlg.isShowing.not()) return@launch
            heading(getString(R.string.profiles_current))
            body(
                if (current.isNotBlank()) current else getString(R.string.profiles_empty),
                onClick = if (current.isBlank()) null else {
                    {
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("Clear the active profile?")
                            .setMessage(
                                "The AI stops using this personality profile. " +
                                    "Older profiles below stay unless you delete them individually."
                            )
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Clear") { _, _ ->
                                prefs.customStylePrompt = ""
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        com.localboard.keyboard.data.db.AppDatabase
                                            .get(this@SettingsActivity).styleStatDao()
                                            .putAll(listOf(
                                                com.localboard.keyboard.data.db.StyleStatEntity(
                                                    "personality_notes", ""
                                                )
                                            ))
                                    } catch (_: Throwable) { }
                                }
                                dlg.dismiss()
                                Toast.makeText(
                                    this@SettingsActivity,
                                    "Active profile cleared", Toast.LENGTH_SHORT
                                ).show()
                            }
                            .show()
                    }
                }
            )
            if (history.isNotEmpty()) {
                heading(getString(R.string.profiles_past) + "  (tap one to use or delete it)")
                val fmt = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
                for (h in history.drop(1)) {  // newest is the current one, shown above
                    val src = getString(
                        if (h.source == "sms") R.string.profiles_from_sms
                        else R.string.profiles_from_nightly
                    )
                    heading("${fmt.format(java.util.Date(h.ts))} · $src")
                    body(h.notes, dim = true, onClick = {
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("Profile from ${fmt.format(java.util.Date(h.ts))}")
                            .setItems(
                                arrayOf("Use this profile", "Delete this profile")
                            ) { _, which ->
                                when (which) {
                                    0 -> {
                                        prefs.customStylePrompt = h.notes
                                        dlg.dismiss()
                                        Toast.makeText(
                                            this@SettingsActivity,
                                            "Profile applied — edit it in My writing style anytime",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    1 -> {
                                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            try {
                                                val db = com.localboard.keyboard.data.db
                                                    .AppDatabase.get(this@SettingsActivity)
                                                db.distillationDao().delete(h.id)
                                                // If the deleted row IS the live
                                                // profile, clear the live slot too.
                                                if (prefs.customStylePrompt == h.notes)
                                                    prefs.customStylePrompt = ""
                                            } catch (_: Throwable) { }
                                        }
                                        dlg.dismiss()
                                        Toast.makeText(
                                            this@SettingsActivity,
                                            "Profile deleted", Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    })
                }
            }
        }
    }

    /** The accessibility row: the switch mirrors whether the AI can read
     *  on-screen text; tapping it (or the row) deep-links to the system
     *  toggle, since only the user can flip it there. */
    private fun refreshScreenContextRow() {
        val enabled = isScreenContextEnabled()
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.switch_screen_context
        ).isChecked = enabled
        // P5: with the accessibility service off, the AI silently drafts
        // blind (no thread titles, no on-screen conversation). Say so right
        // here, in warning colors, instead of letting the user discover it
        // from worse replies.
        val detail = findViewById<TextView>(R.id.label_screen_context_detail)
        if (enabled) {
            detail.text = getString(R.string.settings_screen_context_detail)
            detail.setTextColor(ContextCompat.getColor(this, R.color.app_text))
            detail.alpha = 0.6f
        } else {
            detail.text = "⚠  Off — AI replies can't see your screen. Tap to enable in system settings."
            detail.setTextColor(0xFFE5534B.toInt())
            detail.alpha = 1f
        }
    }

    private fun isScreenContextEnabled(): Boolean {
        // No such service in this distribution — never report it as on.
        if (!com.localboard.keyboard.context.ScreenContextService.AVAILABLE) return false
        val expected = android.content.ComponentName(
            this, com.localboard.keyboard.context.ScreenContextService::class.java
        ).flattenToString()
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun wireTuningSection() {
        // Autocorrect boldness radio.
        val acGroup = findViewById<RadioGroup>(R.id.autocorrect_group)
        listOf(
            1 to R.string.settings_ac_conservative,
            2 to R.string.settings_ac_balanced,
            3 to R.string.settings_ac_bold
        ).forEach { (lvl, labelRes) ->
            val rb = MaterialRadioButton(this).apply {
                text = getString(labelRes)
                tag = lvl
                layoutParams = RadioGroup.LayoutParams(
                    0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            acGroup.addView(rb)
            if (prefs.autoCorrectLevel == lvl) rb.isChecked = true
        }
        acGroup.setOnCheckedChangeListener { _, id ->
            (acGroup.findViewById<MaterialRadioButton>(id)?.tag as? Int)
                ?.let { prefs.autoCorrectLevel = it }
        }

        // Swipe activation distance (60–140% of the base threshold).
        val sensSeek = findViewById<android.widget.SeekBar>(R.id.seek_swipe_sens)
        val sensLabel = findViewById<TextView>(R.id.label_swipe_sens)
        fun renderSens() {
            sensLabel.text = getString(R.string.settings_swipe_sens) +
                " — ${(prefs.swipeSensitivity * 100).toInt()}%"
        }
        sensSeek.progress = (prefs.swipeSensitivity * 100).toInt() - 60
        renderSens()
        sensSeek.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.swipeSensitivity = (p + 60) / 100f
            renderSens()
        })

        // Long-press delay (250–600 ms).
        val lpSeek = findViewById<android.widget.SeekBar>(R.id.seek_longpress)
        val lpLabel = findViewById<TextView>(R.id.label_longpress)
        fun renderLp() {
            lpLabel.text = getString(R.string.settings_longpress) + " — ${prefs.longPressMs}ms"
        }
        lpSeek.progress = (prefs.longPressMs - 250) / 10
        renderLp()
        lpSeek.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.longPressMs = 250 + p * 10
            renderLp()
        })

        // Manual key-grid shift (−48…+48 dp each axis).
        val oxSeek = findViewById<android.widget.SeekBar>(R.id.seek_offset_x)
        val oxLabel = findViewById<TextView>(R.id.label_offset_x)
        fun renderOx() {
            oxLabel.text = getString(R.string.settings_offset_x) + " — ${prefs.keyOffsetXDp.toInt()}dp"
        }
        oxSeek.progress = prefs.keyOffsetXDp.toInt() + 48
        renderOx()
        oxSeek.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.keyOffsetXDp = (p - 48).toFloat()
            renderOx()
        })

        val oySeek = findViewById<android.widget.SeekBar>(R.id.seek_offset_y)
        val oyLabel = findViewById<TextView>(R.id.label_offset_y)
        fun renderOy() {
            oyLabel.text = getString(R.string.settings_offset_y) + " — ${prefs.keyOffsetYDp.toInt()}dp"
        }
        oySeek.progress = prefs.keyOffsetYDp.toInt() + 48
        renderOy()
        oySeek.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.keyOffsetYDp = (p - 48).toFloat()
            renderOy()
        })
    }

    private fun simpleSeek(onChange: (Int) -> Unit) =
        object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
    }

    // ------------------------------------------------------------------
    // Voice typing (offline)
    // ------------------------------------------------------------------

    private val voiceModels by lazy {
        com.localboard.keyboard.llm.VoiceModelManager(applicationContext)
    }

    private fun wireVoiceSection() {
        val section = findViewById<View>(R.id.voice_section)
        if (!com.localboard.keyboard.llm.VoiceBridge.available) {
            // Engine absent from this build: the feature is invisible, never broken.
            section.visibility = View.GONE
            return
        }
        findViewById<MaterialButton>(R.id.btn_voice_permission).setOnClickListener {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        bindSwitch(R.id.switch_voice_typing, prefs.voiceTypingEnabled) { enabled ->
            prefs.voiceTypingEnabled = enabled
            if (enabled && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
        }
        val status = findViewById<TextView>(R.id.voice_status)
        val btn = findViewById<MaterialButton>(R.id.btn_voice_model)
        fun render() {
            if (voiceModels.isReady) {
                status.text = getString(R.string.voice_model_ready) +
                    " (${voiceModels.sizeBytes / 1_000_000} MB)"
                btn.text = getString(R.string.voice_model_delete)
            } else {
                status.text = getString(R.string.voice_model_not_ready)
                btn.text = getString(R.string.voice_model_download)
            }
            btn.isEnabled = !voiceModels.downloading
        }
        render()
        btn.setOnClickListener {
            if (voiceModels.isReady) {
                voiceModels.deleteModel()
                render()
            } else if (!voiceModels.downloading) {
                btn.isEnabled = false
                lifecycleScope.launch {
                    voiceModels.download(
                        onProgress = { pct ->
                            status.text = getString(R.string.voice_model_not_ready) + " — $pct%"
                        },
                        onDone = { ok, err ->
                            if (!ok && err.isNotEmpty() && err != "cancelled") {
                                android.widget.Toast.makeText(
                                    this@SettingsActivity,
                                    "Voice model download failed: $err",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            render()
                        }
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Advanced: hide the tinkerer rows until explicitly switched on
    // ------------------------------------------------------------------

    /** Typing feel: vibration strength, key-click sound pack + volume,
     *  and animation level. All live-applied — the keyboard reads these
     *  prefs every time it is (re)shown. */
    private fun wireTypingFeel() {
        val vibIds = intArrayOf(
            R.id.radio_vib_off, R.id.radio_vib_light,
            R.id.radio_vib_medium, R.id.radio_vib_strong
        )
        val vibGroup = findViewById<RadioGroup>(R.id.radio_vib_group)
        vibGroup.check(vibIds[prefs.vibLevel.coerceIn(0, 3)])
        vibGroup.setOnCheckedChangeListener { _, id ->
            prefs.vibLevel = vibIds.indexOf(id).coerceAtLeast(0)
        }

        val soundIds = intArrayOf(
            R.id.radio_sound_off, R.id.radio_sound_soft,
            R.id.radio_sound_tactile, R.id.radio_sound_bubble
        )
        val soundGroup = findViewById<RadioGroup>(R.id.radio_sound_group)
        soundGroup.check(soundIds[prefs.soundPack.coerceIn(0, 3)])
        soundGroup.setOnCheckedChangeListener { _, id ->
            prefs.soundPack = soundIds.indexOf(id).coerceAtLeast(0)
        }

        val volSeek = findViewById<android.widget.SeekBar>(R.id.seek_sound_volume)
        val volLabel = findViewById<TextView>(R.id.label_sound_volume)
        fun renderVol() {
            volLabel.text = getString(R.string.feel_sound_volume) + " — ${prefs.soundVolume}%"
        }
        volSeek.progress = prefs.soundVolume
        renderVol()
        volSeek.setOnSeekBarChangeListener(simpleSeek { p ->
            prefs.soundVolume = p
            renderVol()
        })

        val animIds = intArrayOf(
            R.id.radio_anim_off, R.id.radio_anim_reduced, R.id.radio_anim_full
        )
        val animGroup = findViewById<RadioGroup>(R.id.radio_anim_group)
        animGroup.check(animIds[prefs.animLevel.coerceIn(0, 2)])
        animGroup.setOnCheckedChangeListener { _, id ->
            prefs.animLevel = animIds.indexOf(id).coerceAtLeast(0)
        }
    }

    // ---------------------------------------------------------------
    // Import your words: chat exports (WhatsApp…) + system dictionary
    // ---------------------------------------------------------------

    private fun wireImportSection() {
        findViewById<MaterialButton>(R.id.btn_import_chat).setOnClickListener {
            // .txt exports surface under different mime types depending on
            // which app saved them (Files, Drive, WhatsApp's own share).
            pickChatExport.launch(arrayOf("text/*", "application/octet-stream"))
        }
        val dictBtn = findViewById<MaterialButton>(R.id.btn_import_dictionary)
        dictBtn.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    this, "android.permission.READ_USER_DICTIONARY"
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                runDictionaryImport()
            } else {
                reqUserDict.launch("android.permission.READ_USER_DICTIONARY")
            }
        }
        addWordListImportRow(dictBtn)
    }

    /** "Import word list" lives in code (not the layout) so it can slot in
     *  right under the system-dictionary button. Mirrors the outlined-button
     *  + dimmed hint style of the rows around it. */
    private fun addWordListImportRow(anchor: MaterialButton) {
        val parent = anchor.parent as? android.widget.LinearLayout ?: return
        val at = parent.indexOfChild(anchor) + 1
        val btn = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Import word list"
        }
        parent.addView(
            btn, at,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(8) }
        )
        val hint = TextView(this).apply {
            alpha = 0.6f
            setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall
            )
            text = "Paste words or phrases below — one per line, or separated " +
                "by commas/semicolons. Tip: Gboard's \"Personal dictionary\" " +
                "entries sync into the Android User Dictionary, so those can " +
                "be pulled in with the \"Import personal dictionary\" option above."
        }
        parent.addView(
            hint, at + 1,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpi(4) }
        )
        btn.setOnClickListener { showWordListImportDialog() }
    }

    private fun showWordListImportDialog() {
        val pad = dpi(20)
        val input = android.widget.EditText(this).apply {
            minLines = 4
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            hint = "e.g.\nKubernetes\nSan Francisco\non my way!"
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, dpi(8), pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Import word list")
            .setView(box)
            .setPositiveButton("Import") { _, _ ->
                val words = input.text.toString()
                    .split('\n', ',', ';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.length <= 40 }
                    .distinct()
                if (words.isEmpty()) {
                    Toast.makeText(
                        this, "No usable words found — nothing imported.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    importWordList(words)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importWordList(words: List<String>) {
        val dlg = AlertDialog.Builder(this)
            .setMessage(getString(R.string.import_learning))
            .setCancelable(false).create()
        dlg.show()
        lifecycleScope.launch {
            try {
                val added = withContext(Dispatchers.IO) {
                    contextEngine.initialize()
                    contextEngine.importExternalDictionary(words)
                }
                dlg.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    "Imported ${words.size} words — $added newly learned.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (t: Throwable) {
                dlg.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    "Import hit a problem (${t.javaClass.simpleName}) — nothing was changed.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun onChatExportPicked(uri: android.net.Uri) {
        val dlg = AlertDialog.Builder(this)
            .setMessage(getString(R.string.import_reading))
            .setCancelable(false).create()
        dlg.show()
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        // Cap at ~3.9M chars — years of chat (≈30k messages),
                        // without ever letting a huge file near the heap.
                        val sb = StringBuilder(4_000_000)
                        ins.bufferedReader(Charsets.UTF_8).let { r ->
                            val cbuf = CharArray(8192)
                            while (sb.length < 3_900_000) {
                                val n = r.read(cbuf)
                                if (n < 0) break
                                sb.append(cbuf, 0, n)
                            }
                        }
                        ChatExportParser.parse(sb.toString())
                    }
                } catch (t: Throwable) { null }
            }
            dlg.dismiss()
            if (parsed == null || parsed.bySender.isEmpty()) {
                Toast.makeText(
                    this@SettingsActivity,
                    "No chat messages found — is this a WhatsApp/Telegram export .txt?",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val senders = ChatExportParser.sendersByActivity(parsed).take(10)
            val labels = senders.map { "${it.first}  ·  ${it.second} messages" }.toTypedArray()
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.import_who_is_you)
                .setItems(labels) { _, which ->
                    val mine = parsed.bySender[senders[which].first].orEmpty()
                    learnImportedChat(mine)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Learn ONLY the user's own messages from the export: words + phrases
     *  into the prediction/swipe model. The style profile is deliberately
     *  left untouched — a chat import must not overwrite a profile the user
     *  is happy with. */
    private fun learnImportedChat(messages: List<String>) {
        val dlg = AlertDialog.Builder(this)
            .setMessage(getString(R.string.import_learning))
            .setCancelable(false).create()
        dlg.show()
        lifecycleScope.launch {
            try {
                contextEngine.initialize()
                contextEngine.importCorpus(
                    messages.joinToString("\n"), updateStyleProfile = false
                )
                dlg.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    "Learned from ${messages.size} of your messages — predictions " +
                        "and swipe pick them up immediately.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (t: Throwable) {
                dlg.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    "Import hit a problem (${t.javaClass.simpleName}) — nothing was changed.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun runDictionaryImport() {
        val dlg = AlertDialog.Builder(this)
            .setMessage(getString(R.string.import_learning))
            .setCancelable(false).create()
        dlg.show()
        lifecycleScope.launch {
            val words = withContext(Dispatchers.IO) {
                val out = ArrayList<String>()
                try {
                    contentResolver.query(
                        android.provider.UserDictionary.Words.CONTENT_URI,
                        arrayOf(android.provider.UserDictionary.Words.WORD),
                        null, null, null
                    )?.use { c ->
                        val idx = c.getColumnIndex(android.provider.UserDictionary.Words.WORD)
                        while (c.moveToNext() && out.size < 5000) {
                            c.getString(idx)?.trim()
                                ?.takeIf { it.isNotEmpty() }?.let { out += it }
                        }
                    }
                } catch (t: Throwable) { }
                out
            }
            if (words.isEmpty()) {
                dlg.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    "The system personal dictionary is empty. Words you add via " +
                        "Gboard's \"Personal dictionary\" land there — learned " +
                        "words inside Gboard itself stay private to Gboard.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            try {
                contextEngine.initialize()
                val added = contextEngine.importExternalDictionary(words)
                dlg.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    "Imported ${words.size} dictionary words — $added newly learned.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (t: Throwable) {
                dlg.dismiss()
                Toast.makeText(
                    this@SettingsActivity,
                    "Import hit a problem (${t.javaClass.simpleName}) — nothing was changed.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** AI context-window size (tokens). The model is reloaded lazily the next
     *  time it runs, so a change here takes effect on the next AI tap — no
     *  reload happens while the user is typing. */
    private fun wireContextWindow() {
        val sizes = intArrayOf(1024, 2048, 4096, 8192)
        val ids = intArrayOf(
            R.id.radio_ctx_1024, R.id.radio_ctx_2048,
            R.id.radio_ctx_4096, R.id.radio_ctx_8192
        )
        val current = sizes.indexOf(prefs.llmCtxSize).takeIf { it >= 0 } ?: 1
        val group = findViewById<RadioGroup>(R.id.radio_ctx_group)
        group.check(ids[current])
        group.setOnCheckedChangeListener { _, id ->
            val i = ids.indexOf(id)
            if (i >= 0) prefs.llmCtxSize = sizes[i]
        }
    }

    /** Rows owned by the Advanced switch. Section collapsing must leave these
     *  alone, or expanding a section would resurrect hidden advanced rows. */
    private val advancedIds = intArrayOf(
        R.id.label_offset_x, R.id.seek_offset_x,
        R.id.label_offset_y, R.id.seek_offset_y,
        R.id.model_instructions,
        R.id.btn_reset_fingerfit, R.id.engine_status,
        R.id.diag_ai, R.id.diag_ai_body, R.id.btn_diag_copy
    )

    private fun applyAdvancedVisibility(show: Boolean) {
        val vis = if (show) View.VISIBLE else View.GONE
        for (id in advancedIds) {
            val v = findViewById<View>(id) ?: continue
            // An advanced row inside a COLLAPSED section stays hidden either
            // way — the section wins.
            val sectionOpen = sections.firstOrNull { s -> s.body.any { it.id == id } }
                ?.let { isExpanded(it) } ?: true
            v.visibility = if (show && sectionOpen) vis else View.GONE
        }
    }

    private fun wireAdvancedToggle() {
        bindSwitch(R.id.switch_advanced, prefs.showAdvanced) { show ->
            prefs.showAdvanced = show
            applyAdvancedVisibility(show)
        }
        applyAdvancedVisibility(prefs.showAdvanced)

        findViewById<MaterialButton>(R.id.btn_reset_fingerfit).setOnClickListener {
            prefs.tapModelData = ""
            // keyFitData moves the KEYS, so a reset that left it in place
            // would look like it had done nothing at all.
            prefs.keyFitData = ""
            prefs.keyOffsetXDp = 0f
            prefs.keyOffsetYDp = 0f
            android.widget.Toast.makeText(
                this,
                "Finger fit reset — keys back to standard positions",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private companion object {
        private const val REQ_MIC = 4200
    }

    private suspend fun refreshEngineStatus() {
        val fast = FastEngine.shared(applicationContext)
        val status = findViewById<TextView>(R.id.engine_status)
        val profile = contextEngine.profile
        status.text = buildString {
            // Quiet when healthy: problems get a loud, actionable line and
            // everything else is a one-line human summary — no debug dumps.
            val visible = com.localboard.keyboard.util.ImeStatus.isVisible(this@SettingsActivity)
            val enabled = com.localboard.keyboard.util.ImeStatus.isEnabled(this@SettingsActivity)
            if (!visible || !enabled) {
                append("⚠ Setup isn't finished — keyboard visible to system: ")
                append(if (visible) "yes" else "no")
                append(", enabled: ").append(if (enabled) "yes" else "no").append('\n')
                if (!visible) {
                    append(getString(R.string.keyboard_not_registered_hint)).append('\n')
                }
                append('\n')
            }
            if (!contextEngine.persistenceAvailable) {
                append("⚠ Encrypted storage unavailable on this device — running in-memory only\n\n")
            }
            val spec = com.localboard.keyboard.llm.ModelRegistry.active(prefs)
            val ready = com.localboard.keyboard.llm.ModelRegistry.isReady(applicationContext, spec)
            append("AI model: ").append(spec.displayName.substringBefore(" ("))
                .append(if (ready) " — ready" else " — not downloaded yet")
                .append('\n')
            append("Dictionary: ${fast.baseWordCount} built-in words")
            val learned = fast.learnedWordCount
            if (learned > 0) append(" + $learned learned from you")
            append('\n')
            append("Style profile: ")
            append(
                if (profile.sentencesAnalyzed > 0)
                    "learning your voice (${profile.sentencesAnalyzed} sentences so far)"
                else "not started yet — just keep typing"
            )
        }
    }

    // ------------------------------------------------------------------
    // Language model section: catalog + user imports, one row each.
    // ------------------------------------------------------------------

    private var modelPollJob: kotlinx.coroutines.Job? = null

    private val modelFilePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importModelFile(it) }
    }

    private fun wireModelSection() {
        findViewById<MaterialButton>(R.id.btn_model_add_file).setOnClickListener {
            modelFilePicker.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.btn_model_add_url).setOnClickListener {
            showAddUrlDialog()
        }
        rebuildModelList()
    }

    private fun importModelFile(uri: android.net.Uri) {
        try {
            val name = run {
                var n = "custom-model.gguf"
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) n = c.getString(idx) ?: n
                }
                n
            }
            if (!name.endsWith(".gguf", ignoreCase = true)) {
                android.widget.Toast.makeText(
                    this, "That's not a .gguf file — pick a GGUF model",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }
            val input = contentResolver.openInputStream(uri) ?: return
            val spec = com.localboard.keyboard.llm.ModelRegistry.importFromFile(
                applicationContext, prefs, input, name.removeSuffix(".gguf"), name
            )
            prefs.activeModelId = spec.id
            prefs.activeProviderId = LlamaCppProvider.PROVIDER_ID
            android.widget.Toast.makeText(
                this, "Added ${spec.displayName} (${spec.template.label})",
                android.widget.Toast.LENGTH_LONG
            ).show()
            rebuildModelList()
            lifecycleScope.launch { refreshEngineStatus() }
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                this, "Import failed: ${t.message}", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showAddUrlDialog() {
        val ctx = this
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameInput = android.widget.EditText(ctx).apply {
            hint = getString(R.string.model_custom_name_hint)
        }
        val urlInput = android.widget.EditText(ctx).apply {
            hint = getString(R.string.model_url_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(nameInput)
        layout.addView(urlInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.model_add_url)
            .setView(layout)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.model_download) { _, _ ->
                val url = urlInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                    .ifBlank { url.substringAfterLast('/').removeSuffix(".gguf") }
                if (!url.startsWith("http") || !url.contains(".gguf", ignoreCase = true)) {
                    android.widget.Toast.makeText(
                        this, "Paste a direct .gguf download link",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                val fileName = url.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "custom-model.gguf" }
                val spec = com.localboard.keyboard.llm.ModelSpec(
                    id = com.localboard.keyboard.llm.ModelRegistry.nextCustomId(prefs),
                    displayName = name,
                    fileName = fileName,
                    downloadUrl = url,
                    sizeLabel = "custom link",
                    template = com.localboard.keyboard.llm.ChatTemplate.guess(fileName),
                    nCtx = 2048,
                    defaultTemp = 0.65f,
                    builtIn = false
                )
                com.localboard.keyboard.llm.ModelRegistry.addUserModel(prefs, spec)
                com.localboard.keyboard.llm.ModelRegistry.startDownload(applicationContext, spec)
                prefs.activeModelId = spec.id
                prefs.activeProviderId = LlamaCppProvider.PROVIDER_ID
                rebuildModelList()
                pollModelProgress()
            }
            .show()
    }

    /** One row per model: select / status / download / delete. */
    /** Quick model = the tiny lane for per-word autocorrect. Only downloaded
     *  models are offered; "" falls back to the registry default (0.6B). */
    private fun refreshQuickModelRow() {
        val row = findViewById<TextView>(R.id.quick_model_value) ?: return
        val spec = com.localboard.keyboard.llm.ModelRegistry.quick(prefs)
        val chosen = prefs.quickModelId
        row.text = if (chosen.isEmpty())
            "${spec.displayName} · default" else spec.displayName
        row.setOnClickListener { showQuickModelPicker() }
    }

    private fun showQuickModelPicker() {
        val ready = com.localboard.keyboard.llm.ModelRegistry.all(prefs)
            .filter { com.localboard.keyboard.llm.ModelRegistry.isReady(applicationContext, it) }
        if (ready.isEmpty()) {
            Toast.makeText(this, R.string.settings_quick_model_none_downloaded, Toast.LENGTH_LONG).show()
            return
        }
        val labels = ready.map {
            it.displayName + " · " + it.sizeLabel +
                (if (it.id == prefs.quickModelId) " ✓" else "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_quick_model)
            .setItems(labels) { _, which ->
                prefs.quickModelId = ready[which].id
                refreshQuickModelRow()
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun rebuildModelList() {
        val container = findViewById<android.widget.LinearLayout>(R.id.model_list_container)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // Row 0: the always-available offline composer (no AI).
        container.addView(modelRowShell(
            title = getString(R.string.model_local_title),
            subtitle = "always available, no download",
            selected = prefs.activeProviderId != LlamaCppProvider.PROVIDER_ID,
            onSelect = {
                prefs.activeProviderId = "local_style"
                rebuildModelList()
                lifecycleScope.launch { refreshEngineStatus() }
            },
            actionLabel = null,
            onAction = null
        ))

        val registryModels = com.localboard.keyboard.llm.ModelRegistry.all(prefs)
        for (spec in registryModels) {
            val state = com.localboard.keyboard.llm.ModelRegistry.state(applicationContext, spec)
            val selected = prefs.activeProviderId == LlamaCppProvider.PROVIDER_ID &&
                prefs.activeModelId == spec.id
            val subtitle = buildString {
                append(spec.sizeLabel).append(" · ").append(spec.template.label)
                append(" · ctx ").append(spec.nCtx)
                append(" — ").append(
                    when (state.status) {
                        com.localboard.keyboard.llm.ModelRegistry.Status.READY ->
                            if (selected) "ready · active" else "ready"
                        com.localboard.keyboard.llm.ModelRegistry.Status.DOWNLOADING -> {
                            val mb = state.downloadedBytes / 1_000_000
                            val tot = state.totalBytes / 1_000_000
                            "downloading $mb / $tot MB (${state.progress}%)"
                        }
                        com.localboard.keyboard.llm.ModelRegistry.Status.ERROR ->
                            "error: ${state.error ?: "download failed"}"
                        else -> "not downloaded"
                    }
                )
            }
            val (label, action) = when (state.status) {
                com.localboard.keyboard.llm.ModelRegistry.Status.READY ->
                    (if (selected) getString(R.string.model_active) else getString(R.string.model_select)) to {
                        prefs.activeModelId = spec.id
                        prefs.activeProviderId = LlamaCppProvider.PROVIDER_ID
                        rebuildModelList()
                        lifecycleScope.launch { refreshEngineStatus() }
                        Unit
                    }
                com.localboard.keyboard.llm.ModelRegistry.Status.DOWNLOADING ->
                    getString(R.string.model_cancel) to {
                        com.localboard.keyboard.llm.ModelRegistry.cancelDownload(spec)
                        rebuildModelList()
                    }
                else ->
                    (if (spec.downloadUrl != null) getString(R.string.model_download) else "missing file") to {
                        if (spec.downloadUrl != null) {
                            com.localboard.keyboard.llm.ModelRegistry.startDownload(
                                applicationContext, spec
                            )
                            pollModelProgress()
                            rebuildModelList()
                        }
                    }
            }
            container.addView(modelRowShell(
                title = spec.displayName,
                subtitle = subtitle,
                selected = selected,
                onSelect = {
                    if (com.localboard.keyboard.llm.ModelRegistry.isReady(applicationContext, spec)) {
                        prefs.activeModelId = spec.id
                        prefs.activeProviderId = LlamaCppProvider.PROVIDER_ID
                        rebuildModelList()
                        lifecycleScope.launch { refreshEngineStatus() }
                    }
                },
                actionLabel = label,
                onAction = action,
                onLongPress = {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.model_delete)
                        .setMessage("Remove ${spec.displayName} from this device?")
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.model_delete) { _, _ ->
                            com.localboard.keyboard.llm.ModelRegistry.delete(
                                applicationContext, prefs, spec
                            )
                            rebuildModelList()
                            lifecycleScope.launch { refreshEngineStatus() }
                        }
                        .show()
                }
            ))
        }
    }

    private fun modelRowShell(
        title: String,
        subtitle: String,
        selected: Boolean,
        onSelect: () -> Unit,
        actionLabel: String?,
        onAction: (() -> Unit)?,
        onLongPress: (() -> Unit)? = null
    ): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            if (selected) {
                setBackgroundColor(0x22BB86FC)
            }
            setOnClickListener { onSelect() }
            if (onLongPress != null) setOnLongClickListener { onLongPress(); true }
        }
        val texts = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        texts.addView(TextView(this).apply {
            text = (if (selected) "● " else "○ ") + title
            setTextColor(resources.getColor(R.color.app_text, theme))
            textSize = 14f
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            setTextColor(resources.getColor(R.color.app_text_secondary, theme))
            textSize = 12f
        })
        row.addView(texts, android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (actionLabel != null && onAction != null) {
            row.addView(MaterialButton(this).apply {
                text = actionLabel
                textSize = 12f
                setOnClickListener { onAction() }
            }, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        return row
    }

    private fun pollModelProgress() {
        if (modelPollJob?.isActive == true) return
        modelPollJob = lifecycleScope.launch {
            while (true) {
                delay(500)
                rebuildModelList()
                val anyDownloading = com.localboard.keyboard.llm.ModelRegistry.all(prefs).any {
                    com.localboard.keyboard.llm.ModelRegistry.state(applicationContext, it)
                        .status == com.localboard.keyboard.llm.ModelRegistry.Status.DOWNLOADING
                }
                if (!anyDownloading) break
            }
            rebuildModelList()
            refreshEngineStatus()
        }
    }

    /** Multi-choice list of apps the keyboard has typed in; checked = save
     *  nothing from that app. Blocking also wipes its stored context. */
    private fun showBlockedAppsDialog() {
        lifecycleScope.launch {
            // First open does Keystore + SQLCipher work synchronously — keep
            // it off the main thread.
            val seen = withContext(Dispatchers.IO) {
                try {
                    com.localboard.keyboard.data.db.AppDatabase.get(applicationContext)
                        .appContextDao().all().map { it.packageName }
                } catch (_: Throwable) { emptyList() }
            }
            val pkgs = (seen + prefs.contextBlockedApps).distinct()
            if (pkgs.isEmpty()) {
                android.widget.Toast.makeText(
                    this@SettingsActivity,
                    "No apps seen yet — type in an app first, then it appears here",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val pm = packageManager
            val labels = pkgs.map { pkg ->
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Throwable) { pkg }
            }.toTypedArray()
            val checked = pkgs.map { prefs.isAppBlocked(it) }.toBooleanArray()
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.blocked_apps_title)
                .setMessage(R.string.blocked_apps_message)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                    prefs.setAppBlocked(pkgs[which], isChecked)
                    if (isChecked) {
                        lifecycleScope.launch { contextEngine.eraseAppData(pkgs[which]) }
                    }
                }
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun showPrivacyDashboard() {
        lifecycleScope.launch {
            val stats = contextEngine.dashboardStats()
            val message = buildString {
                append("Everything below lives only on this device, encrypted with a key from the Android Keystore. The network is used only when you choose to download the language model — typing never leaves this device.\n\n")
                stats.forEach { (k, v) -> append("• $k: $v\n") }
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(getString(R.string.settings_privacy_dashboard))
                .setMessage(message)
                .setPositiveButton(R.string.done, null)
                .show()
        }
    }

    private fun confirmErase() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear)
            .setMessage(R.string.settings_clear_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.erase) { _, _ ->
                lifecycleScope.launch {
                    // Full wipe: every database table (learned words, style,
                    // profiles history, typing log, usage), the screen buffer,
                    // and every preference (style prompt, finger-fit
                    // calibration, settings) — a real factory reset. Model
                    // downloads stay on disk (not personal data).
                    contextEngine.eraseAllLearnedData()
                    prefs.clearAll()
                    refreshEngineStatus()
                    Toast.makeText(
                        this@SettingsActivity,
                        "All data erased", Toast.LENGTH_LONG
                    ).show()
                    recreate()
                }
            }
            .show()
    }
}
