package com.localboard.keyboard.settings

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.localboard.keyboard.R
import com.localboard.keyboard.context.ContextEngine
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.llm.AiTestLab
import com.localboard.keyboard.llm.LlamaCppProvider
import com.localboard.keyboard.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs the scripted conversation test (AiTestLab) twice — without and with
 *  the style profile — rendering every turn live. This is the exact
 *  production generation path, so the transcripts are faithful: whatever
 *  the model does here, it does in the field. */
class AiTestLabActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }
    private val contextEngine by lazy {
        ContextEngine(applicationContext, FastEngine.shared(applicationContext))
    }
    private val provider by lazy { LlamaCppProvider(applicationContext) }

    private lateinit var body: TextView
    private lateinit var status: TextView
    private lateinit var runBtn: MaterialButton
    private lateinit var scroller: ScrollView
    private val fullReport = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_test_lab)
        body = findViewById(R.id.test_lab_body)
        status = findViewById(R.id.test_lab_status)
        runBtn = findViewById(R.id.btn_test_lab_run)
        scroller = findViewById(R.id.test_lab_scroll)
        runBtn.setOnClickListener { runAb() }
        findViewById<MaterialButton>(R.id.btn_test_lab_copy).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    "IOKey AI test lab", fullReport.toString()
                )
            )
            Toast.makeText(this, "Transcript copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runAb() {
        runBtn.isEnabled = false
        body.text = ""
        fullReport.setLength(0)
        status.text = "Loading model & profile…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { contextEngine.initialize() }
                for (useProfile in listOf(false, true)) {
                    val label = if (useProfile)
                        "═══ PASS 2 — WITH style profile ═══" else
                        "═══ PASS 1 — NO style profile ═══"
                    appendLine("\n$label")
                    appendLine(if (useProfile)
                        "measured habits + distilled notes in the prompt\n"
                    else
                        "conversation only — no style notes at all\n")
                    var turn = 0
                    val text = AiTestLab.runPass(
                        provider, contextEngine, prefs, useProfile
                    ) { t ->
                        turn++
                        status.text = "${if (useProfile) "Pass 2/2" else "Pass 1/2"}" +
                            " · turn $turn/10"
                        appendLine("Alex: ${t.friend}")
                        appendLine("You (AI): ${t.draft}")
                        appendLine("You (script): ${t.scripted}")
                        appendLine("   · ${t.stat}")
                    }
                    fullReport.append(label).append('\n').append(text).append('\n')
                }
                status.text = "Done — compare the two passes, then set " +
                    "\"Use style profile in drafts\" accordingly."
            } catch (t: Throwable) {
                status.text = "Test failed: ${t.javaClass.simpleName} — " +
                    "is a model downloaded? (Settings → AI model)"
            } finally {
                runBtn.isEnabled = true
                runBtn.text = "Run again"
            }
        }
    }

    private fun appendLine(s: String) {
        body.append(s + "\n")
        scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
