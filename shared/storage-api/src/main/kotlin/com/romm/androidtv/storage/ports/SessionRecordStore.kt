package com.romm.androidtv.storage.ports

import com.romm.androidtv.storage.records.SessionRecord

/** Persistence-neutral store for a single session record. */
interface SessionRecordStore {
    /** Persist [record]. Returns false on non-durable write. */
    fun save(record: SessionRecord): Boolean

    /** Return the current session, or null if none is stored. */
    fun current(): SessionRecord?

    /** Clear the stored session. Returns true on success. */
    fun clear(): Boolean
}
