package com.romm.androidtv.storage.fakes

import com.romm.androidtv.storage.ports.SettingsSnapshot
import com.romm.androidtv.storage.ports.SettingsStore

/** In-memory settings store for tests and desktop dev-loop use. */
class InMemorySettingsStore(
    initialValues: Map<String, String> = emptyMap(),
) : SettingsStore {

    private val data: MutableMap<String, String> = LinkedHashMap(initialValues)

    override fun snapshot(): SettingsSnapshot {
        synchronized(data) {
            return SettingsSnapshot(values = data.toMap())
        }
    }

    override fun write(updates: Map<String, String>): Result<SettingsSnapshot> = runCatching {
        synchronized(data) {
            data.putAll(updates)
            SettingsSnapshot(values = data.toMap())
        }
    }

    override fun clear(vararg keys: String): Result<SettingsSnapshot> = runCatching {
        synchronized(data) {
            keys.forEach { data.remove(it) }
            SettingsSnapshot(values = data.toMap())
        }
    }
}
