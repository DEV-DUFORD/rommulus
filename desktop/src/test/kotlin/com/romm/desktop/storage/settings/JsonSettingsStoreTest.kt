package com.romm.desktop.storage.settings

import com.romm.androidtv.storage.contract.SettingsStoreContract
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/** Shared [SettingsStoreContract] suite wired against the JSON-file implementation. */
class JsonSettingsStoreContractTest {

    @TempDir
    lateinit var tempDir: Path

    private var caseNumber = 0

    private val contract = SettingsStoreContract {
        // Each contract case gets its own isolated file so cases do not leak state.
        val dir = Files.createDirectories(tempDir.resolve("case-${caseNumber++}"))
        JsonSettingsStore(dir.resolve("settings.json"))
    }

    @Test
    fun `contract - write returns default snapshot`() {
        contract.write_default_snapshot()
    }

    @Test
    fun `contract - write merges into existing values`() {
        contract.write_merge()
    }

    @Test
    fun `contract - clear removes the given keys`() {
        contract.clear_settings()
    }

    @Test
    fun `contract - snapshots are defensive copies`() {
        contract.snapshot_defensive_copy()
    }

    @Test
    fun `contract - boolean parsing`() {
        contract.SettingsSnapshot_boolean_parsing()
    }
}

class JsonSettingsStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun settingsPath(name: String = "settings.json"): Path = tempDir.resolve(name)

    @Test
    fun `snapshot is empty when the file is absent`() {
        val store = JsonSettingsStore(settingsPath())
        assertThat(store.snapshot().values).isEmpty()
    }

    @Test
    fun `write persists across a new instance`() {
        val path = settingsPath()
        JsonSettingsStore(path).write(mapOf("romm_origin" to "http://localhost:13378")).getOrThrow()

        val reloaded = JsonSettingsStore(path).snapshot()
        assertThat(reloaded.get("romm_origin")).isEqualTo("http://localhost:13378")
        assertThat(Files.readString(path)).contains("schemaVersion")
    }

    @Test
    fun `write merges into existing values`() {
        val store = JsonSettingsStore(settingsPath())
        store.write(mapOf("a" to "1", "b" to "2")).getOrThrow()

        val snap = store.write(mapOf("b" to "updated", "c" to "3")).getOrThrow()
        assertThat(snap.get("a")).isEqualTo("1")
        assertThat(snap.get("b")).isEqualTo("updated")
        assertThat(snap.get("c")).isEqualTo("3")
    }

    @Test
    fun `clear removes only the requested keys and persists`() {
        val path = settingsPath()
        val store = JsonSettingsStore(path)
        store.write(mapOf("a" to "1", "b" to "2", "c" to "3")).getOrThrow()

        val snap = store.clear("a", "c").getOrThrow()
        assertThat(snap.get("a")).isNull()
        assertThat(snap.get("c")).isNull()
        assertThat(snap.get("b")).isEqualTo("2")

        val reloaded = JsonSettingsStore(path).snapshot()
        assertThat(reloaded.values).isEqualTo(mapOf("b" to "2"))
    }

    @Test
    fun `clear of a missing key is a no-op success`() {
        val store = JsonSettingsStore(settingsPath())
        val result = store.clear("never_written")
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().values).isEmpty()
    }

    @Test
    fun `unknown keys survive read and write round trips`() {
        val path = settingsPath()
        Files.writeString(
            path,
            """{"schemaVersion":"1","romm_origin":"http://old","future_feature":"keep_me"}""",
        )
        val store = JsonSettingsStore(path)

        // Unknown future key is readable but not owned by this build.
        assertThat(store.snapshot().get("future_feature")).isEqualTo("keep_me")

        // A write round trip preserves it alongside known keys.
        val after = store.write(mapOf("theme" to "dark")).getOrThrow()
        assertThat(after.get("future_feature")).isEqualTo("keep_me")
        assertThat(after.get("romm_origin")).isEqualTo("http://old")
        assertThat(after.get("theme")).isEqualTo("dark")

        // And it survives on disk for a future build.
        val reloaded = JsonSettingsStore(path).snapshot()
        assertThat(reloaded.get("future_feature")).isEqualTo("keep_me")
        assertThat(Files.readString(path)).contains("\"future_feature\":\"keep_me\"")
    }

    @Test
    fun `malformed file is quarantined on snapshot and store starts fresh`() {
        val path = settingsPath()
        Files.writeString(path, "not-json {{{")
        val store = JsonSettingsStore(path)

        assertThat(store.snapshot().values).isEmpty()
        assertThat(Files.exists(path)).isFalse()

        val backups = Files.list(tempDir).use { stream ->
            stream.filter { it.fileName.toString().startsWith("settings.json.bak-") }.toList()
        }
        assertThat(backups).hasSize(1)
        // The quarantined content is preserved, not deleted.
        assertThat(Files.readString(backups.single())).isEqualTo("not-json {{{")

        // After quarantine the store is usable from a fresh state.
        val snap = store.write(mapOf("a" to "1")).getOrThrow()
        assertThat(snap.get("a")).isEqualTo("1")
    }

    @Test
    fun `write on malformed file surfaces recovery via Result failure`() {
        val path = settingsPath()
        Files.writeString(path, "garbage")
        val store = JsonSettingsStore(path)

        val result = store.write(mapOf("a" to "1"))
        assertThat(result.isFailure).isTrue()

        val recovery = result.exceptionOrNull()
        assertThat(recovery).isInstanceOf(SettingsRecoveryException::class.java)
        assertThat((recovery as SettingsRecoveryException).backupPath).exists()
        assertThat(Files.readString(recovery.backupPath)).isEqualTo("garbage")
        assertThat(Files.exists(path)).isFalse()

        // Actionable reset path: the retry now succeeds against the fresh store.
        val retry = store.write(mapOf("a" to "1"))
        assertThat(retry.isSuccess).isTrue()
        assertThat(retry.getOrThrow().get("a")).isEqualTo("1")
    }

    @Test
    fun `write failure with a directory at the target path fails cleanly and leaves it intact`() {
        val path = settingsPath()
        Files.createDirectories(path)
        val store = JsonSettingsStore(path)

        val result = store.write(mapOf("a" to "1"))
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
        // The directory is untouched and no partial file was created beside it.
        assertThat(Files.isDirectory(path)).isTrue()
        assertThat(Files.list(tempDir).use { it.count() }).isEqualTo(1L)
    }

    @Test
    fun `write failure leaves the prior file intact`() {
        val dir = Files.createDirectories(tempDir.resolve("ro"))
        val path = dir.resolve("settings.json")
        val store = JsonSettingsStore(path)
        store.write(mapOf("a" to "1")).getOrThrow()
        val original = Files.readString(path)

        // Guard: skip on non-POSIX filesystems (mirrors XdgAppPathsTest convention).
        val posixSupported = try {
            Files.getPosixFilePermissions(dir)
            true
        } catch (_: UnsupportedOperationException) {
            false
        }
        if (!posixSupported) return

        val originalPerms = Files.getPosixFilePermissions(dir)
        Files.setPosixFilePermissions(dir, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        try {
            // Guard: if the environment ignores read-only dirs (e.g. running as root), skip.
            val probe = runCatching { Files.createTempFile(dir, "probe", ".tmp") }
            if (probe.isSuccess) {
                Files.deleteIfExists(probe.getOrThrow())
                return
            }

            val result = store.write(mapOf("b" to "2"))
            assertThat(result.isFailure).isTrue()
            // The previous file content is byte-for-byte intact.
            assertThat(Files.readString(path)).isEqualTo(original)
        } finally {
            Files.setPosixFilePermissions(dir, originalPerms)
        }
    }
}
