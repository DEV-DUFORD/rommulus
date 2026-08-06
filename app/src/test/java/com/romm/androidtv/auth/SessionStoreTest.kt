package com.romm.androidtv.auth

import com.romm.androidtv.config.FakeSharedPreferences
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionStoreTest {

    private fun store(vararg pairs: Pair<String, Any?>) = SessionStore(FakeSharedPreferences().apply {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> edit().putString(k, v).commit()
                is Long -> edit().putLong(k, v).commit()
                is Boolean -> edit().putBoolean(k, v).commit()
                is Int -> edit().putInt(k, v).commit()
            }
        }
    })

    @Test
    fun `save returns true and current reflects the durable record`() {
        val store = SessionStore(FakeSharedPreferences())

        val saved = store.save("https://romm.example.com", "root")

        assertThat(saved).isTrue()
        val record = store.current()
        assertThat(record).isNotNull
        assertThat(record!!.origin).isEqualTo("https://romm.example.com")
        assertThat(record.username).isEqualTo("root")
        assertThat(record.kioskMode).isFalse()
    }

    @Test
    fun `kiosk save persists kioskMode and isKioskSession matches the origin`() {
        val store = SessionStore(FakeSharedPreferences())

        val saved = store.save("https://demo.romm.app", "kiosk", kioskMode = true)

        assertThat(saved).isTrue()
        assertThat(store.current()!!.kioskMode).isTrue()
        assertThat(store.isKioskSession("https://demo.romm.app")).isTrue()
        assertThat(store.isKioskSession("https://romm.example.com")).isFalse()
    }

    @Test
    fun `clear removes kiosk session`() {
        val store = SessionStore(FakeSharedPreferences())
        store.save("https://demo.romm.app", "kiosk", kioskMode = true)

        store.clear()

        assertThat(store.current()).isNull()
        assertThat(store.isKioskSession("https://demo.romm.app")).isFalse()
    }

    @Test
    fun `coherentRecord returns the record when it matches the profile origin`() {
        val store = store("last_origin" to "https://romm.example.com", "last_username" to "root")

        val record = store.coherentRecord("https://romm.example.com")

        assertThat(record).isNotNull
        assertThat(record!!.username).isEqualTo("root")
    }

    @Test
    fun `coherentRecord returns null for blank origin`() {
        val store = store("last_origin" to "", "last_username" to "root")

        assertThat(store.coherentRecord("https://romm.example.com")).isNull()
    }

    @Test
    fun `coherentRecord returns null for null username`() {
        val store = store("last_origin" to "https://romm.example.com", "last_username" to null)

        assertThat(store.coherentRecord("https://romm.example.com")).isNull()
    }

    @Test
    fun `coherentRecord returns null when origins differ`() {
        val store = store("last_origin" to "https://romm.example.com", "last_username" to "root")

        assertThat(store.coherentRecord("https://other.example.com")).isNull()
    }

    @Test
    fun `coherentRecord treats default-port equivalent origins as the same`() {
        val store = store("last_origin" to "https://romm.example.com:443", "last_username" to "root")

        assertThat(store.coherentRecord("https://romm.example.com")).isNotNull
    }

    @Test
    fun `coherentRecord returns null when base paths differ`() {
        val store = store("last_origin" to "https://romm.example.com/romm", "last_username" to "root")

        assertThat(store.coherentRecord("https://romm.example.com")).isNull()
    }
}
