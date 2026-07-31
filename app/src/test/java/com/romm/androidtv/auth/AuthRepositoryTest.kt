package com.romm.androidtv.auth

import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.network.AuthError
import com.romm.androidtv.network.AuthFlowResult
import com.romm.androidtv.network.HeartbeatCallResult
import com.romm.androidtv.network.RomMCookieSync
import com.romm.androidtv.romm.ClientToken
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.CookieManager
import java.net.CookiePolicy

class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: okhttp3.OkHttpClient
    private lateinit var sessionStore: SessionStore
    private lateinit var repository: AuthRepository

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        client = okhttp3.OkHttpClient.Builder().build()
        val cookieSync = RomMCookieSync(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        sessionStore = SessionStore(FakeSharedPreferences())
        repository = AuthRepository(client, cookieSync, sessionStore)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    @Test
    fun `login records a session on success`() {
        runBlocking {
            // POST /api/login
            server.enqueue(MockResponse().setResponseCode(200))
            // heartbeat after login
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""
                )
            )
            // /api/users/me
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"root","admin":true}"""))

            val result = repository.login(baseUrl(), "root", "hunter2".toCharArray())

            assertThat(result).isInstanceOf(AuthFlowResult.Success::class.java)
            val stored = sessionStore.current()
            assertThat(stored).isNotNull
            assertThat(stored!!.origin).isEqualTo(baseUrl())
            assertThat(stored.username).isEqualTo("root")
        }
    }

    @Test
    fun `login does not record a session on failure`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = repository.login(baseUrl(), "root", "wrong".toCharArray())

            assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
            assertThat((result as AuthFlowResult.Failure).error).isEqualTo(AuthError.INVALID_CREDENTIALS)
            assertThat(sessionStore.current()).isNull()
        }
    }

    @Test
    fun `verifySession records a session on success`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"root","admin":true}"""))
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""
                )
            )

            val result = repository.verifySession(baseUrl())

            assertThat(result).isInstanceOf(AuthFlowResult.Success::class.java)
            assertThat(sessionStore.current()?.username).isEqualTo("root")
        }
    }

    @Test
    fun `verifySession does not record a session on expired session`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = repository.verifySession(baseUrl())

            assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
            assertThat(sessionStore.current()).isNull()
        }
    }

    @Test
    fun `checkHeartbeat delegates to executeHeartbeat`() {
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""
                )
            )

            val result = repository.checkHeartbeat(baseUrl())

            assertThat(result).isInstanceOf(HeartbeatCallResult.Success::class.java)
        }
    }

    @Nested
    @DisplayName("ClientToken acquisition and persistence")
    inner class ClientTokenAcquisition {

        private lateinit var tokenStorage: FakeClientTokenStorage
        private lateinit var repoWithTokens: AuthRepository

        @BeforeEach
        fun setUp() {
            val cookieSync = RomMCookieSync(CookieManager(null, CookiePolicy.ACCEPT_ALL))
            sessionStore = SessionStore(FakeSharedPreferences())
            tokenStorage = FakeClientTokenStorage()
            repoWithTokens = AuthRepository(client, cookieSync, sessionStore, tokenStorage)
        }

        @Test
        fun `login acquires and persists client token on success`() {
            runBlocking {
                // POST /api/login
                server.enqueue(MockResponse().setResponseCode(200))
                // heartbeat
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""
                    )
                )
                // /api/users/me
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"root","admin":true}"""))
                // POST /api/client-tokens
                server.enqueue(
                    MockResponse().setResponseCode(201).setBody(
                        """{"id": 1, "name": "romm-android-tv", "scopes": ["assets","device"],
                            "raw_token": "rmm_testtoken123", "expires_at": null}"""
                    )
                )

                val result = repoWithTokens.login(baseUrl(), "root", "hunter2".toCharArray())

                assertThat(result).isInstanceOf(AuthFlowResult.Success::class.java)
                assertThat(tokenStorage.storedKeys).containsExactly(listOf(baseUrl(), "root"))
                assertThat(tokenStorage.storedTokens[listOf(baseUrl(), "root")]).isEqualTo("rmm_testtoken123")
            }
        }

        @Test
        fun `verifySession acquires and persists client token on success`() {
            runBlocking {
                // verifyExistingSession: /api/users/me
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"root","admin":true}"""))
                // heartbeat
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""
                    )
                )
                // POST /api/client-tokens
                server.enqueue(
                    MockResponse().setResponseCode(201).setBody(
                        """{"id": 1, "name": "romm-android-tv", "scopes": ["assets","device"],
                            "raw_token": "rmm_verifytoken456", "expires_at": null}"""
                    )
                )

                val result = repoWithTokens.verifySession(baseUrl())

                assertThat(result).isInstanceOf(AuthFlowResult.Success::class.java)
                assertThat(tokenStorage.storedKeys).containsExactly(listOf(baseUrl(), "root"))
            }
        }

        @Test
        fun `login replaces stale token on explicit sign-in`() {
            runBlocking {
                // Pre-seed a stale token
                tokenStorage.setToken(baseUrl(), "root", ClientToken("rmm_stale"))

                // POST /api/login
                server.enqueue(MockResponse().setResponseCode(200))
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""
                    )
                )
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"root","admin":true}"""))
                // POST /api/client-tokens — explicit login always replaces stale token
                server.enqueue(
                    MockResponse().setResponseCode(201).setBody(
                        """{"id": 2, "name": "romm-android-tv", "scopes": ["assets","device"],
                            "raw_token": "rmm_fresh_after_login", "expires_at": null}"""
                    )
                )

                val result = repoWithTokens.login(baseUrl(), "root", "hunter2".toCharArray())

                assertThat(result).isInstanceOf(AuthFlowResult.Success::class.java)
                // Token was replaced with fresh one (not reused stale token)
                assertThat(tokenStorage.storedTokens[listOf(baseUrl(), "root")]).isEqualTo("rmm_fresh_after_login")
                // 4 requests: login, heartbeat, users/me, client-tokens
                assertThat(server.requestCount).isEqualTo(4)
            }
        }

        @Test
        fun `verifySession does not churn existing token`() {
            runBlocking {
                // Pre-seed a stored token
                tokenStorage.setToken(baseUrl(), "root", ClientToken("rmm_existing"))

                // verifyExistingSession: /api/users/me + heartbeat (no client-tokens call expected)
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"root","admin":true}"""))
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""
                    )
                )

                val result = repoWithTokens.verifySession(baseUrl())

                assertThat(result).isInstanceOf(AuthFlowResult.Success::class.java)
                // Token preserved unchanged (no churn on verification)
                assertThat(tokenStorage.storedTokens[listOf(baseUrl(), "root")]).isEqualTo("rmm_existing")
                // Only 2 requests: users/me, heartbeat — no client-tokens call
                assertThat(server.requestCount).isEqualTo(2)
            }
        }

        @Test
        fun `login failure does not acquire or persist token`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(401))

                val result = repoWithTokens.login(baseUrl(), "root", "wrong".toCharArray())

                assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
                assertThat(tokenStorage.storedKeys).isEmpty()
            }
        }

        @Test
        fun `client token acquisition failure does not break login`() {
            runBlocking {
                // POST /api/login
                server.enqueue(MockResponse().setResponseCode(200))
                // heartbeat
                server.enqueue(
                    MockResponse().setResponseCode(200).setBody(
                        """{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""
                    )
                )
                // /api/users/me
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"root","admin":true}"""))
                // POST /api/client-tokens — fails with 500
                server.enqueue(MockResponse().setResponseCode(500))

                val result = repoWithTokens.login(baseUrl(), "root", "hunter2".toCharArray())

                // Login still succeeds despite token acquisition failure
                assertThat(result).isInstanceOf(AuthFlowResult.Success::class.java)
                assertThat(sessionStore.current()).isNotNull
            }
        }

        @Test
        fun `clearClientTokenForCurrentSession removes the stored token`() {
            runBlocking {
                tokenStorage.setToken(baseUrl(), "root", ClientToken("rmm_test"))
                assertThat(tokenStorage.storedKeys).containsExactly(listOf(baseUrl(), "root"))

                repoWithTokens.clearClientTokenForCurrentSession(baseUrl(), "root")

                assertThat(tokenStorage.storedKeys).isEmpty()
            }
        }

        @Test
        fun `forceReconcileClientToken replaces token after clearing stale`() {
            runBlocking {
                // Pre-seed a stale token
                tokenStorage.setToken(baseUrl(), "root", ClientToken("rmm_stale"))

                // POST /api/client-tokens — force reconcile acquires fresh token
                server.enqueue(
                    MockResponse().setResponseCode(201).setBody(
                        """{"id": 3, "name": "romm-android-tv", "scopes": ["assets.read","assets.write","devices.read","devices.write"],
                            "raw_token": "rmm_reconciled", "expires_at": null}"""
                    )
                )

                val success = repoWithTokens.forceReconcileClientToken(baseUrl(), "root")

                assertThat(success).isTrue()
                assertThat(tokenStorage.storedTokens[listOf(baseUrl(), "root")]).isEqualTo("rmm_reconciled")
            }
        }

        @Test
        fun `forceReconcileClientToken returns false on server failure`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(401))

                val success = repoWithTokens.forceReconcileClientToken(baseUrl(), "root")

                assertThat(success).isFalse()
            }
        }

        @Test
        fun `ensureClientTokenExists returns true when token already stored`() {
            runBlocking {
                tokenStorage.setToken(baseUrl(), "root", ClientToken("rmm_existing"))

                val exists = repoWithTokens.ensureClientTokenExists(baseUrl(), "root")

                assertThat(exists).isTrue()
                // No server call made — existing token reused
                assertThat(server.requestCount).isEqualTo(0)
            }
        }

        @Test
        fun `ensureClientTokenExists acquires missing token`() {
            runBlocking {
                server.enqueue(
                    MockResponse().setResponseCode(201).setBody(
                        """{"id": 4, "name": "romm-android-tv", "scopes": ["assets.read","assets.write","devices.read","devices.write"],
                            "raw_token": "rmm_ensured", "expires_at": null}"""
                    )
                )

                val exists = repoWithTokens.ensureClientTokenExists(baseUrl(), "root")

                assertThat(exists).isTrue()
                assertThat(tokenStorage.storedTokens[listOf(baseUrl(), "root")]).isEqualTo("rmm_ensured")
            }
        }

        @Test
        fun `clearExpiredSession clears both SessionStore and token`() {
            sessionStore.save(baseUrl(), "root")
            tokenStorage.setToken(baseUrl(), "root", ClientToken("rmm_to_clear"))

            repoWithTokens.clearExpiredSession(baseUrl(), "root")

            assertThat(sessionStore.current()).isNull()
            assertThat(tokenStorage.storedKeys).isEmpty()
        }

        @Test
        fun `clearExpiredSession with null username still clears SessionStore`() {
            sessionStore.save(baseUrl(), null)

            repoWithTokens.clearExpiredSession(baseUrl(), "")

            assertThat(sessionStore.current()).isNull()
        }
    }

    /** In-memory fake ClientTokenStorage for unit testing. */
    private class FakeClientTokenStorage : ClientTokenStorage {
        private val tokens = mutableMapOf<List<String>, String>()

        override fun getToken(origin: String, username: String): ClientToken? {
            return tokens[listOf(origin, username)]?.let { ClientToken(it) }
        }

        override fun setToken(origin: String, username: String, token: ClientToken) {
            tokens[listOf(origin, username)] = token.raw
        }

        override fun clearToken(origin: String, username: String) {
            tokens.remove(listOf(origin, username))
        }

        val storedKeys: Set<List<String>> get() = tokens.keys
        val storedTokens: Map<List<String>, String> get() = tokens.toMap()
    }
}
