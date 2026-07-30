package com.romm.androidtv.emulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class CandidateSaveMetadataTest {

    @Test
    fun `valid candidate metadata is accepted`() {
        val meta = CandidateSaveMetadata(
            rommSessionId = 7L,
            rommSaveId = 10L,
            candidatePath = "/data/user/0/com.romm.androidtv.debug/files/saves/server/alice/1/hash-a/autosave/quarantine/candidate.srm",
            downloadedSizeBytes = 32768L,
            serverContentHash = "hash-abc",
            emulator = "sameboy",
            romId = 42L,
            romHash = "sha256-of-rom",
            coreId = "sameboy",
            coreBuildRevision = "v1.0.3-libretro",
        )

        assertThat(meta.rommSessionId).isEqualTo(7L)
        assertThat(meta.rommSaveId).isEqualTo(10L)
        assertThat(meta.downloadedSizeBytes).isEqualTo(32768L)
        assertThat(meta.romId).isEqualTo(42L)
        assertThat(meta.romHash).isEqualTo("sha256-of-rom")
        assertThat(meta.coreId).isEqualTo("sameboy")
        assertThat(meta.coreBuildRevision).isEqualTo("v1.0.3-libretro")
    }

    @Test
    fun `zero rommSessionId throws`() {
        assertThatThrownBy {
            CandidateSaveMetadata(rommSessionId = 0L, rommSaveId = 1L, candidatePath = "/path", downloadedSizeBytes = 1L, serverContentHash = null, emulator = null, romId = 1L, romHash = "hash", coreId = "core", coreBuildRevision = "rev")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank candidate path throws`() {
        assertThatThrownBy {
            CandidateSaveMetadata(rommSessionId = 1L, rommSaveId = 1L, candidatePath = "", downloadedSizeBytes = 1L, serverContentHash = null, emulator = null, romId = 1L, romHash = "hash", coreId = "core", coreBuildRevision = "rev")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `zero rommSaveId throws`() {
        assertThatThrownBy {
            CandidateSaveMetadata(rommSessionId = 1L, rommSaveId = 0L, candidatePath = "/path", downloadedSizeBytes = 1L, serverContentHash = null, emulator = null, romId = 1L, romHash = "hash", coreId = "core", coreBuildRevision = "rev")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `zero downloadedSizeBytes throws`() {
        assertThatThrownBy {
            CandidateSaveMetadata(rommSessionId = 1L, rommSaveId = 1L, candidatePath = "/path", downloadedSizeBytes = 0L, serverContentHash = null, emulator = null, romId = 1L, romHash = "hash", coreId = "core", coreBuildRevision = "rev")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `zero romId throws`() {
        assertThatThrownBy {
            CandidateSaveMetadata(rommSessionId = 1L, rommSaveId = 1L, candidatePath = "/path", downloadedSizeBytes = 1L, serverContentHash = null, emulator = null, romId = 0L, romHash = "hash", coreId = "core", coreBuildRevision = "rev")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank romHash throws`() {
        assertThatThrownBy {
            CandidateSaveMetadata(rommSessionId = 1L, rommSaveId = 1L, candidatePath = "/path", downloadedSizeBytes = 1L, serverContentHash = null, emulator = null, romId = 1L, romHash = "", coreId = "core", coreBuildRevision = "rev")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank coreId throws`() {
        assertThatThrownBy {
            CandidateSaveMetadata(rommSessionId = 1L, rommSaveId = 1L, candidatePath = "/path", downloadedSizeBytes = 1L, serverContentHash = null, emulator = null, romId = 1L, romHash = "hash", coreId = "", coreBuildRevision = "rev")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank coreBuildRevision throws`() {
        assertThatThrownBy {
            CandidateSaveMetadata(rommSessionId = 1L, rommSaveId = 1L, candidatePath = "/path", downloadedSizeBytes = 1L, serverContentHash = null, emulator = null, romId = 1L, romHash = "hash", coreId = "core", coreBuildRevision = "")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `validateAppPrivate_accepts_path_within_base_directory`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "candidate-test")
        tempDir.mkdirs()
        val nestedPath = File(tempDir, "saves/server/user/rom/quarantine/candidate.srm").absolutePath

        val meta = CandidateSaveMetadata(rommSessionId = 1L, rommSaveId = 1L, candidatePath = nestedPath, downloadedSizeBytes = 100L, serverContentHash = null, emulator = null, romId = 1L, romHash = "hash", coreId = "core", coreBuildRevision = "rev")
        val result = meta.validateAppPrivate(tempDir)

        assertThat(result.isSuccess).isTrue()
        tempDir.deleteRecursively()
    }

    @Test
    fun `validateAppPrivate_rejects_path_traversal_with_dotdot`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "candidate-test")
        tempDir.mkdirs()
        // A path that contains ".." but resolves outside the base.
        val escapePath = File(tempDir, "../etc/passwd").absolutePath

        val meta = CandidateSaveMetadata(rommSessionId = 1L, rommSaveId = 1L, candidatePath = escapePath, downloadedSizeBytes = 100L, serverContentHash = null, emulator = null, romId = 1L, romHash = "hash", coreId = "core", coreBuildRevision = "rev")
        val result = meta.validateAppPrivate(tempDir)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        tempDir.deleteRecursively()
    }

    @Test
    fun `validateAppPrivate_rejects_path_outside_base_directory`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "candidate-test")
        tempDir.mkdirs()

        val meta = CandidateSaveMetadata(rommSessionId = 1L, rommSaveId = 1L, candidatePath = "/system/bin/sh", downloadedSizeBytes = 100L, serverContentHash = null, emulator = null, romId = 1L, romHash = "hash", coreId = "core", coreBuildRevision = "rev")
        val result = meta.validateAppPrivate(tempDir)

        assertThat(result.isFailure).isTrue()
        tempDir.deleteRecursively()
    }

    @Test
    fun `validateAppPrivate_rejects_relative_path`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "candidate-test")
        tempDir.mkdirs()

        val meta = CandidateSaveMetadata(rommSessionId = 1L, rommSaveId = 1L, candidatePath = "relative/path.srm", downloadedSizeBytes = 100L, serverContentHash = null, emulator = null, romId = 1L, romHash = "hash", coreId = "core", coreBuildRevision = "rev")
        val result = meta.validateAppPrivate(tempDir)

        assertThat(result.isFailure).isTrue()
        tempDir.deleteRecursively()
    }

    private fun assertThatThrownBy(block: () -> Unit) = org.assertj.core.api.Assertions.assertThatThrownBy(block)
}
