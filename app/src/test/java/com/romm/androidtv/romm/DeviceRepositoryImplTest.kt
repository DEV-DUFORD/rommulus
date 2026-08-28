package com.romm.androidtv.romm

import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.network.AndroidDeviceIdentityStorage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeviceRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var client: okhttp3.OkHttpClient
    private lateinit var identityStore: DeviceIdentityStore
    private lateinit var repository: DeviceRepositoryImpl

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        client = okhttp3.OkHttpClient.Builder().build()
        identityStore = DeviceIdentityStore(FakeSharedPreferences())
        repository = DeviceRepositoryImpl(client, AndroidDeviceIdentityStorage(identityStore))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    @Test
    fun `ensureRegistered caches the device id on a newly-created device`() {
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setBody("""{"device_id": "new-1", "name": null, "created_at": "2026-01-01T00:00:00Z"}""")
            )

            val result = repository.ensureRegistered(baseUrl(), "root")

            assertThat(result).isInstanceOf(DeviceRegistrationResult.Success::class.java)
            val success = result as DeviceRegistrationResult.Success
            assertThat(success.identity.rommDeviceId).isEqualTo("new-1")
            assertThat(success.alreadyExisted).isFalse()
            assertThat(identityStore.cachedDeviceId(baseUrl(), "root")).isEqualTo("new-1")
        }
    }

    @Test
    fun `ensureRegistered reports alreadyExisted for a reused device`() {
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"device_id": "existing-1", "name": null, "created_at": "2026-01-01T00:00:00Z"}""")
            )

            val result = repository.ensureRegistered(baseUrl(), "root") as DeviceRegistrationResult.Success

            assertThat(result.alreadyExisted).isTrue()
        }
    }

    @Test
    fun `ensureRegistered sends the stable local installation id as client_device_identifier`() {
        runBlocking {
            val installationId = identityStore.installationId(baseUrl(), "root")
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setBody("""{"device_id": "new-1", "created_at": "2026-01-01T00:00:00Z"}""")
            )

            repository.ensureRegistered(baseUrl(), "root")

            val recorded = server.takeRequest()
            assertThat(recorded.body.readUtf8()).contains(installationId)
        }
    }

    @Test
    fun `ensureRegistered reuses the same installation id across calls`() {
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setBody("""{"device_id": "new-1", "created_at": "2026-01-01T00:00:00Z"}""")
            )
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"device_id": "new-1", "created_at": "2026-01-01T00:00:00Z"}""")
            )

            val first = repository.ensureRegistered(baseUrl(), "root") as DeviceRegistrationResult.Success
            val second = repository.ensureRegistered(baseUrl(), "root") as DeviceRegistrationResult.Success

            assertThat(second.identity.installationId).isEqualTo(first.identity.installationId)
        }
    }

    @Test
    fun `ensureRegistered on failure does not cache and preserves a previously-cached device id`() {
        runBlocking {
            identityStore.saveDeviceId(baseUrl(), "root", "previously-cached")
            server.enqueue(MockResponse().setResponseCode(500))

            val result = repository.ensureRegistered(baseUrl(), "root")

            assertThat(result).isInstanceOf(DeviceRegistrationResult.Failure::class.java)
            assertThat((result as DeviceRegistrationResult.Failure).error).isEqualTo(RommApiError.SERVER_ERROR)
            assertThat(identityStore.cachedDeviceId(baseUrl(), "root")).isEqualTo("previously-cached")
        }
    }

    @Test
    fun `forget clears the cached device id but keeps the installation id`() {
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(201)
                    .setBody("""{"device_id": "new-1", "created_at": "2026-01-01T00:00:00Z"}""")
            )
            val registered = repository.ensureRegistered(baseUrl(), "root") as DeviceRegistrationResult.Success

            repository.forget(baseUrl(), "root")

            assertThat(identityStore.cachedDeviceId(baseUrl(), "root")).isNull()
            assertThat(identityStore.installationId(baseUrl(), "root")).isEqualTo(registered.identity.installationId)
        }
    }
}
