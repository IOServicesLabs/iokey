package com.localboard.keyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an update does to a real user's stored settings, end to end on a plain JVM: the file an
 * old build leaves behind, the repair at start, a second start, then a backup from before the
 * update coming back through the same filter the restore uses.
 */
class OldInstallRepairTest {

    /** Behaves like SharedPreferences for what the store can see: a failed write changes nothing. */
    private class FileLikeStore(initial: Map<String, Any?>) : PrefsMigrations.Store {
        val data = HashMap(initial)
        var writes = 0
        var failWrites = false
        var failReads = false

        override fun snapshot(): Map<String, Any?> {
            if (failReads) throw IllegalStateException("the settings file could not be read")
            return HashMap(data)
        }

        override fun commit(edit: PrefsMigrations.Edit): Boolean {
            if (failWrites) return false
            writes++
            edit.remove.forEach { data.remove(it) }
            data.putAll(edit.putInts)
            data.putAll(edit.putBooleans)
            return true
        }
    }

    /** Stored the way an old build stores them, after a few runs of the typing test. */
    private fun oldFile(): Map<String, Any?> = mapOf(
        "key_layout_fit" to "q:1.1,9.1;h:5.7,4.8;k:-3.2,4.4",
        "tap_model_data" to "a:1.0,2.0;s:0.5,3.1",
        "key_offset_x" to 5f,
        "key_offset_y" to -3f,
        "nav_strip_mode" to 3,
        "swipe_bias_data" to "the\t-5\nyou\t-3",
        "llm_suggestions" to true,
        "llm_suggestions_reset_v2" to true,
        "theme" to "dark",
        "key_spacing" to 1f,
        "height_scale" to 1.1f
    )

    private val retired = listOf("key_layout_fit", "tap_model_data", "key_offset_x", "key_offset_y")

    private fun assertRepaired(data: Map<String, Any?>) {
        for (key in retired) assertFalse("$key must be gone", data.containsKey(key))
        assertEquals(PrefsMigrations.GEOMETRY_SCHEMA, data[PrefsMigrations.KEY_GEOMETRY_SCHEMA])
        assertEquals(0, data["nav_strip_mode"])
        assertFalse(data.containsKey("swipe_bias_data"))
        assertFalse(data.containsKey("llm_suggestions_reset_v2"))
    }

    @Test
    fun `the old file is repaired at the first start and untouched at the second`() {
        val store = FileLikeStore(oldFile())
        assertTrue(PrefsMigrations.run(store))
        assertRepaired(store.data)
        assertEquals(true, store.data[PrefsMigrations.KEY_KEYS_RESET_NOTICE])
        val afterFirst = HashMap(store.data)

        assertFalse(PrefsMigrations.run(store))

        assertEquals(1, store.writes)
        assertEquals(afterFirst, store.data)
    }

    @Test
    fun `everything the person chose survives the repair`() {
        val store = FileLikeStore(oldFile())
        PrefsMigrations.run(store)
        assertEquals(true, store.data["llm_suggestions"])
        assertEquals("dark", store.data["theme"])
        assertEquals(1f, store.data["key_spacing"])
        assertEquals(1.1f, store.data["height_scale"])
    }

    @Test
    fun `the notice comes once, and not again after it was shown and the app restarted`() {
        val store = FileLikeStore(oldFile())
        PrefsMigrations.run(store)
        store.data.remove(PrefsMigrations.KEY_KEYS_RESET_NOTICE)
        PrefsMigrations.run(store)
        assertNull(store.data[PrefsMigrations.KEY_KEYS_RESET_NOTICE])
    }

    @Test
    fun `a write that fails leaves the old file whole and the next start finishes the job`() {
        val store = FileLikeStore(oldFile())
        store.failWrites = true
        assertFalse(PrefsMigrations.run(store))
        assertEquals(oldFile(), store.data)

        store.failWrites = false
        assertTrue(PrefsMigrations.run(store))
        assertRepaired(store.data)
        assertEquals(true, store.data[PrefsMigrations.KEY_KEYS_RESET_NOTICE])
    }

