package com.localboard.keyboard.ime

import android.content.Intent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.settings.SettingsActivity
import com.localboard.keyboard.ui.KeyboardLayouts
import com.localboard.keyboard.ui.KeyboardModel
import com.localboard.keyboard.ui.KeyboardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The key grid: which page is up, how it is drawn, and its background.
 *
 * Fields only this file needs belong here. The service holds one instance, so
 * adding a field leaves it untouched.
 */
internal class LayoutState {
    val notice = NoticeState()
}

internal fun LLMKeyboardService.onShowApplyKeyboardPrefs(info: EditorInfo, restarting: Boolean) {
    // Re-apply prefs each time the keyboard is shown (settings may have changed).
    theme = KeyboardTheme.loadCached(this, prefs, info.packageName ?: "").also { t ->
        keyboardView.setTheme(t)
        predictionBar.setTheme(t)
    }
    keyboardView.swipeEnabled = prefs.swipeEnabled
    keyboardView.hapticEnabled = prefs.haptic
    keyboardView.soundEnabled = prefs.sound
    keyboardView.keyFeedback.vibLevel = prefs.vibLevel
    keyboardView.keyFeedback.soundPack = prefs.soundPack
    keyboardView.keyFeedback.soundVolume = prefs.soundVolume
    keyboardView.keyFeedback.coalesce = prefs.coalesceHaptics
    keyboardView.outlineKeys = prefs.outlineKeys
}

internal fun LLMKeyboardService.onShowApplyKeyMetrics(info: EditorInfo, restarting: Boolean) {
    keyboardView.animLevel = prefs.animLevel
    keyboardView.pressEffect = prefs.pressEffect
    keyboardView.previewEnabled = prefs.popupPreview
    keyboardView.maxBoardHeightPx = boardHeightCap()
    keyboardView.setMetrics(prefs.heightScale, prefs.keyTextScale)
    keyboardView.setKeySpacing(prefs.keySpacing)
    keyboardView.setOneHanded(prefs.oneHandedMode, prefs.oneHandedWidth)
}

/**
 * The tallest board that leaves room for everything else in the window: the whole screen less
 * its top inset, the bar, the quick row if it is on and the strip below the keys.
 *
 * Measured frames are no use here. A wrap-content root is measured against the display and then
 * the frame, so a board that listened would move its keys and could leave the window short. The
 * bar and the strip are read as fixed reservations, not as they are this moment, because the bar
 * is gone with emoji up and the strip can grow after this hook: then every show gets the same
 * answer, and the keys and the glide table have no second layout to follow.
 */
private fun LLMKeyboardService.boardHeightCap(): Int = try {
    val window = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics
    val topInset = window.windowInsets.getInsetsIgnoringVisibility(
        android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()
    ).top
    val density = resources.displayMetrics.density
    val quickRow = if (prefs.emojiRow) (density * 44).toInt() else 0
    val strip = maxOf(navStrip.layoutParams?.height ?: 0, (density * 48).toInt())
    window.bounds.height() - topInset -
        resources.getDimensionPixelSize(com.localboard.keyboard.R.dimen.prediction_bar_height) - quickRow - strip
} catch (_: Throwable) {
    Int.MAX_VALUE
}

internal fun LLMKeyboardService.onShowApplyGesturePrefs(info: EditorInfo, restarting: Boolean) {
    keyboardView.setSwipeActivationScale(prefs.swipeSensitivity)
    keyboardView.setTrailEnabled(prefs.swipeTrail)
    keyboardView.setLongPressMs(prefs.longPressMs)
}

internal fun LLMKeyboardService.onShowApplyKeyboardModel(info: EditorInfo, restarting: Boolean) {
    // Note: the applied-layout identity survives on the KeyboardView —
    // re-focusing a field with the same layout must NOT re-attach and
    // re-lay-out the keyboard (pure churn). Prefs that change the layout
    // are part of the key itself. It does NOT survive the view being
    // recreated, which is the point of storing it there.
    applyKeyboardModel()
    // A glide decodes nothing where predictions are off, and in a password field a long drag
    // would then type nothing and show nothing among the dots. So there it is simply a tap.
    keyboardView.swipeEnabled = prefs.swipeEnabled && predictionsEnabled
    keyboardView.glideBlockedHere = prefs.swipeEnabled && !predictionsEnabled
}

/**
 * Push the animated-background settings to the view.
 *
 * Single entry point on purpose: this was set in onStartInputView and then
 * set AGAIN by applyKeyboardBackground a few lines later, which silently
 * discarded the low-power override.
 */
