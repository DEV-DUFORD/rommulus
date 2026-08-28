package com.romm.desktop.storage.sqlite

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Focused tests for [SqliteDeviceIdentityStorage] over the V3 `device_identity` table:
 * stable installation UUIDs, pairing-id semantics, save/forget device-id behavior, and
 * Android-mirroring scope sanitization. Cached RomM device ids are asserted via direct SQL
 * (the seam exposes no read method by design — it mirrors the Android interface).
 */
class SqliteDeviceIdentityStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private fun openStore(): SqliteDeviceIdentityStorage =
        SqliteDeviceIdentityStorage(
            SqliteDatabase.open(tempDir.resolve("rommulus.db")).getOrThrow(),
        )

    private fun db(): SqliteDatabase = SqliteDatabase.open(tempDir.resolve("rommulus.db")).getOrThrow()

    // ── installationId ─────────────────────────────────────────────────────────

    @Test
    fun `installation id is stable for the same origin and username`() {
        val store = openStore()
        val first = store.installationId("https://romm.example.com", "player1")
        val second = store.installationId("https://romm.example.com", "player1")

        assertThat(first).isNotBlank()
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `installation id survives reopening the database`() {
        val first = openStore().installationId("https://romm.example.com", "player1")

        val reopened = openStore().installationId("https://romm.example.com", "player1")
        assertThat(reopened).isEqualTo(first)
    }

    @Test
    fun `different users on the same server get distinct installation ids`() {
        val store = openStore()
        val idA = store.installationId("https://romm.example.com", "alice")
        val idB = store.installationId("https://romm.example.com", "bob")

        assertThat(idB).isNotEqualTo(idA)
    }

    @Test
    fun `scope keys are sanitized like android`() {
        val store = openStore()
        val canonical = store.installationId("https://romm.example.com", "Player1")
        val varied = store.installationId(" HTTPS://ROMM.EXAMPLE.COM ", " player1 ")

        assertThat(varied).isEqualTo(canonical)
    }

    // ── pairingInstallationId ──────────────────────────────────────────────────

    @Test
    fun `pairing installation id is stable per origin before a username is known`() {
        val store = openStore()
        val first = store.pairingInstallationId("https://romm.example.com")
        val second = store.pairingInstallationId("https://romm.example.com")

        assertThat(first).isNotNull
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `pairing installation id is preserved across user-scoped writes`() {
        val store = openStore()
        val pairing = store.pairingInstallationId("https://romm.example.com")!!

        // A later normal-scope write must not clobber the origin-scoped pairing id.
        store.installationId("https://romm.example.com", "player1")
        assertThat(store.pairingInstallationId("https://romm.example.com")).isEqualTo(pairing)
    }

    // ── savePairedIdentity / saveDeviceId / forgetDeviceId ─────────────────────

    @Test
    fun `save paired identity adopts the pairing id into the user scope`() {
        val store = openStore()
        val pairing = store.pairingInstallationId("https://romm.example.com")!!

        assertThat(store.savePairedIdentity("https://romm.example.com", "player1", pairing, "dev-42")).isTrue()

        // The QR flow copies the anonymous pairing value into the origin+username scope.
        assertThat(store.installationId("https://romm.example.com", "player1")).isEqualTo(pairing)
        assertThat(cachedDeviceId("https://romm.example.com", "player1")).isEqualTo("dev-42")
    }

    @Test
    fun `save device id persists the romm-assigned identity`() {
        val store = openStore()
        store.installationId("https://romm.example.com", "player1")

        store.saveDeviceId("https://romm.example.com", "player1", "dev-99")
        assertThat(cachedDeviceId("https://romm.example.com", "player1")).isEqualTo("dev-99")
    }

    @Test
    fun `forget device id clears the cached identity but keeps the installation uuid`() {
        val store = openStore()
        val installId = store.installationId("https://romm.example.com", "player1")
        store.saveDeviceId("https://romm.example.com", "player1", "dev-99")
        assertThat(cachedDeviceId("https://romm.example.com", "player1")).isEqualTo("dev-99")

        store.forgetDeviceId("https://romm.example.com", "player1")

        assertThat(cachedDeviceId("https://romm.example.com", "player1")).isNull()
        // Same install on next sign-in: the local UUID is unchanged.
        assertThat(store.installationId("https://romm.example.com", "player1")).isEqualTo(installId)
    }

    @Test
    fun `forget device id for an unknown scope is a no-op`() {
        val store = openStore()
        store.forgetDeviceId("https://nowhere.example.com", "ghost") // must not throw
        assertThat(cachedDeviceId("https://nowhere.example.com", "ghost")).isNull()
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private fun cachedDeviceId(origin: String, username: String): String? = db().use { d ->
        d.queryOne(
            "SELECT romm_device_id FROM device_identity WHERE origin = ? AND username = ?",
            { rs -> rs.getString(1) },
            origin.trim().lowercase(), username.trim().lowercase(),
        )
    }
}
