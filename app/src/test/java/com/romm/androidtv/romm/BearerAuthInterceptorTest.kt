package com.romm.androidtv.romm

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BearerAuthInterceptor — token injection into OkHttp requests")
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

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    @Test
    @DisplayName("Token present: injects Authorization Bearer header")
    fun `injects Authorization Bearer header when token is available`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor { "rmm_test_token_123" })
            .build()

        val request = okhttp3.Request.Builder().url("${baseUrl()}/api/test").get().build()
        client.newCall(request).execute().use { /* ignore response */ }

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer rmm_test_token_123")
    }

    @Test
    @DisplayName("Token present: preserves existing headers alongside Bearer")
    fun `preserves existing request headers alongside Bearer`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor { "rmm_token" })
            .build()

        val request = okhttp3.Request.Builder()
            .url("${baseUrl()}/api/test")
            .header("X-Custom", "value")
            .get()
            .build()
        client.newCall(request).execute().use { /* ignore */ }

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer rmm_token")
        assertThat(recorded.getHeader("X-Custom")).isEqualTo("value")
    }

    @Test
    @DisplayName("Token absent: omits Authorization header")
    fun `omits Authorization header when token provider returns null`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor { null })
            .build()

        val request = okhttp3.Request.Builder().url("${baseUrl()}/api/test").get().build()
        client.newCall(request).execute().use { /* ignore */ }

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }

    @Test
    @DisplayName("Token absent: server 401 is propagated")
    fun `server 401 is propagated when no token is injected`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor { null })
            .build()

        val request = okhttp3.Request.Builder().url("${baseUrl()}/api/protected").get().build()
        client.newCall(request).execute().use { response ->
            assertThat(response.code).isEqualTo(401)
        }
    }

    @Test
    @DisplayName("Dynamic provider: token is re-evaluated on each request")
    fun `token is re-evaluated on each request`() {
        var callCount = 0
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor {
                "rmm_token_${callCount++}"
            })
            .build()

        val req1 = okhttp3.Request.Builder().url("${baseUrl()}/api/test").get().build()
        val req2 = okhttp3.Request.Builder().url("${baseUrl()}/api/test").get().build()
        client.newCall(req1).execute().use { }
        client.newCall(req2).execute().use { }

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer rmm_token_0")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer rmm_token_1")
    }
}
