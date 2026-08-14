package com.romm.androidtv.storage.android

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.storage.ports.SessionRecordStore
import com.romm.androidtv.storage.records.SessionRecord

/** Thin adapter: delegates [SessionRecordStore] to Android [SessionStore]. */
class SharedPreferencesSessionRecordStore(
    private val sessionStore: SessionStore,
) : SessionRecordStore {

    override fun save(record: SessionRecord): Boolean =
        sessionStore.save(
            origin = record.origin,
            username = record.username,
            verifiedAtEpochMillis = record.verifiedAtEpochMillis,
            kioskMode = record.kioskMode,
        )

    override fun current(): SessionRecord? =
        sessionStore.current()?.toSessionRecord()

    override fun clear(): Boolean {
        sessionStore.clear()
        return true
    }
}

/** Map [SessionStore.Record] → [SessionRecord] 1:1 by field name. */
internal fun SessionStore.Record.toSessionRecord(): SessionRecord =
    SessionRecord(
        origin = this.origin,
        username = this.username,
        verifiedAtEpochMillis = this.verifiedAtEpochMillis,
        kioskMode = this.kioskMode,
    )
