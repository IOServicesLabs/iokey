package com.localboard.keyboard.llm

import com.localboard.keyboard.llm.ModelRegistry.Status
import com.localboard.keyboard.util.NetGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelChoiceTest {
    private fun spec(id: String) = ModelRegistry.CATALOG.first { it.id == id }

    private val imported = ModelSpec(
        id = "custom:1",
        displayName = "My import",
        fileName = "some-model-q4.gguf",
        downloadUrl = null,
        sizeLabel = "local file",
        template = ChatTemplate.CHATML
    )

    private val roomy = "qwen3_17b"

    @Test
    fun recommendation_followsMemory_andStaysOnLlamaCpp() {
        assertEquals("qwen3_17b", ModelRegistry.recommendedIdFor(12_000_000_000L))
        assertEquals("qwen3_17b", ModelRegistry.recommendedIdFor(7_600_000_000L))
        // A phone sold as 6 GB reports less than that.
        assertEquals("qwen3_17b", ModelRegistry.recommendedIdFor(5_600_000_000L))
        assertEquals("qwen3_06b", ModelRegistry.recommendedIdFor(3_800_000_000L))
        // Memory could not be read.
        assertEquals("qwen3_06b", ModelRegistry.recommendedIdFor(0L))
        for (ram in listOf(0L, 3_800_000_000L, 12_000_000_000L)) {
            assertFalse(LeapEngine.handles(spec(ModelRegistry.recommendedIdFor(ram))))
        }
    }

    @Test
    fun catalog_callsOnlyTheRecommendedModelRecommended() {
        val claiming = ModelRegistry.CATALOG.filter { "recommended" in it.displayName.lowercase() }
        assertEquals(listOf("qwen3_17b"), claiming.map { it.id })
    }

    @Test
    fun catalog_sizeLabelsMatchTheBytes() {
        for (m in ModelRegistry.CATALOG) {
            val label = m.sizeLabel.removePrefix("~")
            val number = label.substringBefore(' ').toDouble()
            val labelledBytes = if (label.endsWith("GB")) number * 1e9 else number * 1e6
            val off = kotlin.math.abs(labelledBytes - m.expectedBytes) / m.expectedBytes
            assertTrue("${m.id} says ${m.sizeLabel} for ${m.expectedBytes} bytes", off < 0.05)
        }
    }

    @Test
    fun pick_prefersTheRecommendedModel() {
        val low = "qwen3_06b"
        val ready = listOf(spec("qwen3_4b"), spec("qwen3_06b"))
        assertEquals("qwen3_06b", ModelRegistry.pickBest(ready, low)?.id)
        assertEquals("qwen3_4b", ModelRegistry.pickBest(ready, roomy)?.id)
    }

    @Test
    fun pick_runsLlamaCppBeforeTheClosedRuntime_andTinyModelsLast() {
        val order = listOf(
            "qwen3_17b", "qwen3_4b", "custom:1", "gemma3_1b", "qwen3_06b", "lfm2_12b", "smollm2_360m"
        )
        val all = listOf(
            spec("smollm2_360m"), spec("lfm2_12b"), spec("qwen3_06b"), spec("gemma3_1b"),
            imported, spec("qwen3_4b"), spec("qwen3_17b")
        )
        val picked = ArrayList<String>()
        var left: List<ModelSpec> = all
        while (left.isNotEmpty()) {
            val best = ModelRegistry.pickBest(left, "none")!!
            picked += best.id
            left = left - best
        }
        assertEquals(order, picked)
    }

    @Test
    fun pick_withNothingReady_isNull() {
        assertNull(ModelRegistry.pickBest(emptyList(), roomy))
    }

    @Test
    fun download_neverReplacesAChosenWorkingModel() {
        assertFalse(ModelRegistry.downloadTakesOver("qwen3_06b", currentReady = true, downloadedId = "qwen3_4b"))
        assertFalse(ModelRegistry.downloadTakesOver("qwen3_06b", true, "qwen3_4b", storedId = "qwen3_06b"))
        assertFalse(ModelRegistry.downloadTakesOver("gemma3_1b", true, "qwen3_4b", storedId = "gemma3_1b"))
    }

    /** Or the sparkle would show for the new download, and drafts would run on the 0.6B. */
    @Test
    fun download_replacesAnUnchosenQuickDefault() {
        assertTrue(ModelRegistry.downloadTakesOver("qwen3_06b", true, "qwen3_17b", storedId = null))
    }

    @Test
    fun download_ofTheQuickDefault_neverActivatesItself() {
        assertFalse("nothing stored, nothing working", ModelRegistry.downloadTakesOver("qwen3_06b", false, "qwen3_06b", storedId = null))
        assertFalse("nothing stored, its own file back", ModelRegistry.downloadTakesOver("qwen3_06b", true, "qwen3_06b", storedId = null))
        assertFalse("another model chosen, gone", ModelRegistry.downloadTakesOver("qwen3_17b", false, "qwen3_06b", storedId = "qwen3_17b"))
        assertFalse("another model chosen, working", ModelRegistry.downloadTakesOver("qwen3_17b", true, "qwen3_06b", storedId = "qwen3_17b"))
    }

    @Test
    fun download_ofTheChosenQuickDefault_turnsItOn() {
        assertTrue(ModelRegistry.downloadTakesOver("qwen3_06b", false, "qwen3_06b", storedId = "qwen3_06b"))
    }

    @Test
    fun download_takesOverWhenNothingWorks() {
        assertTrue(ModelRegistry.downloadTakesOver("qwen3_06b", currentReady = false, downloadedId = "qwen3_17b"))
    }

    @Test
    fun download_ofTheActiveModel_alsoTurnsItOn() {
        // Someone on the composer, whose chosen model only now arrived.
        assertTrue(ModelRegistry.downloadTakesOver("qwen3_17b", currentReady = true, downloadedId = "qwen3_17b"))
    }

    @Test
    fun repair_activatesADownloadedModelThatWasNeverTurnedOn() {
        val picked = ModelRegistry.repairTarget("qwen3_06b", false, listOf(spec("gemma3_1b"), spec("qwen3_17b")), roomy)
        assertEquals("qwen3_17b", picked?.id)
    }

    @Test
    fun repair_leavesAWorkingModelAlone() {
        assertNull(ModelRegistry.repairTarget("qwen3_06b", true, listOf(spec("qwen3_17b")), roomy))
    }

    @Test
    fun repair_leavesSomeoneWhoChoseTheComposerAlone() {
        assertNull(ModelRegistry.repairTarget("local_style", false, listOf(spec("qwen3_17b")), roomy))
    }

    @Test
    fun repair_doesNotTurnOnATinyModel() {
        assertNull(ModelRegistry.repairTarget("qwen3_06b", false, listOf(spec("lfm25_230m")), roomy))
    }

    @Test
    fun repair_withNothingDownloaded_doesNothing() {
        assertNull(ModelRegistry.repairTarget("qwen3_06b", false, emptyList(), roomy))
    }

    @Test
    fun repair_ignoresAnUnchosenQuickDefault() {
        val ready = listOf(spec("qwen3_06b"))
        assertNull(ModelRegistry.repairTarget("qwen3_06b", false, ModelRegistry.withoutQuickDefault(ready), roomy))
        val alongside = listOf(spec("qwen3_06b"), spec("gemma3_1b"))
        assertEquals("gemma3_1b", ModelRegistry.repairTarget("qwen3_06b", false, ModelRegistry.withoutQuickDefault(alongside), "qwen3_06b")?.id)
    }

    @Test
    fun replacement_leavesOutTheQuickDefault() {
        assertNull(ModelRegistry.replacementAmong(listOf(spec("qwen3_06b")), roomy))
        assertNull(ModelRegistry.replacementAmong(listOf(spec("qwen3_06b")), "qwen3_06b"))
        assertNull(ModelRegistry.replacementAmong(emptyList(), roomy))
        assertEquals("qwen3_4b", ModelRegistry.replacementAmong(listOf(spec("qwen3_06b"), spec("qwen3_4b")), "qwen3_06b")?.id)
    }

    private fun answer(ready: List<String>, stored: String?, others: List<String> = emptyList()): Boolean =
        ModelRegistry.draftingAnswer((ready + others).map(::spec), { it.id in ready }, stored)

    @Test
    fun draftingAnswer_table() {
        assertFalse("nothing on the phone", answer(emptyList(), null, others = listOf("qwen3_06b", "qwen3_17b")))
        assertFalse("nothing stored, 0.6B ready", answer(listOf("qwen3_06b"), null))
        assertTrue("stored 0.6B", answer(listOf("qwen3_06b"), "qwen3_06b"))
        assertFalse("0.6B ready, another stored", answer(listOf("qwen3_06b"), "qwen3_17b"))
        assertTrue("1.7B ready", answer(listOf("qwen3_17b"), null))
        assertTrue("0.6B and 1.7B ready, nothing stored", answer(listOf("qwen3_06b", "qwen3_17b"), null))
        assertTrue("an import counts", ModelRegistry.draftingAnswer(listOf(imported), { true }, null))
        assertFalse("0.6B stored, its file gone", answer(emptyList(), "qwen3_06b", others = listOf("qwen3_06b")))
    }

    /** It asks whether a file is there, not whether the last load worked, so low memory takes no sparkle away. */
    @Test
    fun draftingAnswer_aFailedLoadDoesNotChangeIt() {
        val onPhone = spec("qwen3_17b")
        val failedLoadButFileThere: (ModelSpec) -> Boolean = { it.id == onPhone.id }
        assertTrue(ModelRegistry.draftingAnswer(listOf(onPhone), failedLoadButFileThere, "qwen3_17b"))
    }

    /** The quick lane may be pointed at any ready model. A big one still drafts, and it is the default that does not. */
    @Test
    fun draftingAnswer_theLanePointedAtABigModel() {
        assertTrue(answer(listOf("qwen3_4b"), null))
        assertEquals("qwen3_06b", ModelRegistry.QUICK_DEFAULT_ID)
    }

    @Test
    fun chosen_needsAStoredValue() {
        assertFalse(ModelRegistry.isChosenActive(null, "qwen3_06b"))
        assertFalse(ModelRegistry.isChosenActive("qwen3_17b", "qwen3_06b"))
        assertTrue(ModelRegistry.isChosenActive("qwen3_06b", "qwen3_06b"))
    }

    @Test
    fun status_keepsAFailureUntilSomethingChanges() {
        assertEquals(Status.ERROR, ModelRegistry.nextStatus(Status.ERROR, fileReady = false))
        assertEquals(Status.READY, ModelRegistry.nextStatus(Status.ERROR, fileReady = true))
        assertEquals(Status.DOWNLOADING, ModelRegistry.nextStatus(Status.DOWNLOADING, fileReady = false))
        assertEquals(Status.NOT_DOWNLOADED, ModelRegistry.nextStatus(Status.READY, fileReady = false))
        assertEquals(Status.READY, ModelRegistry.nextStatus(Status.NOT_DOWNLOADED, fileReady = true))
    }

    @Test
    fun mirror_isOnlyForASiteThatCouldNotServeTheFile() {
        assertTrue(ModelRegistry.mirrorMightHelp(java.net.UnknownHostException("x")))
        assertTrue(ModelRegistry.mirrorMightHelp(java.net.ConnectException("x")))
        assertTrue(ModelRegistry.mirrorMightHelp(java.net.SocketTimeoutException("x")))
        assertTrue(ModelRegistry.mirrorMightHelp(ModelRegistry.DownloadProblem("server", true)))
        assertFalse(ModelRegistry.mirrorMightHelp(ModelRegistry.DownloadProblem("bad checksum")))
        assertFalse(ModelRegistry.mirrorMightHelp(java.io.IOException("cancelled")))
        assertFalse(ModelRegistry.mirrorMightHelp(NetGuard.BlockedException("switched off")))
    }
}
