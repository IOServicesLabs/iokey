package com.localboard.keyboard.ime

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.localboard.keyboard.llm.VoiceBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Offline dictation pipeline: AudioRecord captures 16 kHz mono PCM while the
 * user talks; on stop, whisper.cpp transcribes on a background thread and
 * the text lands in the field. Nothing leaves the device, ever.
 */
class VoiceInputManager(
    private val scope: CoroutineScope,
    private val bridge: VoiceBridge,
    private val modelPath: () -> String,
    private val onState: (State) -> Unit,
    private val onResult: (String) -> Unit
) {
    enum class State { RECORDING, TRANSCRIBING, IDLE }

    @Volatile
    private var recording = false

    @Volatile
    private var cancelled = false

    private var recordThread: Thread? = null
    private var samples = ShortArray(INITIAL_CAPACITY)
    private var sampleCount = 0

    val isActive: Boolean
        get() = recording

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO first
    fun start() {
        if (recording) return
        cancelled = false
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                (minBuf * 2).coerceAtLeast(8192)
            )
        } catch (t: Throwable) {
            null
        } ?: run {
            onState(State.IDLE)
            onResult("")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onState(State.IDLE)
            onResult("")
            return
        }
        // A previous recordThread's tail may still be copying into `samples`
        // under the lock — wait for it (recording is already false, so it
        // exits its loop within one read), then reset the buffer INSIDE the
        // same lock so no copy can interleave with the reset.
        try { recordThread?.join(500) } catch (_: InterruptedException) { }
        recording = true
        synchronized(this) {
            samples = ShortArray(INITIAL_CAPACITY)
            sampleCount = 0
        }
        onState(State.RECORDING)
        val chunk = ShortArray(2048)
        recordThread = Thread {
            try {
                rec.startRecording()
                while (recording) {
                    val n = rec.read(chunk, 0, chunk.size)
                    // Negative = persistent AudioRecord error
                    // (ERROR_INVALID_OPERATION etc.): looping here is a 100%
                    // CPU busy-spin. Zero = no data: breathe, don't spin.
                    if (n < 0) break
                    if (n == 0) {
                        // Interrupts land in the outer catch and end the thread.
                        Thread.sleep(10)
                        continue
                    }
                    var full = false
                    synchronized(this) {
                        if (sampleCount + n > samples.size) {
                            // Cap at MAX_SECONDS — plenty for a message.
                            if (sampleCount >= SAMPLE_RATE * MAX_SECONDS) {
                                full = true
                            } else {
                                val grown = ShortArray(samples.size * 2)
                                System.arraycopy(samples, 0, grown, 0, sampleCount)
                                samples = grown
                            }
                        }
                        if (!full) {
                            System.arraycopy(chunk, 0, samples, sampleCount, n)
                            sampleCount += n
                        }
                    }
                    if (full) recording = false
                }
            } catch (_: Throwable) {
            } finally {
                try { rec.stop() } catch (_: Throwable) { }
                rec.release()
            }
            val count = synchronized(this) { sampleCount }
            if (cancelled || count < SAMPLE_RATE / 3) {
                // Too short to be speech (or the user bailed).
                onState(State.IDLE)
                return@Thread
            }
            onState(State.TRANSCRIBING)
            val pcm = ShortArray(count)
            synchronized(this) { System.arraycopy(samples, 0, pcm, 0, count) }
            scope.launch(Dispatchers.Default) {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                // Model load + transcription are multi-second CPU bursts —
                // background priority, same rule as the LLM lanes, so they
                // never compete with typing.
                try {
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND
                    )
                    val ok = bridge.ensureModelLoaded(modelPath(), threads)
                    val text = if (ok) bridge.transcribe(pcm, pcm.size, threads) else ""
                    onState(State.IDLE)
                    onResult(text)
                } finally {
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_DEFAULT
                    )
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Stop and transcribe what was said. */
    fun stop() {
        recording = false
    }

    /** Stop and throw the audio away. */
    fun cancel() {
        cancelled = true
        recording = false
    }

    private companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MAX_SECONDS = 90
        private const val INITIAL_CAPACITY = SAMPLE_RATE * 30
    }
}
