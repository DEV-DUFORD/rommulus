package com.romm.androidtv.romm

import com.romm.androidtv.network.RommServerAddress
import com.romm.androidtv.network.ServerAddressResult
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BearerAuthInterceptor — origin-scoped token injection into OkHttp requests")
class BearerAuthInterceptorTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    /** Builds the interceptor from a canonical origin string via parseAndNormalize. */
    private fun client(origin: String, tokenProvider: () -> String?): OkHttpClient {
        val valid = RommServerAddress.parseAndNormalize(origin) as ServerAddressResult.Valid
        return OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor({ valid }, tokenProvider))
            .build()
    }

    /** Same-origin config rooted at this server's host/port with base path `/base`. */
    private fun sameOrigin(): String =
        "http://localhost:${server.port}/base"

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    @Test
    @DisplayName("Same-origin request (scheme+host+port+base path match): injects Bearer token")
    fun `injects token for same-origin request`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val client = client(sameOrigin()) { "rmm_test_token_123" }
        client.newCall(
            okhttp3.Request.Builder().url("${baseUrl()}/base/api/test").get().build(),
        ).execute().use { }

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer rmm_test_token_123")
    }

    @Test
    @DisplayName("Same-origin request: preserves existing headers alongside Bearer")
    fun `preserves existing headers alongside Bearer for same-origin`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val client = client(sameOrigin()) { "rmm_token" }
        client.newCall(
            okhttp3.Request.Builder()
                .url("${baseUrl()}/base/api/test")
                .header("X-Custom", "value")
                .get()
                .build(),
        ).execute().use { }

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer rmm_token")
        assertThat(recorded.getHeader("X-Custom")).isEqualTo("value")
    }

    @Test
    @DisplayName("Different host: token NOT injected")
    fun `does not inject token for different host`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val client = client("https://other.example.com/base") { "rmm_token" }
        client.newCall(
            okhttp3.Request.Builder().url("${baseUrl()}/base/api/test").get().build(),
        ).execute().use { }

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    @DisplayName("Different scheme: token NOT injected")
    fun `does not inject token for different scheme`() {
        server.enqueue(MockResponse().setResponseCode(200))

        // Origin is https, request goes to http (MockWebServer).
        val client = client("https://localhost:${server.port}/base") { "rmm_token" }
        client.newCall(
            okhttp3.Request.Builder().url("${baseUrl()}/base/api/test").get().build(),
        ).execute().use { }

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    @DisplayName("Different port: token NOT injected")
    fun `does not inject token for different port`() {
        server.enqueue(MockResponse().setResponseCode(200))

        // Origin points at a different (non-default, unused) port.
        val client = client("http://localhost:1/base") { "rmm_token" }
        client.newCall(
            okhttp3.Request.Builder().url("${baseUrl()}/base/api/test").get().build(),
        ).execute().use { }

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    @DisplayName("Different base path: token NOT injected")
    fun `does not inject token for different base path`() {
        server.enqueue(MockResponse().setResponseCode(200))

        // Origin base path is /base, request targets /other -> not under origin.
        val client = client(sameOrigin()) { "rmm_token" }
        client.newCall(
            okhttp3.Request.Builder().url("${baseUrl()}/other/api/test").get().build(),
        ).execute().use { }

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    @DisplayName("Non-origin request already carrying Authorization has the header STRIPPED")
    fun `strips Authorization header on non-origin request`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val client = client(sameOrigin()) { "rmm_token" }
        client.newCall(
            okhttp3.Request.Builder()
                .url("${baseUrl()}/other/api/test") // not under /base origin
                .header("Authorization", "Bearer leaked_credential")
                .get()
                .build(),
        ).execute().use { }

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    @DisplayName("Null token: no Authorization header on same-origin request")
    fun `omits Authorization header when token provider returns null`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val client = client(sameOrigin()) { null }
        client.newCall(
            okhttp3.Request.Builder().url("${baseUrl()}/base/api/protected").get().build(),
        ).execute().use { response ->
            assertThat(response.code).isEqualTo(401)
        }

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    @DisplayName("Same-origin explicit Basic credentials are preserved for login")
    fun `preserves explicit Basic credentials for same-origin login`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val authorization = "Basic dXNlcjpwYXNz"
        val client = client(sameOrigin()) { null }

        client.newCall(
            okhttp3.Request.Builder()
                .url("${baseUrl()}/base/api/login")
                .header("Authorization", authorization)
                .post(ByteArray(0).toRequestBody(null))
                .build(),
        ).execute().use { }

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo(authorization)
    }

    @Test
    @DisplayName("Origin target resolved per request lets a client retarget between origins")
    fun `origin provider resolved per request allows retargeting`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        // Start pinned to an unrelated origin, then flip to the live server's origin
        // on the second request without rebuilding the client.
        var current: ServerAddressResult.Valid =
            RommServerAddress.parseAndNormalize("https://other.example.com/base") as ServerAddressResult.Valid
        val client = OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor({ current }, { "rmm_token" }))
            .build()

        // 1st request: origin target is "other.example.com" -> no token for the live server.
        client.newCall(
            okhttp3.Request.Builder().url("${baseUrl()}/base/api/test").get().build(),
        ).execute().use { }
        assertThat(server.takeRequest().getHeader("Authorization")).isNull()

        // Retarget the provider to the live server; same client now injects the token.
        current = RommServerAddress.parseAndNormalize(baseUrl() + "/base") as ServerAddressResult.Valid
        client.newCall(
            okhttp3.Request.Builder().url("${baseUrl()}/base/api/test").get().build(),
        ).execute().use { }
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer rmm_token")
    }

    @Test
    @DisplayName("Null origin provider: token NOT injected, any Authorization stripped")
    fun `omits Authorization when origin provider returns null`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor({ null }, { "rmm_token" }))
            .build()
        client.newCall(
            okhttp3.Request.Builder().url("${baseUrl()}/base/api/test").get().build(),
        ).execute().use { }

        assertThat(server.takeRequest().getHeader("Authorization")).isNull()
    }
}
