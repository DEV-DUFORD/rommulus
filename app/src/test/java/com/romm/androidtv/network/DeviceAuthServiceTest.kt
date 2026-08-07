package com.romm.androidtv.network

import com.romm.androidtv.auth.RommClientTokenScopes
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeviceAuthServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `init builds QR URL under configured base path`() {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """
                {
                  "device_code":"secret-device-code",
                  "user_code":"ABCD1234",
                  "verification_path":"/pair/device",
                  "verification_path_complete":"/pair/device?user_code=ABCD1234",
                  "expires_in":600,
                  "interval":5
                }
                """.trimIndent(),
            ),
        )

        val result = DeviceAuthService.initiate(
            client,
            server.url("/romm").toString().removeSuffix("/"),
            request(),
        )

        val info = (result as DeviceAuthInitResult.Success).info
        assertThat(info.verificationUrl)
            .isEqualTo(server.url("/romm/pair/device?user_code=ABCD1234").toString())
        assertThat(server.takeRequest().path).isEqualTo("/romm/api/auth/device/init")
    }

    @Test
    fun `404 init reports unsupported for pre-5_1 servers`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = DeviceAuthService.initiate(client, origin(), request())

        assertThat(result).isEqualTo(DeviceAuthInitResult.Unsupported)
    }

    @Test
    fun `poll maps pending slowdown denial and expiry details`() {
        listOf(
            "authorization_pending" to DeviceAuthTokenResult.Pending,
            "slow_down" to DeviceAuthTokenResult.SlowDown,
            "access_denied" to DeviceAuthTokenResult.Denied,
            "expired_token" to DeviceAuthTokenResult.Expired,
        ).forEach { (detail, expected) ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"$detail"}"""))
            assertThat(DeviceAuthService.poll(client, origin(), "device-code")).isEqualTo(expected)
        }
    }

    @Test
    fun `approved poll returns bound token device and scopes`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"rmm_secret","device_id":"device-1","scopes":["me.read","roms.read"]}""",
            ),
        )

        val result = DeviceAuthService.poll(client, origin(), "device-code")

        val approved = result as DeviceAuthTokenResult.Approved
        assertThat(approved.token.raw).isEqualTo("rmm_secret")
        assertThat(approved.deviceId).isEqualTo("device-1")
        assertThat(server.takeRequest().path).isEqualTo("/api/auth/device/token")
    }

    @Test
    fun `bearer user lookup sends returned token`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"username":"alice","admin":false}"""))

        val user = DeviceAuthService.fetchBearerUser(
            client,
            origin(),
            com.romm.androidtv.romm.ClientToken("rmm_secret"),
        )

        assertThat(user?.username).isEqualTo("alice")
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer rmm_secret")
    }

    private fun origin() = server.url("/").toString().removeSuffix("/")

    private fun request() = DeviceAuthInitRequest(
        clientDeviceIdentifier = "install-1",
        name = "Living Room TV",
        client = "rommulus",
        platform = "android-tv",
        clientVersion = "0.1.0",
        requestedScopes = RommClientTokenScopes.FOREGROUND_NATIVE,
    )
}
