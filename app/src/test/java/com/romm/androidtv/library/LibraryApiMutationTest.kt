package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Request-shape and error-classification tests for the collection mutation
 * endpoints (create / add rom / remove rom). Uses the MockWebServer pattern
 * from RommApiTest — no real server, no credentials.
 */
@DisplayName("LibraryApi — collection mutations")
class LibraryApiMutationTest {

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
        if (::server.isInitialized) server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    private val collectionJson = """
        {
          "id": 5, "name": "My Collection", "rom_count": 2,
          "rom_ids": [10, 20], "is_public": false, "is_favorite": true,
          "is_virtual": false, "is_smart": false,
          "user_id": 1, "owner_username": "zack",
          "path_cover_large": "/assets/romm/resources/collections/5/cover/big.png",
          "path_covers_large": []
        }
    """.trimIndent()

    @Nested
    @DisplayName("createCollection")
    inner class CreateCollection {

        @Test
        fun `success returns parsed collection and sends POST to api collections`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(collectionJson))

            val result = LibraryApi.createCollection(client, baseUrl(), "My Collection", isFavorite = true)

            assertThat(result).isInstanceOf(CollectionMutationResult.Success::class.java)
            val collection = (result as CollectionMutationResult.Success).collection
            assertThat(collection.id).isEqualTo(5)
            assertThat(collection.name).isEqualTo("My Collection")
            assertThat(collection.romIds).containsExactly(10L, 20L)
            assertThat(collection.isFavorite).isTrue

