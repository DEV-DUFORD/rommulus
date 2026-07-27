package com.romm.androidtv.network

import com.romm.androidtv.model.HeartbeatError
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Auth flow sequencing and error classification")
class AuthFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var client: okhttp3.OkHttpClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        // Use a standalone client for tests (no RomM cookie sync — too Android-dependent)
        client = okhttp3.OkHttpClient.Builder().build()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String {
        return server.url("/").toString().removeSuffix("/")
    }

    @Nested
    @DisplayName("executeHeartbeat() — success cases")
    inner class HeartbeatSuccess {
        @Test
        @DisplayName("parses valid heartbeat response")
        fun `valid heartbeat`() {
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody("""{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""))

            val result = executeHeartbeat(client, baseUrl())
            assertThat(result).isInstanceOf(HeartbeatCallResult.Success::class.java)
            val success = result as HeartbeatCallResult.Success
            assertThat(success.response.version).isEqualTo("5.0.0")
            assertThat(success.response.setupComplete).isTrue()
            assertThat(success.response.userpassEnabled).isTrue()
        }
    }

    @Nested
    @DisplayName("executeHeartbeat() — error classification")
    inner class HeartbeatErrors {
        @Test
        @DisplayName("401 returns HTTP_ERROR")
        fun `http 401`() {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = executeHeartbeat(client, baseUrl())
            assertThat(result).isInstanceOf(HeartbeatCallResult.Failure::class.java)
            val failure = result as HeartbeatCallResult.Failure
            assertThat(failure.error).isEqualTo(HeartbeatError.HTTP_ERROR)
            assertThat(failure.httpCode).isEqualTo(401)
        }

        @Test
        @DisplayName("500 returns HTTP_ERROR")
        fun `http 500`() {
            server.enqueue(MockResponse().setResponseCode(500))

            val result = executeHeartbeat(client, baseUrl())
            assertThat(result).isInstanceOf(HeartbeatCallResult.Failure::class.java)
            val failure = result as HeartbeatCallResult.Failure
            assertThat(failure.error).isEqualTo(HeartbeatError.HTTP_ERROR)
            assertThat(failure.httpCode).isEqualTo(500)
        }

        @Test
        @DisplayName("empty body returns PARSE_ERROR")
        fun `empty body`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(""))

            val result = executeHeartbeat(client, baseUrl())
            assertThat(result).isInstanceOf(HeartbeatCallResult.Failure::class.java)
            val failure = result as HeartbeatCallResult.Failure
            assertThat(failure.error).isEqualTo(HeartbeatError.PARSE_ERROR)
        }

        @Test
        @DisplayName("garbage body returns PARSE_ERROR")
        fun `garbage body`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

            val result = executeHeartbeat(client, baseUrl())
            assertThat(result).isInstanceOf(HeartbeatCallResult.Failure::class.java)
            val failure = result as HeartbeatCallResult.Failure
            assertThat(failure.error).isEqualTo(HeartbeatError.PARSE_ERROR)
        }

        @Test
        @DisplayName("blank origin returns ORIGIN_NOT_CONFIGURED")
        fun `blank origin`() {
            val result = executeHeartbeat(client, "")
            assertThat(result).isInstanceOf(HeartbeatCallResult.Failure::class.java)
            val failure = result as HeartbeatCallResult.Failure
            assertThat(failure.error).isEqualTo(HeartbeatError.ORIGIN_NOT_CONFIGURED)
        }

        @Test
        @DisplayName("whitespace origin returns ORIGIN_NOT_CONFIGURED")
        fun `whitespace origin`() {
            val result = executeHeartbeat(client, "   ")
            assertThat(result).isInstanceOf(HeartbeatCallResult.Failure::class.java)
            val failure = result as HeartbeatCallResult.Failure
            assertThat(failure.error).isEqualTo(HeartbeatError.ORIGIN_NOT_CONFIGURED)
        }
    }

    @Nested
    @DisplayName("executeAuthFlow() — success flow")
    inner class AuthSuccess {
        @Test
        @DisplayName("full flow: login + heartbeat + verify")
        fun `full auth flow`() {
            // Login response (200)
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
            // Post-login heartbeat
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody("""{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""))
            // Users/me verification
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody("""{"username":"testuser","admin":true}"""))

            val result = executeAuthFlow(client, baseUrl(), "testuser", charArrayOf('t', 'e', 's', 't', 'p', 'a', 's', 's'))
            assertThat(result).isInstanceOf(AuthFlowResult.Success::class.java)
            val success = result as AuthFlowResult.Success
            assertThat(success.heartbeatAfterLogin.version).isEqualTo("5.0.0")
            assertThat(success.verifiedUser.username).isEqualTo("testuser")
            assertThat(success.verifiedUser.isAdmin).isTrue()
        }

        @Test
        @DisplayName("String password overload works")
        fun `string password overload`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody("""{"version":"5.0.0","setup_complete":true,"userpass_enabled":true,"emulatorjs_enabled":true}"""))
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody("""{"username":"testuser","admin":false}"""))

            val result = executeAuthFlow(client, baseUrl(), "testuser", "testpass")
            assertThat(result).isInstanceOf(AuthFlowResult.Success::class.java)
        }

        @Test
        @DisplayName("login request includes HTTP Basic Auth header")
        fun `basic auth header present`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody("""{"version":"5.0.0","setup_complete":true,"userpass_enabled":true}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"u"}"""))

            executeAuthFlow(client, baseUrl(), "alice", charArrayOf('s', 'e', 'c', 'r', 'e', 't'))

            val loginRequest = server.takeRequest()
            assertThat(loginRequest.path).isEqualTo("/api/login")
            assertThat(loginRequest.method).isEqualTo("POST")
            val expectedAuth = Credentials.basic("alice", "secret")
            assertThat(loginRequest.getHeader("Authorization")).isEqualTo(expectedAuth)
        }

        @Test
        @DisplayName("login request has empty body")
        fun `login empty body`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody("""{"version":"5.0.0","setup_complete":true,"userpass_enabled":true}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"u"}"""))

            executeAuthFlow(client, baseUrl(), "alice", charArrayOf('s', 'e', 'c', 'r', 'e', 't'))

            val loginRequest = server.takeRequest()
            assertThat(loginRequest.bodySize).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("executeAuthFlow() — error classification")
    inner class AuthErrors {
        @Test
        @DisplayName("401 on login returns INVALID_CREDENTIALS")
        fun `invalid credentials`() {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid"}"""))

            val result = executeAuthFlow(client, baseUrl(), "user", charArrayOf('w', 'r', 'o', 'n', 'g'))
            assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
            val failure = result as AuthFlowResult.Failure
            assertThat(failure.error).isEqualTo(com.romm.androidtv.network.AuthError.INVALID_CREDENTIALS)
            assertThat(failure.httpCode).isEqualTo(401)
        }

        @Test
        @DisplayName("500 on login returns SERVER_ERROR")
        fun `server error on login`() {
            server.enqueue(MockResponse().setResponseCode(500))

            val result = executeAuthFlow(client, baseUrl(), "user", charArrayOf('p', 'a', 's', 's'))
            assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
            val failure = result as AuthFlowResult.Failure
            assertThat(failure.error).isEqualTo(com.romm.androidtv.network.AuthError.SERVER_ERROR)
            assertThat(failure.httpCode).isEqualTo(500)
        }

        @Test
        @DisplayName("heartbeat failure after login returns POST_LOGIN_HEARTBEAT_FAILED")
        fun `post-login heartbeat failure`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(500))

            val result = executeAuthFlow(client, baseUrl(), "user", charArrayOf('p', 'a', 's', 's'))
            assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
            val failure = result as AuthFlowResult.Failure
            assertThat(failure.error).isEqualTo(com.romm.androidtv.network.AuthError.POST_LOGIN_HEARTBEAT_FAILED)
        }

        @Test
        @DisplayName("401 on users/me returns VERIFICATION_FAILED")
        fun `verification 401`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody("""{"version":"5.0.0","setup_complete":true,"userpass_enabled":true}"""))
            server.enqueue(MockResponse().setResponseCode(401))

            val result = executeAuthFlow(client, baseUrl(), "user", charArrayOf('p', 'a', 's', 's'))
            assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
            val failure = result as AuthFlowResult.Failure
            assertThat(failure.error).isEqualTo(com.romm.androidtv.network.AuthError.VERIFICATION_FAILED)
        }

        @Test
        @DisplayName("blank origin returns ORIGIN_NOT_CONFIGURED")
        fun `blank origin`() {
            val result = executeAuthFlow(client, "", "user", charArrayOf('p', 'a', 's', 's'))
            assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
            val failure = result as AuthFlowResult.Failure
            assertThat(failure.error).isEqualTo(com.romm.androidtv.network.AuthError.ORIGIN_NOT_CONFIGURED)
        }
    }

    @Nested
    @DisplayName("executeAuthFlow() — redirect rejection")
    inner class RedirectRejection {
        @Test
        @DisplayName("302 redirect on login is classified as SERVER_ERROR, not followed")
        fun `redirect on login rejected`() {
            server.enqueue(MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "https://evil.example.com/steal"))

            val result = executeAuthFlow(client, baseUrl(), "user", charArrayOf('p', 'a', 's', 's'))
            assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
            val failure = result as AuthFlowResult.Failure
            // Redirect on login should NOT be followed; classified as error
            assertThat(failure.error).isEqualTo(com.romm.androidtv.network.AuthError.SERVER_ERROR)
            assertThat(failure.httpCode).isEqualTo(302)
        }

        @Test
        @DisplayName("301 redirect on login is classified as SERVER_ERROR")
        fun `permanent redirect rejected`() {
            server.enqueue(MockResponse()
                .setResponseCode(301)
                .addHeader("Location", "https://evil.example.com/"))

            val result = executeAuthFlow(client, baseUrl(), "user", charArrayOf('p', 'a', 's', 's'))
            assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
            val failure = result as AuthFlowResult.Failure
            assertThat(failure.error).isEqualTo(com.romm.androidtv.network.AuthError.SERVER_ERROR)
            assertThat(failure.httpCode).isEqualTo(301)
        }
    }

    @Nested
    @DisplayName("parseVerifiedUser() — user parsing with Moshi")
    inner class UserParsing {
        @Test
        @DisplayName("parses username and admin fields")
        fun `standard user json`() {
            val user = parseVerifiedUser("""{"username":"admin","admin":true}""")
            assertThat(user.username).isEqualTo("admin")
            assertThat(user.isAdmin).isTrue()
        }

        @Test
        @DisplayName("falls back to 'name' field")
        fun `name fallback`() {
            val user = parseVerifiedUser("""{"name":"player","admin":false}""")
            assertThat(user.username).isEqualTo("player")
        }

        @Test
        @DisplayName("falls back to 'user' field")
        fun `user fallback`() {
            val user = parseVerifiedUser("""{"user":"guest"}""")
            assertThat(user.username).isEqualTo("guest")
        }

        @Test
        @DisplayName("missing admin defaults to false")
        fun `missing admin`() {
            val user = parseVerifiedUser("""{"username":"test"}""")
            assertThat(user.isAdmin).isFalse()
        }

        @Test
        @DisplayName("garbage returns null username, false admin")
        fun `garbage`() {
            val user = parseVerifiedUser("not json")
            assertThat(user.username).isNull()
            assertThat(user.isAdmin).isFalse()
        }

        @Test
        @DisplayName("empty object returns defaults")
        fun `empty object`() {
            val user = parseVerifiedUser("{}")
            assertThat(user.username).isNull()
            assertThat(user.isAdmin).isFalse()
        }
    }

    @Nested
    @DisplayName("executeAuthFlow() — unexpected exception handling")
    inner class UnexpectedExceptions {
        @Test
        @DisplayName("network timeout returns NETWORK_ERROR (not crash)")
        fun `network timeout handled`() {
            // Configure client with very short timeout to force timeout exception
            val timeoutClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofMillis(100))
                .readTimeout(java.time.Duration.ofMillis(100))
                .build()

            // Start server but don't enqueue responses — connection will timeout
            val result = executeAuthFlow(timeoutClient, baseUrl(), "user", charArrayOf('p', 'a', 's', 's'))
            assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
            val failure = result as AuthFlowResult.Failure
            assertThat(failure.error).isEqualTo(com.romm.androidtv.network.AuthError.NETWORK_ERROR)
        }

        @Test
        @DisplayName("password is zeroed even on network error")
        fun `password zeroed on network error`() {
            val timeoutClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofMillis(100))
                .readTimeout(java.time.Duration.ofMillis(100))
                .build()

            val password = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
            executeAuthFlow(timeoutClient, baseUrl(), "user", password)
            assertThat(password).containsOnly('\u0000')
        }
    }

    @Nested
    @DisplayName("zeroCharArray() — password clearing helper")
    inner class PasswordClearing {
        @Test
        @DisplayName("zeros all characters in array")
        fun `zeros array`() {
            val arr = charArrayOf('a', 'b', 'c', 'd', 'e')
            zeroCharArray(arr)
            assertThat(arr).containsOnly('\u0000')
        }

        @Test
        @DisplayName("handles empty array")
        fun `empty array`() {
            val arr = charArrayOf()
            zeroCharArray(arr) // should not throw
            assertThat(arr).isEmpty()
        }

        @Test
        @DisplayName("repeated zeroing is safe")
        fun `repeated zeroing`() {
            val arr = charArrayOf('x', 'y', 'z')
            zeroCharArray(arr)
            zeroCharArray(arr) // idempotent
            assertThat(arr).containsOnly('\u0000')
        }

        @Test
        @DisplayName("CharArray password is zeroed after auth flow")
        fun `password zeroed in finally`() {
            server.enqueue(MockResponse().setResponseCode(401))
            val password = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
            executeAuthFlow(client, baseUrl(), "user", password)
            // Password should be zeroed by the finally block
            assertThat(password).containsOnly('\u0000')
        }
    }

    @Nested
    @DisplayName("verifyExistingSession() — session restoration")
    inner class SessionRestoration {
        @Test
        @DisplayName("valid session returns success")
        fun `valid session`() {
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody("""{"username":"existing","admin":false}"""))
            server.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody("""{"version":"5.0.0","setup_complete":true,"userpass_enabled":true}"""))

            val result = verifyExistingSession(client, baseUrl())
            assertThat(result).isInstanceOf(AuthFlowResult.Success::class.java)
            val success = result as AuthFlowResult.Success
            assertThat(success.verifiedUser.username).isEqualTo("existing")
        }

        @Test
        @DisplayName("expired session (401) returns VERIFICATION_FAILED")
        fun `expired session`() {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = verifyExistingSession(client, baseUrl())
            assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
            val failure = result as AuthFlowResult.Failure
            assertThat(failure.error).isEqualTo(com.romm.androidtv.network.AuthError.VERIFICATION_FAILED)
        }

        @Test
        @DisplayName("blank origin returns ORIGIN_NOT_CONFIGURED")
        fun `blank origin`() {
            val result = verifyExistingSession(client, "")
            assertThat(result).isInstanceOf(AuthFlowResult.Failure::class.java)
            val failure = result as AuthFlowResult.Failure
            assertThat(failure.error).isEqualTo(com.romm.androidtv.network.AuthError.ORIGIN_NOT_CONFIGURED)
        }
    }
}
