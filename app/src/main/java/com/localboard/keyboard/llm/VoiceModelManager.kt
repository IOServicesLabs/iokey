package com.localboard.keyboard.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the offline voice-typing model (whisper tiny.en, ~75 MB) in
 * private storage: presence checks, download with progress, delete.
 * Nothing about the audio or the transcript ever leaves the device — the
 * network is touched exactly once, to fetch the model file.
 */
class VoiceModelManager(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    val modelFile: File
        get() = File(dir, MODEL_NAME)

    private val partFile: File
        get() = File(dir, "$MODEL_NAME.part")

    val isReady: Boolean
        get() = modelFile.exists() && modelFile.length() > MIN_BYTES

    val sizeBytes: Long
        get() = if (modelFile.exists()) modelFile.length() else 0L

    @Volatile
    var downloading = false
        private set

    @Volatile
    private var cancelFlag = false

    fun cancelDownload() {
        cancelFlag = true
    }

    fun deleteModel() {
        cancelFlag = true
        modelFile.delete()
        partFile.delete()
    }

    /** Streams the model to a .part file, then renames into place.
     *  onProgress receives 0..100; onDone receives success + error text. */
    suspend fun download(
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (downloading) return@withContext
        downloading = true
        cancelFlag = false
        var conn: HttpURLConnection? = null
        try {
            com.localboard.keyboard.util.NetGuard.require(MODEL_URL)
            conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                withContext(Dispatchers.Main) { onDone(false, "HTTP ${conn.responseCode}") }
                return@withContext
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: EXPECTED_BYTES
            partFile.delete()
            conn.inputStream.use { input ->
                partFile.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    var lastPct = -1
                    while (true) {
                        if (cancelFlag) {
                            partFile.delete()
                            withContext(Dispatchers.Main) { onDone(false, "cancelled") }
                            return@withContext
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val pct = ((got * 100) / total).toInt().coerceIn(0, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
            }
            if (partFile.length() < MIN_BYTES) {
                partFile.delete()
                withContext(Dispatchers.Main) { onDone(false, "incomplete download") }
                return@withContext
            }
            // Integrity gate: this blob is executed by native whisper code.
            // SHA256 is whisper.cpp's published LFS object id for the file.
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            partFile.inputStream().use { ins ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    if (cancelFlag) {
                        partFile.delete()
                        withContext(Dispatchers.Main) { onDone(false, "cancelled") }
                        return@withContext
                    }
                    val n = ins.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val hex = digest.digest()
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            if (!hex.equals(SHA256, ignoreCase = true)) {
                partFile.delete()
                withContext(Dispatchers.Main) { onDone(false, "checksum mismatch — file rejected") }
                return@withContext
            }
            modelFile.delete()
            partFile.renameTo(modelFile)
            withContext(Dispatchers.Main) { onDone(modelFile.exists(), "") }
        } catch (t: Throwable) {
            partFile.delete()
            val msg = t.message ?: "download failed"
            withContext(Dispatchers.Main) { onDone(false, msg) }
        } finally {
            conn?.disconnect()
            downloading = false
        }
    }

    companion object {
        private const val MODEL_NAME = "ggml-tiny.en.bin"
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
        private const val EXPECTED_BYTES = 77_000_000L
        private const val MIN_BYTES = 70_000_000L
        /** whisper.cpp's published LFS oid for ggml-tiny.en.bin — an LFS oid
         *  is the file's sha256, so this is checkable against the model page. */
        private const val SHA256 =
            "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f"
    }
}
