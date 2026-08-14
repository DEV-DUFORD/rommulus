package com.romm.androidtv.storage.android

import com.romm.androidtv.storage.ports.SettingsKeys
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class SettingsTypeCodecTest {

    @Nested
    inner class `parseNativeValue` {
        @Test
        fun `string keys return value as-is`() {
            assertThat(SettingsTypeCodec.parseNativeValue(SettingsKeys.THEME, "RomM"))
                .isEqualTo("RomM")
            assertThat(SettingsTypeCodec.parseNativeValue(SettingsKeys.ORIGIN, "https://romm.example.com"))
                .isEqualTo("https://romm.example.com")
            assertThat(SettingsTypeCodec.parseNativeValue(SettingsKeys.SEGACD_BIOS_FILE_NAME, "bios.bin"))
                .isEqualTo("bios.bin")
        }

        @Test
        fun `boolean keys convert to Boolean`() {
            assertThat(SettingsTypeCodec.parseNativeValue(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS, "true")).isEqualTo(true)
            assertThat(SettingsTypeCodec.parseNativeValue(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS, "false")).isEqualTo(false)
            assertThat(SettingsTypeCodec.parseNativeValue(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS, "other")).isEqualTo(false)
        }

        @Test
        fun `long keys convert to Long`() {
            assertThat(SettingsTypeCodec.parseNativeValue(SettingsKeys.SEGACD_BIOS_ID, "42"))
                .isEqualTo(42L)
            assertThat(SettingsTypeCodec.parseNativeValue(SettingsKeys.PSX_BIOS_ID, "999"))
                .isEqualTo(999L)
        }

        @Test
        fun `unknown key returns string as-is`() {
            assertThat(SettingsTypeCodec.parseNativeValue("unknown_key", "value"))
                .isEqualTo("value")
        }
    }

    @Nested
    inner class `toStringValue` {
        @Test
        fun `boolean converts to lowercase string`() {
            assertThat(SettingsTypeCodec.toStringValue(true)).isEqualTo("true")
            assertThat(SettingsTypeCodec.toStringValue(false)).isEqualTo("false")
        }

        @Test
        fun `long converts to string`() {
            assertThat(SettingsTypeCodec.toStringValue(42L)).isEqualTo("42")
            assertThat(SettingsTypeCodec.toStringValue(0L)).isEqualTo("0")
        }

        @Test
        fun `int converts to string`() {
            assertThat(SettingsTypeCodec.toStringValue(7)).isEqualTo("7")
        }

        @Test
        fun `string passes through`() {
            assertThat(SettingsTypeCodec.toStringValue("RomMulus")).isEqualTo("RomMulus")
            assertThat(SettingsTypeCodec.toStringValue("")).isEqualTo("")
        }

        @Test
        fun `null returns null`() {
            assertThat(SettingsTypeCodec.toStringValue(null)).isNull()
        }
    }

    @Nested
    inner class `round-trip` {
        @Test
        fun `boolean round-trips`() {
            val key = SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS
            val parsed = SettingsTypeCodec.parseNativeValue(key, "true")
            val str = SettingsTypeCodec.toStringValue(parsed)
            assertThat(str).isEqualTo("true")
        }

        @Test
        fun `long round-trips`() {
            val key = SettingsKeys.SEGACD_BIOS_ID
            val parsed = SettingsTypeCodec.parseNativeValue(key, "12345")
            val str = SettingsTypeCodec.toStringValue(parsed)
            assertThat(str).isEqualTo("12345")
        }

        @Test
        fun `string round-trips`() {
            val key = SettingsKeys.THEME
            val parsed = SettingsTypeCodec.parseNativeValue(key, "RomM")
            val str = SettingsTypeCodec.toStringValue(parsed)
            assertThat(str).isEqualTo("RomM")
        }
    }

    @Nested
    inner class `nativeTypes completeness` {
        @Test
        fun `all 12 settings keys have a declared type`() {
            val keys = listOf(
                SettingsKeys.THEME,
                SettingsKeys.ORIGIN,
                SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS,
                SettingsKeys.VERIFY_SHA1_ON_LAUNCH,
                SettingsKeys.SCANLINES_ENABLED,
                SettingsKeys.INTEGER_SCALING_ENABLED,
                SettingsKeys.AUTOCLEAN_SAVES_ON_UPLOAD,
                SettingsKeys.ONSCREEN_GAME_CONTROLS,
                SettingsKeys.SEGACD_BIOS_ID,
                SettingsKeys.SEGACD_BIOS_FILE_NAME,
                SettingsKeys.PSX_BIOS_ID,
                SettingsKeys.PSX_BIOS_FILE_NAME,
            )
            assertThat(keys.size).isEqualTo(12)
            for (key in keys) {
                val entry = SettingsTypeCodec.NATIVE_TYPES[key]
                    ?: error("key $key should have a declared native type")
                assertThat(entry).isNotNull()
            }
        }
    }
}
