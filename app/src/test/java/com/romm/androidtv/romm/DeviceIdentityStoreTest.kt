package com.romm.androidtv.romm

import com.romm.androidtv.config.FakeSharedPreferences
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeviceIdentityStoreTest {

    private lateinit var store: DeviceIdentityStore

    @BeforeEach
    fun setUp() {
        store = DeviceIdentityStore(FakeSharedPreferences())
    }

    @Test
    fun `installationId generates and persists a UUID on first use`() {
        val first = store.installationId("https://romm.example", "root")

        assertThat(first).isNotBlank()
        assertThat(store.installationId("https://romm.example", "root")).isEqualTo(first)
    }

    @Test
    fun `installationId is scoped per origin and username`() {
        val a = store.installationId("https://romm.example", "root")
        val b = store.installationId("https://romm.example", "other")
        val c = store.installationId("https://other.example", "root")

        assertThat(b).isNotEqualTo(a)
        assertThat(c).isNotEqualTo(a)
    }

    @Test
    fun `installationId scoping is case-insensitive`() {
        val a = store.installationId("https://Romm.Example", "Root")
        val b = store.installationId("https://romm.example", "root")

        assertThat(b).isEqualTo(a)
    }

    @Test
    fun `cachedDeviceId is null until saved`() {
        assertThat(store.cachedDeviceId("https://romm.example", "root")).isNull()

        store.saveDeviceId("https://romm.example", "root", "device-1")

        assertThat(store.cachedDeviceId("https://romm.example", "root")).isEqualTo("device-1")
    }

    @Test
    fun `forgetDeviceId clears the device id but keeps the installation id`() {
        val installationId = store.installationId("https://romm.example", "root")
        store.saveDeviceId("https://romm.example", "root", "device-1")

        store.forgetDeviceId("https://romm.example", "root")

        assertThat(store.cachedDeviceId("https://romm.example", "root")).isNull()
        assertThat(store.installationId("https://romm.example", "root")).isEqualTo(installationId)
    }

    @Test
    fun `forgetDeviceId does not affect a different scope`() {
        store.saveDeviceId("https://romm.example", "root", "device-1")
        store.saveDeviceId("https://romm.example", "other", "device-2")

        store.forgetDeviceId("https://romm.example", "root")

        assertThat(store.cachedDeviceId("https://romm.example", "other")).isEqualTo("device-2")
    }
}
