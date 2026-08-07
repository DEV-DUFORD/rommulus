package com.romm.androidtv.auth

import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.romm.ClientToken
import com.romm.androidtv.romm.DeviceIdentityStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class QrLoginRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var sessionStore: SessionStore
    private lateinit var tokenStorage: FakeTokenStorage
    private lateinit var identityStore: DeviceIdentityStore
    private lateinit var repository: QrLoginRepository

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessionStore = SessionStore(FakeSharedPreferences())
        tokenStorage = FakeTokenStorage()
        identityStore = DeviceIdentityStore(FakeSharedPreferences())
        repository = QrLoginRepository(
            client = OkHttpClient(),
            sessionStore = sessionStore,
            tokenStorage = tokenStorage,
            identityStore = identityStore,
            deviceName = "Google TV Streamer",
            clientVersion = "0.1.0",
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `approved pairing durably adopts token session and bound device`() = runBlocking {
        enqueueInit()
        val start = repository.start(origin()) as QrLoginStartResult.Ready
        enqueueApproved(RommClientTokenScopes.FOREGROUND_NATIVE)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"alice"}"""))

        val result = repository.poll(origin(), start.session)

        assertThat(result).isInstanceOf(QrLoginPollResult.Success::class.java)
        assertThat(tokenStorage.getToken(origin(), "alice")?.raw).isEqualTo("rmm_paired")
        assertThat(sessionStore.current()?.username).isEqualTo("alice")
        assertThat(identityStore.cachedDeviceId(origin(), "alice")).isEqualTo("device-1")
        assertThat(identityStore.installationId(origin(), "alice")).isEqualTo(start.session.installationId)
    }

    @Test
    fun `missing approved scope is rejected without local credentials`() = runBlocking {
        enqueueInit()
        val start = repository.start(origin()) as QrLoginStartResult.Ready
        enqueueApproved(listOf("me.read"))

        val result = repository.poll(origin(), start.session)

        assertThat(result).isEqualTo(QrLoginPollResult.InsufficientScopes)
        assertThat(sessionStore.current()).isNull()
        assertThat(tokenStorage.tokens).isEmpty()
    }

    private fun enqueueInit() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """
                {
                  "device_code":"device-code",
                  "user_code":"ABCD1234",
                  "verification_path_complete":"/pair/device?user_code=ABCD1234",
                  "expires_in":600,
                  "interval":5
                }
                """.trimIndent(),
            ),
        )
    }

    private fun enqueueApproved(scopes: List<String>) {
        val scopeJson = scopes.joinToString(",") { "\"$it\"" }
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"rmm_paired","device_id":"device-1","scopes":[$scopeJson]}""",
            ),
        )
    }

    private fun origin() = server.url("/").toString().removeSuffix("/")

    private class FakeTokenStorage : ClientTokenStorage {
        val tokens = mutableMapOf<Pair<String, String>, ClientToken>()

        override fun getToken(origin: String, username: String): ClientToken? =
            tokens[origin to username]

        override fun setToken(
            origin: String,
            username: String,
            token: ClientToken,
        ): TokenPersistResult {
            tokens[origin to username] = token
            return TokenPersistResult.Success
        }

        override fun clearToken(origin: String, username: String) {
            tokens.remove(origin to username)
        }
    }
}
