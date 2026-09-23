package com.localboard.keyboard.settings

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.localboard.keyboard.context.ContextEngine
import com.localboard.keyboard.context.StyleProfile
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.llm.LlamaCppBridge
import com.localboard.keyboard.llm.ModelManager
import com.localboard.keyboard.onboarding.SMSReader
import com.localboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "My writing style": shows the distilled style the keyboard has learned,
 * lets the user edit the style instructions handed to the AI composer,
 * and browse/delete individual learned dictionary words.
 */
class StyleActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val contextEngine by lazy {
        ContextEngine(applicationContext, FastEngine.shared(applicationContext))
    }

    private var distillDialog: AlertDialog? = null
    private var pendingDistill = false

    private lateinit var summaryView: TextView
    private lateinit var styleInput: EditText
    private lateinit var dictContainer: LinearLayout
    private lateinit var loading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "My writing style"

        // This screen lists the words the keyboard has learned from private
        // messages. If the user asked for it to sit behind the lock screen,
        // show nothing until they authenticate — rendering first and asking
        // after would defeat the point.
        //
        // The "already unlocked" state is a transient flag, never a pref: the
        // preference is the user's standing choice and must survive a crash
        // mid-unlock, so it is never written to as a side effect of showing
        // this screen.
        if (prefs.biometricLock && !unlockedThisSession) {
            setContentView(android.view.View(this))
            com.localboard.keyboard.util.Unlock.require(this, "My writing style", true) {
                unlockedThisSession = true
                recreate()
            }
            return
        }
        unlockedThisSession = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        summaryView = TextView(this).apply { textSize = 15f }
        root.addView(summaryView)

        root.addView(header("AI style instructions"))
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "This text is handed to the on-device AI every time you tap ✨. " +
                "Edit it to steer how it writes as you — tone, emoji, quirks. " +
                "Leave it empty to use the automatically distilled style."
        })
        styleInput = EditText(this).apply {
            minLines = 4
            textSize = 14f
            setHint("e.g. casual, lowercase, short sentences, lots of 😂, never formal")
        }
        root.addView(styleInput)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                prefs.customStylePrompt = styleInput.text.toString().trim()
                Toast.makeText(this@StyleActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        })
        btnRow.addView(Button(this).apply {
            text = "Reset to auto"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                prefs.customStylePrompt = ""
                styleInput.setText(autoStyleBlock(contextEngine.profile))
                Toast.makeText(this@StyleActivity, "Using auto-distilled style", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(btnRow)

        if (SMSReader.AVAILABLE) {
            root.addView(Button(this).apply {
                text = "✨ Distill my style from SMS (AI)"
                setOnClickListener { startDistillFlow() }
            })
        }
        root.addView(TextView(this).apply {
            textSize = 13f
            val model = com.localboard.keyboard.llm.ModelRegistry.activeName(
                com.localboard.keyboard.util.Prefs(this@StyleActivity)
            )
            text = "Reads your sent texts entirely on-device and lets $model write your " +
                "style profile for you — the result lands in the box above, fully editable."
        })

        root.addView(header("Learned dictionary"))
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "Words the keyboard learned from your typing. Tap ✕ to forget one."
        })
        loading = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(loading)
        dictContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(dictContainer)

        lifecycleScope.launch {
            contextEngine.initialize()
            renderSummary()
            renderDict()
        }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun renderSummary() {
        val p = contextEngine.profile
        summaryView.text = if (p == null || p.sentencesAnalyzed < 10) {
            "Still learning your style — type more (or import samples from onboarding) " +
                "and this fills in. Sentences analyzed so far: ${p?.sentencesAnalyzed ?: 0}."
        } else {
            buildString {
                append("From ${p.sentencesAnalyzed} of your sentences, you write:\n\n")
                append("• Tone: ${if (p.formality >= 0.5) "fairly formal" else "casual"} (formality ${(p.formality * 100).toInt()}%)\n")
                append("• Length: ~${p.avgSentenceLength.toInt()} words per sentence\n")
                append("• Emoji: ${if (p.emojiPerSentence >= 0.25) "often" else "rarely"}")
                if (p.topEmojis.isNotEmpty()) append(" ${p.topEmojis.take(5).joinToString(" ")}")
                append("\n")
                append("• Capitalization: ${if (p.capitalizationStyle == 1) "mostly lowercase" else "standard"}\n")
                append("• Contractions: ${if (p.usesContractions) "yes" else "rarely"}\n")
                if (p.topWords.isNotEmpty())
                    append("• Favorite words: ${p.topWords.take(10).joinToString { it.first }}\n")
            }
        }
        // Prefill instructions: user's saved text, else the auto block.
        // A saved block that failed a past distillation (non-printable soup)
        // is wiped so it can't poison generation anymore.
        val saved = prefs.customStylePrompt
        if (saved.isNotBlank()) {
            val plain = saved.count { it in ' '..'~' || it == '\n' }
            if (plain.toFloat() / saved.length < 0.9f) prefs.customStylePrompt = ""
        }
        styleInput.setText(
            prefs.customStylePrompt.ifBlank { autoStyleBlock(p) }
        )
    }

    private fun renderDict() {
        lifecycleScope.launch {
            val words = contextEngine.topLearnedWords(100)
            loading.visibility = View.GONE
            dictContainer.removeAllViews()
            if (words.isEmpty()) {
                dictContainer.addView(TextView(this@StyleActivity).apply {
                    text = "No personal words learned yet."
                    textSize = 14f
                })
                return@launch
            }
            for (w in words) {
                val row = LinearLayout(this@StyleActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this@StyleActivity).apply {
                    text = "${w.word}  ·  ${w.count}×"
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this@StyleActivity).apply {
                    text = "✕"
                    textSize = 16f
                    setPadding(dp(14), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        lifecycleScope.launch {
                            contextEngine.deleteLearnedWord(w.word)
                            renderDict()
                            Toast.makeText(this@StyleActivity, "Forgot \"${w.word}\"", Toast.LENGTH_SHORT).show()
                        }
                    }
                })
                dictContainer.addView(row)
            }
        }
    }

    // ------------------------------------------------------------------
    // AI distillation from SMS
    // ------------------------------------------------------------------

    private fun startDistillFlow() {
        val prefs = com.localboard.keyboard.util.Prefs(this)
        val spec = com.localboard.keyboard.llm.ModelRegistry.active(prefs)
        if (!com.localboard.keyboard.llm.ModelRegistry.isReady(this, spec)) {
            Toast.makeText(this, "Download the AI model first (main settings)", Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDistill = true
            requestPermissions(arrayOf(Manifest.permission.READ_SMS), REQ_SMS_DISTILL)
            return
        }
        runDistill()
    }

    private fun runDistill() {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Distilling your style")
            .setMessage("Reading your sent messages…")
            .setCancelable(false)
            .create()
        distillDialog = dlg
        dlg.show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // Load the JNI library before ANY external call (calling one
                // first throws UnsatisfiedLinkError), and never let a native
                // error escape into the UI coroutine. Without it, the
                // deterministic fallback profile still gets written.
                val libOk = LlamaCppBridge.ensureLibraryLoaded()
                val msgs = try {
                    SMSReader.readSentMessages(this@StyleActivity, 300)
                } catch (t: Throwable) { return@withContext null }
                if (msgs.size < 8) return@withContext null

                // Build the FULL sample. It used to be capped at ~1400 chars
                // (≈ a dozen texts) — that's why distillations only reflected
                // the first few messages and latched onto whatever talking
                // points happened to be in them.
                val urlRe = Regex(
                    "(https?://|www\\.)\\S+|\\b\\S+?\\.(com|net|org|io|co|app|dev|me|gg|tv|info|biz|ly|link|site)(/\\S*)?\\b",
                    RegexOption.IGNORE_CASE
                )
                val lines = ArrayList<String>(msgs.size)
                for (m in msgs) {
                    val line = urlRe.replace(m.replace('\n', ' '), " ").trim()
                    if (line.length >= 2) lines += line.take(140)
                }
                if (lines.size < 8) return@withContext null

                // Deterministic style engine (the hallucination fix): a tiny
                // model asked to ANALYZE a writing sample does not read it —
                // it answers from its prior and produces the same generic
                // "casual voice… productivity" paragraph no matter what it
                // is shown (proven offline against the exact model). So the
                // sample is MEASURED into hard facts here, and the model's
                // only job is rewriting those facts into one fluent
                // paragraph — a task it does faithfully. Its rewrite is
                // checked against the measurements before it can be saved;
                // anything unverifiable falls back to the raw facts.
                val facts = com.localboard.keyboard.llm.StyleProfiler.analyze(lines)
                withContext(Dispatchers.Main) {
                    dlg.setMessage("Measuring your writing style…")
                }
                val factsText = com.localboard.keyboard.llm.StyleProfiler.factsBlock(facts)

                var modelReady = libOk &&
                    LlamaCppBridge.nativeIsLoaded(LlamaCppBridge.SLOT_DRAFT)
                if (libOk && !modelReady) {
                    val prefs = com.localboard.keyboard.util.Prefs(this@StyleActivity)
                    val spec = com.localboard.keyboard.llm.ModelRegistry.active(prefs)
                    modelReady = try {
                        LlamaCppBridge.nativeLoad(
                            com.localboard.keyboard.llm.ModelRegistry.modelFile(
                                this@StyleActivity, spec
                            ).absolutePath,
                            nThreads = 4, nCtx = spec.nCtx,
                            slot = LlamaCppBridge.SLOT_DRAFT
                        )
                    } catch (t: Throwable) { false }
                }
                if (!modelReady) {
                    // No usable model — the measured profile stands on its own.
                    return@withContext com.localboard.keyboard.llm.StyleProfiler.fallbackProfile(facts)
                }
                val model = com.localboard.keyboard.llm.ModelRegistry.activeName(
                    com.localboard.keyboard.util.Prefs(this@StyleActivity)
                )
                withContext(Dispatchers.Main) {
                    dlg.setMessage("$model is writing your style profile…")
                }

                val provider = com.localboard.keyboard.llm.LlamaCppProvider(this@StyleActivity)
                val rewriteInstr = "Rewrite the notes below into ONE smooth " +
                    "paragraph (70-110 words) that an AI ghostwriter can " +
                    "follow. Keep every fact EXACTLY as stated — do not " +
                    "add, soften, or contradict anything. No bullets, no " +
                    "preamble. Write in present tense."
                fun wordsOf(s: String?) =
                    s?.trim()?.split(Regex("\\s+"))?.count { it.length > 1 } ?: 0
                var rewritten = try {
                    provider.generate(
                        com.localboard.keyboard.llm.GenerateRequest(
                            contextBefore = factsText,
                            styleProfile = null,
                            maxWords = 130,
                            temperature = 0.3f,
                            instruction = rewriteInstr,
                            purpose = com.localboard.keyboard.llm.GenerateRequest.Purpose.PROFILE
                        )
                    ) { }
                } catch (t: Throwable) { null }
                if (wordsOf(rewritten) < 30) {
                    // Tiny models sometimes bail after one sentence — one
                    // warmer retry before falling back to the measured facts.
                    rewritten = try {
                        provider.generate(
                            com.localboard.keyboard.llm.GenerateRequest(
                                contextBefore = factsText,
                                styleProfile = null,
                                maxWords = 130,
                                temperature = 0.55f,
                                instruction = rewriteInstr,
                                purpose = com.localboard.keyboard.llm.GenerateRequest.Purpose.PROFILE
                            )
                        ) { }
                    } catch (t: Throwable) { null }
                }
                if (rewritten.isNullOrBlank() ||
                    !com.localboard.keyboard.llm.StyleProfiler.isConsistent(rewritten, facts)
                )
                    com.localboard.keyboard.llm.StyleProfiler.fallbackProfile(facts)
                else rewritten.trim()
            }

            dlg.dismiss()
            distillDialog = null
            val cleaned = result?.let { cleanDistillOutput(it) }
            if (cleaned.isNullOrBlank() || cleaned.length < 30) {
                Toast.makeText(
                    this@StyleActivity,
                    "Couldn't distill — needs 8+ sent SMS and the downloaded model",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                prefs.customStylePrompt = cleaned
                styleInput.setText(cleaned)
                // Archive it — the profile history keeps every distillation
                // so the user can look back at how their voice evolved.
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val db = com.localboard.keyboard.data.db.AppDatabase.get(this@StyleActivity)
                        db.distillationDao().insert(
                            com.localboard.keyboard.data.db.DistillationEntity(
                                ts = System.currentTimeMillis(),
                                source = "sms",
                                notes = cleaned
                            )
                        )
                        db.distillationDao().prune(90)
                    } catch (_: Throwable) { }
                }
                Toast.makeText(
                    this@StyleActivity,
                    "Style distilled — edit it above anytime",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun cleanDistillOutput(raw: String): String {
        var t = raw.substringAfterLast("</think>").trim()
        t = t.removePrefix("\"").removeSuffix("\"").trim()
        t = t.replace(Regex("\\s*\\n\\s*"), " ").trim()
        t = t.take(600)
        // Reject poisoned output (the multilingual-soup failure): a style
        // paragraph must be mostly plain printable characters.
        if (t.isNotEmpty()) {
            val plain = t.count { it in ' '..'~' }
            if (plain.toFloat() / t.length < 0.9f) return ""
        }
        return t
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SMS_DISTILL) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (granted && pendingDistill) runDistill()
            pendingDistill = false
        }
    }

    override fun onDestroy() {
        distillDialog?.dismiss()
        distillDialog = null
        super.onDestroy()
    }

    companion object {
        private const val REQ_SMS_DISTILL = 42

        /** Set once the user has authenticated for this activity instance.
         *  Transient by design — a fresh launch asks again. */
        @Volatile
        private var unlockedThisSession = false

        /** Same distillation the Qwen provider builds from the profile. */
        fun autoStyleBlock(style: StyleProfile?): String {
            if (style == null || style.sentencesAnalyzed < 10) return ""
            return buildString {
                append(if (style.formality >= 0.5) "fairly formal" else "casual")
                append(", ~${style.avgSentenceLength.toInt()} words per sentence")
                append(if (style.emojiPerSentence >= 0.25) ", emoji often" else ", emoji rarely")
                if (style.capitalizationStyle == 1) append(", lowercase")
                if (style.usesContractions) append(", contractions")
                if (style.topWords.isNotEmpty())
                    append(", favorite words: ${style.topWords.take(6).joinToString { it.first }}")
            }
        }
    }

}
