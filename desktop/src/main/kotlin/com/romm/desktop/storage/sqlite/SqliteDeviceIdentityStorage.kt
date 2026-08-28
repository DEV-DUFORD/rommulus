package com.romm.desktop.storage.sqlite

import com.romm.androidtv.romm.DeviceIdentityStorage
import java.sql.ResultSet
import java.util.UUID

/**
 * SQLite-backed [DeviceIdentityStorage] (desktop schema v3; plans/PHASE6.md §5 decision 2).
 *
 * Mirrors the Android `DeviceIdentityStore` semantics on top of the single-row-per-origin
 * `device_identity` table:
 * - scoping keys are sanitized exactly like Android (`trim().lowercase()`) so switching
 *   accounts/servers lands on a fresh (or independently-cached) identity and the same
 *   server/user pair resolves to the same installation UUID as on Android;
 * - [installationId] returns the stable local installation UUID for the origin+username
 *   scope, generating + persisting one on first use (the `client_device_identifier` the
 *   server uses to dedupe re-registrations);
 * - [pairingInstallationId] is the origin-scoped identifier usable before the paired
 *   account's username is known (anonymous QR device authorization), generated once per
 *   origin and preserved across later user-scoped writes;
 * - [savePairedIdentity] durably adopts the identity returned by RomM's device
 *   authorization flow (the pairing value is copied into the normal scope);
 * - [forgetDeviceId] clears the cached RomM device id but keeps the local installation UUID.
 *
 * Schema deviation from Android (locked in plans/PHASE6.md §5.2): Android keys one prefs
 * entry per origin+username, while desktop stores ONE row per origin. Consequently at most
 * one user's identity is retained per origin — switching to a different username on the
 * same server generates a fresh installation UUID and discards the previous user's cached
 * RomM device id (the previous user must re-register on next sign-in). Pairing ids are
 * unaffected. All writes are fail-closed: any JDBC failure is caught and surfaced as `null`/
 * `false`, never as an exception across the seam.
 */
class SqliteDeviceIdentityStorage(private val db: SqliteDatabase) : DeviceIdentityStorage {

    override fun installationId(origin: String, username: String): String {
        val o = sanitize(origin)
        val u = sanitize(username)
        val row = lookup(o)
        if (row != null && row.username == u && row.installationId.isNotEmpty()) return row.installationId
        // New scope for this origin (first use, or a different user): generate + persist.
        // A scope switch discards the previous user's cached RomM device id — it belongs to
        // that account and must not be presented as this one (see class KDoc).
        val generated = UUID.randomUUID().toString()
        upsert(
            origin = o,
            username = u,
            installationId = generated,
            pairingInstallationId = row?.pairingInstallationId,
            rommDeviceId = if (row != null && row.username == u) row.rommDeviceId else null,
        )
        return generated
    }

    override fun pairingInstallationId(origin: String): String? {
        val o = sanitize(origin)
        val row = lookup(o)
        row?.pairingInstallationId?.let { return it }
        val generated = UUID.randomUUID().toString()
        // The table requires an installation_id per row; when this origin has no user-scoped
        // identity yet, seed a placeholder that savePairedIdentity will replace once the QR
        // flow completes (Android keeps the pair key independent in prefs).
        val ok = upsert(
            origin = o,
            username = row?.username,
            installationId = row?.installationId ?: UUID.randomUUID().toString(),
            pairingInstallationId = generated,
            rommDeviceId = row?.rommDeviceId,
        )
        return if (ok) generated else null
    }

    override fun savePairedIdentity(
        origin: String,
        username: String,
        installationId: String,
        rommDeviceId: String,
    ): Boolean = upsert(
        origin = sanitize(origin),
        username = sanitize(username),
        installationId = installationId,
        pairingInstallationId = lookup(sanitize(origin))?.pairingInstallationId,
        rommDeviceId = rommDeviceId,
    )

    override fun saveDeviceId(origin: String, username: String, rommDeviceId: String) {
        val o = sanitize(origin)
        val u = sanitize(username)
        val row = lookup(o)
        upsert(
            origin = o,
            username = u,
            installationId = if (row != null && row.username == u) row.installationId else UUID.randomUUID().toString(),
            pairingInstallationId = row?.pairingInstallationId,
            rommDeviceId = rommDeviceId,
        )
    }

    override fun forgetDeviceId(origin: String, username: String) {
        // Clears only the cached RomM device id; the local installation UUID is kept so a
        // future re-registration is still recognized as the same install by the server.
        runCatching {
            db.executeUpdate(
                "UPDATE device_identity SET romm_device_id = NULL WHERE origin = ? AND username = ?",
                sanitize(origin), sanitize(username),
            )
        }
    }

    // ---- internals ----

    private data class Row(
        val origin: String,
        val username: String?,
        val installationId: String,
        val pairingInstallationId: String?,
        val rommDeviceId: String?,
    )

    private fun lookup(origin: String): Row? = db.queryOne(
        """
        SELECT origin, username, installation_id, pairing_installation_id, romm_device_id
        FROM device_identity WHERE origin = ?
        """.trimIndent(),
        ::mapRow,
        origin,
    )

    private fun upsert(
        origin: String,
        username: String?,
        installationId: String,
        pairingInstallationId: String?,
        rommDeviceId: String?,
    ): Boolean = runCatching {
        db.executeUpdate(
            """
            INSERT INTO device_identity (
                origin, username, installation_id, pairing_installation_id, romm_device_id, updated_at_epoch_millis
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (origin) DO UPDATE SET
                username = excluded.username,
                installation_id = excluded.installation_id,
                pairing_installation_id = excluded.pairing_installation_id,
                romm_device_id = excluded.romm_device_id,
                updated_at_epoch_millis = excluded.updated_at_epoch_millis
            """.trimIndent(),
            origin, username, installationId, pairingInstallationId, rommDeviceId, System.currentTimeMillis(),
        )
    }.isSuccess

    private fun mapRow(rs: ResultSet) = Row(
        origin = rs.getString(1) ?: "",
        username = rs.getString(2),
        installationId = rs.getString(3) ?: "",
        pairingInstallationId = rs.getString(4),
        rommDeviceId = rs.getString(5),
    )

    /** Mirrors Android DeviceIdentityStore.sanitize: trim + lowercase scope keys. */
    private fun sanitize(raw: String): String = raw.trim().lowercase()
}
