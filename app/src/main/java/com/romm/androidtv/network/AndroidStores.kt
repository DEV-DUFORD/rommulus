package com.romm.androidtv.network

import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.romm.DeviceIdentityStorage
import com.romm.androidtv.romm.DeviceIdentityStore

/**
 * Android adapters wiring the portable platform seams (in `:shared:network`) to
 * the existing Android-backed store classes. Behavior is delegated 1:1 so the
 * portable callers see exactly what the Android stores previously produced.
 */
class AndroidSessionStorage(private val delegate: SessionStore) : SessionStorage {
    override fun save(
        origin: String,
        username: String?,
        verifiedAtEpochMillis: Long,
        kioskMode: Boolean,
    ): Boolean = delegate.save(origin, username, verifiedAtEpochMillis, kioskMode)

    override fun coherentRecord(profileOrigin: String?): SessionStorage.Record? =
        delegate.coherentRecord(profileOrigin)?.let {
            SessionStorage.Record(
                origin = it.origin,
                username = it.username,
                verifiedAtEpochMillis = it.verifiedAtEpochMillis,
                kioskMode = it.kioskMode,
            )
        }

    override fun clear() = delegate.clear()
}

class AndroidDeviceIdentityStorage(private val delegate: DeviceIdentityStore) : DeviceIdentityStorage {
    override fun installationId(origin: String, username: String): String =
        delegate.installationId(origin, username)

    override fun pairingInstallationId(origin: String): String? =
        delegate.pairingInstallationId(origin)

    override fun savePairedIdentity(
        origin: String,
        username: String,
        installationId: String,
        rommDeviceId: String,
    ): Boolean = delegate.savePairedIdentity(origin, username, installationId, rommDeviceId)

    override fun saveDeviceId(origin: String, username: String, rommDeviceId: String) =
        delegate.saveDeviceId(origin, username, rommDeviceId)

    override fun forgetDeviceId(origin: String, username: String) =
        delegate.forgetDeviceId(origin, username)
}

class AndroidSessionCookieSync(private val delegate: RomMCookieSync) : SessionCookieSync {
    override suspend fun syncToWebView(origin: String) = delegate.syncToWebView(origin)

    override fun importFromWebView(origin: String) = delegate.importFromWebView(origin)
}
