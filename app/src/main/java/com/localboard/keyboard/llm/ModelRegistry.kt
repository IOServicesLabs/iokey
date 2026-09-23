package com.localboard.keyboard.llm

import android.content.Context
import com.localboard.keyboard.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Every chat format a small GGUF might use. Pick the wrong one and the model
 * rambles or echoes; pick the right one and even a 0.3B model behaves.
 */
enum class ChatTemplate(val id: String, val label: String) {
    CHATML("chatml", "ChatML (Qwen, SmolLM)"),
    ZEPHYR("zephyr", "Zephyr (TinyLlama, Mistral)"),
    LLAMA3("llama3", "Llama 3 / 3.1 / 3.2"),
    GEMMA("gemma", "Gemma 2 / 3"),
    PHI3("phi3", "Phi-3 / Phi-3.5"),
    ALPACA("alpaca", "Alpaca (generic)");

    companion object {
        fun fromId(id: String): ChatTemplate = entries.firstOrNull { it.id == id } ?: CHATML

        /** Best guess from a file name, so user imports work out of the box. */
        fun guess(fileName: String): ChatTemplate {
            val n = fileName.lowercase()
            return when {
                "qwen" in n || "smollm" in n -> CHATML
                "tinyllama" in n || "zephyr" in n || "mistral" in n -> ZEPHYR
                "llama-3" in n || "llama3" in n -> LLAMA3
                "gemma" in n -> GEMMA
                "phi-3" in n || "phi3" in n || "phi-4" in n -> PHI3
                else -> CHATML
            }
        }
    }
}

data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String?,      // null = imported from a local file
    val sizeLabel: String,
    val template: ChatTemplate,
    val nCtx: Int = 2048,
    val defaultTemp: Float = 0.65f,
    val builtIn: Boolean = false,
    /** ~350M params and below: needs the stripped-down prompt (no style
     *  analysis to parrot, shorter context, shorter outputs). */
    val tinyModel: Boolean = false,
    /** Expected SHA-256 of the downloaded file, lowercase hex. For the curated
     *  catalog these are HuggingFace's own LFS object ids (an LFS oid IS the
     *  file's sha256), so they can be re-checked against the model page at any
     *  time. A download that does not match is deleted, not loaded: this app
     *  hands the file straight to native code and runs it, so "probably fine"
     *  is not good enough. null = user-supplied URL/file, where there is no
     *  published hash to compare against (see trust-on-first-use below). */
    val sha256: String? = null,
    /** Expected byte length, when published. Cheap pre-check before spending
     *  minutes hashing a multi-GB file. */
    val expectedBytes: Long = 0L
)

/**
 * Catalog of known-good small on-device models plus the user's own imports
 * (a GGUF file picked from storage, or any direct download link). Strong
 * defaults are baked in per model; everything is overridable by supplying
 * your own file/URL in settings.
 */
object ModelRegistry {

