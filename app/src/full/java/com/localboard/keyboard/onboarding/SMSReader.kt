package com.localboard.keyboard.onboarding

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot, fully local read of the user's *sent* SMS messages, used only
 * when they explicitly choose SMS bootstrapping in onboarding. Requires
 * READ_SMS (requested at runtime). Message text never leaves the device.
 */
object SMSReader {

    /** This build ships SMS style import. The Play build does not. */
    const val AVAILABLE = true

    fun hasPermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    suspend fun readSentMessages(context: Context, limit: Int = 3000): List<String> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<String>(limit.coerceAtMost(500))
            try {
                val cursor = context.contentResolver.query(
                    Uri.parse("content://sms/sent"),
                    arrayOf("body"),
                    null,
                    null,
                    "date DESC"
                )
                cursor?.use {
                    val bodyIdx = it.getColumnIndex("body")
                    while (it.moveToNext() && out.size < limit) {
                        val body = it.getString(bodyIdx)?.trim()
                        if (!body.isNullOrEmpty() && body.length >= 8) {
                            out += body
                        }
                    }
                }
            } catch (e: SecurityException) {
                return@withContext emptyList()
            }
            out
        }
}
