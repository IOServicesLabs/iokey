package com.localboard.keyboard.onboarding

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.localboard.keyboard.R
import com.localboard.keyboard.context.ContextEngine
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.settings.SettingsActivity
import com.localboard.keyboard.util.ImeStatus
import com.localboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run flow: privacy pitch -> choose a style bootstrap (samples /
 * paste your writing / import SMS) -> build the personal model locally ->
 * enable & switch to the keyboard.
 */
class OnboardingActivity : AppCompatActivity() {

    private enum class Step { WELCOME, CHOICE, FREEFORM, WORKING, DONE }

    private lateinit var prefs: Prefs
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var choices: LinearLayout
    private lateinit var freeformLayout: TextInputLayout
    private lateinit var freeformInput: TextInputEditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var primary: Button
    private lateinit var secondary: Button
    private lateinit var settingsBtn: Button
    private var step = Step.WELCOME

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) runSmsBootstrap()
        else toast("SMS permission declined — pick another option")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        prefs = Prefs(this)

        title = findViewById(R.id.onb_title)
        body = findViewById(R.id.onb_body)
        choices = findViewById(R.id.onb_choices)
        freeformLayout = findViewById(R.id.freeform_input_layout)
        freeformInput = findViewById(R.id.freeform_input)
        progress = findViewById(R.id.onb_progress)
        status = findViewById(R.id.onb_status)
        primary = findViewById(R.id.btn_primary)
        secondary = findViewById(R.id.btn_secondary)
        settingsBtn = findViewById(R.id.btn_settings)
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btn_choice_samples).setOnClickListener {
            runCorpusBootstrap(BootstrapSamples.SAMPLE_TEXTS)
        }
        findViewById<Button>(R.id.btn_choice_freeform).setOnClickListener { showStep(Step.FREEFORM) }
        // The Play distribution ships no SMS access at all, so the option is
        // removed rather than offered and then failing.
        val smsButton = findViewById<Button>(R.id.btn_choice_sms)
        smsButton.visibility = if (SMSReader.AVAILABLE) View.VISIBLE else View.GONE
        smsButton.setOnClickListener {
            if (SMSReader.hasPermission(this)) runSmsBootstrap()
            else smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
        }

        primary.setOnClickListener { onPrimary() }
        secondary.setOnClickListener { onSecondary() }

        showStep(if (prefs.onboardingDone) Step.DONE else Step.WELCOME)
    }

    // ---------------------------------------------------------------

    private fun onPrimary() {
        when (step) {
            Step.WELCOME -> showStep(Step.CHOICE)
            Step.FREEFORM -> {
                val text = freeformInput.text?.toString().orEmpty().trim()
                if (text.length < 80) {
                    toast("Paste a bit more text so the model can learn your style (80+ characters)")
                } else {
                    runCorpusBootstrap(text)
                }
            }
            Step.DONE -> {
                if (ImeStatus.isEnabled(this)) {
                    // First time through, the tour comes before the settings
                    // wall: the features worth knowing about (glide, undo,
                    // hold-✨) aren't discoverable from a list of switches.
                    if (!prefs.tutorialSeen) {
                        startActivity(Intent(this, TutorialActivity::class.java))
                    } else {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                } else {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            }
            else -> Unit
        }
    }

    private fun onSecondary() {
        when (step) {
            Step.CHOICE -> showStep(Step.WELCOME)
            Step.DONE -> {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
            else -> Unit
        }
    }

    private fun showStep(s: Step) {
        step = s
        choices.visibility = if (s == Step.CHOICE) View.VISIBLE else View.GONE
        freeformLayout.visibility = if (s == Step.FREEFORM) View.VISIBLE else View.GONE
        progress.visibility = if (s == Step.WORKING) View.VISIBLE else View.GONE
        status.visibility = if (s == Step.WORKING) View.VISIBLE else View.GONE
        secondary.visibility = if (s == Step.CHOICE || s == Step.DONE) View.VISIBLE else View.GONE
        settingsBtn.visibility = if (s == Step.DONE) View.VISIBLE else View.GONE

        when (s) {
            Step.WELCOME -> {
                title.setText(R.string.onb_welcome_title)
                body.setText(R.string.onb_welcome_body)
                primary.visibility = View.VISIBLE
                primary.setText(R.string.onb_get_started)
            }
            Step.CHOICE -> {
                title.setText(R.string.onb_bootstrap_title)
                body.setText(R.string.onb_bootstrap_body)
                primary.text = ""
                primary.visibility = View.GONE
                secondary.setText(R.string.cancel)
            }
            Step.FREEFORM -> {
                title.setText(R.string.onb_choice_freeform)
                body.setText(R.string.onb_choice_freeform_desc)
                primary.visibility = View.VISIBLE
                primary.setText(R.string.onb_next)
                secondary.visibility = View.GONE
            }
            Step.DONE -> {
                title.setText(R.string.onb_done)
                body.text = doneBody()
                refreshDoneButtons()
            }
            Step.WORKING -> primary.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the live IME status every time we come back from system settings.
        if (step == Step.DONE) {
            body.text = doneBody()
            refreshDoneButtons()
        }
    }

    /** DONE-step buttons follow the live IME status: while the keyboard
     *  isn't enabled the primary action opens system IME settings; once it
     *  is, the primary action jumps straight into IOKey Settings. */
    private fun refreshDoneButtons() {
        val enabled = ImeStatus.isEnabled(this)
        primary.visibility = View.VISIBLE
        primary.setText(if (enabled) R.string.onb_open_settings else R.string.onb_enable)
        secondary.setText(R.string.onb_switch)
        // Once the primary button IS the settings shortcut, the smaller
        // text button would just duplicate it.
        settingsBtn.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    /**
     * Status-aware closing guidance: one short human paragraph pointing at
     * the single next step — no debug dumps.
     */
    private fun doneBody(): String {
        val visible = ImeStatus.isVisible(this)
        val enabled = ImeStatus.isEnabled(this)
        return when {
            enabled ->
                "You're all set. Open Settings to make the keyboard yours, or tap " +
                "\"Switch to IOKey\" and start typing."
            visible ->
                "Almost there — tap \"Enable IOKey\" below, find IOKey in the " +
                "list (Samsung: General management → Keyboard list and default), toggle " +
                "it ON, then come back here."
            else ->
                "The system hasn't indexed IOKey yet:\n\n" +
                "1. Restart the phone once — some devices only index new keyboards at boot.\n" +
                "2. Still missing after the restart? Check Settings → Security and privacy → " +
                "Auto Blocker and allow this app, then restart again.\n" +
                "3. Reopen IOKey — this screen updates itself."
        }
    }

    // ---------------------------------------------------------------

    private fun runSmsBootstrap() {
        showStep(Step.WORKING)
        updateStatus("Reading your sent messages…", 10)
        lifecycleScope.launch {
            val messages = SMSReader.readSentMessages(this@OnboardingActivity, 2500)
            if (messages.isEmpty()) {
                toast(getString(R.string.onb_no_sms))
                showStep(Step.CHOICE)
                primary.visibility = View.GONE
                return@launch
            }
            buildModel(messages.joinToString("\n"))
        }
    }

    private fun runCorpusBootstrap(text: String) {
        showStep(Step.WORKING)
        lifecycleScope.launch { buildModel(text) }
    }

    private suspend fun buildModel(corpus: String) {
        try {
            updateStatus(getString(R.string.onb_analyzing), 25)
            val engine = ContextEngine(applicationContext, FastEngine.shared(applicationContext))
            withContext(Dispatchers.IO) { engine.initialize() }

            updateStatus(getString(R.string.onb_building), 55)
            engine.importCorpus(corpus)

            updateStatus("Finalizing…", 90)
            delay(400)
            prefs.onboardingDone = true
            updateStatus("", 100)
            if (!engine.persistenceAvailable) {
                toast("Note: encrypted storage unavailable on this device — model kept in memory for this session")
            }
            showStep(Step.DONE)
        } catch (t: Throwable) {
            // Never crash onboarding: report and let the user pick another path.
            toast("Setup hit a problem (${t.javaClass.simpleName}). Please try another option.")
            showStep(Step.CHOICE)
            primary.visibility = View.GONE
        }
    }

    private fun updateStatus(text: String, pct: Int) {
        status.text = text
        progress.progress = pct
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
