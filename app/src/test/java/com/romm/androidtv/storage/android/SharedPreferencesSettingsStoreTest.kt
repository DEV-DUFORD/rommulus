package com.romm.androidtv.storage.android

import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.storage.ports.SettingsKeys
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class SharedPreferencesSettingsStoreTest {

    private fun createStore(vararg preloads: Pair<String, Any?>) =
        SharedPreferencesSettingsStore(
            FakeSharedPreferences().apply {
                for ((k, v) in preloads) {
                    when (v) {
                        is String -> edit().putString(k, v).apply()
                        is Boolean -> edit().putBoolean(k, v).apply()
                        is Long -> edit().putLong(k, v).apply()
                        null -> edit().putString(k, null).apply()
                    }
                }
            }
        )

    @Nested
    inner class `snapshot` {
        @Test
        fun `empty prefs returns empty snapshot`() {
            val store = createStore()
            val snapshot = store.snapshot()
            assertThat(snapshot.values).isEmpty()
        }

        @Test
        fun `boolean keys convert to string`() {
            val store = createStore(
                SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS to true,
                SettingsKeys.VERIFY_SHA1_ON_LAUNCH to false,
            )
            val snapshot = store.snapshot()
            assertThat(snapshot.get(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS)).isEqualTo("true")
            assertThat(snapshot.get(SettingsKeys.VERIFY_SHA1_ON_LAUNCH)).isEqualTo("false")
        }

        @Test
        fun `long keys convert to string`() {
            val store = createStore(SettingsKeys.SEGACD_BIOS_ID to 42L)
            val snapshot = store.snapshot()
            assertThat(snapshot.get(SettingsKeys.SEGACD_BIOS_ID)).isEqualTo("42")
        }

        @Test
        fun `string keys pass through`() {
            val store = createStore(SettingsKeys.THEME to "RomM")
            val snapshot = store.snapshot()
            assertThat(snapshot.get(SettingsKeys.THEME)).isEqualTo("RomM")
        }

        @Test
        fun `mixed types in snapshot`() {
            val store = createStore(
                SettingsKeys.THEME to "RomM",
                SettingsKeys.ORIGIN to "https://romm.example.com",
                SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS to true,
                SettingsKeys.SEGACD_BIOS_ID to 7L,
                SettingsKeys.SEGACD_BIOS_FILE_NAME to "bios.bin",
            )
            val snapshot = store.snapshot()
            assertThat(snapshot.values).hasSize(5)
            assertThat(snapshot.get(SettingsKeys.THEME)).isEqualTo("RomM")
            assertThat(snapshot.get(SettingsKeys.ORIGIN)).isEqualTo("https://romm.example.com")
            assertThat(snapshot.get(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS)).isEqualTo("true")
            assertThat(snapshot.get(SettingsKeys.SEGACD_BIOS_ID)).isEqualTo("7")
            assertThat(snapshot.get(SettingsKeys.SEGACD_BIOS_FILE_NAME)).isEqualTo("bios.bin")
        }
    }

    @Nested
    inner class `write` {
        @Test
        fun `string key writes as string`() {
            val store = createStore()
            val result = store.write(mapOf(SettingsKeys.THEME to "RomM"))
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow().get(SettingsKeys.THEME)).isEqualTo("RomM")
        }

        @Test
        fun `boolean key writes as boolean and round-trips`() {
            val store = createStore()
            val result = store.write(mapOf(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS to "true"))
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow().get(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS)).isEqualTo("true")
            // snapshot().boolean() proves native type is Boolean (not String "true")
            assertThat(result.getOrThrow().boolean(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS, false)).isTrue()
        }

        @Test
        fun `long key writes as long`() {
            val store = createStore()
            val result = store.write(mapOf(SettingsKeys.SEGACD_BIOS_ID to "12345"))
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow().get(SettingsKeys.SEGACD_BIOS_ID)).isEqualTo("12345")
        }

        @Test
        fun `unknown key falls back to string`() {
            val store = createStore()
            val result = store.write(mapOf("unknown_key" to "value"))
            assertThat(result.isSuccess).isTrue()
            // Unknown keys are not in NATIVE_TYPES so won't appear in snapshot
            // (snapshot only iterates known keys)
        }

        @Test
        fun `write returns new snapshot reflecting changes`() {
            val store = createStore(SettingsKeys.THEME to "RomMulus")
            val result = store.write(mapOf(SettingsKeys.THEME to "RomM"))
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow().get(SettingsKeys.THEME)).isEqualTo("RomM")
        }
    }

    @Nested
    inner class `clear` {
        @Test
        fun `removes specified keys`() {
            val store = createStore(
                SettingsKeys.THEME to "RomM",
                SettingsKeys.ORIGIN to "https://romm.example.com",
            )
            val result = store.clear(SettingsKeys.THEME)
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow().get(SettingsKeys.THEME)).isNull()
            assertThat(result.getOrThrow().get(SettingsKeys.ORIGIN)).isEqualTo("https://romm.example.com")
        }

        @Test
        fun `clear multiple keys`() {
            val store = createStore(
                SettingsKeys.THEME to "RomM",
                SettingsKeys.ORIGIN to "https://romm.example.com",
                SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS to true,
            )
            val result = store.clear(SettingsKeys.THEME, SettingsKeys.ORIGIN)
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrThrow().get(SettingsKeys.THEME)).isNull()
            assertThat(result.getOrThrow().get(SettingsKeys.ORIGIN)).isNull()
            assertThat(result.getOrThrow().get(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS)).isEqualTo("true")
        }
    }

    @Nested
    inner class `full round-trip` {
        @Test
        fun `write then snapshot preserves typed values`() {
            val store = createStore()
            val writeResult = store.write(
                mapOf(
                    SettingsKeys.THEME to "RomM",
                    SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS to "true",
                    SettingsKeys.SEGACD_BIOS_ID to "42",
                    SettingsKeys.SEGACD_BIOS_FILE_NAME to "bios.bin",
                )
            )
            assertThat(writeResult.isSuccess).isTrue()
            val snapshot = store.snapshot()
            assertThat(snapshot.get(SettingsKeys.THEME)).isEqualTo("RomM")
            assertThat(snapshot.get(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS)).isEqualTo("true")
            assertThat(snapshot.get(SettingsKeys.SEGACD_BIOS_ID)).isEqualTo("42")
            assertThat(snapshot.get(SettingsKeys.SEGACD_BIOS_FILE_NAME)).isEqualTo("bios.bin")
            // Verify boolean helper works (proves native type is Boolean, not String)
            assertThat(snapshot.boolean(SettingsKeys.HIDE_UNSUPPORTED_SYSTEMS, false)).isTrue()
        }
    }
}
