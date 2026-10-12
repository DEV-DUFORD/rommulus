package com.romm.androidtv.romm

import com.romm.androidtv.network.RommLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Stable tag for all auth-loop boundary diagnostics (logcat -s RommAuthDx). */
private const val TAG = "RommAuthDx"

/**
 * Real [DeviceRepository] implementation backed by [RommSyncApi.registerDevice]
 * and cached via [DeviceIdentityStore] (LIBRETRO_REFACTOR.md section 11.2).
 *
 * The RomM device id is cached for each server + user scope, whether it came
 * from registration or from device-code pairing, and reused for as long as the
 * server still has that device. `POST /api/devices` cannot dedupe for us: RomM
 * matches an existing device only by MAC address, or by hostname together with
 * platform, and drops `client_device_identifier`, which it reads only in the
 * device-code pairing flow. So a registration is made only when there is no
 * cached id, or when the server answers 404 for it (the device was deleted).
 *
 * The RomM-assigned device id is only cached on a successful response; a
 * failure never overwrites a previously-cached identity.
 */
class DeviceRepositoryImpl(
    private val client: OkHttpClient,
    private val identityStore: DeviceIdentityStorage,
) : DeviceRepository {

    override suspend fun ensureRegistered(serverOrigin: String, username: String): DeviceRegistrationResult =
        withContext(Dispatchers.IO) {
            val installationId = identityStore.installationId(serverOrigin, username)

            identityStore.cachedDeviceId(serverOrigin, username)?.let { cached ->
                when (val lookup = RommSyncApi.fetchDevice(client, serverOrigin, cached)) {
                    DeviceLookupResult.Found -> {
                        RommLog.debug(TAG, "DeviceRepository.ensureRegistered: success status=cached")
                        return@withContext DeviceRegistrationResult.Success(
                            identity = DeviceIdentity(installationId, cached),
                            alreadyExisted = true,
                        )
                    }
                    is DeviceLookupResult.Failure -> {
                        if (lookup.error != RommApiError.NOT_FOUND) {
                            RommLog.debug(TAG, "DeviceRepository.ensureRegistered: lookup failure error=${lookup.error.name} httpCode=${lookup.httpCode}")
                            return@withContext DeviceRegistrationResult.Failure(lookup.error, lookup.httpCode)
                        }
                        RommLog.debug(TAG, "DeviceRepository.ensureRegistered: cached device gone, registering again")
                        identityStore.forgetDeviceId(serverOrigin, username)
                    }
                }
            }

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
                    RommLog.debug(TAG, "DeviceRepository.ensureRegistered: success status=${if (result.alreadyExisted) "reused" else "created"}")
                    DeviceRegistrationResult.Success(
                        identity = DeviceIdentity(installationId, result.device.deviceId),
                        alreadyExisted = result.alreadyExisted,
                    )
                }
                is DeviceRegisterResult.Failure -> {
                    RommLog.debug(TAG, "DeviceRepository.ensureRegistered: failure error=${result.error.name} httpCode=${result.httpCode}")
                    DeviceRegistrationResult.Failure(result.error, result.httpCode)
                }
            }
        }

    override fun forget(serverOrigin: String, username: String) {
        identityStore.forgetDeviceId(serverOrigin, username)
    }
}