    /** The caller's catch is what keeps the keyboard up. All that matters here is that nothing is half done. */
    @Test
    fun `a read that throws changes nothing and the next start finishes the job`() {
        val store = FileLikeStore(oldFile())
        store.failReads = true
        val thrown = runCatching { PrefsMigrations.run(store) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(oldFile(), store.data)

        store.failReads = false
        assertTrue(PrefsMigrations.run(store))
        assertRepaired(store.data)
    }

    @Test
    fun `glide bias the keyboard writes after the repair stays over later starts`() {
        val store = FileLikeStore(oldFile())
        PrefsMigrations.run(store)
        store.data["swipe_bias_data"] = "you\t-1"
        assertFalse(PrefsMigrations.run(store))
        assertEquals("you\t-1", store.data["swipe_bias_data"])
    }

    // ---------------------------------------------------------------------------------------------
    // A backup taken before the update
    // ---------------------------------------------------------------------------------------------

    /** What restoring writes: every stored value that passes the filter, then the repairs again. */
    private fun restore(store: FileLikeStore, backup: Map<String, Any?>) {
        store.data.putAll(backup.filterKeys { !BackupManager.nonTravelling(it) })
        PrefsMigrations.run(store, afterRestore = true)
    }

    @Test
    fun `an old backup restored after the update does not bring the old fit back`() {
        val store = FileLikeStore(oldFile())
        PrefsMigrations.run(store)
        store.data.remove(PrefsMigrations.KEY_KEYS_RESET_NOTICE)

        restore(store, oldFile())

        assertRepaired(store.data)
        assertNull("nothing moved back, so there is nothing to announce", store.data[PrefsMigrations.KEY_KEYS_RESET_NOTICE])
        assertEquals("dark", store.data["theme"])

        val afterRestore = HashMap(store.data)
        assertFalse("the next start finds nothing left to do", PrefsMigrations.run(store))
        assertEquals(afterRestore, store.data)
    }

    /** The filter on the way in is not the only defence: a key that gets past it is still removed. */
    @Test
    fun `retired keys that reach the file some other way are still removed`() {
        val store = FileLikeStore(oldFile())
        PrefsMigrations.run(store)
        store.data.putAll(oldFile())
        PrefsMigrations.run(store, afterRestore = true)
        assertRepaired(store.data)
    }

    @Test
    fun `nothing that describes this install travels in either direction`() {
        val stays = retired + listOf(
            PrefsMigrations.KEY_GEOMETRY_SCHEMA,
            PrefsMigrations.KEY_KEYS_RESET_NOTICE,
            "llm_suggestions_reset_v2",
            Prefs.KEY_SWIPE_BIAS_RESET_PENDING,
            "touch_model_v2_qwerty",
            "touch_check_last",
            "wallpaper_path",
            "custom_models_json",
            "active_model_id"
        )
        for (key in stays) assertTrue(key, BackupManager.nonTravelling(key))
    }

    /** It holds whatever was copied, and a backup with no passphrase is plain text. */
    @Test
    fun `clip history travels in neither direction and its switches do`() {
        assertTrue(BackupManager.nonTravelling("clipboard_entries"))
        for (key in listOf("clipboard_history", "clipboard_expiry_min")) {
            assertFalse(key, BackupManager.nonTravelling(key))
        }
    }

    @Test
    fun `the person's settings and glide learning still travel`() {
        for (key in listOf("theme", "key_spacing", "height_scale", "nav_strip_mode", "swipe_bias_data", "llm_suggestions")) {
            assertFalse(key, BackupManager.nonTravelling(key))
        }
    }

    @Test
    fun `pieces of contractions in an old backup are not taught back`() {
        for (word in listOf("isn", "doesn", "didn", "wouldn", "ve", "ll", "re", "ISN", "Ve")) {
            assertFalse(word, BackupManager.restorableWord(word))
        }
    }

    @Test
    fun `real words in an old backup are`() {
        for (word in listOf("don", "can", "won", "haven", "the", "really", "don't", "We")) {
            assertTrue(word, BackupManager.restorableWord(word))
        }
        assertFalse("an empty entry is no word", BackupManager.restorableWord(""))
    }
}
