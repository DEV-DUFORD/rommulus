package com.romm.desktop.storage

import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.network.RommOrigin
import com.romm.androidtv.storage.ports.SessionRecordStore
import com.romm.androidtv.storage.records.SessionRecord

/**
 * Desktop [SessionStorage] seam adapter (plans/PHASE6.md §5 decision 2): the durable
 * session record lives in SQLite via a [SessionRecordStore] (production wiring passes
 * [com.romm.desktop.storage.sqlite.SqliteSessionRecordStore] over the V3 `session_records`
 * table), NOT in settings JSON.
 *
 * Behavior mirrors Android's `SessionStore` exactly so portable callers ([AuthRepository],
 * [QrLoginRepository]) see no change:
 * - [save] is a durable synchronous write returning `true` only when the record was
 *   persisted (the store maps commit failures to `false`; nothing throws across the seam);
 * - [coherentRecord] returns the stored [Record] only when it is coherent with
 *   [profileOrigin]: non-blank origin, non-blank username, and a canonically-equivalent
 *   origin (same scheme/host/effective-port/base-path after normalization, per
 *   [RommOrigin]) — otherwise null;
 * - [clear] removes the stored record.
 */
class DesktopSessionStorage(
    private val store: SessionRecordStore,
) : SessionStorage {

    override fun save(
        origin: String,
        username: String?,
        verifiedAtEpochMillis: Long,
        kioskMode: Boolean,
    ): Boolean = store.save(
        SessionRecord(
            origin = origin,
            username = username,
            verifiedAtEpochMillis = verifiedAtEpochMillis,
            kioskMode = kioskMode,
        )
    )

    override fun coherentRecord(profileOrigin: String?): com.romm.androidtv.auth.SessionStorage.Record? {
        val record = store.current()?.toSeam() ?: return null
        if (record.origin.isBlank()) return null
        if (record.username.isNullOrBlank()) return null
        if (profileOrigin.isNullOrBlank()) return null

        val recordOrigin = RommOrigin.parse(record.origin) ?: return null
        val profileParsed = RommOrigin.parse(profileOrigin) ?: return null
        val sameOrigin = recordOrigin.isSameOrigin(profileParsed) &&
            recordOrigin.path == profileParsed.path
        return if (sameOrigin) record else null
    }

    override fun clear() {
        store.clear()
    }

    private fun SessionRecord.toSeam(): com.romm.androidtv.auth.SessionStorage.Record =
        com.romm.androidtv.auth.SessionStorage.Record(
            origin,
            username,
            verifiedAtEpochMillis,
            kioskMode,
        )
}
