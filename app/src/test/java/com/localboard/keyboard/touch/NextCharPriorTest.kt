package com.localboard.keyboard.touch

import com.localboard.keyboard.engine.NextCharPrior
import com.localboard.keyboard.engine.TapResolver
import com.localboard.keyboard.engine.Trie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class NextCharPriorTest {

    private val out = FloatArray(TapResolver.PRIOR_SIZE)

    private fun theFamily() = Trie().apply {
        insert("the", 90)
        insert("then", 5)
        insert("them", 5)
    }

    @Test
    fun `next symbol probabilities from a known prefix`() {
        assertTrue(NextCharPrior.fill(theFamily(), "the", out))
        assertEquals("end of word", 0.9f, out[26], 1e-4f)
        assertEquals(0.05f, out['n' - 'a'], 1e-4f)
        assertEquals(0.05f, out['m' - 'a'], 1e-4f)
        assertEquals(0f, out['z' - 'a'], 0f)
        assertEquals(1f, out.sum(), 1e-4f)
    }

    @Test
    fun `an empty prefix is the first letter distribution`() {
        assertTrue(NextCharPrior.fill(theFamily(), "", out))
        assertEquals(1f, out['t' - 'a'], 1e-4f)
        assertEquals(0f, out[26], 0f)
    }

    @Test
    fun `an unknown prefix gives no opinion`() {
        assertFalse(NextCharPrior.fill(theFamily(), "qq", out))
    }

    @Test
    fun `a prefix whose words were all removed gives no opinion`() {
        val t = Trie().apply { insert("cat", 10) }
        t.remove("cat")
        assertFalse(NextCharPrior.fill(t, "ca", out))
    }

    @Test
    fun `symbols outside a to z do not take any of the mass`() {
        val t = Trie().apply {
            insert("don", 75)
            insert("don't", 25)
        }
        assertTrue(NextCharPrior.fill(t, "don", out))
        assertEquals("the apostrophe path has no slot, so end of word is all there is", 1f, out[26], 1e-4f)
    }

    @Test
    fun `stale values in the buffer are cleared`() {
        out.fill(0.5f)
        assertTrue(NextCharPrior.fill(theFamily(), "then", out))
        assertEquals(1f, out[26], 1e-4f)
        assertEquals(1f, out.sum(), 1e-4f)
    }

    @Test
    fun `subtree sums match a brute force recount after random inserts and removes`() {
        val t = Trie()
        val rng = Random(3)
        val truth = HashMap<String, Long>()
        repeat(60_000) {
            val w = buildString { repeat(1 + rng.nextInt(5)) { append('a' + rng.nextInt(6)) } }
            if (rng.nextInt(4) == 0) {
                t.remove(w)
                truth.remove(w)
            } else {
                val f = 1L + rng.nextInt(1000)
                t.insert(w, f)
                truth[w] = maxOf(truth[w] ?: 0L, f)
            }
        }
        val prefixes = HashSet<String>()
        for (w in truth.keys) for (i in 0..w.length) prefixes += w.substring(0, i)
        for (p in prefixes) {
            val want = truth.entries.filter { it.key.startsWith(p) }.sumOf { it.value }
            assertEquals("prefix '$p'", want, t.node(p)?.subtree)
        }
    }
}