    /** Curated small models with known-good settings. */
    val CATALOG: List<ModelSpec> = listOf(
        // Conversation-quality ladder, verified offline against the scripted
        // 10-turn chat test (AiTestLab): 0.6B loops/echoes no matter what the
        // prompt does (capacity ceiling), 1.7B grounds most replies, 4B
        // answers the actual last message ~every turn. The S22 Ultra class
        // of hardware runs 4B Q4 fine; drafts there cost a few more seconds.
        ModelSpec(
            id = "qwen3_4b",
            displayName = "Qwen3 4B (recommended — real conversation quality)",
            fileName = "Qwen_Qwen3-4B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/resolve/main/Qwen_Qwen3-4B-Q4_K_M.gguf",
            sha256 = "fbe1d5edd4ce802ae3ae7c7e4ab7d09789d697fdac1fc7929f8df4ca3c41bae3",
            expectedBytes = 2497280960L,
            sizeLabel = "~2.5 GB",
            template = ChatTemplate.CHATML,
            nCtx = 2048,
            defaultTemp = 0.7f,
            builtIn = true
        ),
        ModelSpec(
            id = "qwen3_17b",
            displayName = "Qwen3 1.7B (balanced — good drafts, faster)",
            fileName = "Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            sha256 = "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb",
            expectedBytes = 1282439584L,
            sizeLabel = "~1.3 GB",
            template = ChatTemplate.CHATML,
            nCtx = 2048,
            defaultTemp = 0.7f,
            builtIn = true
        ),
        ModelSpec(
            id = "qwen3_06b",
            displayName = "Qwen3 0.6B (fastest — basic drafts only)",
            fileName = "Qwen_Qwen3-0.6B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF/resolve/main/Qwen_Qwen3-0.6B-Q4_K_M.gguf",
            sha256 = "9acfc1e001311f34b4252001b626f2e466d592a42065f66571bff3790d4e1b14",
            expectedBytes = 484220320L,
            sizeLabel = "~400 MB",
            template = ChatTemplate.CHATML,
            nCtx = 2048,
            defaultTemp = 0.65f,
            builtIn = true
        ),
        // LFM2 models run on the LEAP engine (Liquid AI's own runtime: the
        // vendor chat template and manifest-tuned sampling ship WITH the
        // model — the recipe owner is the vendor, which is exactly what the
        // Qwen3.5 investigation taught us small models need). Routing is by
        // file name — see LeapEngine.handles.
        ModelSpec(
            id = "lfm2_12b",
            displayName = "LFM2 1.2B (Liquid AI · LEAP engine — recommended)",
            fileName = "LFM2-1.2B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/LiquidAI/LFM2-1.2B-GGUF/resolve/main/LFM2-1.2B-Q4_K_M.gguf",
            sha256 = "55175400e3f509a9616227afeffd58d87e80b9f628a5d3d54ada884d85221fed",
            expectedBytes = 730893248L,
            sizeLabel = "~730 MB",
            template = ChatTemplate.CHATML, // unused on the LEAP path
            nCtx = 2048,
            defaultTemp = 0.6f,
            builtIn = true
        ),
        ModelSpec(
            id = "lfm2_700m",
            displayName = "LFM2 700M (Liquid AI · LEAP engine — faster)",
            fileName = "LFM2-700M-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/LiquidAI/LFM2-700M-GGUF/resolve/main/LFM2-700M-Q4_K_M.gguf",
            sha256 = "684e8406dc13321452b3f6aeca432776e2a6a7e1ad6c23f7887b8fe3efbe2efa",
            expectedBytes = 468624320L,
            sizeLabel = "~470 MB",
            template = ChatTemplate.CHATML, // unused on the LEAP path
            nCtx = 2048,
            defaultTemp = 0.6f,
            builtIn = true
        ),
        ModelSpec(
            id = "smollm2_360m",
            displayName = "SmolLM2 360M (fastest, experimental)",
            fileName = "SmolLM2-360M-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_K_M.gguf",
            sha256 = "2fa3f013dcdd7b99f9b237717fa0b12d75bbb89984cc1274be1471a465bac9c2",
            expectedBytes = 270590880L,
            sizeLabel = "~230 MB",
            template = ChatTemplate.CHATML,
            nCtx = 2048,
            defaultTemp = 0.7f,
            builtIn = true,
            tinyModel = true
        ),
        ModelSpec(
            id = "qwen25_05b",
            displayName = "Qwen2.5 0.5B Instruct",
            fileName = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            sha256 = "6eb923e7d26e9cea28811e1a8e852009b21242fb157b26149d3b188f3a8c8653",
            expectedBytes = 397808192L,
            sizeLabel = "~400 MB",
            template = ChatTemplate.CHATML,
            nCtx = 2048,
            defaultTemp = 0.65f,
            builtIn = true
        ),
        ModelSpec(
            id = "lfm25_230m",
            displayName = "LFM2.5 230M (Liquid AI, experimental)",
            fileName = "LFM2.5-230M-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/resolve/main/LFM2.5-230M-Q4_K_M.gguf",
            sha256 = "7bbd90384d3deffe4c646ec9643b212802d32d4ce417c90a1ec9282100650062",
            expectedBytes = 153406304L,
            sizeLabel = "~190 MB",
            template = ChatTemplate.CHATML,
            nCtx = 2048,
            defaultTemp = 0.7f,
            builtIn = true,
            tinyModel = true
        ),
        ModelSpec(
            id = "tinyllama_11b",
            displayName = "TinyLlama 1.1B Chat",
            fileName = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            sha256 = "9fecc3b3cd76bba89d504f29b616eedf7da85b96540e490ca5824d3f7d2776a0",
            expectedBytes = 668788096L,
            sizeLabel = "~670 MB",
            template = ChatTemplate.ZEPHYR,
            nCtx = 2048,
            defaultTemp = 0.7f,
            builtIn = true
        ),
        ModelSpec(
            id = "llama32_1b",
            displayName = "Llama 3.2 1B Instruct",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83",
            expectedBytes = 807694464L,
            sizeLabel = "~810 MB",
            template = ChatTemplate.LLAMA3,
            nCtx = 2048,
            defaultTemp = 0.7f,
            builtIn = true
        ),
        ModelSpec(
            id = "gemma3_1b",
            displayName = "Gemma 3 1B IT",
            fileName = "google_gemma-3-1b-it-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q4_K_M.gguf",
            sha256 = "12bf0fff8815d5f73a3c9b586bd8fee8e7b248c935de70dec367679873d0f29d",
            expectedBytes = 806058496L,
            sizeLabel = "~810 MB",
            template = ChatTemplate.GEMMA,
            nCtx = 2048,
            defaultTemp = 0.7f,
            builtIn = true
        ),
        ModelSpec(
            id = "phi35_mini",
            displayName = "Phi-3.5 mini 3.8B (heavy, smartest)",
            fileName = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            sha256 = "e4165e3a71af97f1b4820da61079826d8752a2088e313af0c7d346796c38eff5",
            expectedBytes = 2393232672L,
            sizeLabel = "~2.3 GB",
            template = ChatTemplate.PHI3,
            nCtx = 2048,
            defaultTemp = 0.6f,
            builtIn = true
        )
    )

