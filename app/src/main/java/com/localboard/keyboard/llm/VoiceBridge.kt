package com.localboard.keyboard.llm

/**
 * Thin JNI bridge to the statically-linked whisper.cpp voice engine
 * (liblocalboard_voice.so). Entirely offline: 16 kHz mono PCM in, text out.
 *
 * The library may be absent from a given build — [available] is false then
 * and every voice UI hides itself, so the feature degrades to invisible
 * rather than broken.
 */
object VoiceBridge {

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var modelLoaded = false

    @Volatile
    private var loadedPath: String? = null

    val available: Boolean
        get() = ensureLibraryLoaded()

    @Synchronized
    fun ensureLibraryLoaded(): Boolean {
        if (libraryLoaded) return true
        return try {
            System.loadLibrary("localboard_voice")
            libraryLoaded = true
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Load the whisper model (idempotent; reloads only if the path changed). */
    @Synchronized
    fun ensureModelLoaded(path: String, threads: Int): Boolean {
        if (!ensureLibraryLoaded()) return false
        if (modelLoaded && loadedPath == path) return true
        if (modelLoaded) {
            try { nativeFreeModel() } catch (_: Throwable) { }
            modelLoaded = false
            loadedPath = null
        }
        val ok = try { nativeLoadModel(path, threads) } catch (_: Throwable) { false }
        if (ok) {
            modelLoaded = true
            loadedPath = path
        }
        return ok
    }

    @Synchronized
    fun transcribe(pcm: ShortArray, samples: Int, threads: Int): String {
        if (!modelLoaded) return ""
        return try {
            nativeTranscribe(pcm, samples, threads)?.trim().orEmpty()
        } catch (t: Throwable) {
            ""
        }
    }

    @Synchronized
    fun freeModel() {
        if (!libraryLoaded || !modelLoaded) return
        try { nativeFreeModel() } catch (_: Throwable) { }
        modelLoaded = false
        loadedPath = null
    }

    private external fun nativeLoadModel(path: String, threads: Int): Boolean
    private external fun nativeTranscribe(pcm: ShortArray, samples: Int, threads: Int): String?
    private external fun nativeFreeModel()
}
