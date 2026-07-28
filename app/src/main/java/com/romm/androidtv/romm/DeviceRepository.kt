package com.romm.androidtv.romm

/**
 * Persists a durable, server- and user-scoped device identity via
 * `POST /api/devices` (LIBRETRO_REFACTOR.md section 11.2). No implementation
 * exists yet: this is a Phase 1 seam so `SaveSyncCoordinator` (a later phase)
 * can depend on an interface instead of a concrete HTTP client.
 *
 * Implementations must reuse the persisted identity across launches and
 * rotate it only on explicit sign-out/data reset or a server rejection that
 * requires re-registration.
 */
interface DeviceRepository {
    /** Returns the durable device identity for the current server + user scope, registering if needed. */
    suspend fun ensureRegistered(serverOrigin: String, username: String): DeviceIdentity
}

data class DeviceIdentity(
    val installationId: String,
    val rommDeviceId: String,
)
