package com.romm.desktop.storage.secret

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class FileSecretBackendTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `file backend round trips overwrites and deletes scopes`() {
        val backend = FileSecretBackend(tempDir.resolve("credentials/tokens.properties"))

        assertThat(backend.state()).isEqualTo(KeyringState.Available)
        assertThat(backend.store("one", "first")).isTrue()
        assertThat(backend.store("two", "second")).isTrue()
        assertThat(backend.store("one", "updated")).isTrue()
        assertThat(backend.retrieve("one")).isEqualTo("updated")
        assertThat(backend.retrieve("two")).isEqualTo("second")

        backend.delete("one")
        assertThat(backend.retrieve("one")).isNull()
        assertThat(backend.retrieve("two")).isEqualTo("second")
        backend.deleteAll()
        assertThat(backend.retrieve("two")).isNull()
    }

    @Test
    fun `file backend enforces owner-only permissions`() {
        val file = tempDir.resolve("credentials/tokens.properties")
        val backend = FileSecretBackend(file)

        assertThat(backend.store("scope", "secret")).isTrue()
        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        assertThat(Files.getPosixFilePermissions(file.parent)).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
    }

    @Test
    fun `fallback is used only when secret service is unavailable`() {
        val primary = FakeSecretBackend()
        val fallback = FakeSecretBackend()
        val backend = UnavailableSecretServiceFallback(primary, fallback)

        primary.mode = KeyringState.Unavailable
        assertThat(backend.store("scope", "deck-token")).isTrue()
        assertThat(fallback.retrieve("scope")).isEqualTo("deck-token")

        primary.mode = KeyringState.Locked
        assertThat(backend.state()).isEqualTo(KeyringState.Locked)
        assertThat(backend.retrieve("scope")).isNull()
        assertThat(backend.store("scope", "replacement")).isFalse()

        primary.mode = KeyringState.Denied("denied")
        assertThat(backend.state()).isEqualTo(KeyringState.Denied("denied"))
        assertThat(backend.retrieve("scope")).isNull()
    }

    @Test
    fun `fallback token migrates when secret service becomes available`() {
        val primary = FakeSecretBackend().apply { mode = KeyringState.Unavailable }
        val fallback = FakeSecretBackend()
        val backend = UnavailableSecretServiceFallback(primary, fallback)

        assertThat(backend.store("scope", "deck-token")).isTrue()
        primary.mode = KeyringState.Available

        assertThat(backend.retrieve("scope")).isEqualTo("deck-token")
        assertThat(primary.retrieve("scope")).isEqualTo("deck-token")
        assertThat(fallback.retrieve("scope")).isNull()
    }
}
