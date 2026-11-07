package com.localboard.keyboard.touch

import com.localboard.keyboard.util.PrefsMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsMigrationsTest {

    /** Behaves like SharedPreferences for what the store interface can see. */
    private class FakeStore(initial: Map<String, Any?> = emptyMap()) : PrefsMigrations.Store {
        val data = HashMap<String, Any?>(initial)
        var commits = 0
        var commitResult = true

        override fun snapshot(): Map<String, Any?> = HashMap(data)

        override fun commit(edit: PrefsMigrations.Edit): Boolean {
            commits++
            edit.remove.forEach { data.remove(it) }
            data.putAll(edit.putInts)
            data.putAll(edit.putBooleans)
            return commitResult
        }
    }

    private val notice = PrefsMigrations.KEY_KEYS_RESET_NOTICE
    private val schema = PrefsMigrations.KEY_GEOMETRY_SCHEMA

    /** An install that has been through the old typing test more than once. */
    private fun oldInstall() = mapOf<String, Any?>(
        "key_layout_fit" to "q:1.1,9.1;h:5.7,4.8",
        "tap_model_data" to "a:1.0,2.0",
        "key_offset_x" to 5f,
        "key_offset_y" to -3f,
        "nav_strip_mode" to 3,
        "swipe_bias_data" to "the\t-5\nyou\t-3",
        "llm_suggestions" to true,
        "llm_suggestions_reset_v2" to true,
        "theme" to "dark",
        "key_spacing" to 1f
    )

    @Test
    fun `an old install is repaired in one write`() {
        val s = FakeStore(oldInstall())
        assertTrue(PrefsMigrations.run(s))
        assertEquals(1, s.commits)
        for (key in PrefsMigrations.RETIRED) assertFalse(key, s.data.containsKey(key))
        assertEquals(PrefsMigrations.GEOMETRY_SCHEMA, s.data[schema])
        assertEquals(0, s.data["nav_strip_mode"])
        assertFalse(s.data.containsKey("swipe_bias_data"))
        assertFalse(s.data.containsKey("llm_suggestions_reset_v2"))
        assertEquals(true, s.data[notice])
    }

    @Test
    fun `the schema constant is two`() {
        assertEquals(2, PrefsMigrations.GEOMETRY_SCHEMA)
        assertEquals("geometry_schema", PrefsMigrations.KEY_GEOMETRY_SCHEMA)
    }

    @Test
    fun `other settings are untouched`() {
        val s = FakeStore(oldInstall())
        PrefsMigrations.run(s)
        assertEquals("dark", s.data["theme"])
        assertEquals(1f, s.data["key_spacing"])
    }

    @Test
    fun `the user's own llm choice survives losing the flag`() {
        val s = FakeStore(oldInstall())
        PrefsMigrations.run(s)
        assertEquals(true, s.data["llm_suggestions"])
    }

    @Test
    fun `the second run is a no op and writes nothing`() {
        val s = FakeStore(oldInstall())
        assertTrue(PrefsMigrations.run(s))
        val after = HashMap(s.data)
        assertFalse(PrefsMigrations.run(s))
        assertEquals(1, s.commits)
        assertEquals(after, s.data)
    }

    @Test
    fun `a fresh install is stamped and gets no notice`() {
        val s = FakeStore()
        assertTrue(PrefsMigrations.run(s))
        assertEquals(PrefsMigrations.GEOMETRY_SCHEMA, s.data[schema])
        assertEquals(1, s.data.size)
        assertFalse(PrefsMigrations.run(s))
    }

    @Test
    fun `a board that never moved gets no notice`() {
        val s = FakeStore(mapOf("tap_model_data" to "a:1.0,2.0"))
        PrefsMigrations.run(s)
        assertFalse(s.data.containsKey("tap_model_data"))
        assertNull(s.data[notice])
    }

    @Test
    fun `blank and zero values do not count as moved`() {
        val s = FakeStore(mapOf("key_layout_fit" to "  ", "key_offset_x" to 0f, "key_offset_y" to 0f))
        PrefsMigrations.run(s)
        for (key in PrefsMigrations.RETIRED) assertFalse(key, s.data.containsKey(key))
        assertNull(s.data[notice])
    }

    @Test
    fun `any one visible key earns the notice`() {
        val cases = listOf<Map<String, Any?>>(
            mapOf("key_layout_fit" to "q:1.0,1.0"),
            mapOf("key_offset_x" to 2.5f),
            mapOf("key_offset_y" to -0.5f)
        )
        for (case in cases) {
            val s = FakeStore(case)
            PrefsMigrations.run(s)
            assertEquals(case.toString(), true, s.data[notice])
        }
    }

    @Test
    fun `other nav strip modes are left alone`() {
        for (mode in 0..2) {
            val s = FakeStore(mapOf("nav_strip_mode" to mode))
            PrefsMigrations.run(s)
            assertEquals(mode, s.data["nav_strip_mode"])
        }
    }

    @Test
    fun `glide bias is reset once only`() {
        val s = FakeStore(mapOf("swipe_bias_data" to "the\t-5"))
        PrefsMigrations.run(s)
        assertFalse(s.data.containsKey("swipe_bias_data"))
        // What the keyboard writes after the reset stays, even in the old two field
        // form it keeps writing until the day stamp lands.
        s.data["swipe_bias_data"] = "you\t-1"
        assertFalse(PrefsMigrations.run(s))
        assertEquals("you\t-1", s.data["swipe_bias_data"])
    }

    @Test
    fun `erase all then start again finds nothing to repair`() {
        val s = FakeStore(oldInstall())
        PrefsMigrations.run(s)
        s.data.clear()
        assertTrue(PrefsMigrations.run(s))
        assertEquals(mapOf<String, Any?>(schema to PrefsMigrations.GEOMETRY_SCHEMA), s.data)
    }

    /** Restore onto an install that has already been repaired: the stamp cannot help. */
    @Test
    fun `an old backup restored later is healed on the re run`() {
        val s = FakeStore(oldInstall())
        PrefsMigrations.run(s)
        s.data.remove(notice)
        val restored = oldInstall().filterKeys { !PrefsMigrations.staysOnDevice(it) }
        s.data.putAll(restored)

        assertTrue(PrefsMigrations.run(s, afterRestore = true))

        assertEquals(0, s.data["nav_strip_mode"])
        assertFalse(s.data.containsKey("swipe_bias_data"))
        for (key in PrefsMigrations.RETIRED) assertFalse(key, s.data.containsKey(key))
        assertNull("nothing moved back, so there is nothing to announce", s.data[notice])
        assertEquals(PrefsMigrations.GEOMETRY_SCHEMA, s.data[schema])
        assertEquals("dark", s.data["theme"])
    }

    @Test
    fun `retired keys that slip past the backup filter are still removed`() {
        val s = FakeStore(mapOf(schema to PrefsMigrations.GEOMETRY_SCHEMA))
        s.data.putAll(oldInstall())
        assertTrue(PrefsMigrations.run(s, afterRestore = true))
        for (key in PrefsMigrations.RETIRED) assertFalse(key, s.data.containsKey(key))
        assertNull(s.data[notice])
    }

    /** No key on this screen ever moved for it: the fit only just came in with the backup. */
    @Test
    fun `a fit arriving with a restore goes without a notice`() {
        val s = FakeStore(mapOf("key_layout_fit" to "q:1.1,9.1", "key_offset_x" to 5f))
        assertTrue(PrefsMigrations.run(s, afterRestore = true))
        assertFalse(s.data.containsKey("key_layout_fit"))
        assertFalse(s.data.containsKey("key_offset_x"))
        assertFalse(s.data.containsKey(notice))
    }

    @Test
    fun `a restored nav strip mode is healed even without the restore flag`() {
        val s = FakeStore(mapOf(schema to PrefsMigrations.GEOMETRY_SCHEMA, "nav_strip_mode" to 3))
        assertTrue(PrefsMigrations.run(s))
        assertEquals(0, s.data["nav_strip_mode"])
    }

    @Test
    fun `stamped bias survives a restore and an erased stamp`() {
        val stamped = "the\t-2\t20400\nyou\t1\t20399"
        val afterRestore = FakeStore(mapOf(schema to 2, "swipe_bias_data" to stamped))
        assertFalse(PrefsMigrations.run(afterRestore, afterRestore = true))
        assertEquals(stamped, afterRestore.data["swipe_bias_data"])

        val erasedThenRestored = FakeStore(mapOf("swipe_bias_data" to stamped))
        PrefsMigrations.run(erasedThenRestored, afterRestore = true)
        assertEquals(stamped, erasedThenRestored.data["swipe_bias_data"])
    }

    @Test
    fun `one unstamped line condemns the whole restored bias`() {
        val mixed = "the\t-2\t20400\nyou\t-5"
        val s = FakeStore(mapOf(schema to 2, "swipe_bias_data" to mixed))
        assertTrue(PrefsMigrations.run(s, afterRestore = true))
        assertFalse(s.data.containsKey("swipe_bias_data"))
    }

    @Test
    fun `empty bias is left alone`() {
        val s = FakeStore(mapOf(schema to 2, "swipe_bias_data" to ""))
        assertFalse(PrefsMigrations.run(s, afterRestore = true))
        assertEquals("", s.data["swipe_bias_data"])
    }

    @Test
    fun `a failed commit reports no change and the next start tries again`() {
        val s = FakeStore(mapOf("nav_strip_mode" to 3))
        s.commitResult = false
        assertFalse(PrefsMigrations.run(s))
        // Editor.commit() failing leaves the disk as it was, so the retry starts over.
        s.data["nav_strip_mode"] = 3
        s.data.remove(schema)
        s.commitResult = true
        assertTrue(PrefsMigrations.run(s))
        assertEquals(0, s.data["nav_strip_mode"])
    }

    @Test
    fun `a newer stamp is respected`() {
        val s = FakeStore(mapOf(schema to 3, "swipe_bias_data" to "you\t-1"))
        assertFalse(PrefsMigrations.run(s))
        assertEquals(3, s.data[schema])
        assertEquals("you\t-1", s.data["swipe_bias_data"])
    }

    @Test
    fun `a stamp or mode of an unexpected type does not throw`() {
        val s = FakeStore(mapOf(schema to "two", "nav_strip_mode" to 3L, "swipe_bias_data" to 7))
        assertTrue(PrefsMigrations.run(s))
        assertEquals(2, s.data[schema])
        assertEquals(0, s.data["nav_strip_mode"])
        assertFalse(s.data.containsKey("swipe_bias_data"))
    }

    @Test
    fun `flags and geometry and touch learning stay out of backups`() {
        val stays = listOf(
            "key_layout_fit", "tap_model_data", "key_offset_x", "key_offset_y",
            "geometry_schema", "keys_reset_notice", "llm_suggestions_reset_v2",
            "touch_model_v2_qwerty", "touch_model_v2_dvorak", "touch_model_generation",
            "touch_check_last"
        )
        for (key in stays) assertTrue(key, PrefsMigrations.staysOnDevice(key))
    }

    @Test
    fun `an erase leaves the chosen models and the repair stamp`() {
        val stays = listOf("active_model_id", "active_provider", "llm_quick_model_id", "geometry_schema", "screen_offer_never")
        for (key in stays) assertTrue(key, key in com.localboard.keyboard.util.Prefs.ERASE_KEEPS)
    }

    @Test
    fun `the user's choices and data still travel`() {
        val travels = listOf(
            "theme", "key_spacing", "nav_strip_mode", "swipe_bias_data",
            "llm_suggestions", "smart_key_targets", "layout_id"
        )
        for (key in travels) assertFalse(key, PrefsMigrations.staysOnDevice(key))
    }
}
