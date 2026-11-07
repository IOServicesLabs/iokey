package com.localboard.keyboard.settings

import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.localboard.keyboard.R

/**
 * Where the typing test and the grid game used to be. Both wrote what they measured into
 * the drawn layout, so keys moved under fingers that had learned where they were, and every
 * run moved them again. All that is left to say here is that this has stopped.
 */
class CalibrateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.keys_stay_title)

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val text = ContextCompat.getColor(this, R.color.app_text)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        page.addView(TextView(this).apply {
            setText(R.string.keys_stay_title)
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        page.addView(TextView(this).apply {
            setText(R.string.keys_stay_body)
            setTextColor(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(12), 0, dp(20))
        })
        page.addView(MaterialButton(this).apply {
            setText(R.string.done)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(ScrollView(this).apply { addView(page) })
    }
}
