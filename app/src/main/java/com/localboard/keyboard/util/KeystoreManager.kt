package com.localboard.keyboard.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates and protects the SQLCipher database passphrase using the
 * Android Keystore (AES/GCM). The raw passphrase never touches disk.
 */
object KeystoreManager {

    private const val KEY_ALIAS = "localboard_db_key"
    private const val PREFS = "localboard_keystore"
    private const val KEY_ENC_PASS = "enc_passphrase"
    private const val KEY_IV = "enc_iv"

    /** Decrypted passphrase, cached so the Keystore is hit once per process —
     *  SQLCipher opens are frequent and every decrypt used to be a full
     *  AES/GCM unwrap. */
    @Volatile
    private var cachedPassphrase: ByteArray? = null

    /**
     * The passphrase, or a failure that surfaces, whatever it looks like: a GCM tag mismatch from a
     * torn write, provider unavailability, or a key that is gone for good. A new passphrase would
     * silently cost access to the existing db, and only the user can decide to give that up: see
     * [forgetPassphraseIfUnreadable].
     *
     * @param storeHasContent a store with something in it is on disk. A passphrase missing from the
     * prefs is then one that could not be read, and a new one would make that store read as damaged.
     * Only moving the store away, which Start fresh does, makes a new one safe.
     */
    @Synchronized
    fun getDatabasePassphrase(context: Context, storeHasContent: Boolean): ByteArray {
        cachedPassphrase?.let { return it }
        val prefs = prefs(context)
        val stored = try {
            val enc = prefs.getString(KEY_ENC_PASS, null)
            val iv = prefs.getString(KEY_IV, null)
            if (enc != null && iv != null) {
                WrappedPassphrase(Base64.decode(enc, Base64.NO_WRAP), Base64.decode(iv, Base64.NO_WRAP))
            } else null
        } catch (e: IllegalArgumentException) {
            throw KeyStoreException("the stored passphrase is not readable", e)
        }
        return PassphraseRules.passphraseFor(stored, storeHasContent, AndroidWrappingKey) { wrapped ->
            // commit(), not apply(), and before the store exists: a kill right after this must
            // not leave a store behind with no passphrase to open it.
            prefs.edit()
                .putString(KEY_ENC_PASS, Base64.encodeToString(wrapped.cipherText, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(wrapped.iv, Base64.NO_WRAP))
                .commit()
        }.also { cachedPassphrase = it }
    }

    /** The user chose to start fresh. If the stored passphrase can't be unwrapped it never will
     *  be for this new start, so the next open makes another. One that still unwraps stays,
     *  because it is what opens the copy of the old store. */
    @Synchronized
    fun forgetPassphraseIfUnreadable(context: Context) {
        try {
            // With nothing stored this says so rather than make one. The next open does that.
            getDatabasePassphrase(context, storeHasContent = true)
        } catch (_: Exception) {
            cachedPassphrase = null
            prefs(context).edit().clear().commit()
        }
    }

    /** What the prefs hold right now, for [putBack] when a start fresh does not work. */
    internal fun stored(context: Context): StoredPassphrase = prefs(context).let {
        StoredPassphrase(it.getString(KEY_ENC_PASS, null), it.getString(KEY_IV, null))
    }

    /** Back to exactly [stored], because the store it belongs to is back too. This is only worth
     *  anything since an unwrap stopped being able to replace the key. */
    @Synchronized
    internal fun putBack(context: Context, stored: StoredPassphrase): Boolean {
        cachedPassphrase = null
        val edit = prefs(context).edit().clear()
        stored.enc?.let { edit.putString(KEY_ENC_PASS, it) }
        stored.iv?.let { edit.putString(KEY_IV, it) }
        return edit.commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private object AndroidWrappingKey : WrappingKey {
        override fun lookUp(): SecretKey? {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        }

        override fun generate(): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }
    }
}

/** The prefs file's two strings as they are, whatever they hold. */
internal class StoredPassphrase(val enc: String?, val iv: String?)

/** The passphrase as the prefs hold it, wrapped by the Keystore key. */
internal class WrappedPassphrase(val cipherText: ByteArray, val iv: ByteArray)

/** The Keystore key that wraps the passphrase, apart from the rules so that a test can have it fail as the platform does. */
internal interface WrappingKey {
    /** The key, or null when the lookup gave none. Android answers the same for a key that is gone
     *  and for a Keystore that returned an error, so null never means one may be made. */
    fun lookUp(): SecretKey?

    /** Makes a key under the one alias, and whatever key was there is gone for good. */
    fun generate(): SecretKey
}

/** When a passphrase or a key may be made, which is hardly ever. */
internal object PassphraseRules {
    private const val TAG_BITS = 128
    private const val PASSPHRASE_BYTES = 32

    fun passphraseFor(
        stored: WrappedPassphrase?,
        storeHasContent: Boolean,
        key: WrappingKey,
        save: (WrappedPassphrase) -> Boolean
    ): ByteArray {
        if (stored != null) return unwrap(stored, key)
        if (storeHasContent) {
            throw KeyStoreException("the wrapped passphrase is missing, and a store exists that needs it")
        }
        val raw = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        if (!save(wrap(raw, key))) throw java.io.IOException("the wrapped passphrase could not be saved")
        return raw
    }

    /** Only ever a look at the key. A lookup that failed once would otherwise replace the key that
     *  wraps the passphrase, and a hiccup would become a store that can never open again. */
    private fun unwrap(wrapped: WrappedPassphrase, key: WrappingKey): ByteArray {
        val secret = key.lookUp() ?: throw KeyStoreException("the Keystore gave back no key for the passphrase")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, wrapped.iv))
        return cipher.doFinal(wrapped.cipherText)
    }

    /**
     * For a passphrase being made, when no open store and no wrapped passphrase depend on the key. A set-aside
     * copy can: Start fresh gets here after a passphrase it could not unwrap, and a lookup that failed only for
     * the moment then replaces the key, which leaves that copy unreadable. Nothing in the app reads the copy.
     */
    private fun wrap(raw: ByteArray, key: WrappingKey): WrappedPassphrase {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key.lookUp() ?: key.generate())
        return WrappedPassphrase(cipher.doFinal(raw), cipher.iv)
    }
}
