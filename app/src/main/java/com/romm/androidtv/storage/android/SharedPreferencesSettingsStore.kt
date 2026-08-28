package com.romm.androidtv.storage.android

import android.content.SharedPreferences
import com.romm.androidtv.storage.ports.SettingsKeys
import com.romm.androidtv.storage.ports.SettingsSnapshot
import com.romm.androidtv.storage.ports.SettingsStore

/** Thin adapter: delegates [SettingsStore] to Android [SharedPreferences]. */
class SharedPreferencesSettingsStore(
    private val prefs: SharedPreferences,
) : SettingsStore {

    override fun snapshot(): SettingsSnapshot {
        val values = mutableMapOf<String, String>()
        for ((key, type) in SettingsTypeCodec.NATIVE_TYPES) {
            if (!prefs.contains(key)) continue
            val native = when (type) {
                KeyType.STRING -> prefs.getString(key, null)
                KeyType.BOOLEAN -> prefs.getBoolean(key, false)
                KeyType.LONG -> prefs.getLong(key, 0L)
            }
            val str = SettingsTypeCodec.toStringValue(native) ?: continue
            values[key] = str
        }
        return SettingsSnapshot(values)
    }

    override fun write(updates: Map<String, String>): Result<SettingsSnapshot> = runCatching {
        val editor = prefs.edit()
        for ((key, value) in updates) {
            val native = SettingsTypeCodec.parseNativeValue(key, value)
            when (native) {
                is String -> editor.putString(key, native)
                is Boolean -> editor.putBoolean(key, native)
                is Long -> editor.putLong(key, native)
                else -> editor.putString(key, value)
            }
        }
        editor.apply()
        snapshot()
    }

    override fun clear(vararg keys: String): Result<SettingsSnapshot> = runCatching {
        val editor = prefs.edit()
        for (key in keys) {
            editor.remove(key)
        }
        editor.apply()
        snapshot()
    }
}
