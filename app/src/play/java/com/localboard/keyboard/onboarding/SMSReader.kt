package com.localboard.keyboard.onboarding

import android.content.Context

/**
 * Play build: no SMS access.
 *
 * The full build can read your *sent* messages once, locally, to learn your
 * writing style during onboarding. `READ_SMS` is a Play "restricted
 * permission": Google grants it for a short published list of use cases —
 * default SMS handler, backup, and a few others — and "import writing style"
 * is not among them. Declaring it would mean a Permissions Declaration Form
 * and, realistically, a rejection.
 *
 * So the Play build ships neither the permission nor the reader. This stub
 * keeps the two call sites compiling; [AVAILABLE] lets the UI drop the option
 * entirely rather than show a button that fails.
 *
 * Onboarding still offers the other two bootstraps — sample texts, and paste
 * your own writing — and the keyboard learns from everything you type anyway.
 */
object SMSReader {

    /** False, so the SMS bootstrap option is never offered in this build. */
    const val AVAILABLE = false

    fun hasPermission(context: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun readSentMessages(context: Context, limit: Int = 3000): List<String> = emptyList()
}
