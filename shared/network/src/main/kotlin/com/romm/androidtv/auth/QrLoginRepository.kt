package com.romm.androidtv.auth

import com.romm.androidtv.network.DeviceAuthInitRequest
import com.romm.androidtv.network.DeviceAuthInitResult
import com.romm.androidtv.network.DeviceAuthService
import com.romm.androidtv.network.DeviceAuthTokenResult
import com.romm.androidtv.romm.DeviceIdentityStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class QrLoginRepository(
    private val client: okhttp3.OkHttpClient,
    private val sessionStore: SessionStorage,
    private val tokenStorage: ClientTokenStorage,
    private val identityStore: DeviceIdentityStorage,
    private val deviceName: String,
    private val clientVersion: String,
) {
    private val qrClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun start(origin: String): QrLoginStartResult = withContext(Dispatchers.IO) {
        val installationId = identityStore.pairingInstallationId(origin)
            ?: return@withContext QrLoginStartResult.PersistenceFailure
        when (
            val result = DeviceAuthService.initiate(
                client = qrClient,
                origin = origin,
                request = DeviceAuthInitRequest(
                    clientDeviceIdentifier = installationId,
                    name = deviceName.take(255),
                    client = "rommulus",
                    platform = "android-tv",
                    clientVersion = clientVersion.take(50),
                    requestedScopes = RommClientTokenScopes.FOREGROUND_NATIVE,
                ),
            )
        ) {
            is DeviceAuthInitResult.Success -> QrLoginStartResult.Ready(
                QrLoginSession(
                    deviceCode = result.info.deviceCode,
                    userCode = result.info.userCode,
                    verificationUrl = result.info.verificationUrl,
                    expiresInSeconds = result.info.expiresInSeconds,
                    pollIntervalSeconds = result.info.pollIntervalSeconds,
                    installationId = installationId,
                ),
            )
            DeviceAuthInitResult.Unsupported -> QrLoginStartResult.Unsupported
            DeviceAuthInitResult.Failure -> QrLoginStartResult.NetworkFailure
        }
    }

    suspend fun poll(origin: String, session: QrLoginSession): QrLoginPollResult =
        withContext(Dispatchers.IO) {
            val result = DeviceAuthService.poll(qrClient, origin, session.deviceCode)
            currentCoroutineContext().ensureActive()
            when (result) {
                DeviceAuthTokenResult.Pending -> QrLoginPollResult.Pending
                DeviceAuthTokenResult.SlowDown -> QrLoginPollResult.SlowDown
                DeviceAuthTokenResult.Denied -> QrLoginPollResult.Denied
                DeviceAuthTokenResult.Expired -> QrLoginPollResult.Expired
                DeviceAuthTokenResult.Failure -> QrLoginPollResult.NetworkFailure
                is DeviceAuthTokenResult.Approved -> complete(origin, session, result)
            }
        }

    private suspend fun complete(
        origin: String,
        session: QrLoginSession,
        approved: DeviceAuthTokenResult.Approved,
    ): QrLoginPollResult {
        if (!approved.scopes.containsAll(RommClientTokenScopes.FOREGROUND_NATIVE)) {
            return QrLoginPollResult.InsufficientScopes
        }

        val user = DeviceAuthService.fetchBearerUser(qrClient, origin, approved.token)
            ?: return QrLoginPollResult.VerificationFailure
        currentCoroutineContext().ensureActive()
        val username = user.username ?: return QrLoginPollResult.VerificationFailure

        if (tokenStorage.setToken(origin, username, approved.token) != TokenPersistResult.Success) {
            return QrLoginPollResult.PersistenceFailure
        }
        if (tokenStorage.getToken(origin, username)?.raw != approved.token.raw) {
            tokenStorage.clearToken(origin, username)
            return QrLoginPollResult.PersistenceFailure
        }
        if (!identityStore.savePairedIdentity(origin, username, session.installationId, approved.deviceId)) {
            tokenStorage.clearToken(origin, username)
            return QrLoginPollResult.PersistenceFailure
        }
        if (!sessionStore.save(origin, username)) {
            tokenStorage.clearToken(origin, username)
            return QrLoginPollResult.PersistenceFailure
        }
        return QrLoginPollResult.Success(user)
    }
}
