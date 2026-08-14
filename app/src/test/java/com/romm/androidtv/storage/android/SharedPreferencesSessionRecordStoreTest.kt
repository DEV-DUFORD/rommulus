package com.romm.androidtv.storage.android

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.storage.records.SessionRecord
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

class SharedPreferencesSessionRecordStoreTest {

    private fun createAdapter(vararg preloads: Pair<String, Any?>) =
        SharedPreferencesSessionRecordStore(
            SessionStore(
                FakeSharedPreferences().apply {
                    for ((k, v) in preloads) {
                        when (v) {
                            is String -> edit().putString(k, v).apply()
                            is Long -> edit().putLong(k, v).apply()
                            is Boolean -> edit().putBoolean(k, v).apply()
                            null -> edit().putString(k, null).apply()
                        }
                    }
                }
            )
        )

    @Nested
    inner class `save` {
        @Test
        fun `saves record and returns true`() {
            val adapter = createAdapter()
            val record = SessionRecord(
                origin = "https://romm.example.com",
                username = "alice",
                verifiedAtEpochMillis = 1_700_000_000_000L,
                kioskMode = false,
            )
            val result = adapter.save(record)
            assertThat(result).isTrue()
        }

        @Test
        fun `kiosk session saves correctly`() {
            val adapter = createAdapter()
            val record = SessionRecord(
                origin = "https://romm.example.com",
                username = null,
                verifiedAtEpochMillis = 1_700_000_000_000L,
                kioskMode = true,
            )
            val result = adapter.save(record)
            assertThat(result).isTrue()
            val current = adapter.current()
            assertThat(current?.kioskMode).isTrue()
            assertThat(current?.username).isNull()
        }
    }

    @Nested
    inner class `current` {
        @Test
        fun `returns null when no session`() {
            val adapter = createAdapter()
            assertThat(adapter.current()).isNull()
        }

        @Test
        fun `returns record after save`() {
            val adapter = createAdapter()
            val record = SessionRecord(
                origin = "https://romm.example.com",
                username = "bob",
                verifiedAtEpochMillis = 1_700_000_000_000L,
                kioskMode = false,
            )
            adapter.save(record)
            val current = adapter.current()
            assertThat(current).isNotNull()
            assertThat(current?.origin).isEqualTo("https://romm.example.com")
            assertThat(current?.username).isEqualTo("bob")
            assertThat(current?.verifiedAtEpochMillis).isEqualTo(1_700_000_000_000L)
            assertThat(current?.kioskMode).isFalse()
        }

        @Test
        fun `field mapping is one-to-one`() {
            val adapter = createAdapter()
            val record = SessionRecord(
                origin = "https://test.example.com",
                username = "charlie",
                verifiedAtEpochMillis = 9_876_543_210L,
                kioskMode = true,
            )
            adapter.save(record)
            val current = adapter.current()!!
            assertThat(current.origin).isEqualTo(record.origin)
            assertThat(current.username).isEqualTo(record.username)
            assertThat(current.verifiedAtEpochMillis).isEqualTo(record.verifiedAtEpochMillis)
            assertThat(current.kioskMode).isEqualTo(record.kioskMode)
        }
    }

    @Nested
    inner class `clear` {
        @Test
        fun `removes session and returns true`() {
            val adapter = createAdapter()
            adapter.save(
                SessionRecord(
                    origin = "https://romm.example.com",
                    username = "dave",
                    verifiedAtEpochMillis = 1_700_000_000_000L,
                )
            )
            assertThat(adapter.current()).isNotNull()
            val result = adapter.clear()
            assertThat(result).isTrue()
            assertThat(adapter.current()).isNull()
        }
    }

    @Nested
    inner class `toSessionRecord mapping` {
        @Test
        fun `preserves all fields including null username`() {
            val adapter = createAdapter(
                "last_origin" to "https://romm.example.com",
                "last_username" to null,
                "last_verified_at" to 1_234_567_890L,
                "last_kiosk_mode" to true,
            )
            val record = adapter.current()
            assertThat(record?.origin).isEqualTo("https://romm.example.com")
            assertThat(record?.username).isNull()
            assertThat(record?.verifiedAtEpochMillis).isEqualTo(1_234_567_890L)
            assertThat(record?.kioskMode).isTrue()
        }
    }
}
