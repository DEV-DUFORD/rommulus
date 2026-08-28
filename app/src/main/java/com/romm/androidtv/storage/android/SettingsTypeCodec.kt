package com.romm.androidtv.storage.android

import com.romm.androidtv.storage.ports.SettingsKeys

/** Enum of native SharedPreferences value types used by SettingsRepository. */
enum class KeyType { STRING, BOOLEAN, LONG }

/**
 * Pure helper: converts a string value to the native type expected by
 * SharedPreferences for a given settings key. Used by [SharedPreferencesSettingsStore.write].
 *
 * Factored out so it can be unit-tested without any Android dependency.
 */
object SettingsTypeCodec {

    /** Declares the native SharedPreferences type for each known settings key. */
    val NATIVE_TYPES: Map<String, KeyType> = mapOf(
        SettingsKeys.THEME to KeyType.STRING,
        SettingsKeys.ORIGIN to KeyType.STRING,
        SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS to KeyType.BOOLEAN,
        SettingsKeys.VERIFY_SHA1_ON_LAUNCH to KeyType.BOOLEAN,
        SettingsKeys.SCANLINES_ENABLED to KeyType.BOOLEAN,
        SettingsKeys.INTEGER_SCALING_ENABLED to KeyType.BOOLEAN,
        SettingsKeys.AUTOCLEAN_SAVES_ON_UPLOAD to KeyType.BOOLEAN,
        SettingsKeys.ONSCREEN_GAME_CONTROLS to KeyType.BOOLEAN,
        SettingsKeys.SEGACD_BIOS_ID to KeyType.LONG,
        SettingsKeys.SEGACD_BIOS_FILE_NAME to KeyType.STRING,
        SettingsKeys.PSX_BIOS_ID to KeyType.LONG,
        SettingsKeys.PSX_BIOS_FILE_NAME to KeyType.STRING,
    )

    /**
     * Parse a string value back to the native type for [key].
     * Returns the value that SharedPreferences would store (String, Boolean, or Long).
     * Unknown keys are returned as-is (String).
     */
    fun parseNativeValue(key: String, value: String): Any? {
        return when (NATIVE_TYPES[key]) {
            KeyType.STRING -> value
            KeyType.BOOLEAN -> value.toBoolean()
            KeyType.LONG -> value.toLong()
            null -> value
        }
    }

    /**
     * Convert a native SharedPreferences value to its canonical string form.
     * Boolean → "true"/"false", Long → toString(), String → as-is.
     */
    fun toStringValue(native: Any?): String? {
        if (native == null) return null
        return when (native) {
            is Boolean -> native.toString()
            is Long -> native.toString()
            is Int -> native.toString()
            is String -> native
            else -> native.toString()
        }
    }
}
