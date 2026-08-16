package com.romm.desktop.network

import com.romm.androidtv.auth.AuthRepository
import com.romm.androidtv.auth.ClientTokenStorage
import com.romm.androidtv.auth.QrLoginRepository
import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.library.LibraryRepository
import com.romm.androidtv.library.LibraryRepositoryImpl
import com.romm.androidtv.network.SessionCookieSync
import com.romm.androidtv.network.ServerAddressResult
import com.romm.androidtv.network.ServerAddressResult.Valid
import com.romm.androidtv.network.RommServerAddress
import com.romm.androidtv.romm.BearerAuthInterceptor
import com.romm.androidtv.romm.DeviceIdentityStorage
import com.romm.androidtv.romm.DeviceRepository
import com.romm.androidtv.romm.DeviceRepositoryImpl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Desktop wiring module: constructs the full RomM client stack from seam interfaces.
 *
 * This module takes the four network seams (SessionStorage, ClientTokenStorage,
 * DeviceIdentityStorage, SessionCookieSync) as constructor parameters so it
 * compiles independently of their concrete implementations. The parallel task
 * in `com.romm.desktop.storage` provides the concrete classes; this module only
 * depends on the interfaces from `:shared:network`.
 *
 * The OkHttpClient is configured as a cookie-free bearer client with bounded
 * timeouts, mirroring Android's `nativeOkHttpClient` policy.
 */
class DesktopNetworkModule(
    private val sessionStorage: SessionStorage,
    private val clientTokenStorage: ClientTokenStorage,
    private val deviceIdentityStorage: DeviceIdentityStorage,
    private val sessionCookieSync: SessionCookieSync,
    private val originProvider: () -> String?,
    private val usernameProvider: () -> String?,
    deviceName: String = defaultDeviceName(),
    clientVersion: String = DEFAULT_CLIENT_VERSION,
) {
    val okHttpClient: OkHttpClient = buildOkHttpClient()
    val authRepository: AuthRepository = buildAuthRepository()
    val qrLoginRepository: QrLoginRepository = buildQrLoginRepository(deviceName, clientVersion)
    val deviceRepository: DeviceRepository = buildDeviceRepository()
    val libraryRepository: LibraryRepository = buildLibraryRepository()

    private fun buildOkHttpClient(): OkHttpClient {
        val originResult = originProvider()?.let { RommServerAddress.parseAndNormalize(it) } as? Valid
        val tokenProvider: () -> String? = tokenProvider@{
            val origin = originResult?.origin ?: return@tokenProvider null
            val username = usernameProvider() ?: return@tokenProvider null
            clientTokenStorage.getToken(origin, username)?.raw
        }

        return OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor({ originResult }, tokenProvider))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .build()
    }

    private fun buildAuthRepository(): AuthRepository {
        return AuthRepository(
            client = okHttpClient,
            cookieSync = sessionCookieSync,
            sessionStore = sessionStorage,
            clientTokenStorage = clientTokenStorage,
        )
    }

    private fun buildQrLoginRepository(deviceName: String, clientVersion: String): QrLoginRepository {
        return QrLoginRepository(
            client = okHttpClient,
            sessionStore = sessionStorage,
            tokenStorage = clientTokenStorage,
            identityStore = deviceIdentityStorage,
            deviceName = deviceName,
            clientVersion = clientVersion,
        )
    }

    private fun buildDeviceRepository(): DeviceRepository {
        return DeviceRepositoryImpl(
            client = okHttpClient,
            identityStore = deviceIdentityStorage,
        )
    }

    private fun buildLibraryRepository(): LibraryRepository {
        return LibraryRepositoryImpl(
            client = okHttpClient,
            originProvider = { originProvider() ?: "" },
            usernameProvider = usernameProvider,
        )
    }

    companion object {
        private fun defaultDeviceName(): String {
            return try {
                java.net.InetAddress.getLocalHost().hostName
            } catch (_: Exception) {
                "romm-desktop"
            }
        }

        private const val DEFAULT_CLIENT_VERSION = "1.0.0-desktop"
    }
}
