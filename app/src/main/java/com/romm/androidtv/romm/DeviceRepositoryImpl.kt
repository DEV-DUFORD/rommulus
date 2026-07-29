package com.romm.androidtv.romm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Real [DeviceRepository] implementation backed by [RommSyncApi.registerDevice]
 * and cached via [DeviceIdentityStore] (LIBRETRO_REFACTOR.md section 11.2).
 *
 * The local installation UUID ([DeviceIdentityStore.installationId]) is
 * always sent as `client_device_identifier` so the server can dedupe
 * re-registrations from the same physical install (this is what
 * distinguishes an HTTP 200 reuse from a 201 creation). The RomM-assigned
 * device id is only cached on a successful response; a failure never
 * overwrites a previously-cached identity.
 */
class DeviceRepositoryImpl(
    private val client: OkHttpClient,
    private val identityStore: DeviceIdentityStore,
) : DeviceRepository {

    override suspend fun ensureRegistered(serverOrigin: String, username: String): DeviceRegistrationResult =
        withContext(Dispatchers.IO) {
            val installationId = identityStore.installationId(serverOrigin, username)

            val result = RommSyncApi.registerDevice(
                client,
                serverOrigin,
                DeviceRegisterRequest(
                    platform = "android",
                    client = "android-tv",
                    clientDeviceIdentifier = installationId,
                ),
            )

            when (result) {
                is DeviceRegisterResult.Success -> {
                    identityStore.saveDeviceId(serverOrigin, username, result.device.deviceId)
                    DeviceRegistrationResult.Success(
                        identity = DeviceIdentity(installationId, result.device.deviceId),
                        alreadyExisted = result.alreadyExisted,
                    )
                }
                is DeviceRegisterResult.Failure -> DeviceRegistrationResult.Failure(result.error, result.httpCode)
            }
        }

    override fun forget(serverOrigin: String, username: String) {
        identityStore.forgetDeviceId(serverOrigin, username)
    }
}
