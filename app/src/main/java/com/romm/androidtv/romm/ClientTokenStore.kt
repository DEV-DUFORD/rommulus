package com.romm.androidtv.romm

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.romm.androidtv.auth.ClientTokenStorage
import java.nio.charset.StandardCharsets.UTF_8
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Server- and user-scoped AES-GCM encrypted store for RomM ClientToken material.
 *
 * Uses the Android Keystore to hold a single AES key per device install. Each
 * token value (raw string) is encrypted with AES/GCM/NoPadding under that key,
 * and the resulting ciphertext + nonce (IV) are persisted as base64 strings in
 * [SharedPreferences]. The Keystore key itself can never be extracted from the
 * device — only used for encrypt/decrypt operations — so a rooted device cannot
 * read token material without re-encrypting it.
 *
 * Token lifecycle: written immediately after foreground authenticated acquisition;
 * cleared together with the matching [com.romm.androidtv.auth.SessionStore] record on explicit sign-out.
 */
class ClientTokenStore(context: Context) : ClientTokenStorage {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher get() = Cipher.getInstance(TRANSFORMATION)

    /** Returns the stored token for this server/user scope, or null if none exists. */
    override fun getToken(origin: String, username: String): ClientToken? {
        val scopeKey = makeScopeKey(origin, username)
        val encrypted = prefs.getString("${scopeKey}.enc", null) ?: return null
        val nonceB64 = prefs.getString("${scopeKey}.nonce", null) ?: return null

        val key = try { ensureKey() } catch (e: Exception) {
            Log.w(TAG, "ensureKey failed in getToken; returning null", e)
            return null
        }
        return try {
            val nonce = android.util.Base64.decode(nonceB64, android.util.Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val decryptedBytes = cipher.doFinal(
                android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP),
            )
            ClientToken(String(decryptedBytes, UTF_8))
        } catch (_: Exception) {
            // Corrupted or key-rotated; treat as absent.
            null
        }
    }

    /** Encrypts and persists the raw token for this scope. Fails closed: never crashes login, never persists plaintext. */
    override fun setToken(origin: String, username: String, token: ClientToken) {
        val key = try { ensureKey() } catch (e: Exception) {
            Log.w(TAG, "ensureKey failed in setToken; aborting persist", e)
            return
        }

        try {
            val scopeKey = makeScopeKey(origin, username)
            val nonce = ByteArray(NONCE_BYTES)
            appContext.secureRandom().nextBytes(nonce)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val encrypted = cipher.doFinal(token.raw.toByteArray(UTF_8))

            prefs.edit()
                .putString("${scopeKey}.enc", android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
                .putString("${scopeKey}.nonce", android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            // Cipher failure after ensureKey succeeded; do not persist plaintext.
            Log.w(TAG, "Encryption/persist failed in setToken; token NOT stored", e)
        }
    }

    /** Removes the stored token for this scope (sign-out / session clear). */
    override fun clearToken(origin: String, username: String) {
        val scopeKey = makeScopeKey(origin, username)
        prefs.edit()
            .remove("${scopeKey}.enc")
            .remove("${scopeKey}.nonce")
            .apply()
    }

    /** Removes all stored tokens (full data reset). */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun ensureKey(): SecretKey {
        val ks = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }

        // Fast path: existing key is present and retrievable.
        if (ks.containsAlias(KEY_ALIAS)) {
            try {
                val existing = ks.getKey(KEY_ALIAS, null)
                if (existing is SecretKey) return existing
            } catch (_: Exception) {
                // Alias exists but getKey threw (e.g. UnrecoverableKeyException,
                // or the keystore entry is corrupted/unreadable). Attempt cleanup.
            }

            // Alias exists but isn't a usable SecretKey — delete corrupted entry
            // so createKey() can generate a fresh one.
            try { ks.deleteEntry(KEY_ALIAS) } catch (_: Exception) {
                // If deletion fails, createKey will also fail; let that exception surface.
            }
        }

        return createKey()
    }

    private fun createKey(): SecretKey {
        // NOTE: Do NOT deleteEntry before generating. On Android 14 Google TV physical
        // hardware (and other HW-backed keystores), deleting an alias can leave the
        // AndroidKeyStore provider in an inconsistent state, causing subsequent
        // KeyGenerator.generateKey() to throw IllegalStateException: Not initialized.
        // Instead, rely on generateKey() which creates a fresh entry for a new alias,
        // or throws if one already exists (handled by caller).

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(KEY_SIZE_BITS)
            // Keystore key is not user-authenticated; worker runs in background.
        }.build()

        val kg = KeyGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
        kg.init(spec)
        return kg.generateKey()
    }

    private fun makeScopeKey(origin: String, username: String): String =
        "${sanitize(origin)}|${sanitize(username)}"

    private fun sanitize(raw: String): String = raw.trim().lowercase()

    companion object {
        private const val TAG = "ClientTokenStore"
        const val PREFS_NAME = "romm_client_tokens"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALIAS = "romm_token_key"
        private const val KEY_SIZE_BITS = 256
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128

        private fun Context.secureRandom(): java.security.SecureRandom =
            java.security.SecureRandom()
    }
}
