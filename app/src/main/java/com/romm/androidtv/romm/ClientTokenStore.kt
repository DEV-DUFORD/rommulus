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
 * **Android 14 IV handling**: On Android 14+, the Keystore rejects caller-supplied
 * GCM IVs during encryption ([InvalidAlgorithmParameterException]). This class
 * delegates IV generation to the Keystore by calling `cipher.init(ENCRYPT_MODE, key)`
 * without a [GCMParameterSpec], then reads back `cipher.iv`. Decryption uses the
 * stored IV with [GCMParameterSpec] (still permitted on all platforms).
 *
 * Token lifecycle: written immediately after foreground authenticated acquisition;
 * cleared together with the matching [com.romm.androidtv.auth.SessionStore] record on explicit sign-out.
 */
class ClientTokenStore(context: Context) : ClientTokenStorage {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the stored token for this server/user scope, or null if none exists. */
    override fun getToken(origin: String, username: String): ClientToken? {
        val scopeKey = makeScopeKey(origin, username)
        val encrypted = prefs.getString("${scopeKey}.enc", null) ?: run {
            Log.d("RommAuthDx", "ClientTokenStore.getToken: absent")
            return null
        }
        val nonceB64 = prefs.getString("${scopeKey}.nonce", null) ?: run {
            Log.d("RommAuthDx", "ClientTokenStore.getToken: nonce absent")
            return null
        }

        val key = try { ensureKey() } catch (e: Exception) {
            Log.w(TAG, "ensureKey failed in getToken; returning null", e)
            return null
        }
        return try {
            val nonce = android.util.Base64.decode(nonceB64, android.util.Base64.NO_WRAP)
            val localCipher = Cipher.getInstance(TRANSFORMATION)
            localCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val decryptedBytes = localCipher.doFinal(
                android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP),
            )
            Log.d("RommAuthDx", "ClientTokenStore.getToken: present=true")
            ClientToken(String(decryptedBytes, UTF_8))
        } catch (_: Exception) {
            // Corrupted or key-rotated; treat as absent.
            Log.d("RommAuthDx", "ClientTokenStore.getToken: decryptFailed")
            null
        }
    }

    /**
     * Encrypts and persists the raw token for this scope. Fails closed: never
     * crashes login, never persists plaintext. Returns a [com.romm.androidtv.auth.TokenPersistResult]
     * so an encryption/commit failure is surfaced (not swallowed) — onboarding
     * treats a non-`Success` result as terminal persistence failure.
     */
    override fun setToken(origin: String, username: String, token: ClientToken): com.romm.androidtv.auth.TokenPersistResult {
        val scopeKey = makeScopeKey(origin, username)
        val encPrefKey = "${scopeKey}.enc"
        val noncePrefKey = "${scopeKey}.nonce"

        // Attempt encryption with current key. If it fails (e.g. Android 14 IV rejection
        // or corrupted keystore entry), delete + recreate the key exactly once and retry.
        var attempt = 0
        while (attempt <= 1) {
            val key = try { ensureKey() } catch (e: Exception) {
                Log.w(TAG, "ensureKey failed in setToken (attempt $attempt); aborting persist", e)
                return com.romm.androidtv.auth.TokenPersistResult.Failure
            }

            try {
                // Use a local cipher instance so init/doFinal operate on the same object.
                val localCipher = Cipher.getInstance(TRANSFORMATION)

                // Android 14+: let Keystore generate the GCM IV. Do NOT pass GCMParameterSpec.
                localCipher.init(Cipher.ENCRYPT_MODE, key)
                val encrypted = localCipher.doFinal(token.raw.toByteArray(UTF_8))

                // Read back the Keystore-generated IV for persistence.
                val generatedIv = localCipher.iv
                if (generatedIv.size !in GCM_IV_MIN_SIZE..GCM_IV_MAX_SIZE) {
                    Log.w(TAG, "Keystore generated IV of unexpected length ${generatedIv.size}; aborting persist")
                    return com.romm.androidtv.auth.TokenPersistResult.Failure
                }

                // Atomic commit: both ciphertext and nonce written together.
                val committed = prefs.edit()
                    .putString(encPrefKey, android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
                    .putString(noncePrefKey, android.util.Base64.encodeToString(generatedIv, android.util.Base64.NO_WRAP))
                    .commit()

                if (!committed) {
                    Log.w(TAG, "SharedPreferences commit failed in setToken; token NOT stored")
                    Log.d("RommAuthDx", "ClientTokenStore.setToken: persistFailed committed=false")
                    return com.romm.androidtv.auth.TokenPersistResult.Failure
                }
                Log.d("RommAuthDx", "ClientTokenStore.setToken: persisted=true")
                return com.romm.androidtv.auth.TokenPersistResult.Success
            } catch (e: Exception) {
                // On first failure, try key recreation. On second, fail safely.
                if (attempt == 0) {
                    Log.w(TAG, "Encryption failed on first attempt; attempting key recreation", e)
                    deleteKeyQuietly()
                } else {
                    // Remove stale partial prefs from a failed write attempt.
                    removePrefPair(encPrefKey, noncePrefKey)
                    Log.w(TAG, "Encryption/persist failed after retry in setToken; token NOT stored", e)
                    return com.romm.androidtv.auth.TokenPersistResult.Failure
                }
            }
            attempt++
        }
        return com.romm.androidtv.auth.TokenPersistResult.Failure
    }

    /** Removes the stored token for this scope (sign-out / session clear). */
    override fun clearToken(origin: String, username: String) {
        val scopeKey = makeScopeKey(origin, username)
        prefs.edit()
            .remove("${scopeKey}.enc")
            .remove("${scopeKey}.nonce")
            .apply()
        Log.d("RommAuthDx", "ClientTokenStore.clearToken: completed")
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

    /** Deletes the keystore key alias quietly (used only for retry logic). */
    private fun deleteKeyQuietly() {
        try {
            val ks = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS)
            }
        } catch (_: Exception) {
            // Ignore; caller will handle the resulting failure.
        }
    }

    private fun createKey(): SecretKey {
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

    /** Removes stale preference pair to avoid orphaned ciphertext/nonce on failure. */
    private fun removePrefPair(encKey: String, nonceKey: String) {
        prefs.edit().remove(encKey).remove(nonceKey).apply()
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
        private const val GCM_TAG_BITS = 128
        // GCM IV length: NIST SP 800-38D recommends 12 bytes; allow a safe range.
        private const val GCM_IV_MIN_SIZE = 12
        private const val GCM_IV_MAX_SIZE = 16
    }
}
