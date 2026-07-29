package com.romm.androidtv.library

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Verifies [LibraryApi.fetchRoms] builds the correct `GET /api/roms` query
 * parameters for each [RomQuery] variant and for pagination (`limit`/`offset`),
 * against a real HTTP request captured by [MockWebServer] rather than
 * inspecting URL-building internals directly.
 */
@DisplayName("LibraryApi.fetchRoms — query parameter building")
class LibraryApiFetchRomsTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        client = OkHttpClient.Builder().build()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun origin(): String = server.url("/").toString().removeSuffix("/")

    @Test
    fun `ByCollection sends collection_id and not a platform_ids or collections param`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items": [], "total": 0}"""))

        LibraryApi.fetchRoms(client, origin(), RomQuery.ByCollection(collectionId = 42))

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("collection_id=42")
        assertThat(recorded.path).doesNotContain("platform_ids")
    }

    @Test
    fun `ByPlatform sends platform_ids`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items": [], "total": 0}"""))

        LibraryApi.fetchRoms(client, origin(), RomQuery.ByPlatform(platformId = 7))

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("platform_ids=7")
        assertThat(recorded.path).doesNotContain("collection_id")
    }

    @Test
    fun `limit and offset are forwarded for pagination`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items": [], "total": 0}"""))

        LibraryApi.fetchRoms(client, origin(), RomQuery.ByPlatform(platformId = 7), limit = 40, offset = 80)

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("limit=40")
        assertThat(recorded.path).contains("offset=80")
    }

    @Test
    fun `offset defaults to 0 when not specified`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items": [], "total": 0}"""))

        LibraryApi.fetchRoms(client, origin(), RomQuery.RecentlyAdded)

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("offset=0")
    }
}
