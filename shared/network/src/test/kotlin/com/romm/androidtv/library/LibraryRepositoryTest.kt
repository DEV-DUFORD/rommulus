package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Repository-layer tests for collection mutations and owned-writable filtering.
 * Uses MockWebServer so no real server or credentials are needed.
 */
@DisplayName("LibraryRepository — collection mutations and ownership filtering")
class LibraryRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val TEST_USERNAME = "zack"

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        client = OkHttpClient.Builder().build()
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    // --- Fixtures ---

    private val userPublicCollectionJson = """
        {
          "id": 1, "name": "User Public", "rom_count": 3,
          "rom_ids": [100, 200, 300], "is_public": true, "is_favorite": false,
          "is_virtual": false, "is_smart": false,
          "user_id": 1, "owner_username": "$TEST_USERNAME"
        }
    """.trimIndent()

    private val userPrivateCollectionJson = """
        {
          "id": 2, "name": "User Private", "rom_count": 0,
          "rom_ids": [], "is_public": false, "is_favorite": false,
          "is_virtual": false, "is_smart": false,
          "user_id": 1, "owner_username": "$TEST_USERNAME"
        }
    """.trimIndent()

    private val userFavoriteCollectionJson = """
        {
          "id": 3, "name": "Favorites", "rom_count": 5,
          "rom_ids": [1, 2, 3, 4, 5], "is_public": false, "is_favorite": true,
          "is_virtual": false, "is_smart": false,
          "user_id": 1, "owner_username": "$TEST_USERNAME"
        }
    """.trimIndent()

    private val otherUserCollectionJson = """
        {
          "id": 4, "name": "Other Public", "rom_count": 10,
          "rom_ids": [10, 20], "is_public": true, "is_favorite": false,
          "is_virtual": false, "is_smart": false,
          "user_id": 99, "owner_username": "otheruser"
        }
    """.trimIndent()

    private val virtualCollectionJson = """
        {
          "id": 5, "name": "All", "rom_count": 100,
          "rom_ids": [], "is_public": true, "is_favorite": false,
          "is_virtual": true, "is_smart": false,
          "user_id": 1, "owner_username": "$TEST_USERNAME"
        }
    """.trimIndent()

    private fun makeRepo(usernameProvider: () -> String? = { TEST_USERNAME }): LibraryRepositoryImpl =
        LibraryRepositoryImpl(client, { baseUrl() }, usernameProvider)

    // --- Tests ---

    @Nested
    @DisplayName("fetchOwnedWritableCollections")
    inner class FetchOwnedWritableCollections {

        @Test
        fun `excludes another user's public collection`() = runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """[$userPublicCollectionJson, $otherUserCollectionJson]"""
            ))

            val result = makeRepo().fetchOwnedWritableCollections()

            assertThat(result).isInstanceOf(LibraryResult.Success::class.java)
            val collections = (result as LibraryResult.Success).data
            assertThat(collections).hasSize(1)
            assertThat(collections[0].name).isEqualTo("User Public")
        }

        @Test
        fun `retains current user's public and private collections`() = runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """[$userPublicCollectionJson, $userPrivateCollectionJson]"""
            ))

            val result = makeRepo().fetchOwnedWritableCollections()

            assertThat(result).isInstanceOf(LibraryResult.Success::class.java)
            val collections = (result as LibraryResult.Success).data
            assertThat(collections).hasSize(2)
            assertThat(collections.map { it.name }).containsExactly("User Public", "User Private")
        }

        @Test
        fun `retains current user's is_favorite collection`() = runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """[$userFavoriteCollectionJson, $otherUserCollectionJson]"""
            ))

            val result = makeRepo().fetchOwnedWritableCollections()

            assertThat(result).isInstanceOf(LibraryResult.Success::class.java)
            val collections = (result as LibraryResult.Success).data
            assertThat(collections).hasSize(1)
            assertThat(collections[0].name).isEqualTo("Favorites")
            assertThat(collections[0].isFavorite).isTrue()
        }

        @Test
        fun `returns AUTH_EXPIRED when usernameProvider returns null`() = runBlocking {
            val result = makeRepo({ null }).fetchOwnedWritableCollections()

            assertThat(result).isInstanceOf(LibraryResult.Failure::class.java)
            val failure = result as LibraryResult.Failure
            assertThat(failure.error).isEqualTo(RommApiError.AUTH_EXPIRED)
            // No network call should be made when username is absent.
            assertThat(server.requestCount).isEqualTo(0)
        }

        @Test
        fun `returns AUTH_EXPIRED when usernameProvider returns blank string`() = runBlocking {
            val result = makeRepo({ "  " }).fetchOwnedWritableCollections()

            assertThat(result).isInstanceOf(LibraryResult.Failure::class.java)
            assertThat((result as LibraryResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
            assertThat(server.requestCount).isEqualTo(0)
        }

        @Test
        fun `exact match on owner_username (not case-folded)`() = runBlocking {
            // Server returns a collection whose owner is "Zack" (capital Z), but our username is "zack".
            val caseMismatchJson = """
                {
                  "id": 1, "name": "Casey", "rom_count": 0,
                  "rom_ids": [], "is_public": true, "is_favorite": false,
                  "is_virtual": false, "is_smart": false,
                  "user_id": 1, "owner_username": "Zack"
                }
            """.trimIndent()
            server.enqueue(MockResponse().setResponseCode(200).setBody("""[$caseMismatchJson]"""))

            val result = makeRepo({ "zack" }).fetchOwnedWritableCollections()

            assertThat(result).isInstanceOf(LibraryResult.Success::class.java)
            assertThat((result as LibraryResult.Success).data).isEmpty()
        }

        @Test
        fun `trims whitespace from owner_username before matching`() = runBlocking {
            val trimmedJson = """
                {
                  "id": 1, "name": "Trimmed", "rom_count": 0,
                  "rom_ids": [], "is_public": true, "is_favorite": false,
                  "is_virtual": false, "is_smart": false,
                  "user_id": 1, "owner_username": "  $TEST_USERNAME  "
                }
            """.trimIndent()
            server.enqueue(MockResponse().setResponseCode(200).setBody("""[$trimmedJson]"""))

            val result = makeRepo().fetchOwnedWritableCollections()

            assertThat(result).isInstanceOf(LibraryResult.Success::class.java)
            assertThat((result as LibraryResult.Success).data).hasSize(1)
        }

        @Test
        fun `propagates server failure with httpCode`() = runBlocking {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = makeRepo().fetchOwnedWritableCollections()

            assertThat(result).isInstanceOf(LibraryResult.Failure::class.java)
            val failure = result as LibraryResult.Failure
            assertThat(failure.error).isEqualTo(RommApiError.AUTH_EXPIRED)
            assertThat(failure.httpCode).isEqualTo(401)
        }
    }

    @Nested
    @DisplayName("createCollection")
    inner class CreateCollection {

        @Test
        fun `maps success and preserves collection fields`() = runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(userPublicCollectionJson))

            val result = makeRepo().createCollection("New Collection", isFavorite = false)

            assertThat(result).isInstanceOf(LibraryResult.Success::class.java)
            val collection = (result as LibraryResult.Success).data
            assertThat(collection.id).isEqualTo(1)
            assertThat(collection.name).isEqualTo("User Public")
            assertThat(collection.romCount).isEqualTo(3)
            assertThat(collection.romIds).containsExactlyInAnyOrder(100L, 200L, 300L)

            val recorded = server.takeRequest()
            assertThat(recorded.method).isEqualTo("POST")
            assertThat(recorded.path).startsWith("/api/collections")
        }

        @Test
        fun `maps failure and preserves httpCode`() = runBlocking {
            server.enqueue(MockResponse().setResponseCode(409))

            val result = makeRepo().createCollection("Duplicate", isFavorite = false)

            assertThat(result).isInstanceOf(LibraryResult.Failure::class.java)
            val failure = result as LibraryResult.Failure
            assertThat(failure.error).isEqualTo(RommApiError.SERVER_ERROR)
            assertThat(failure.httpCode).isEqualTo(409)
        }
    }

    @Nested
    @DisplayName("addRomToCollection")
    inner class AddRomToCollection {

        @Test
        fun `maps success and preserves membership and count`() = runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(userPublicCollectionJson))

            val result = makeRepo().addRomToCollection(collectionId = 1, romId = 999)

            assertThat(result).isInstanceOf(LibraryResult.Success::class.java)
            val collection = (result as LibraryResult.Success).data
            assertThat(collection.id).isEqualTo(1)
            assertThat(collection.romCount).isEqualTo(3)
            assertThat(collection.romIds).containsExactlyInAnyOrder(100L, 200L, 300L)

            val recorded = server.takeRequest()
            assertThat(recorded.method).isEqualTo("POST")
            assertThat(recorded.path).isEqualTo("/api/collections/1/roms")
        }

        @Test
        fun `maps failure and preserves httpCode`() = runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = makeRepo().addRomToCollection(collectionId = 999, romId = 1)

            assertThat(result).isInstanceOf(LibraryResult.Failure::class.java)
            val failure = result as LibraryResult.Failure
            assertThat(failure.error).isEqualTo(RommApiError.NOT_FOUND)
            assertThat(failure.httpCode).isEqualTo(404)
        }
    }

    @Nested
    @DisplayName("removeRomFromCollection")
    inner class RemoveRomFromCollection {

        @Test
        fun `maps success and preserves membership and count`() = runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(userPublicCollectionJson))

            val result = makeRepo().removeRomFromCollection(collectionId = 1, romId = 100)

            assertThat(result).isInstanceOf(LibraryResult.Success::class.java)
            val collection = (result as LibraryResult.Success).data
            assertThat(collection.id).isEqualTo(1)
            assertThat(collection.romCount).isEqualTo(3)
            assertThat(collection.romIds).containsExactlyInAnyOrder(100L, 200L, 300L)

            val recorded = server.takeRequest()
            assertThat(recorded.method).isEqualTo("DELETE")
            assertThat(recorded.path).isEqualTo("/api/collections/1/roms")
        }

        @Test
        fun `maps failure and preserves httpCode`() = runBlocking {
            server.enqueue(MockResponse().setResponseCode(403))

            val result = makeRepo().removeRomFromCollection(collectionId = 1, romId = 100)

            assertThat(result).isInstanceOf(LibraryResult.Failure::class.java)
            val failure = result as LibraryResult.Failure
            assertThat(failure.error).isEqualTo(RommApiError.AUTH_EXPIRED)
            assertThat(failure.httpCode).isEqualTo(403)
        }
    }
}
