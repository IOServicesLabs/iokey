package com.localboard.keyboard.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Export and restore everything the user configured or the keyboard learned.
 *
 * WHY THIS EXISTS
 * ---------------
 * Android cannot move app data between package ids, and it wipes private
 * storage on uninstall. Without an export, switching build variants or
 * reinstalling silently destroys the settings, the theme, the finger
 * calibration and the learned vocabulary — which is exactly what happened on
 * 2026-07-28. A keyboard that spends months learning how one person types
 * owes them a way to carry that with them.
 *
 * ENCRYPTION
 * ----------
 * The backup contains a model of how its owner writes: their vocabulary,
 * their snippets, the words they use most. Writing that to /Downloads in
 * plaintext would undo the point of an encrypted database. So a passphrase
 * gets AES-256-GCM with PBKDF2 key derivation; no passphrase falls back to
 * plaintext JSON, but the caller is expected to say so out loud.
 *
 * FORMAT
 * ------
 * Plaintext: a JSON object with "version", "prefs" and "words".
 * Encrypted: the ASCII header "LBBK1", then base64 of salt|iv|ciphertext.
 */
object BackupManager {

    private const val MAGIC = "LBBK1"
    private const val VERSION = 1
    private const val PBKDF2_ITERS = 120_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Prefs that must never travel: they point at files inside one specific
     *  app sandbox (wallpaper_path) or reference model files/downloads that do
     *  not exist on a fresh install (custom_models_json, active_model_id), so
     *  restoring them elsewhere yields dangling paths/ids. */
    private val PATH_KEYS = setOf("wallpaper_path", "custom_models_json", "active_model_id")

    data class Payload(val prefs: Map<String, Any?>, val words: List<Pair<String, Int>>)

    // ---------------------------------------------------------------- build

    fun buildJson(prefs: Prefs, words: List<Pair<String, Int>>): String {
        val o = JSONObject()
        o.put("version", VERSION)
        o.put("createdAt", System.currentTimeMillis())

        // Each entry carries its SharedPreferences type. JSON collapses
        // Int/Long/Float into "number", and restoring a Long key as an Int
        // makes the next getLong() throw ClassCastException — a crash on the
        // settings screen, caused by a backup. The tag removes the guesswork.
        val p = JSONObject()
        for ((k, v) in prefs.snapshotAll()) {
            if (k in PATH_KEYS) continue
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                is Set<*> -> e.put("t", "set")
                    .put("v", JSONArray().also { arr -> v.forEach { arr.put(it.toString()) } })
                else -> continue
            }
            p.put(k, e)
        }
        o.put("prefs", p)

        val w = JSONArray()
        for ((word, count) in words) {
            w.put(JSONObject().put("w", word).put("c", count))
        }
        o.put("words", w)
        return o.toString()
    }

    fun parseJson(json: String): Payload {
        val o = JSONObject(json)
        val p = o.optJSONObject("prefs") ?: JSONObject()
        val map = HashMap<String, Any?>()
        for (k in p.keys()) {
            if (k in PATH_KEYS) continue
            val e = p.optJSONObject(k) ?: continue
            map[k] = when (e.optString("t")) {
                "b" -> e.optBoolean("v")
                "i" -> e.optInt("v")
                "l" -> e.optLong("v")
                "f" -> e.optDouble("v").toFloat()
                "s" -> e.optString("v")
                "set" -> {
                    val arr = e.optJSONArray("v") ?: JSONArray()
                    (0 until arr.length()).map { arr.getString(it) }.toSet()
                }
                else -> continue
            }
        }
        val words = ArrayList<Pair<String, Int>>()
        val w = o.optJSONArray("words") ?: JSONArray()
        for (i in 0 until w.length()) {
            val e = w.optJSONObject(i) ?: continue
            val word = e.optString("w", "")
            if (word.isNotEmpty()) words.add(word to e.optInt("c", 1))
        }
        return Payload(map, words)
    }

    // ------------------------------------------------------------ encrypt

    fun encrypt(plain: String, passphrase: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val blob = salt + iv + body
        return (MAGIC + android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP))
            .toByteArray(Charsets.US_ASCII)
    }

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.length &&
            String(bytes.copyOfRange(0, MAGIC.length), Charsets.US_ASCII) == MAGIC

    /** @throws javax.crypto.AEADBadTagException on a wrong passphrase — GCM
     *  authenticates, so a bad key fails loudly instead of yielding garbage. */
    fun decrypt(bytes: ByteArray, passphrase: String): String {
        val b64 = String(bytes, Charsets.US_ASCII).removePrefix(MAGIC)
        val blob = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        require(blob.size > SALT_BYTES + IV_BYTES) { "backup file is truncated" }
        val salt = blob.copyOfRange(0, SALT_BYTES)
        val iv = blob.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val body = blob.copyOfRange(SALT_BYTES + IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERS, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    // ------------------------------------------------------------- restore

    /**
     * Write restored preferences back. Deliberately does NOT clear existing
     * prefs first: a backup from an older version is missing keys that exist
     * now, and wiping would reset those to defaults rather than leaving the
     * user's current choices alone.
     */
    fun restorePrefs(prefs: Prefs, map: Map<String, Any?>) {
        prefs.restoreAll(map)
    }

    fun describe(context: Context, payload: Payload): String {
        val n = payload.prefs.size
        val w = payload.words.size
        return "$n settings, $w learned words"
    }
}
