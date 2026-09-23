package com.localboard.keyboard.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.localboard.keyboard.R
import com.localboard.keyboard.billing.Pro
import com.localboard.keyboard.billing.ProFeature

/**
 * The upgrade screen.
 *
 * Written to be honest rather than pushy. It leads with what stays free,
 * because for this app that is the product's actual promise and burying it
 * would be the wrong trade for a couple of dollars. No countdown, no fake
 * discount, no nagging — it is reachable from Settings and it appears when
 * you tap something you do not own yet.
 */
class ProActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "IOKey Pro"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20)
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

        val requested = intent?.getStringExtra(Pro.EXTRA_FEATURE)
            ?.let { name -> runCatching { ProFeature.valueOf(name) }.getOrNull() }

        if (Pro.isUnlocked) {
            title("Pro is active")
            body("Thanks — everything below is unlocked on this device.")
        } else if (requested != null) {
            title(requested.label + " is part of Pro")
            body(requested.blurb)
        } else {
            title("IOKey Pro")
            body("One purchase, on this account, forever. No subscription.")
        }

        // ── What you are NOT paying for ──────────────────────────────────
        header("Free, and staying free")
        body(
            "Everything that protects you is free and always will be — it would " +
                "be a strange privacy keyboard that charged for privacy:"
        )
        for (line in listOf(
            "Typing, autocorrect and glide typing",
            "Offline voice typing",
            "Every keyboard layout and key customisation",
            "The network kill switch and the log of anything sent",
            "Incognito support and per-app privacy rules",
            "Encrypted storage, backup and export",
            "Larger keys, one-handed mode, the readable font"
        )) bullet(line, free = true)

        // ── What Pro adds ────────────────────────────────────────────────
        header("What Pro adds")
        for (f in ProFeature.entries) {
            bullet("${f.label} — ${f.blurb}", free = false)
        }

        // ── Action ───────────────────────────────────────────────────────
        if (Pro.isUnlocked) {
            body("\nPro is unlocked on this device.")
            return
        }

        val price = Pro.priceLabel
        val buy = MaterialButton(this).apply {
            text = if (price != null) "Unlock Pro — $price" else "Unlock Pro"
            isEnabled = price != null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            setOnClickListener {
                isEnabled = false
                Pro.purchase(this@ProActivity) { ok ->
                    runOnUiThread {
                        isEnabled = true
                        if (ok) {
                            Toast.makeText(
                                this@ProActivity, "Pro unlocked — thank you", Toast.LENGTH_LONG
                            ).show()
                            build()
                        }
                    }
                }
            }
        }
        root.addView(buy)

        if (price == null) {
            hint("Waiting for the store to respond. If this does not clear, check your connection and reopen this screen.")
        }

        root.addView(MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Restore a previous purchase"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                Pro.restore { ok ->
                    runOnUiThread {
                        Toast.makeText(
                            this@ProActivity,
                            if (ok) "Pro restored" else "No previous purchase found on this account",
                            Toast.LENGTH_LONG
                        ).show()
                        if (ok) build()
                    }
                }
            }
        })

        hint(
            "Bought on another device? Restore works on any device signed in to " +
                "the same Google account. The unlock is checked once and then " +
                "remembered — the keyboard never needs a network to keep working."
        )
    }

    // ── widgets ──────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(text: String) {
        root.addView(TextView(this).apply {
            setText(text)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@ProActivity, R.color.app_text))
            setPadding(0, 0, 0, dp(6))
        })
    }

    private fun header(text: String) {
        root.addView(TextView(this).apply {
            setText(text)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@ProActivity, R.color.app_text))
            setPadding(0, dp(22), 0, dp(6))
        })
    }

    private fun body(text: String) {
        root.addView(TextView(this).apply {
            setText(text)
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@ProActivity, R.color.app_text))
            setPadding(0, 0, 0, dp(4))
        })
    }

    private fun hint(text: String) {
        root.addView(TextView(this).apply {
            setText(text)
            textSize = 12f
            alpha = 0.7f
            setTextColor(ContextCompat.getColor(this@ProActivity, R.color.app_text))
            setPadding(0, dp(10), 0, 0)
        })
    }

    private fun bullet(text: String, free: Boolean) {
        root.addView(TextView(this).apply {
            setText((if (free) "✓  " else "★  ") + text)
            textSize = 14f
            alpha = if (free) 0.85f else 1f
            setTextColor(ContextCompat.getColor(this@ProActivity, R.color.app_text))
            setPadding(dp(4), dp(3), 0, dp(3))
        })
    }
}
