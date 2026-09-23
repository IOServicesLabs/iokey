package com.localboard.keyboard.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
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

    @Synchronized
    fun getDatabasePassphrase(context: Context): ByteArray {
        cachedPassphrase?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enc = prefs.getString(KEY_ENC_PASS, null)
        val iv = prefs.getString(KEY_IV, null)

        if (enc != null && iv != null) {
            try {
                return decrypt(Base64.decode(enc, Base64.NO_WRAP), Base64.decode(iv, Base64.NO_WRAP))
                    .also { cachedPassphrase = it }
            } catch (e: Exception) {
                // Only a PERMANENTLY invalidated key (lock screen removed,
                // auth enforced after the fact) means the stored passphrase is
                // unrecoverable and we start fresh. Transient errors — GCM tag
                // mismatch from a torn write, provider unavailability — must
                // surface, not silently destroy access to the existing db.
                if (!isPermanentKeyLoss(e)) throw e
                prefs.edit().clear().apply()
            }
        }

        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val (cipherText, newIv) = encrypt(raw)
        prefs.edit()
            .putString(KEY_ENC_PASS, Base64.encodeToString(cipherText, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(newIv, Base64.NO_WRAP))
            .apply()
        cachedPassphrase = raw
        return raw
    }

    /** True when the Keystore key itself is gone for good, so the wrapped
     *  passphrase can never be recovered and regenerating is the only way
     *  forward. */
    private fun isPermanentKeyLoss(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is android.security.keystore.KeyPermanentlyInvalidatedException ||
                cur is android.security.keystore.UserNotAuthenticatedException ||
                (cur is java.security.InvalidKeyException &&
                    cur.message?.contains("invalidate", ignoreCase = true) == true)
            ) return true
            cur = cur.cause
        }
        return false
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

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

    private fun encrypt(data: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.doFinal(data) to cipher.iv
    }

    private fun decrypt(data: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }
}
