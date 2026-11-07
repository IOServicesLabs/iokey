package com.localboard.keyboard.data

import com.localboard.keyboard.context.StorageHealth
import com.localboard.keyboard.util.PassphraseRules
import com.localboard.keyboard.util.WrappedPassphrase
import com.localboard.keyboard.util.WrappingKey
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Keystore's lookup answers null both for a key that is gone and for a Keystore that returned an
 * error. Generating on that null is what turned one bad moment into a store that can never open.
 */
class PassphraseRulesTest {
    private class FakeKey(private var key: SecretKey? = null) : WrappingKey {
        var generated = 0
        var lookupFails = false

        override fun lookUp(): SecretKey? = if (lookupFails) null else key

        override fun generate(): SecretKey {
            generated++
            return newKey().also { key = it }
        }
    }

    private companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private var saved: WrappedPassphrase? = null

    private fun passphrase(key: WrappingKey, storeHasContent: Boolean, save: Boolean = true): ByteArray =
        PassphraseRules.passphraseFor(saved, storeHasContent, key) { wrapped ->
            if (save) saved = wrapped
            save
        }

    @Test
    fun aLookupThatGivesNothingLeavesTheKeyAloneAndTheSamePassphraseComesBack() {
        val key = FakeKey()
        val first = passphrase(key, storeHasContent = false)
        assertEquals(1, key.generated)

        key.lookupFails = true
        val failure = assertThrows(GeneralSecurityException::class.java) { passphrase(key, storeHasContent = true) }
        assertEquals("a lookup that failed must not replace the key", 1, key.generated)
        assertTrue("the failure must read as one of the key: $failure", StorageHealth.isKeyFailure(failure))

        key.lookupFails = false
        assertArrayEquals("the store opens again once the Keystore answers", first, passphrase(key, storeHasContent = true))
        assertEquals(1, key.generated)
    }

    /** Nothing of this start can tell a lost key from one that will be back, so neither makes a new one. */
    @Test
    fun aKeyThatIsGoneForGoodIsNotReplacedByTheUnwrapEither() {
        val key = FakeKey()
        passphrase(key, storeHasContent = false)
        val gone = FakeKey(key = null)
        assertThrows(GeneralSecurityException::class.java) { passphrase(gone, storeHasContent = false) }
        assertEquals(0, gone.generated)
    }

    @Test
    fun aWrappedPassphraseThatIsMissingBesideAStoreIsAFailureNotANewPassphrase() {
        val key = FakeKey(newKey())
        val failure = assertThrows(GeneralSecurityException::class.java) { passphrase(key, storeHasContent = true) }
        assertTrue(StorageHealth.isKeyFailure(failure))
        assertEquals(0, key.generated)
        assertNull("nothing may be written over what could not be read", saved)
    }

    @Test
    fun aNewPassphraseIsOnlyGivenOutOnceItIsSaved() {
        val key = FakeKey()
        assertThrows(IOException::class.java) { passphrase(key, storeHasContent = false, save = false) }
        assertNull(saved)

        val given = passphrase(key, storeHasContent = false)
        assertNotNull(saved)
        assertArrayEquals("what was saved must be what opens the store", given, passphrase(key, storeHasContent = false))
    }

    @Test
    fun aKeyThatIsThereWrapsTheNewPassphraseWithoutBeingReplaced() {
        val key = FakeKey(newKey())
        passphrase(key, storeHasContent = false)
        assertEquals("a key another store may depend on stays", 0, key.generated)
    }

    @Test
    fun aTornWrappedPassphraseIsAKeyFailureAndNotANewPassphrase() {
        val key = FakeKey()
        passphrase(key, storeHasContent = false)
        val good = saved!!
        val torn = good.cipherText.clone().also { it[0] = (it[0] + 1).toByte() }
        saved = WrappedPassphrase(torn, good.iv)

        val failure = assertThrows(GeneralSecurityException::class.java) { passphrase(key, storeHasContent = true) }
        assertTrue(StorageHealth.isKeyFailure(failure))
        assertEquals(1, key.generated)
    }
}
