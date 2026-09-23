package com.localboard.keyboard.llm

import android.content.Context

/**
 * Backwards-compatible facade over ModelRegistry for the default model
 * (Qwen3-1.7B since 1.6.1 — the 0.6B loops/echoes on real conversations;
 * verified offline against the scripted chat test). New code should use
 * ModelRegistry directly — it handles every catalog model and the user's
 * own imports.
 *
 * Downloads remain the ONLY network traffic in the entire app, and happen
 * solely when the user explicitly taps "Download". Typing data is never
 * transmitted.
 */
object ModelManager {

    const val QWEN3_FILE = "Qwen_Qwen3-0.6B-Q4_K_M.gguf"
    const val QWEN3_DISPLAY_SIZE = "~400 MB"

    private val defaultSpec: ModelSpec
        get() = ModelRegistry.CATALOG.first { it.id == "qwen3_17b" }

    enum class Status { NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

    val status: Status
        get() = when (state0()?.status) {
            ModelRegistry.Status.DOWNLOADING -> Status.DOWNLOADING
            ModelRegistry.Status.READY -> Status.READY
            ModelRegistry.Status.ERROR -> Status.ERROR
            else -> Status.NOT_DOWNLOADED
        }

    val progress: Int get() = state0()?.progress ?: 0
    val downloadedBytes: Long get() = state0()?.downloadedBytes ?: 0L
    val totalBytes: Long get() = state0()?.totalBytes ?: 0L
    val errorMessage: String? get() = state0()?.error

    @Volatile
    private var appContext: Context? = null

    private fun state0(): ModelRegistry.DownloadState? =
        appContext?.let { ModelRegistry.state(it, defaultSpec) }

    fun qwen3ModelFile(context: Context): java.io.File =
        ModelRegistry.modelFile(context, defaultSpec)

    fun isDownloaded(context: Context): Boolean = ModelRegistry.isReady(context, defaultSpec)

    fun refreshStatus(context: Context): Status {
        appContext = context.applicationContext
        return status
    }

    fun startDownload(context: Context) {
        appContext = context.applicationContext
        ModelRegistry.startDownload(context, defaultSpec)
    }

    fun cancelDownload() = ModelRegistry.cancelDownload(defaultSpec)

    fun delete(context: Context) {
        appContext = context.applicationContext
        ModelRegistry.cancelDownload(defaultSpec)
        qwen3ModelFile(context).delete()
        java.io.File(context.filesDir, "models/$QWEN3_FILE.part").delete()
        ModelRegistry.invalidateReady(defaultSpec.id)
    }
}
