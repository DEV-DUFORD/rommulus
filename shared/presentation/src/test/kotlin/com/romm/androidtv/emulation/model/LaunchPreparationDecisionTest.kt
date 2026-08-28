package com.romm.androidtv.emulation.model

import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncOperation
import com.romm.androidtv.romm.save.SaveSyncOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [LaunchPreparationDecision] — one test per [PreparationResult] variant,
 * plus the AUTH_EXPIRED special case inside Failure and the candidate-metadata builder.
 */
@DisplayName("LaunchPreparationDecision - pure sync-outcome mapping")
class LaunchPreparationDecisionTest {

    private class RecordingLogger : LaunchLogger {
        val entries = mutableListOf<Triple<Int, String, String>>()
        override fun log(priority: Int, tag: String, message: String) {
            entries.add(Triple(priority, tag, message))
        }
    }

    private lateinit var logger: RecordingLogger
    private lateinit var decision: LaunchPreparationDecision

    private companion object {
        const val ROM_ID = 42L
        const val ROM_HASH = "rom-hash"
        const val CORE_ID = "core-id"
        const val CORE_REVISION = "rev-1"
    }

    @BeforeEach
    fun setUp() {
        logger = RecordingLogger()
        decision = LaunchPreparationDecision(logger = logger, logTag = "TestTag")
    }

    private fun conflictOperation(): SyncOperation = SyncOperation(
        action = SyncAction.CONFLICT,
        romId = ROM_ID,
        saveId = 7L,
        fileName = "save.srm",
        slot = "autosave",
        emulator = CORE_ID,
        reason = "both-changed",
        serverUpdatedAt = null,
        serverContentHash = "server-hash",
    )

    @Nested
    @DisplayName("mapSyncOutcome")
    inner class MapSyncOutcome {

        @Test
        fun `AwaitingCoreValidation maps to Ready with fully-populated candidate metadata`() {
            val outcome = SaveSyncOutcome.AwaitingCoreValidation(
                sessionId = 11L,
                rommSaveId = 22L,
                quarantinedPath = "/data/app/saves/candidate.srm",
                downloadedSizeBytes = 131072L,
                serverContentHash = "server-hash",
                emulator = CORE_ID,
            )

            val result = decision.mapSyncOutcome(outcome, ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION)

            assertThat(result).isInstanceOf(PreparationResult.Ready::class.java)
            val candidate = (result as PreparationResult.Ready).candidateMetadata
            assertThat(candidate).isNotNull
            assertThat(candidate!!.rommSessionId).isEqualTo(11L)
            assertThat(candidate.rommSaveId).isEqualTo(22L)
            assertThat(candidate.candidatePath).isEqualTo("/data/app/saves/candidate.srm")
            assertThat(candidate.downloadedSizeBytes).isEqualTo(131072L)
            assertThat(candidate.serverContentHash).isEqualTo("server-hash")
            assertThat(candidate.emulator).isEqualTo(CORE_ID)
            assertThat(candidate.romId).isEqualTo(ROM_ID)
            assertThat(candidate.romHash).isEqualTo(ROM_HASH)
            assertThat(candidate.coreId).isEqualTo(CORE_ID)
            assertThat(candidate.coreBuildRevision).isEqualTo(CORE_REVISION)
        }

        @Test
        fun `Downloaded maps to Ready without candidate`() {
            val outcome = SaveSyncOutcome.Downloaded(sessionId = 1L, rommSaveId = 2L, sizeBytes = 100L, confirmed = true)

            val result = decision.mapSyncOutcome(outcome, ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION)

            assertThat(result).isEqualTo(PreparationResult.Ready(null))
        }

        @Test
        fun `NoOpSynced maps to Ready without candidate`() {
            val outcome = SaveSyncOutcome.NoOpSynced(sessionId = 1L)

            val result = decision.mapSyncOutcome(outcome, ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION)

            assertThat(result).isEqualTo(PreparationResult.Ready(null))
        }

        @Test
        fun `UploadQueued maps to Ready without candidate`() {
            val outcome = SaveSyncOutcome.UploadQueued(sessionId = 1L, pendingOperationId = 99L)

            val result = decision.mapSyncOutcome(outcome, ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION)

            assertThat(result).isEqualTo(PreparationResult.Ready(null))
        }

        @Test
        fun `ConflictRequiresResolution maps to Conflict carrying sessionId and operation`() {
            val operation = conflictOperation()
            val outcome = SaveSyncOutcome.ConflictRequiresResolution(sessionId = 7L, operation = operation)

            val result = decision.mapSyncOutcome(outcome, ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION)

            assertThat(result).isEqualTo(PreparationResult.Conflict(sessionId = 7L, operation = operation))
        }

        @Test
        fun `Quarantined maps to Quarantined carrying reason and path`() {
            val outcome = SaveSyncOutcome.Quarantined(reason = "size-mismatch", quarantinedPath = "/data/app/saves/q.srm")

            val result = decision.mapSyncOutcome(outcome, ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION)

            assertThat(result).isEqualTo(
                PreparationResult.Quarantined(reason = "size-mismatch", quarantinedPath = "/data/app/saves/q.srm")
            )
        }

        @Test
        fun `PlayOfflineLocal maps to OfflineLocal with error name`() {
            val outcome = SaveSyncOutcome.PlayOfflineLocal(error = RommApiError.NETWORK_ERROR, httpCode = null)

            val result = decision.mapSyncOutcome(outcome, ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION)

            assertThat(result).isEqualTo(PreparationResult.OfflineLocal("NETWORK_ERROR"))
        }

        @Test
        fun `Failure with AUTH_EXPIRED maps to AuthExpired`() {
            val outcome = SaveSyncOutcome.Failure(error = RommApiError.AUTH_EXPIRED, httpCode = 401)

            val result = decision.mapSyncOutcome(outcome, ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION)

            assertThat(result).isEqualTo(PreparationResult.AuthExpired)
        }

        @Test
        fun `Failure with non-auth error maps to Failed with error name in reason`() {
            val outcome = SaveSyncOutcome.Failure(error = RommApiError.NETWORK_ERROR, httpCode = null)

            val result = decision.mapSyncOutcome(outcome, ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION)

            assertThat(result).isEqualTo(PreparationResult.Failed("Save sync failed: NETWORK_ERROR"))
        }
    }

