package com.romm.androidtv.emulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class SavePathPolicyTest {

    private val filesDir = Files.createTempDirectory("savepathpolicy-test").toFile()

    @Test
    fun `builds the exact section 11-1 directory layout`() {
        val path = SavePathPolicy.autosaveSramPath(
            filesDir = filesDir,
            serverKey = "romm.example.com",
            userKey = "alice",
            romId = 42,
            romHash = "abc123",
        )

        val expected = File(filesDir, "saves/romm.example.com/alice/42/abc123/autosave/srm.srm").absolutePath
        assertThat(path).isEqualTo(expected)
    }

    @Test
    fun `a different rom hash resolves to a completely different path`() {
        val base = SavePathPolicy.autosaveSramPath(filesDir, "s", "u", 1, "hashA")
        val differentHash = SavePathPolicy.autosaveSramPath(filesDir, "s", "u", 1, "hashB")

        assertThat(differentHash).isNotEqualTo(base)
    }

    @Test
    fun `a different server or user resolves to a completely different path`() {
        val base = SavePathPolicy.autosaveSramPath(filesDir, "server-a", "user-a", 1, "hash")
        assertThat(SavePathPolicy.autosaveSramPath(filesDir, "server-b", "user-a", 1, "hash")).isNotEqualTo(base)
        assertThat(SavePathPolicy.autosaveSramPath(filesDir, "server-a", "user-b", 1, "hash")).isNotEqualTo(base)
    }

    @Test
    fun `a custom memory id changes only the file name`() {
        val path = SavePathPolicy.autosaveSramPath(filesDir, "s", "u", 1, "hash", memoryId = "rtc")

        assertThat(path).endsWith("rtc.srm")
    }

    @Test
    fun `romId must be positive`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            SavePathPolicy.autosaveSramPath(filesDir, "s", "u", 0, "hash")
        }
    }

    @Test
    fun `blank serverKey, userKey, or romHash are rejected`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            SavePathPolicy.autosaveSramPath(filesDir, "", "u", 1, "hash")
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            SavePathPolicy.autosaveSramPath(filesDir, "s", "", 1, "hash")
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            SavePathPolicy.autosaveSramPath(filesDir, "s", "u", 1, "")
        }
    }

    @Test
    fun `path traversal characters in a scope segment cannot escape the intended directory`() {
        val path = SavePathPolicy.autosaveSramPath(filesDir, "../../etc", "u", 1, "hash")

        assertThat(path).doesNotContain("../")
        assertThat(File(path).absolutePath).startsWith(filesDir.absolutePath)
    }

    @Test
    fun `a scope segment that is exactly dot-dot is neutralized`() {
        val path = SavePathPolicy.autosaveSramPath(filesDir, "..", "u", 1, "hash")

        assertThat(File(path).absolutePath).startsWith(filesDir.absolutePath)
    }
}
