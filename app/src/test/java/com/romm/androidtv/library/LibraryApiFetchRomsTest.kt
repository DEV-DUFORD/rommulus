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

    @Test
    fun `RecentlyAdded uses order_by created_at desc`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items": [], "total": 0}"""))

        LibraryApi.fetchRoms(client, origin(), RomQuery.RecentlyAdded)

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("order_by=created_at")
        assertThat(recorded.path).contains("order_dir=desc")
    }

    @Test
    fun `ContinuePlaying sets last_played true and orders by last_played desc`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items": [], "total": 0}"""))

        LibraryApi.fetchRoms(client, origin(), RomQuery.ContinuePlaying)

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("last_played=true")
        assertThat(recorded.path).contains("order_by=last_played")
        assertThat(recorded.path).contains("order_dir=desc")
    }

    @Test
    fun `Favorites sets favorite true`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items": [], "total": 0}"""))

        LibraryApi.fetchRoms(client, origin(), RomQuery.Favorites)

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("favorite=true")
    }

    @Test
    fun `Search passes the query term as search_term`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items": [], "total": 0}"""))

        LibraryApi.fetchRoms(client, origin(), RomQuery.Search("Elder Scrolls"))

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("search_term=Elder%20Scrolls")
    }

    @Test
    fun `all variants include common base query parameters`() {
        val queries = listOf<RomQuery>(
            RomQuery.RecentlyAdded,
            RomQuery.ContinuePlaying,
            RomQuery.Favorites,
            RomQuery.Search("foo"),
            RomQuery.ByPlatform(1L),
            RomQuery.ByCollection(1L),
        )

        // Enqueue one response per variant so each request is consumed and asserted.
        queries.forEach {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items": [], "total": 0}"""))
        }

        queries.forEach { query ->
            LibraryApi.fetchRoms(client, origin(), query)
        }

        // Assert each recorded request individually.
        queries.forEach { _ ->
            val recorded = server.takeRequest()
            assertThat(recorded.path).contains("with_char_index=false")
            assertThat(recorded.path).contains("with_filter_values=false")
            assertThat(recorded.path).contains("with_rom_id_index=false")
            assertThat(recorded.path).contains("group_by_meta_id=true")
            assertThat(recorded.path).contains("limit=20")
            assertThat(recorded.path).contains("offset=0")
        }
    }

    @Test
    fun `sends group_by_meta_id=true so sibling rom versions collapse to one gallery entry`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items": [], "total": 0}"""))

        LibraryApi.fetchRoms(client, origin(), RomQuery.RecentlyAdded)

        val recorded = server.takeRequest()
        assertThat(recorded.path).contains("group_by_meta_id=true")
    }

    @Test
    fun `Search does not leak unrelated variant params like last_played or favorite`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items": [], "total": 0}"""))

        LibraryApi.fetchRoms(client, origin(), RomQuery.Search("test"))

        val recorded = server.takeRequest()
        assertThat(recorded.path).doesNotContain("last_played")
        assertThat(recorded.path).doesNotContain("favorite=true")
        assertThat(recorded.path).doesNotContain("platform_ids")
        assertThat(recorded.path).doesNotContain("collection_id")
    }
}
