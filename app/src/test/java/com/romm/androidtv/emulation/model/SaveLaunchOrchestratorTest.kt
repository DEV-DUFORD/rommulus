package com.romm.androidtv.emulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("SaveLaunchOrchestrator - pre-launch preparation")
class SaveLaunchOrchestratorTest {

    @Nested
    @DisplayName("PreparationResult sealed hierarchy")
    inner class PreparationResultHierarchy {

        @Test
        fun `Ready carries candidate metadata or null`() {
            val withCandidate = SaveLaunchOrchestrator.PreparationResult.Ready(
                CandidateSaveMetadata(
                    rommSessionId = 1L, rommSaveId = 2L, candidatePath = "/tmp/candidate.srm",
                    downloadedSizeBytes = 100, serverContentHash = "hash", emulator = "core",
                    romId = 1L, romHash = "h", coreId = "c", coreBuildRevision = "r",
                )
            )
            assertThat(withCandidate.candidateMetadata).isNotNull
            assertThat(withCandidate.candidateMetadata!!.rommSessionId).isEqualTo(1L)

            val withoutCandidate = SaveLaunchOrchestrator.PreparationResult.Ready(null)
            assertThat(withoutCandidate.candidateMetadata).isNull()
        }

        @Test
        fun `Conflict carries sessionId and operation`() {
            val op = com.romm.androidtv.romm.SyncOperation(
                action = com.romm.androidtv.romm.SyncAction.CONFLICT,
                romId = 1L, saveId = 5L, fileName = "srm", slot = "autosave",
                emulator = "core", reason = "both changed", serverUpdatedAt = null, serverContentHash = null,
            )
            val conflict = SaveLaunchOrchestrator.PreparationResult.Conflict(sessionId = 7L, operation = op)
            assertThat(conflict.sessionId).isEqualTo(7L)
            assertThat(conflict.operation.reason).isEqualTo("both changed")
        }

        @Test
        fun `Quarantined carries reason and path`() {
            val q = SaveLaunchOrchestrator.PreparationResult.Quarantined(
                reason = "size-mismatch", quarantinedPath = "/tmp/q.srm"
            )
            assertThat(q.reason).isEqualTo("size-mismatch")
            assertThat(q.quarantinedPath).isEqualTo("/tmp/q.srm")
        }

        @Test
        fun `Failed carries actionable reason`() {
            val f = SaveLaunchOrchestrator.PreparationResult.Failed("Save sync failed: NETWORK_ERROR.")
            assertThat(f.reason).contains("NETWORK_ERROR")
        }

        @Test
        fun `AuthExpired is a singleton data object`() {
            val ae = SaveLaunchOrchestrator.PreparationResult.AuthExpired
            // AuthExpired carries no payload; it's a typed marker for reconciliation.
            assertThat(ae).isInstanceOf(SaveLaunchOrchestrator.PreparationResult::class.java)
        }

        @Test
        fun `all PreparationResult variants are exhaustible`() {
            val results: List<SaveLaunchOrchestrator.PreparationResult> = listOf(
                SaveLaunchOrchestrator.PreparationResult.Ready(null),
                SaveLaunchOrchestrator.PreparationResult.Conflict(
                    sessionId = 1L,
                    operation = com.romm.androidtv.romm.SyncOperation(
                        action = com.romm.androidtv.romm.SyncAction.CONFLICT,
                        romId = 1L, saveId = null, fileName = "", slot = null,
                        emulator = null, reason = "", serverUpdatedAt = null, serverContentHash = null,
                    ),
                ),
                SaveLaunchOrchestrator.PreparationResult.Quarantined(reason = "x", quarantinedPath = "/tmp/x"),
                SaveLaunchOrchestrator.PreparationResult.AuthExpired,
                SaveLaunchOrchestrator.PreparationResult.Failed("error"),
            )
            // Exhaustive when compiles only if all variants are covered.
            results.forEach { result ->
                val classified: String = when (result) {
                    is SaveLaunchOrchestrator.PreparationResult.Ready -> "ready"
                    is SaveLaunchOrchestrator.PreparationResult.Conflict -> "conflict"
                    is SaveLaunchOrchestrator.PreparationResult.Quarantined -> "quarantined"
                    SaveLaunchOrchestrator.PreparationResult.AuthExpired -> "auth-expired"
                    is SaveLaunchOrchestrator.PreparationResult.Failed -> "failed"
                }
                assertThat(classified).`as`("variant $result").isNotNull
            }
        }
    }

    // ---- Defect 2: expectedSramSizeBytes known-size fast path vs unknown-size candidate path ----
    // These behavior tests live in SaveSyncCoordinatorImplTest (JVM-compatible, no android.util.Log):
    // - "download with known size adopts server bytes, confirms, and completes the session"
    // - "download with known size mismatch is quarantined and session completed as failed"
    // - "download with unknown size returns AwaitingCoreValidation, quarantines, does not confirm"
    // The orchestrator delegates to coordinator.syncBeforeLaunch which exercises these paths.
    // MainActivity now supplies expectedSramSizeBytes from existing replica (Defect 2 fix).
}
