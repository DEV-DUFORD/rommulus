package com.romm.androidtv.romm

/**
 * Persists a durable, server- and user-scoped device identity via
 * `POST /api/devices` (LIBRETRO_REFACTOR.md section 11.2). Later phases
 * (`SaveSyncCoordinator`) depend on this interface, not a concrete HTTP
 * client, so they can be tested without a network.
 *
 * Implementations must reuse the persisted identity across launches and
 * rotate it only on explicit sign-out/data reset or a server rejection that
 * requires re-registration.
 */
interface DeviceRepository {
    /** Returns the durable device identity for the current server + user scope, registering if needed. */
    suspend fun ensureRegistered(serverOrigin: String, username: String): DeviceRegistrationResult

    /**
     * Clears the cached RomM device identity for this scope (explicit
     * sign-out/data reset). The local installation UUID is preserved so a
     * future re-registration is still recognized as the same install.
     */
    fun forget(serverOrigin: String, username: String)
}

data class DeviceIdentity(
    val installationId: String,
    val rommDeviceId: String,
)

sealed interface DeviceRegistrationResult {
    data class Success(val identity: DeviceIdentity, val alreadyExisted: Boolean) : DeviceRegistrationResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : DeviceRegistrationResult
}
