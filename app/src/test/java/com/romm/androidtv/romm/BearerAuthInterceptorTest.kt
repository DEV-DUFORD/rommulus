package com.romm.androidtv.romm

import com.romm.androidtv.network.RommServerAddress
import com.romm.androidtv.network.ServerAddressResult
import okhttp3.OkHttpClient
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
            .addInterceptor(BearerAuthInterceptor(valid, tokenProvider))
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
    @DisplayName("Cross-origin redirect: second hop does not carry the Authorization header")
    fun `cross-origin redirect does not forward Authorization`() {
        val redirectTarget = MockWebServer()
        redirectTarget.start(0)
        try {
            // First (same-origin) hop 302s to a different host/port.
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", redirectTarget.url("/").toString()),
            )
            redirectTarget.enqueue(MockResponse().setResponseCode(200))

            val client = client(sameOrigin()) { "rmm_token" }
            client.newCall(
                okhttp3.Request.Builder().url("${baseUrl()}/base/api/test").get().build(),
            ).execute().use { }

            val first = server.takeRequest()
            assertThat(first.getHeader("Authorization")).isEqualTo("Bearer rmm_token")

            val second = redirectTarget.takeRequest()
            assertThat(second.getHeader("Authorization")).isNull()
        } finally {
            redirectTarget.shutdown()
        }
    }
}