    private val DEFAULT_ID = "qwen3_06b"

    // ---- user-added models (persisted as JSON in prefs) ----

    fun userModels(prefs: Prefs): List<ModelSpec> {
        val out = ArrayList<ModelSpec>()
        try {
            val arr = JSONArray(prefs.customModelsJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += ModelSpec(
                    id = o.getString("id"),
                    displayName = o.getString("name"),
                    fileName = o.getString("file"),
                    downloadUrl = if (o.isNull("url")) null else o.getString("url"),
                    sizeLabel = o.optString("size", "custom"),
                    template = ChatTemplate.fromId(o.optString("template", "chatml")),
                    nCtx = o.optInt("nctx", 2048).coerceIn(1024, 8192),
                    defaultTemp = o.optDouble("temp", 0.65).toFloat().coerceIn(0.2f, 0.9f),
                    builtIn = false
                )
            }
        } catch (_: Throwable) { }
        return out
    }

    private fun saveUserModels(prefs: Prefs, models: List<ModelSpec>) {
        val arr = JSONArray()
        for (m in models) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.displayName)
                put("file", m.fileName)
                put("url", m.downloadUrl ?: JSONObject.NULL)
                put("size", m.sizeLabel)
                put("template", m.template.id)
                put("nctx", m.nCtx)
                put("temp", m.defaultTemp.toDouble())
            })
        }
        prefs.customModelsJson = arr.toString()
    }

    fun all(prefs: Prefs): List<ModelSpec> = CATALOG + userModels(prefs)

    fun byId(prefs: Prefs, id: String): ModelSpec? = all(prefs).firstOrNull { it.id == id }

    /** ~350M params and below (catalog flag, or a size hint in an imported
     *  filename). Tiny models get the stripped-down prompt and short output
     *  budgets — anything analysis-shaped gets parroted at this size. */
    fun isTinyModel(spec: ModelSpec): Boolean =
        spec.tinyModel || Regex(
            "(13[05]m|230m|270m|360m|0\\.[12]b)", RegexOption.IGNORE_CASE
        ).containsMatchIn(spec.fileName)

    fun active(prefs: Prefs): ModelSpec =
        byId(prefs, prefs.activeModelId) ?: CATALOG.first { it.id == DEFAULT_ID }

    /** The QUICK lane model: tiny, always-warm, does per-word autocorrect
     *  in ~100ms bursts so the big drafting model never has to. Defaults to
     *  Qwen3 0.6B (already on most devices) — the 230M/360M tinies are too
     *  echo-prone even for word choices. */
    fun quick(prefs: Prefs): ModelSpec =
        byId(prefs, prefs.quickModelId) ?: CATALOG.first { it.id == "qwen3_06b" }

    /** Short user-facing name of the active model ("Qwen3 0.6B"). Every UI
     *  surface uses this — nothing about the provider is model-specific. */
    fun activeName(prefs: Prefs): String =
        active(prefs).displayName.substringBefore(" (")

    fun addUserModel(prefs: Prefs, spec: ModelSpec) {
        val models = userModels(prefs).filterNot { it.id == spec.id } + spec
        saveUserModels(prefs, models)
    }

    fun removeUserModel(context: Context, prefs: Prefs, id: String) {
        val target = userModels(prefs).firstOrNull { it.id == id } ?: return
        saveUserModels(prefs, userModels(prefs).filterNot { it.id == id })
        modelFile(context, target).delete()
        partFile(context, target).delete()
        invalidateReady(id)
        if (prefs.activeModelId == id) prefs.activeModelId = DEFAULT_ID
    }

    fun nextCustomId(prefs: Prefs): String {
        var n = 1
        val existing = userModels(prefs).map { it.id }.toSet()
        while (existing.contains("custom:$n")) n++
        return "custom:$n"
    }

    // ---- files on disk ----

    fun modelsDir(context: Context): File = File(context.filesDir, "models")

    fun modelFile(context: Context, spec: ModelSpec): File = File(modelsDir(context), spec.fileName)

    private fun partFile(context: Context, spec: ModelSpec): File =
        File(modelsDir(context), "${spec.fileName}.part")

    /** 1.4.0 moved models from filesDir/ into filesDir/models/, orphaning
     *  the model users had already downloaded ("no model loaded" even though
     *  400 MB were sitting one directory up). Adopt any legacy GGUF at the
     *  old location; called from isReady() so every check heals itself. */
    fun migrateLegacy(context: Context) {
        try {
            val dir = modelsDir(context)
            context.filesDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".gguf") && f.length() > 50_000_000L) {
                    val target = File(dir, f.name)
                    if (!target.exists()) { dir.mkdirs(); f.renameTo(target) }
                    else f.delete()
                } else if (f.isFile && f.name.endsWith(".gguf.part")) {
                    f.delete()
                }
            }
        } catch (_: Throwable) { }
    }

    /** Readiness cache: isReady() runs on hot paths (e.g. per committed word
     *  for the autocorrect gate), so the migrateLegacy() directory scan and
     *  the File stat syscalls happen ONCE per spec per process, not per call.
     *  Invalidated by every mutation that can change the answer: download
     *  completion, delete, user-model removal, import. */
    private val readyCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var legacyMigrated = false

    /** Drop the cached readiness for a model. Call after ANY change to the
     *  model file on disk (download finished, file deleted, import done). */
    fun invalidateReady(specId: String) {
        readyCache.remove(specId)
    }

    fun isReady(context: Context, spec: ModelSpec): Boolean =
        readyCache[spec.id] ?: run {
            if (!legacyMigrated) {
                migrateLegacy(context)
                legacyMigrated = true
            }
            val f = modelFile(context, spec)
            val ok = f.exists() && f.length() > 50_000_000L
            readyCache[spec.id] = ok
            ok
        }

    /** Import a GGUF the user picked from storage; returns the new spec. */
    fun importFromFile(context: Context, prefs: Prefs, source: java.io.InputStream,
                       displayName: String, fileName: String): ModelSpec {
        modelsDir(context).mkdirs()
        val cleanName = fileName.substringAfterLast('/').ifBlank { "custom-model.gguf" }
        val spec = ModelSpec(
            id = nextCustomId(prefs),
            displayName = displayName.ifBlank { cleanName },
            fileName = cleanName,
            downloadUrl = null,
            sizeLabel = "local file",
            template = ChatTemplate.guess(cleanName),
            nCtx = 2048,
            defaultTemp = 0.65f,
            builtIn = false
        )
        val target = modelFile(context, spec)
        source.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, 128 * 1024) }
        }
        addUserModel(prefs, spec)
        readyCache[spec.id] = true
        return spec
    }

    // ---- downloads (any catalog or user model with a URL) ----

    enum class Status { NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

    /** Fields are written from the IO download coroutine and read under
     *  synchronized(states) on UI paths — @Volatile so the reads see the
     *  writes without taking the lock. */
    data class DownloadState(
        @Volatile var status: Status = Status.NOT_DOWNLOADED,
        @Volatile var progress: Int = 0,
        @Volatile var downloadedBytes: Long = 0L,
        @Volatile var totalBytes: Long = 0L,
        @Volatile var error: String? = null
    )

    private val states = HashMap<String, DownloadState>()
    private val jobs = HashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun state(context: Context, spec: ModelSpec): DownloadState = synchronized(states) {
        states.getOrPut(spec.id) {
            DownloadState(status = if (isReady(context, spec)) Status.READY else Status.NOT_DOWNLOADED)
        }.also { s ->
            if (s.status != Status.DOWNLOADING) {
                s.status = if (isReady(context, spec)) Status.READY else Status.NOT_DOWNLOADED
            }
        }
    }

    @Synchronized
    fun startDownload(context: Context, spec: ModelSpec) {
        val url = spec.downloadUrl ?: return
        if (jobs[spec.id]?.isActive == true) return
        val appContext = context.applicationContext
        val st = state(context, spec)
        st.status = Status.DOWNLOADING
        st.progress = 0
        st.downloadedBytes = 0
        st.error = null
        jobs[spec.id] = scope.launch {
            val target = modelFile(appContext, spec)
            val part = partFile(appContext, spec)
            modelsDir(appContext).mkdirs()
            try {
                downloadOnce(url, part, spec.id, st)
                verifyDownload(appContext, spec, part, spec.id)
                if (!part.renameTo(target)) throw java.io.IOException("finalize failed")
                st.status = Status.READY
                st.progress = 100
                readyCache[spec.id] = true
            } catch (t: Throwable) {
                // One retry via the hf-mirror.com mirror (huggingface.co is
                // unreachable from some networks). NOTE: this is a third-party
                // host serving an executable model blob — the hash check after
                // it is the only thing making that acceptable.
                try {
                    val mirror = url.replace("https://huggingface.co/", "https://hf-mirror.com/")
                    downloadOnce(mirror, part, spec.id, st)
                    verifyDownload(appContext, spec, part, spec.id)
                    if (!part.renameTo(target)) throw java.io.IOException("finalize failed")
                    st.status = Status.READY
                    st.progress = 100
                    readyCache[spec.id] = true
                } catch (t2: Throwable) {
                    part.delete()
                    st.status = Status.ERROR
                    st.error = t2.javaClass.simpleName + ": " + (t2.message ?: "download failed")
                }
            }
        }
    }

    @Synchronized
    fun cancelDownload(spec: ModelSpec) {
        jobs[spec.id]?.cancel()
        jobs.remove(spec.id)
        synchronized(states) {
            states[spec.id]?.let { it.status = Status.NOT_DOWNLOADED; it.progress = 0 }
        }
    }

    fun delete(context: Context, prefs: Prefs, spec: ModelSpec) {
        cancelDownload(spec)
        modelFile(context, spec).delete()
        partFile(context, spec).delete()
        invalidateReady(spec.id)
        synchronized(states) { states[spec.id]?.status = Status.NOT_DOWNLOADED }
        if (!spec.builtIn) removeUserModel(context, prefs, spec.id)
        else if (prefs.activeModelId == spec.id) prefs.activeModelId = DEFAULT_ID
    }

    private fun downloadOnce(urlString: String, part: File, id: String, st: DownloadState) {
        val conn = openWithRedirects(urlString)
        val total = conn.contentLengthLong
        st.totalBytes = total
        part.delete()
        conn.inputStream.use { input ->
            FileOutputStream(part).use { output ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var written = 0L
                var lastReport = 0L
                while (true) {
                    if (jobs[id]?.isActive != true) throw java.io.IOException("cancelled")
                    read = input.read(buf)
                    if (read < 0) break
                    output.write(buf, 0, read)
                    written += read
                    st.downloadedBytes = written
                    if (total > 0 && written - lastReport > 2 * 1024 * 1024) {
                        st.progress = ((written * 100) / total).toInt().coerceIn(0, 99)
                        lastReport = written
                    }
                }
                if (total > 0 && written < total * 95 / 100) {
                    throw java.io.IOException("incomplete: $written/$total bytes")
                }
            }
        }
        conn.disconnect()
    }

    /** SHA-256 of a file, in lowercase hex. Streams in 1 MB blocks — these are
     *  multi-GB files and must never be read into memory. Cancellable: hashing
     *  a 2.5 GB model takes a few seconds and the user may have walked away. */
    private fun sha256Of(f: File, stillWanted: () -> Boolean): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(f).use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                if (!stillWanted()) throw java.io.IOException("cancelled")
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** Hashes learned on first download of a user-supplied URL, so a later
     *  re-download of the same model has something to be checked against. */
    private fun tofuFile(context: Context) = File(modelsDir(context), ".hashes")

    private fun recordedHash(context: Context, id: String): String? = try {
        tofuFile(context).takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.startsWith("$id=") }?.substringAfter('=')?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    private fun recordHash(context: Context, id: String, hash: String) {
        try {
            val f = tofuFile(context)
            val keep = if (f.exists()) f.readLines().filterNot { it.startsWith("$id=") } else emptyList()
            f.writeText((keep + "$id=$hash").joinToString("\n"))
        } catch (_: Throwable) { /* advisory only */ }
    }

    /**
     * Integrity gate between "bytes arrived" and "file becomes the model we
     * load". This app hands the file to native code and executes it, and the
     * download can come from a third-party mirror (see the hf-mirror fallback
     * in startDownload), so an unverified file is genuinely dangerous.
     *
     * Catalog models carry HuggingFace's published LFS oid, which IS the
     * sha256. User-supplied URLs have no published hash, so the first
     * successful download establishes one (trust on first use) and every
     * later re-download must match it.
     *
     * Throws on mismatch; the caller deletes the part file.
     */
    private fun verifyDownload(context: Context, spec: ModelSpec, part: File, id: String) {
        if (spec.expectedBytes > 0 && part.length() != spec.expectedBytes) {
            throw java.io.IOException(
                "size mismatch: got ${part.length()}, expected ${spec.expectedBytes}"
            )
        }
        val expected = spec.sha256 ?: recordedHash(context, spec.id)
        val actual = sha256Of(part) { jobs[id]?.isActive == true }
        if (expected != null && !expected.equals(actual, ignoreCase = true)) {
            throw java.io.IOException("SHA-256 mismatch — file rejected (got $actual)")
        }
        if (expected == null) recordHash(context, spec.id, actual)
    }

    private fun openWithRedirects(urlString: String): HttpURLConnection {
        var url = urlString
        repeat(5) {
            // HTTPS only, at EVERY hop. Without this an http:// redirect
            // silently downgrades the transport mid-download, which is the
            // classic way a "secure" fetch stops being one.
            if (!url.startsWith("https://", ignoreCase = true)) {
                throw java.io.IOException("refusing non-HTTPS model URL: $url")
            }
            // Every hop goes through the guard, so a redirect chain cannot
            // walk around the user's network switch, and each host it touches
            // lands in the ledger.
            com.localboard.keyboard.util.NetGuard.require(url)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "IOKey-Android/1.4")
            when (conn.responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307, 308 -> {
                    url = conn.getHeaderField("Location") ?: throw java.io.IOException("bad redirect")
                    conn.disconnect()
                }
                else -> return conn
            }
        }
        throw java.io.IOException("too many redirects")
    }
}
