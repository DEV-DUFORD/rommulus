package com.romm.androidtv.romm

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumented tests for [ClientTokenStore]: encrypt/decrypt round-trip,
 * scope isolation, clear behavior, corrupt ciphertext handling, and generated-IV
 * uniqueness (Android 14 Keystore IV delegation).
 * Requires real Android Context (Keystore + SharedPreferences).
 */
@RunWith(AndroidJUnit4::class)
class ClientTokenStoreInstrumentedTest {

    private lateinit var store: ClientTokenStore
    private val origin = "https://romm.example.com"
    private val username = "alice"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        store = ClientTokenStore(context)
        // Start clean
        store.clearAll()
    }

    @Test
    fun setTokenThenGetTokenRoundTripsRawValue() {
        val token = ClientToken("rmm_roundtrip_token_value")
        store.setToken(origin, username, token)

        val retrieved = store.getToken(origin, username)

        assertTrue(retrieved != null)
        assertEquals("rmm_roundtrip_token_value", retrieved!!.raw)
    }

    @Test
    fun getTokenReturnsNullForAbsentScope() {
        val retrieved = store.getToken(origin, username)
        assertNull(retrieved)
    }

    @Test
    fun scopeIsolationDifferentOriginDoesNotLeakToken() {
        store.setToken(origin, username, ClientToken("rmm_token_a"))

        val fromOtherOrigin = store.getToken("https://other-romm.example.com", username)
        assertNull(fromOtherOrigin)

        // Original scope still works
        val fromCorrectScope = store.getToken(origin, username)
        assertEquals("rmm_token_a", fromCorrectScope!!.raw)
    }

    @Test
    fun scopeIsolationDifferentUsernameDoesNotLeakToken() {
        store.setToken(origin, username, ClientToken("rmm_alice"))

        val fromOtherUser = store.getToken(origin, "bob")
        assertNull(fromOtherUser)
    }

    @Test
    fun setTokenOverwritesPreviousValueForSameScope() {
        store.setToken(origin, username, ClientToken("rmm_first"))
        store.setToken(origin, username, ClientToken("rmm_second"))

        val retrieved = store.getToken(origin, username)
        assertEquals("rmm_second", retrieved!!.raw)
    }

    @Test
    fun clearTokenRemovesOnlyMatchingScope() {
        store.setToken(origin, username, ClientToken("rmm_alice"))
        store.setToken(origin, "bob", ClientToken("rmm_bob"))

        store.clearToken(origin, username)

        assertNull(store.getToken(origin, username))
        assertEquals("rmm_bob", store.getToken(origin, "bob")!!.raw)
    }

    @Test
    fun clearAllRemovesEveryStoredToken() {
        store.setToken(origin, username, ClientToken("rmm_a"))
        store.setToken("https://other.com", "bob", ClientToken("rmm_b"))

        store.clearAll()

        assertNull(store.getToken(origin, username))
        assertNull(store.getToken("https://other.com", "bob"))
    }

    @Test
    fun corruptCiphertextReturnsNullInsteadOfCrashing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences(ClientTokenStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)

        // Write garbage directly into the prefs to simulate corruption
        val scopeKey = "${origin.lowercase()}|${username.lowercase()}"
        prefs.edit()
            .putString("${scopeKey}.enc", "not-valid-base64-or-corrupt===")
            .putString("${scopeKey}.nonce", android.util.Base64.encodeToString(ByteArray(12), android.util.Base64.NO_WRAP))
            .apply()

        // Re-create store to pick up the corrupted data
        val freshStore = ClientTokenStore(context)
        val result = freshStore.getToken(origin, username)

        assertNull("Corrupt ciphertext should return null, not throw", result)
    }

    @Test
    fun clientTokenRejectsBlankRawValue() {
        assertThrows(IllegalArgumentException::class.java) {
            ClientToken("")
        }

        assertThrows(IllegalArgumentException::class.java) {
            ClientToken("  ")
        }
    }

    @Test
    fun scopeKeyIsCaseInsensitiveAndTrimmed() {
        store.setToken("  HTTPS://ROMM.EXAMPLE.COM  ", "  Alice  ", ClientToken("rmm_case_test"))

        // Retrieve with different casing/whitespace — should match
        val retrieved = store.getToken("https://romm.example.com", "alice")
        assertEquals("rmm_case_test", retrieved!!.raw)
    }

    @Test
    fun getTokenReturnsNullWhenPrefsMissingButKeyExists() {
        // ensureKey creates the keystore key. Then we delete all prefs entries,
        // simulating a scenario where the key was created but no token was ever stored.
        store.clearAll()

        val retrieved = store.getToken(origin, username)
        assertNull("Should return null when no ciphertext is stored", retrieved)
    }

    /** Each setToken call must produce a unique Keystore-generated IV (nonce). */
    @Test
    fun generatedIvIsUniqueAcrossWrites() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences(ClientTokenStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val scopeKey = "${origin.lowercase()}|${username.lowercase()}"

        store.setToken(origin, username, ClientToken("rmm_iv_test"))
        val nonce1 = prefs.getString("${scopeKey}.nonce", null)
        assertNotNull("First write must persist a nonce", nonce1)

        store.setToken(origin, username, ClientToken("rmm_iv_test_2"))
        val nonce2 = prefs.getString("${scopeKey}.nonce", null)
        assertNotNull("Second write must persist a nonce", nonce2)

        assertNotEquals(
            "Keystore-generated IVs must differ across writes (no IV reuse)",
            nonce1, nonce2
        )
    }

    /** Verify that the persisted ciphertext and nonce are both present after setToken. */
    @Test
    fun setTokenPersistsBothCiphertextAndNonceAtomically() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences(ClientTokenStore.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val scopeKey = "${origin.lowercase()}|${username.lowercase()}"

        store.setToken(origin, username, ClientToken("rmm_atomic_test"))

        val enc = prefs.getString("${scopeKey}.enc", null)
        val nonce = prefs.getString("${scopeKey}.nonce", null)
        assertNotNull("Ciphertext must be persisted", enc)
        assertNotNull("Nonce/IV must be persisted", nonce)
    }
}
