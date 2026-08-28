package com.romm.androidtv.storage.fakes

import com.romm.androidtv.storage.ports.SessionRecordStore
import com.romm.androidtv.storage.records.SessionRecord

/** In-memory session store for tests and desktop dev-loop use. */
class InMemorySessionRecordStore : SessionRecordStore {

    @Volatile private var stored: SessionRecord? = null

    override fun save(record: SessionRecord): Boolean {
        stored = record
        return true
    }

    override fun current(): SessionRecord? = stored

    override fun clear(): Boolean {
        stored = null
        return true
    }
}
