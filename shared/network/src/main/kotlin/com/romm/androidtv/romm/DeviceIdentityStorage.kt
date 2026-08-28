package com.romm.androidtv.romm

/**
 * Platform-neutral seam over the durable, server- and user-scoped store for the
 * local installation UUID and the RomM-assigned device identity.
 *
 * Production Android is backed by [com.romm.androidtv.romm.DeviceIdentityStore]
 * via the app-side `AndroidDeviceIdentityStorage` adapter. Behavior mirrors
 * `DeviceIdentityStore` exactly for the methods the portable callers need.
 */
interface DeviceIdentityStorage {

    /** Returns the stable local installation UUID for this server + user scope, generating on first use. */
    fun installationId(origin: String, username: String): String

    /** Returns a stable identifier usable before the paired account's username is known, or null on failure. */
    fun pairingInstallationId(origin: String): String?

    /** Durably adopts the identity returned by RomM's device authorization flow. */
    fun savePairedIdentity(
        origin: String,
        username: String,
        installationId: String,
        rommDeviceId: String,
    ): Boolean

    /** Persists a newly-registered (or reused) RomM device identity for this scope. */
    fun saveDeviceId(origin: String, username: String, rommDeviceId: String)

    /** Clears the cached RomM device identity, keeping the local installation UUID. */
    fun forgetDeviceId(origin: String, username: String)
}
