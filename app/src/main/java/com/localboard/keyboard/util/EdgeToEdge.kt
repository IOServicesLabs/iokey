package com.localboard.keyboard.util

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.localboard.keyboard.R

/**
 * Edge-to-edge window handling for every settings screen.
 *
 * From API 35 Android stops honouring `android:statusBarColor` and stops
 * fitting content inside the system bars: every activity draws under the
 * status bar and the navigation bar whether it asked to or not. Without
 * insets, the first row of a settings screen sits under the clock and the
 * last button sits under the gesture pill.
 *
 * This is installed once from [Application.registerActivityLifecycleCallbacks]
 * rather than being pasted into each of the dozen activities. Those screens
 * build their views in code and all share one shape — a scrolling column
 * inside `android.R.id.content` — so one listener on the content root covers
 * every one of them, including any added later. An activity that needs
 * different behaviour can still consume the insets itself; this only ever
 * pads the outermost frame.
 *
 * The same path runs on every supported API level, not just 35+, so there is
 * one layout behaviour to test rather than two.
 */
object EdgeToEdge {

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    // Runs after the activity's own onCreate has returned, so
                    // setContentView has already happened and there is a real
                    // content view to attach to.
                    apply(activity)
                }

                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
        )
    }

    private fun apply(activity: Activity) {
        val window = activity.window ?: return
        val content = window.decorView.findViewById<android.view.View>(android.R.id.content)
            ?: return

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Transparent bars are what edge-to-edge means; on API 35+ these are
        // ignored and already transparent, and setting them keeps 31-34
        // looking identical instead of drawing an opaque strip.
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }

        // The system draws its icons in one colour; which one is legible
        // depends on what is behind them. Deriving it from the actual
        // background means the values-night palette is handled without a
        // second code path, and a future palette change cannot leave white
        // icons on a white bar.
        val bg = runCatching {
            androidx.core.content.ContextCompat.getColor(activity, R.color.app_bg)
        }.getOrDefault(Color.BLACK)
        val lightBackground = ColorUtils.calculateLuminance(bg) > 0.5
        WindowCompat.getInsetsController(window, content).apply {
            isAppearanceLightStatusBars = lightBackground
            isAppearanceLightNavigationBars = lightBackground
        }

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            // The IME inset is deliberately NOT consumed here. These screens
            // scroll, and letting the keyboard overlap a scrolling view is the
            // normal Android behaviour; consuming it would fight the resize.
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            // Return the insets unconsumed so any child that cares still sees
            // them — consuming here would silently break a future screen.
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }
}