internal fun LLMKeyboardService.applyLiveBackground() {
    if (!keyboardViewInitialized) return
    // Decoration is the first thing to go when the phone is low or hot.
    val lowPower = prefs.powerAwareAi &&
        com.localboard.keyboard.util.PowerGate.isLowPower(this, prefs)
    // Animated backgrounds are Pro. Free keeps the flat theme colour —
    // which is also, conveniently, the cheapest thing to draw.
    val bgAllowed = com.localboard.keyboard.billing.Pro.has(
        com.localboard.keyboard.billing.ProFeature.ANIMATED_BACKGROUND
    )
    keyboardView.setLiveBackground(
        if (lowPower || !bgAllowed) "none" else prefs.liveBgStyle,
        prefs.liveBgFps, prefs.liveBgWhileTyping, prefs.liveBgIntensity
    )
    applyNavStrip(lowPower, bgAllowed)
}

internal fun LLMKeyboardService.applyKeyboardBackground() {
    val path = prefs.wallpaperPath
    if (path.isNotEmpty()) {
        val f = java.io.File(path)
        if (f.exists()) {
            val key = "$path@${f.lastModified()}"
            if (key == wallpaperCacheKey && wallpaperCacheBmp != null) {
                keyboardView.setWallpaper(wallpaperCacheBmp, prefs.wallpaperDim)
                return
            }
            // Cold cache (first open after force-stop): decode OFF the
            // main thread — a multi-megabyte photo decode used to block
            // the keyboard's first appearance for hundreds of ms.
            val dim = prefs.wallpaperDim
            serviceScope.launch(Dispatchers.IO) {
                val bmp = decodeSampled(f, 1080)
                wallpaperCacheKey = if (bmp != null) key else null
                wallpaperCacheBmp = bmp
                withContext(Dispatchers.Main) {
                    if (!keyboardViewInitialized) return@withContext
                    if (bmp != null) {
                        keyboardView.setWallpaper(bmp, dim)
                    } else {
                        // Missing/corrupt file: clear the pref so we stop trying.
                        prefs.wallpaperPath = ""
                        keyboardView.setWallpaper(null, prefs.wallpaperDim)
                        applyLiveBackground()
                    }
                }
            }
            return
        }
        // Missing/corrupt file: clear the pref so we stop trying.
        prefs.wallpaperPath = ""
        wallpaperCacheKey = null
        wallpaperCacheBmp = null
    }
    keyboardView.setWallpaper(null, prefs.wallpaperDim)
    applyLiveBackground()
}

internal fun LLMKeyboardService.decodeSampled(f: java.io.File, maxDim: Int): android.graphics.Bitmap? {
    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim &&
            bounds.outHeight / (sample * 2) >= maxDim
        ) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
    } catch (t: Throwable) {
        null
    }
}

internal fun LLMKeyboardService.applyKeyboardModel() {
    attachNoticeView()
    // The layout options are part of the cache identity: without them a
    // bottom-row or symbols-density change in Settings would not reach a
    // keyboard that is already attached with the same page.
    val opts = com.localboard.keyboard.ui.LayoutOptions.from(prefs)
    val key = when {
        showingSelection -> "sel"
        // The number pad's shape depends on the layout option too (the
        // Gboard variant), so it cannot keep the bare "num" identity.
        numberMode -> "num:${opts.cacheKey()}"
        symbolsPage == 1 -> "s1:${opts.cacheKey()}"
        symbolsPage == 2 -> "s2:${opts.cacheKey()}"
        else -> "q:${opts.cacheKey()}"
    }
    if (showingEmoji) {
        keyboardView.appliedModelKey = null
        showEmojiPanel()
        return
    }
    // The cache identity is read from the VIEW, not from a field here: the
    // service survives configuration changes and its input view does not,
    // so anything remembered here would describe a view that is gone. A
    // freshly created KeyboardView has no applied key and is always
    // configured. See KeyboardView.appliedModelKey.
    if (keyboardView.parent === container && key == keyboardView.appliedModelKey) return
    keyboardView.appliedModelKey = key
    val model: KeyboardModel = when {
        showingSelection -> KeyboardLayouts.selectionPad()
        numberMode -> KeyboardLayouts.numberPad(opts)
        symbolsPage == 1 -> KeyboardLayouts.symbols1(opts)
        symbolsPage == 2 -> KeyboardLayouts.symbols2(opts)
        else -> KeyboardLayouts.qwerty(opts)
    }
    // Autocorrect's fat-finger model must describe the keys actually on
    // screen, not the QWERTY it was born with.
    com.localboard.keyboard.engine.FastEngine.setKeyboardRows(
        KeyboardLayouts.letterRows(opts.layoutId)
    )
    // Coming back from emoji: the suggestion bar returns with the keys.
    if (predictionBarInitialized) predictionBar.visibility = View.VISIBLE
    // Only the way back from the emoji panel and the first show need the add. Taking the
    // view out on every page change cancels the touch under it, and whatever a second
    // finger had pressed by then never hears its lift.
    if (keyboardView.parent !== container) {
        container.removeAllViews()
        container.addView(keyboardView)
    }
    keyboardView.setKeyboard(model)
}

// ------------------------------------------------------------------
// Settings shortcut
// ------------------------------------------------------------------

internal fun LLMKeyboardService.openSettings() {
    val intent = Intent(this, SettingsActivity::class.java)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