    @Nested
    @DisplayName("buildCandidateMetadata")
    inner class BuildCandidateMetadata {

        @Test
        fun `builds metadata with all authoritative fields`() {
            val metadata = decision.buildCandidateMetadata(
                rommSessionId = 0L, // valid sentinel: explicit adoption without negotiate session
                rommSaveId = 5L,
                quarantinedPath = "/data/app/saves/candidate.srm",
                downloadedSizeBytes = 64L,
                serverContentHash = null,
                emulator = null,
                romId = ROM_ID,
                romHash = ROM_HASH,
                coreId = CORE_ID,
                coreBuildRevision = CORE_REVISION,
            )

            assertThat(metadata.rommSessionId).isZero()
            assertThat(metadata.rommSaveId).isEqualTo(5L)
            assertThat(metadata.candidatePath).isEqualTo("/data/app/saves/candidate.srm")
            assertThat(metadata.downloadedSizeBytes).isEqualTo(64L)
            assertThat(metadata.serverContentHash).isNull()
            assertThat(metadata.emulator).isNull()
            assertThat(metadata.romId).isEqualTo(ROM_ID)
            assertThat(metadata.romHash).isEqualTo(ROM_HASH)
            assertThat(metadata.coreId).isEqualTo(CORE_ID)
            assertThat(metadata.coreBuildRevision).isEqualTo(CORE_REVISION)
        }

        @Test
        fun `matches the outcome-derived candidate from mapSyncOutcome`() {
            val outcome = SaveSyncOutcome.AwaitingCoreValidation(
                sessionId = 3L,
                rommSaveId = 4L,
                quarantinedPath = "/data/app/saves/c.srm",
                downloadedSizeBytes = 10L,
                serverContentHash = "h",
                emulator = "e",
            )
            val fromOutcome = (decision.mapSyncOutcome(
                outcome, ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION
            ) as PreparationResult.Ready).candidateMetadata!!

            val direct = decision.buildCandidateMetadata(
                rommSessionId = 3L,
                rommSaveId = 4L,
                quarantinedPath = "/data/app/saves/c.srm",
                downloadedSizeBytes = 10L,
                serverContentHash = "h",
                emulator = "e",
                romId = ROM_ID,
                romHash = ROM_HASH,
                coreId = CORE_ID,
                coreBuildRevision = CORE_REVISION,
            )

            assertThat(fromOutcome).isEqualTo(direct)
        }
    }

    @Nested
    @DisplayName("logging")
    inner class Logging {

        @Test
        fun `logs at expected priorities for each branch`() {
            decision.mapSyncOutcome(
                SaveSyncOutcome.AwaitingCoreValidation(1L, 2L, "/p", 3L, null, null),
                ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION
            )
            decision.mapSyncOutcome(SaveSyncOutcome.Downloaded(1L, 2L, 3L, true), ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION)
            decision.mapSyncOutcome(SaveSyncOutcome.Failure(RommApiError.SERVER_ERROR), ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION)

            val priorities = logger.entries.map { it.first }
            assertThat(priorities).containsExactly(
                LaunchLogPriority.INFO, // AwaitingCoreValidation
                LaunchLogPriority.DEBUG, // Downloaded
                LaunchLogPriority.WARN, // Failure
            )
            assertThat(logger.entries.map { it.second }).containsOnly("TestTag")
        }

        @Test
        fun `default logger is a no-op and mapping still works`() {
            val silent = LaunchPreparationDecision()
            val result = silent.mapSyncOutcome(
                SaveSyncOutcome.NoOpSynced(1L), ROM_ID, ROM_HASH, CORE_ID, CORE_REVISION
            )
            assertThat(result).isEqualTo(PreparationResult.Ready(null))
        }
    }
}
