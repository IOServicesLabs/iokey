package com.localboard.keyboard.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.localboard.keyboard.R
import com.localboard.keyboard.context.ContextEngine
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.util.BackupManager
import com.localboard.keyboard.util.ContactNames
import com.localboard.keyboard.util.NetGuard
import com.localboard.keyboard.util.Prefs
import com.localboard.keyboard.util.Unlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data and network controls — the screen that makes the privacy claim
 * checkable instead of merely stated.
 *
 * Built in code rather than XML because it is a flat list of switches whose
 * shape follows the preference list; a 400-line layout file would only be a
 * second place to keep the same order in sync.
 */
class PrivacyActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val contextEngine by lazy {
        ContextEngine(applicationContext, FastEngine.shared(applicationContext))
    }

    private lateinit var root: LinearLayout
    private var pendingBackupJson: String? = null

    /** SAF: where to write the backup the user just asked for. */
    private val createBackup = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { writeBackupTo(it) } }

    private val openBackup = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { readBackupFrom(it) } }

    private val reqContacts = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) importContacts()
        else Toast.makeText(this, "Contacts permission denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "Data and network"

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(16)
            setPadding(p, p, p, dp(32))
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setContentView(scroll)
        build()
    }

    private fun build() {
        root.removeAllViews()

        // ---------------------------------------------------------- network
        header("Network")
        switch(
            "Allow network access",
            "Off means model downloads refuse to open a connection at all. " +
                "Typing data is never sent either way — this makes that structural.",
            NetGuard.allowed
        ) { on ->
            NetGuard.setAllowed(on)
            build()
        }

        val ledger = NetGuard.entries()
        hint(
            if (ledger.isEmpty()) "Nothing has left this device."
            else "Last ${ledger.size} network events, newest first:"
        )
        if (ledger.isNotEmpty()) {
            val body = ledger.take(30).joinToString("\n") { (ts, host, what) ->
                val when_ = android.text.format.DateFormat.format("MMM d HH:mm", ts)
                "$when_  $host  $what"
            }
            mono(body)
            button("Clear network log") { NetGuard.clear(); build() }
        }

        // ---------------------------------------------------------- capture
        header("What the keyboard may see")
        switch(
            "Hide codes and card numbers",
            "Strips one-time codes, card-shaped numbers and key-like tokens out " +
                "of captured screen text before it is stored or shown to the AI.",
            prefs.redactScreenText
        ) { on ->
            prefs.redactScreenText = on
            com.localboard.keyboard.context.ScreenBuffer.redactEnabled = on
        }
        switch(
            "Require unlock for saved data",
            "Ask for fingerprint or PIN before showing the learned-words viewer " +
                "and before exporting a backup.",
            prefs.biometricLock
        ) { on -> prefs.biometricLock = on }

        // --------------------------------------------------------- per-app
        header("Per-app rules")
        hint(
            "Apps you have given a rule to. Incognito fields (Signal, private " +
                "browser tabs) are already honoured automatically."
        )
        val ruled = prefs.appsWithPrivacyRules().sorted()
        if (ruled.isEmpty()) hint("No per-app rules yet.")
        for (pkg in ruled) {
            val bits = buildList {
                if (prefs.isAppBlocked(pkg)) add("no context")
                if (pkg in prefs.noLearnApps) add("no learning")
                if (pkg in prefs.noScreenApps) add("no screen read")
                if (pkg in prefs.noAiApps) add("no AI")
            }.joinToString(", ")
            button("${labelFor(pkg)} — $bits") { editAppRules(pkg) }
        }
        button("Add an app…") { pickApp() }

        // ---------------------------------------------------------- backup
        header("Backup")
        hint(
            "Settings, theme, snippets, finger calibration and learned words. " +
                "Android cannot move app data between installs, so this file is " +
                "the only way to carry them across."
        )
        button("Export to a file…") { startExport() }
        button("Restore from a file…") { openBackup.launch(arrayOf("*/*")) }

        // -------------------------------------------------------- contacts
        header("Contact names")
        switch(
            "Learn names from contacts",
            "Stops proper nouns being autocorrected away. Only names are read — " +
                "never numbers, emails or anything else — and nothing is uploaded.",
            prefs.learnContactNames
        ) { on ->
            prefs.learnContactNames = on
            if (on) requestContacts()
        }
        if (prefs.contactsSyncedAt > 0) {
            hint(
                "Last imported " + android.text.format.DateFormat
                    .format("MMM d, HH:mm", prefs.contactsSyncedAt)
            )
        }
        if (prefs.learnContactNames) button("Import names now") { requestContacts() }

        // ----------------------------------------------------------- power
        header("Battery")
        switch(
            "Skip AI drafting when low",
            "Drafting is the only expensive thing this keyboard does. Below the " +
                "battery floor (and off charge) it is skipped; typing, autocorrect " +
                "and swipe are untouched.",
            prefs.powerAwareAi
        ) { on -> prefs.powerAwareAi = on; build() }
        if (prefs.powerAwareAi) hint("Battery floor: ${prefs.aiBatteryFloor}%")

        // ----------------------------------------------------------- voice
        header("Voice")
        switch(
            "Add punctuation to voice text",
            "Capitalises sentences and adds a full stop, which the offline model " +
                "does not produce on its own.",
            prefs.voicePunctuation
        ) { on -> prefs.voicePunctuation = on }

        // ------------------------------------------------------ diagnostics
        header("Diagnostics")
        val crash = java.io.File(filesDir, com.localboard.keyboard.LocalBoardApp.CRASH_FILE)
        if (crash.exists() && crash.length() > 0) {
            val count = crash.readText().split("═══").size / 2
            hint("$count recorded crash(es). Stored on this device only.")
            button("View crash log") { showCrashLog(crash) }
            button("Clear crash log") { crash.delete(); build() }
        } else {
            hint("No crashes recorded.")
        }
    }

    // ------------------------------------------------------------- backup

    private fun startExport() {
        Unlock.require(this, "Export backup", prefs.biometricLock) {
            lifecycleScope.launch {
                val words = withContext(Dispatchers.IO) {
                    try { contextEngine.learnedWordsForBackup(4000) } catch (_: Throwable) { emptyList() }
                }
                pendingBackupJson = withContext(Dispatchers.Default) {
                    BackupManager.buildJson(prefs, words)
                }
                createBackup.launch("localboard-backup.lbbk")
            }
        }
    }

    private fun writeBackupTo(uri: android.net.Uri) {
        val json = pendingBackupJson ?: return
        pendingBackupJson = null
        askPassphrase("Protect this backup", allowEmpty = true) { pass ->
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val bytes = if (pass.isEmpty()) json.toByteArray()
                        else BackupManager.encrypt(json, pass)
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        true
                    } catch (_: Throwable) { false }
                }
                Toast.makeText(
                    this@PrivacyActivity,
                    if (!ok) "Could not write the backup"
                    else if (pass.isEmpty()) "Exported (unencrypted)"
                    else "Exported and encrypted",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun readBackupFrom(uri: android.net.Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Throwable) { null }
            } ?: run {
                Toast.makeText(this@PrivacyActivity, "Could not read that file", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (BackupManager.isEncrypted(bytes)) {
                askPassphrase("Backup passphrase", allowEmpty = false) { pass ->
                    // PBKDF2 with 120k iterations has no business on the main
                    // thread — same treatment as the encrypt path above.
                    lifecycleScope.launch {
                        val json = withContext(Dispatchers.Default) {
                            runCatching { BackupManager.decrypt(bytes, pass) }.getOrNull()
                        }
                        applyBackup(json)
                    }
                }
            } else {
                applyBackup(String(bytes))
            }
        }
    }

    private fun applyBackup(json: String?) {
        if (json == null) {
            Toast.makeText(this, "Wrong passphrase, or the file is damaged", Toast.LENGTH_LONG).show()
            return
        }
        val payload = try { BackupManager.parseJson(json) } catch (_: Throwable) { null }
        if (payload == null) {
            Toast.makeText(this, "That is not a IOKey backup", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Restore backup")
            .setMessage(
                "This will overwrite your current settings with " +
                    BackupManager.describe(this, payload) + "."
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("Restore") { _, _ ->
                lifecycleScope.launch {
                    BackupManager.restorePrefs(prefs, payload.prefs)
                    withContext(Dispatchers.IO) {
                        try {
                            contextEngine.initialize()
                            // The backup preserved per-word counts; import
                            // them (MAX-upsert) instead of flattening
                            // everything to the default promotion strength.
                            contextEngine.importExternalDictionaryWithCounts(payload.words)
                        } catch (_: Throwable) { }
                    }
                    NetGuard.init(prefs)
                    Toast.makeText(this@PrivacyActivity, "Restored", Toast.LENGTH_LONG).show()
                    build()
                }
            }
            .show()
    }

    private fun askPassphrase(title: String, allowEmpty: Boolean, onOk: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = if (allowEmpty) "Leave blank for no encryption" else "Passphrase"
        }
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.done) { _, _ ->
                val v = input.text.toString()
                if (!allowEmpty && v.isEmpty()) {
                    Toast.makeText(this, "A passphrase is required", Toast.LENGTH_SHORT).show()
                } else onOk(v)
            }
            .show()
    }

    // ----------------------------------------------------------- contacts

    private fun requestContacts() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) importContacts() else reqContacts.launch(android.Manifest.permission.READ_CONTACTS)
    }

    private fun importContacts() {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                val names = ContactNames.read(this@PrivacyActivity)
                if (names.isEmpty()) return@withContext 0
                contextEngine.initialize()
                contextEngine.importExternalDictionary(names)
            }
            prefs.contactsSyncedAt = System.currentTimeMillis()
            Toast.makeText(this@PrivacyActivity, "Added $added names", Toast.LENGTH_LONG).show()
            build()
        }
    }

    // ------------------------------------------------------------ per-app

    private fun labelFor(pkg: String): String = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Throwable) { pkg }

    private fun pickApp() {
        val apps = try {
            packageManager.getInstalledApplications(0)
                .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
        } catch (_: Throwable) { emptyList() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "No apps found", Toast.LENGTH_SHORT).show()
            return
        }
        val names = apps.map { packageManager.getApplicationLabel(it).toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose an app")
            .setItems(names) { _, i -> editAppRules(apps[i].packageName) }
            .show()
    }

    private fun editAppRules(pkg: String) {
        val labels = arrayOf("Never learn my words", "Never read the screen", "No AI here", "Save nothing at all")
        val checked = booleanArrayOf(
            pkg in prefs.noLearnApps,
            pkg in prefs.noScreenApps,
            pkg in prefs.noAiApps,
            prefs.isAppBlocked(pkg)
        )
        AlertDialog.Builder(this)
            .setTitle(labelFor(pkg))
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.done) { _, _ ->
                prefs.setNoLearn(pkg, checked[0])
                prefs.setNoScreen(pkg, checked[1])
                prefs.setNoAi(pkg, checked[2])
                prefs.setAppBlocked(pkg, checked[3])
                build()
            }
            .show()
    }

    private fun showCrashLog(f: java.io.File) {
        val text = try { f.readText().takeLast(8000) } catch (_: Throwable) { "(unreadable)" }
        val body = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            val p = dp(16)
            setPadding(p, p, p, p)
        }
        AlertDialog.Builder(this)
            .setTitle("Crash log")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(R.string.done, null)
            .setNeutralButton("Share") { _, _ ->
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                startActivity(android.content.Intent.createChooser(send, "Share crash log"))
            }
            .show()
    }

    // ------------------------------------------------------------ widgets

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String) {
        root.addView(TextView(this).apply {
            setText(text)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@PrivacyActivity, R.color.app_text))
            setPadding(0, dp(20), 0, dp(4))
        })
    }

    private fun hint(text: String) {
        root.addView(TextView(this).apply {
            setText(text)
            textSize = 12f
            alpha = 0.7f
            setTextColor(ContextCompat.getColor(this@PrivacyActivity, R.color.app_text))
            setPadding(0, dp(2), 0, dp(6))
        })
    }

    private fun mono(text: String) {
        root.addView(TextView(this).apply {
            setText(text)
            textSize = 11f
            alpha = 0.85f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(ContextCompat.getColor(this@PrivacyActivity, R.color.app_text))
            setPadding(0, dp(4), 0, dp(8))
        })
    }

    private fun switch(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        root.addView(MaterialSwitch(this).apply {
            text = label
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        hint(hint)
    }

    private fun button(label: String, onClick: () -> Unit) {
        root.addView(MaterialButton(this).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })
    }

    @Suppress("unused")
    private fun spacer(h: Int) {
        root.addView(View(this), ViewGroup.LayoutParams.MATCH_PARENT, dp(h))
    }
}
