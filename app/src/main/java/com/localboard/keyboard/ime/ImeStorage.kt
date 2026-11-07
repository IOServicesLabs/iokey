package com.localboard.keyboard.ime

import android.content.Intent
import com.localboard.keyboard.R
import com.localboard.keyboard.context.EXTRA_SHOW_STORE_NOTE
import com.localboard.keyboard.context.StoreAdvice
import com.localboard.keyboard.settings.SettingsActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** An app restarting its input right after focus takes any notice away, and one gone this fast went unseen. */
private const val STORE_NOTICE_SEEN_MS = 2_000L

/**
 * A store that will not open looks, from the keys, like a keyboard that forgot everything it knew. So
 * this is where it gets another try, and where the person hears of it once it has lasted. In the
 * keyboard's own window, since a toast from here is dropped by the system: see [NoticeState].
 */
internal fun LLMKeyboardService.onShowCheckStore() {
    contextEngine.retryStoreIfDue()
    if (contextEngine.isInitialized && contextEngine.storeAdvice == StoreAdvice.NONE) return
    // The first show of a process comes before its first load has had time to fail.
    serviceScope.launch {
        contextEngine.awaitFirstLoad()
        showStoreNoticeIfDue()
    }
}

internal fun LLMKeyboardService.onHideCheckStore() {
    contextEngine.retryStoreIfDue()
}

private fun LLMKeyboardService.showStoreNoticeIfDue() {
    // Hidden in the meantime, it stays due for the next show.
    if (!isInputViewShown) return
    when (contextEngine.storeNoticeDue() ?: return) {
        StoreAdvice.FREE_SPACE -> showNotice(R.string.keyboard_store_notice_full, long = true)
        else -> showNotice(R.string.keyboard_store_notice) { openStoreSettings() }
    }
    serviceScope.launch {
        delay(STORE_NOTICE_SEEN_MS)
        if (layoutState.notice.showing) contextEngine.storeNoticeShown()
    }
}

/** Settings has no way to be opened at a section, so this opens the one that holds the note and asks it to scroll there. */
private fun LLMKeyboardService.openStoreSettings() {
    contextEngine.storeNoticeShown()
    prefs.expandedSections = prefs.expandedSections + getString(R.string.sec_privacy).trim()
    startActivity(
        Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_SHOW_STORE_NOTE, true)
    )
}