            val recorded = server.takeRequest()
            assertThat(recorded.method).isEqualTo("POST")
            assertThat(recorded.path).startsWith("/api/collections")
        }

        @Test
        fun `ordinary create includes is_public false and is_favorite false query params`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(collectionJson))

            LibraryApi.createCollection(client, baseUrl(), "Plain", isFavorite = false)

            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/api/collections?is_public=false&is_favorite=false")
        }

        @Test
        fun `favorite create includes is_favorite true query param`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(collectionJson))

            LibraryApi.createCollection(client, baseUrl(), "Fav", isFavorite = true)

            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/api/collections?is_public=false&is_favorite=true")
        }

        @Test
        fun `body is multipart form-data and contains the exact trimmed name`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(collectionJson))

            LibraryApi.createCollection(client, baseUrl(), "  My Collection  ", isFavorite = false)

            val recorded = server.takeRequest()
            val body = recorded.body.readUtf8()
            assertThat(recorded.headers["Content-Type"]).contains("multipart/form-data")
            assertThat(body).contains("name=\"name\"")
            assertThat(body).contains("My Collection")
            assertThat(body).doesNotContain("  My Collection  ")
        }

        @Test
        fun `blank origin returns ORIGIN_NOT_CONFIGURED without a network call`() {
            val result = LibraryApi.createCollection(client, "", "X", isFavorite = false)

            assertThat((result as CollectionMutationResult.Failure).error).isEqualTo(RommApiError.ORIGIN_NOT_CONFIGURED)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("addRomToCollection / removeRomFromCollection")
    inner class Membership {

        @Test
        fun `add sends POST to api collections id roms`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(collectionJson))

            val result = LibraryApi.addRomToCollection(client, baseUrl(), collectionId = 5, romId = 99)

            assertThat(result).isInstanceOf(CollectionMutationResult.Success::class.java)
            assertThat((result as CollectionMutationResult.Success).collection.romIds).containsExactly(10L, 20L)

            val recorded = server.takeRequest()
            assertThat(recorded.method).isEqualTo("POST")
            assertThat(recorded.path).isEqualTo("/api/collections/5/roms")
        }

        @Test
        fun `remove sends DELETE to api collections id roms`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(collectionJson))

            val result = LibraryApi.removeRomFromCollection(client, baseUrl(), collectionId = 5, romId = 99)

            assertThat(result).isInstanceOf(CollectionMutationResult.Success::class.java)

            val recorded = server.takeRequest()
            assertThat(recorded.method).isEqualTo("DELETE")
            assertThat(recorded.path).isEqualTo("/api/collections/5/roms")
        }

        @Test
        fun `both membership bodies are JSON with the single rom id`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(collectionJson))
            LibraryApi.addRomToCollection(client, baseUrl(), 5, 99)
            val addRecorded = server.takeRequest()
            assertThat(addRecorded.headers["Content-Type"]).contains("application/json")
            assertThat(addRecorded.body.readUtf8()).isEqualTo("""{"rom_ids":[99]}""")

            server.enqueue(MockResponse().setResponseCode(200).setBody(collectionJson))
            LibraryApi.removeRomFromCollection(client, baseUrl(), 5, 99)
            val removeRecorded = server.takeRequest()
            assertThat(removeRecorded.headers["Content-Type"]).contains("application/json")
            assertThat(removeRecorded.body.readUtf8()).isEqualTo("""{"rom_ids":[99]}""")
        }
    }

    @Nested
    @DisplayName("error classification")
    inner class Errors {

        private fun enqueue(code: Int) = server.enqueue(MockResponse().setResponseCode(code))

        @Test
        fun `401 maps to AUTH_EXPIRED with http code preserved`() {
            enqueue(401)
            val result = LibraryApi.createCollection(client, baseUrl(), "X", false)
            assertThat((result as CollectionMutationResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
            assertThat(result.httpCode).isEqualTo(401)
        }

        @Test
        fun `403 maps to AUTH_EXPIRED with http code preserved`() {
            enqueue(403)
            val result = LibraryApi.createCollection(client, baseUrl(), "X", false)
            assertThat((result as CollectionMutationResult.Failure).error).isEqualTo(RommApiError.AUTH_EXPIRED)
            assertThat(result.httpCode).isEqualTo(403)
        }

        @Test
        fun `409 maps to SERVER_ERROR with http code preserved`() {
            enqueue(409)
            val result = LibraryApi.createCollection(client, baseUrl(), "X", false)
            assertThat((result as CollectionMutationResult.Failure).error).isEqualTo(RommApiError.SERVER_ERROR)
            assertThat(result.httpCode).isEqualTo(409)
        }

        @Test
        fun `500 maps to SERVER_ERROR with http code preserved`() {
            enqueue(500)
            val result = LibraryApi.createCollection(client, baseUrl(), "X", false)
            assertThat((result as CollectionMutationResult.Failure).error).isEqualTo(RommApiError.SERVER_ERROR)
            assertThat(result.httpCode).isEqualTo(500)
        }

        @Test
        fun `malformed body maps to PARSE_ERROR with http code preserved`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
            val result = LibraryApi.createCollection(client, baseUrl(), "X", false)
            assertThat((result as CollectionMutationResult.Failure).error).isEqualTo(RommApiError.PARSE_ERROR)
            assertThat(result.httpCode).isEqualTo(200)
        }

        @Test
        fun `connection disconnect maps to NETWORK_ERROR`() {
            val deadServer = MockWebServer()
            deadServer.start(0)
            val deadOrigin = deadServer.url("/").toString().removeSuffix("/")
            deadServer.shutdown()

            val result = LibraryApi.createCollection(client, deadOrigin, "X", false)

            assertThat((result as CollectionMutationResult.Failure).error).isEqualTo(RommApiError.NETWORK_ERROR)
            assertThat(result.httpCode).isNull()
        }

        @Test
        fun `TLS handshake failure maps to TLS_ERROR`() {
            val tlsServer = MockWebServer()
            tlsServer.useHttps(testSslContext().socketFactory, false)
            tlsServer.start(0)
            val tlsOrigin = tlsServer.url("/").toString().removeSuffix("/")

            val result = LibraryApi.createCollection(client, tlsOrigin, "X", false)

            assertThat((result as CollectionMutationResult.Failure).error).isEqualTo(RommApiError.TLS_ERROR)
            tlsServer.shutdown()
        }
    }

    @Nested
    @DisplayName("origin with a base path")
    inner class BasePath {

        @Test
        fun `create keeps the base path when origin has one`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(collectionJson))

            LibraryApi.createCollection(client, "${baseUrl()}/romm", "X", false)

            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/romm/api/collections?is_public=false&is_favorite=false")
        }

        @Test
        fun `add rom keeps the base path when origin has one`() {
            server.enqueue(MockResponse().setResponseCode(200).setBody(collectionJson))

            LibraryApi.addRomToCollection(client, "${baseUrl()}/romm", collectionId = 5, romId = 99)

            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/romm/api/collections/5/roms")
        }
    }

    /** Builds an SSLContext backed by a self-signed cert so HTTPS MockWebServer can serve one the client won't trust. */
    private fun testSslContext(): SSLContext {
        val javaHome = System.getProperty("java.home")
        val keytool = File(javaHome, "bin/keytool").path
        val dir = createTempDir(prefix = "romm-tls")
        val ksFile = File(dir, "ks.p12")
        ProcessBuilder(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
            "-storetype", "PKCS12", "-keystore", ksFile.path,
            "-storepass", "changeit", "-keypass", "changeit",
            "-dname", "CN=localhost", "-validity", "365",
        ).redirectErrorStream(true).start().waitFor()

        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(ksFile).use { ks.load(it, "changeit".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "changeit".toCharArray())
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        return ctx
    }
}
