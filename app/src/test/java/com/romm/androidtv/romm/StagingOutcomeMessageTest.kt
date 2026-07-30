package com.romm.androidtv.romm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * JVM tests for [StagingOutcomeMessage]. Pure function, no Android dependencies.
 * Verifies every StagingOutcome variant maps to concise actionable user text,
 * never raw toString() or object identity.
 */
@DisplayName("StagingOutcomeMessage — pure outcome-to-text mapper")
class StagingOutcomeMessageTest {

    @Nested
    @DisplayName("Success")
    inner class SuccessTests {
        @Test
        fun `maps to ready message`() {
            val spec = com.romm.androidtv.emulation.model.LaunchSpec(
                romId = 1, romHash = "hash", contentPath = "/path", coreId = "core",
                backend = com.romm.androidtv.config.PlaybackBackend.NATIVE_LIBRETRO,
                sessionId = java.util.UUID.randomUUID(), serverSaveFileName = "test.srm"
            )
            assertThat(StagingOutcomeMessage.toUserMessage(StagingOutcome.Success(spec)))
                .isEqualTo("Ready to launch")
        }
    }

    @Nested
    @DisplayName("NoApprovedCore")
    inner class NoApprovedCoreTests {
        @Test
        fun `includes platform slug`() {
            assertThat(StagingOutcomeMessage.toUserMessage(StagingOutcome.NoApprovedCore("gb")))
                .isEqualTo("No approved emulator core for gb")
        }
    }

    @Nested
    @DisplayName("UnsupportedMultiFile")
    inner class UnsupportedMultiFileTests {
        @Test
        fun `includes file count`() {
            assertThat(StagingOutcomeMessage.toUserMessage(StagingOutcome.UnsupportedMultiFile(3)))
                .isEqualTo("This ROM has 3 files; only single-file ROMs are supported")
        }
    }

    @Nested
    @DisplayName("UnsupportedArchiveFormat")
    inner class UnsupportedArchiveFormatTests {
        @Test
        fun `includes extension`() {
            assertThat(StagingOutcomeMessage.toUserMessage(StagingOutcome.UnsupportedArchiveFormat("rar")))
                .isEqualTo("Archive format .rar is not supported")
        }
    }

    @Nested
    @DisplayName("ArchiveExtractionFailed")
    inner class ArchiveExtractionFailedTests {
        @Test
        fun `includes reason`() {
            assertThat(StagingOutcomeMessage.toUserMessage(StagingOutcome.ArchiveExtractionFailed("path traversal detected")))
                .isEqualTo("Could not extract ROM archive: path traversal detected")
        }
    }

    @Nested
    @DisplayName("RomNotFound")
    inner class RomNotFoundTests {
        @Test
        fun `maps to not found message`() {
            assertThat(StagingOutcomeMessage.toUserMessage(StagingOutcome.RomNotFound))
                .isEqualTo("ROM not found on server")
        }
    }

    @Nested
    @DisplayName("AuthExpired")
    inner class AuthExpiredTests {
        @Test
        fun `maps to re-auth message`() {
            assertThat(StagingOutcomeMessage.toUserMessage(StagingOutcome.AuthExpired))
                .isEqualTo("Session expired; please log in again")
        }
    }

    @Nested
    @DisplayName("InsufficientSpace")
    inner class InsufficientSpaceTests {
        @Test
        fun `includes space details`() {
            val outcome = StagingOutcome.InsufficientSpace(requiredBytes = 2_048_000, availableBytes = 512_000)
            val msg = StagingOutcomeMessage.toUserMessage(outcome)
            assertThat(msg).contains("Not enough storage space")
            assertThat(msg).contains("2000 KB")
            assertThat(msg).contains("500 KB")
        }
    }

    @Nested
    @DisplayName("CorruptedDownload")
    inner class CorruptedDownloadTests {
        @Test
        fun `includes corruption reason`() {
            assertThat(StagingOutcomeMessage.toUserMessage(StagingOutcome.CorruptedDownload("hash mismatch")))
                .isEqualTo("Download corrupted: hash mismatch")
        }
    }

    @Nested
    @DisplayName("NetworkError")
    inner class NetworkErrorTests {
        @Test
        fun `includes error message`() {
            assertThat(StagingOutcomeMessage.toUserMessage(StagingOutcome.NetworkError("timeout")))
                .isEqualTo("timeout")
        }

        @Test
        fun `blank message falls back to generic`() {
            assertThat(StagingOutcomeMessage.toUserMessage(StagingOutcome.NetworkError("")))
                .isEqualTo("Network error")
        }
    }

    @Nested
    @DisplayName("Never raw toString")
    inner class NoRawToString {
        @Test
        fun `NoApprovedCore does not contain class name`() {
            val msg = StagingOutcomeMessage.toUserMessage(StagingOutcome.NoApprovedCore("gb"))
            assertThat(msg).doesNotContain("NoApprovedCore")
            assertThat(msg).doesNotContain("@")
        }

        @Test
        fun `NetworkError does not contain class name`() {
            val msg = StagingOutcomeMessage.toUserMessage(StagingOutcome.NetworkError("fail"))
            assertThat(msg).doesNotContain("NetworkError")
            assertThat(msg).doesNotContain("@")
        }

        @Test
        fun `RomNotFound does not contain class name`() {
            val msg = StagingOutcomeMessage.toUserMessage(StagingOutcome.RomNotFound)
            assertThat(msg).doesNotContain("RomNotFound")
        }
    }
}
