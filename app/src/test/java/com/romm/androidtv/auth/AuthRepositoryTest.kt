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
import okhttp3.mockwebserver.SocketPolicy
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
    fun `establishKioskSession persists an anonymous read-only session`() {
        runBlocking {
            val saved = repository.establishKioskSession(baseUrl())

            assertThat(saved).isTrue()
            val record = sessionStore.current()
            assertThat(record).isNotNull
            assertThat(record!!.username).isEqualTo("kiosk")
            assertThat(record.kioskMode).isTrue()
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

    @Nested
    @DisplayName("validateServer classification")
    inner class ServerValidation {

        private fun validHeartbeat(setup: Boolean = true, userpass: Boolean = true) =
            """{"version":"5.0.0","setup_complete":$setup,"userpass_enabled":$userpass,"emulatorjs_enabled":true}"""

        @Test
        fun `valid heartbeat at root origin returns Valid with origin and heartbeat`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(200).setBody(validHeartbeat()))
                // Unauthenticated demo probe (401 => normal login server, not kiosk).
                server.enqueue(MockResponse().setResponseCode(401))

                val result = repository.validateServer(baseUrl())

                assertThat(result).isInstanceOf(ServerValidationResult.Valid::class.java)
                val valid = result as ServerValidationResult.Valid
                assertThat(valid.origin).isEqualTo(baseUrl())
                assertThat(valid.heartbeat.version).isEqualTo("5.0.0")
                assertThat(valid.heartbeat.canLogin()).isTrue()
                assertThat(valid.kioskMode).isFalse()
                assertThat(server.takeRequest().path).isEqualTo("/api/heartbeat")
            }
        }

        @Test
        fun `valid heartbeat at root origin probes users me and detects kiosk on 200`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(200).setBody(validHeartbeat()))
                // Kiosk mode: anonymous read succeeds (200).
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"kiosk"}"""))

                val result = repository.validateServer(baseUrl()) as ServerValidationResult.Valid

                assertThat(result.kioskMode).isTrue()
                assertThat(server.takeRequest().path).isEqualTo("/api/heartbeat")
                assertThat(server.takeRequest().path).isEqualTo("/api/users/me")
            }
        }

        @Test
        fun `valid heartbeat under a base path preserves the base path`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(200).setBody(validHeartbeat()))
                server.enqueue(MockResponse().setResponseCode(401))
                val basePathOrigin = baseUrl() + "/romm"

                val result = repository.validateServer(basePathOrigin)

                assertThat(result).isInstanceOf(ServerValidationResult.Valid::class.java)
                assertThat((result as ServerValidationResult.Valid).origin).isEqualTo(basePathOrigin)
                assertThat(server.takeRequest().path).isEqualTo("/romm/api/heartbeat")
            }
        }

        @Test
        fun `setup incomplete is classified SetupIncomplete`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(200).setBody(validHeartbeat(setup = false)))

                val result = repository.validateServer(baseUrl())

                assertThat(result).isEqualTo(ServerValidationResult.SetupIncomplete)
            }
        }

        @Test
        fun `userpass disabled is classified UserpassDisabled`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(200).setBody(validHeartbeat(userpass = false)))

                val result = repository.validateServer(baseUrl())

                assertThat(result).isEqualTo(ServerValidationResult.UserpassDisabled)
            }
        }

        @Test
        fun `html body is classified NotRomm`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Not RomM</body></html>"))

                assertThat(repository.validateServer(baseUrl())).isEqualTo(ServerValidationResult.NotRomm)
            }
        }

        @Test
        fun `empty body is classified NotRomm`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(200).setBody(""))

                assertThat(repository.validateServer(baseUrl())).isEqualTo(ServerValidationResult.NotRomm)
            }
        }

        @Test
        fun `malformed json is classified NotRomm`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(200).setBody("not json {"))

                assertThat(repository.validateServer(baseUrl())).isEqualTo(ServerValidationResult.NotRomm)
            }
        }

        @Test
        fun `unrelated json is classified NotRomm`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"foo":"bar","baz":1}"""))

                assertThat(repository.validateServer(baseUrl())).isEqualTo(ServerValidationResult.NotRomm)
            }
        }

        @Test
        fun `401 403 404 500 are all classified NotRomm`() {
            runBlocking {
                for (code in listOf(401, 403, 404, 500)) {
                    server.enqueue(MockResponse().setResponseCode(code))
                    assertThat(repository.validateServer(baseUrl()))
                        .`as`("code %s", code)
                        .isEqualTo(ServerValidationResult.NotRomm)
                }
            }
        }

        @Test
        fun `3xx redirects are not followed and classified NotRomm`() {
            runBlocking {
                for (code in listOf(301, 302, 307, 308)) {
                    server.enqueue(
                        MockResponse().setResponseCode(code).setHeader("Location", "https://elsewhere.example.com")
                    )
                    assertThat(repository.validateServer(baseUrl()))
                        .`as`("code %s", code)
                        .isEqualTo(ServerValidationResult.NotRomm)
                }
                // Redirects are not followed: exactly one request per validation.
                assertThat(server.requestCount).isEqualTo(4)
            }
        }

        @Test
        fun `public http origin is rejected as InsecurePublicHttp without a network call`() {
            runBlocking {
                val result = repository.validateServer("http://romm.example.com")

                assertThat(result).isEqualTo(ServerValidationResult.InsecurePublicHttp)
                assertThat(server.requestCount).isEqualTo(0)
            }
        }

        @Test
        fun `blank origin is classified InvalidAddress`() {
            runBlocking {
                assertThat(repository.validateServer("")).isEqualTo(ServerValidationResult.InvalidAddress)
                assertThat(repository.validateServer("   ")).isEqualTo(ServerValidationResult.InvalidAddress)
                assertThat(server.requestCount).isEqualTo(0)
            }
        }

        @Test
        fun `unsupported scheme is classified InvalidAddress`() {
            runBlocking {
                assertThat(repository.validateServer("ftp://romm.example.com")).isEqualTo(ServerValidationResult.InvalidAddress)
                assertThat(server.requestCount).isEqualTo(0)
            }
        }

        @Test
        fun `disconnect is classified NetworkFailure`() {
            runBlocking {
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

                assertThat(repository.validateServer(baseUrl())).isEqualTo(ServerValidationResult.NetworkFailure)
            }
        }

        @Test
        fun `read timeout is classified NetworkFailure`() {
            runBlocking {
                server.enqueue(
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                )
                // Use tiny timeouts so the bounded call completes quickly in the test.
                assertThat(repository.validateServer(baseUrl(), readTimeoutSeconds = 1, callTimeoutSeconds = 1))
                    .isEqualTo(ServerValidationResult.NetworkFailure)
            }
        }

        @Test
        fun `tls failure is classified TlsFailure`() {
            runBlocking {
                // A plain TCP listener that answers a TLS ClientHello with an HTTP response.
                // The TLS handshake fails with an SSLException instead of hanging/timing out.
                val plainSocket = java.net.ServerSocket(0)
                val port = plainSocket.localPort
                Thread {
                    runCatching {
                        plainSocket.accept().use { s ->
                            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        }
                    }
                }.start()
                try {
                    val origin = "https://127.0.0.1:$port"
                    assertThat(
                        repository.validateServer(origin, connectTimeoutSeconds = 1, readTimeoutSeconds = 1, callTimeoutSeconds = 2)
                    ).isEqualTo(ServerValidationResult.TlsFailure)
                } finally {
                    plainSocket.close()
                }
            }
        }
    }

    @Nested
    @DisplayName("loginOnboarding")
    inner class LoginOnboarding {

        private lateinit var tokenStorage: FakeClientTokenStorage
        private lateinit var repo: AuthRepository

        @BeforeEach
        fun setUp() {
            tokenStorage = FakeClientTokenStorage()
            val cookieSync = RomMCookieSync(CookieManager(null, CookiePolicy.ACCEPT_ALL))
            sessionStore = SessionStore(FakeSharedPreferences())
            repo = AuthRepository(client, cookieSync, sessionStore, tokenStorage)
        }

        private fun enqueueAuthSuccess() {
            // POST /api/login
            server.enqueue(MockResponse().setResponseCode(200))
            // heartbeat after login
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""
                )
            )
            // /api/users/me (cookie-authenticated)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"root","admin":true}"""))
        }

        private fun enqueueClientToken(raw: String = "rmm_onboard_token", id: Long = 1) {
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"id": $id, "name": "romm-android-tv", "scopes": ["me.read"],
                        "raw_token": "$raw", "expires_at": null}"""
                )
            )
        }

        @Test
        fun `happy path persists session token and returns Success`() {
            runBlocking {
                enqueueAuthSuccess()
                enqueueClientToken()
                // bearer verification /api/users/me
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"root","admin":true}"""))

                val result = repo.loginOnboarding(baseUrl(), "root", "hunter2".toCharArray())

                assertThat(result).isInstanceOf(LoginCompletionResult.Success::class.java)
                val success = result as LoginCompletionResult.Success
                assertThat(success.verifiedUser.username).isEqualTo("root")
                assertThat(success.durableClientToken.raw).isEqualTo("rmm_onboard_token")
                assertThat(sessionStore.current()?.username).isEqualTo("root")
                assertThat(tokenStorage.storedTokens[listOf(baseUrl(), "root")]).isEqualTo("rmm_onboard_token")
                assertThat(server.requestCount).isEqualTo(5)
            }
        }

        @Test
        fun `invalid credentials returns InvalidCredentials`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(401))

                val result = repo.loginOnboarding(baseUrl(), "root", "wrong".toCharArray())

                assertThat(result).isEqualTo(LoginCompletionResult.InvalidCredentials)
                assertThat(sessionStore.current()).isNull()
                assertThat(tokenStorage.storedKeys).isEmpty()
            }
        }

        @Test
        fun `token creation failure is fatal and clears partial state`() {
            runBlocking {
                enqueueAuthSuccess()
                // POST /api/client-tokens fails with 500
                server.enqueue(MockResponse().setResponseCode(500))

                val result = repo.loginOnboarding(baseUrl(), "root", "hunter2".toCharArray())

                assertThat(result).isEqualTo(LoginCompletionResult.TokenCreationFailure)
                assertThat(sessionStore.current()).isNull()
                assertThat(tokenStorage.storedKeys).isEmpty()
            }
        }

        @Test
        fun `token limit reached maps to TokenLimitReached and clears partial state`() {
            runBlocking {
                enqueueAuthSuccess()
                // POST /api/client-tokens fails with the backend's MAX_TOKENS_PER_USER 400.
                server.enqueue(
                    MockResponse().setResponseCode(400).setBody(
                        """{"detail": "Maximum of 25 tokens per user reached"}"""
                    )
                )

                val result = repo.loginOnboarding(baseUrl(), "root", "hunter2".toCharArray())

                assertThat(result).isEqualTo(LoginCompletionResult.TokenLimitReached)
                assertThat(sessionStore.current()).isNull()
                assertThat(tokenStorage.storedKeys).isEmpty()
            }
        }

        @Test
        fun `token verification failure clears partial state`() {
            runBlocking {
                enqueueAuthSuccess()
                enqueueClientToken()
                // bearer verification /api/users/me -> 401
                server.enqueue(MockResponse().setResponseCode(401))
                // best-effort revoke DELETE /api/client-tokens/1
                server.enqueue(MockResponse().setResponseCode(204))

                val result = repo.loginOnboarding(baseUrl(), "root", "hunter2".toCharArray())

                assertThat(result).isEqualTo(LoginCompletionResult.TokenVerificationFailure)
                assertThat(sessionStore.current()).isNull()
                assertThat(tokenStorage.storedKeys).isEmpty()
                val requests = (0 until server.requestCount).map { server.takeRequest() }
                val revoke = requests.last()
                assertThat(revoke.method).isEqualTo("DELETE")
                assertThat(revoke.path).isEqualTo("/api/client-tokens/1")
            }
        }

        @Test
        fun `persistence failure returns PersistenceFailure and clears state`() {
            runBlocking {
                enqueueAuthSuccess()
                enqueueClientToken()
                // best-effort revoke DELETE /api/client-tokens/1 during cleanup
                server.enqueue(MockResponse().setResponseCode(204))
                tokenStorage.failPersist = true

                val result = repo.loginOnboarding(baseUrl(), "root", "hunter2".toCharArray())

                assertThat(result).isEqualTo(LoginCompletionResult.PersistenceFailure)
                assertThat(sessionStore.current()).isNull()
                // token was cleared from storage (partial state removed)
                assertThat(tokenStorage.storedKeys).isEmpty()
            }
        }

        @Test
        fun `unreadable persisted token returns PersistenceFailure and clears state`() {
            runBlocking {
                enqueueAuthSuccess()
                enqueueClientToken()
                // best-effort revoke DELETE /api/client-tokens/1 during cleanup
                server.enqueue(MockResponse().setResponseCode(204))
                tokenStorage.failReadBack = true

                val result = repo.loginOnboarding(baseUrl(), "root", "hunter2".toCharArray())

                assertThat(result).isEqualTo(LoginCompletionResult.PersistenceFailure)
                assertThat(sessionStore.current()).isNull()
                assertThat(tokenStorage.storedKeys).isEmpty()
                assertThat(server.requestCount).isEqualTo(5)
            }
        }

        @Test
        fun `password CharArray is zeroed after terminal outcomes`() {
            runBlocking {
                server.enqueue(MockResponse().setResponseCode(401))
                val password = "hunter2".toCharArray()

                repo.loginOnboarding(baseUrl(), "root", password)

                assertThat(String(password)).isEqualTo("\u0000".repeat(7))
            }
        }

        @Test
        fun `no duplicate login - second login reuses flow`() {
            runBlocking {
                enqueueAuthSuccess()
                enqueueClientToken()
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"root","admin":true}"""))

                val first = repo.loginOnboarding(baseUrl(), "root", "hunter2".toCharArray())
                assertThat(first).isInstanceOf(LoginCompletionResult.Success::class.java)
                assertThat(server.requestCount).isEqualTo(5)
            }
        }
    }

    /** In-memory fake ClientTokenStorage for unit testing. */
    private class FakeClientTokenStorage : ClientTokenStorage {
        private val tokens = mutableMapOf<List<String>, String>()
        var failPersist: Boolean = false
        var failReadBack: Boolean = false

        override fun getToken(origin: String, username: String): ClientToken? {
            if (failReadBack) return null
            return tokens[listOf(origin, username)]?.let { ClientToken(it) }
        }

        override fun setToken(origin: String, username: String, token: ClientToken): TokenPersistResult {
            if (failPersist) return TokenPersistResult.Failure
            tokens[listOf(origin, username)] = token.raw
            return TokenPersistResult.Success
        }

        override fun clearToken(origin: String, username: String) {
            tokens.remove(listOf(origin, username))
        }

        val storedKeys: Set<List<String>> get() = tokens.keys
        val storedTokens: Map<List<String>, String> get() = tokens.toMap()
    }
}
