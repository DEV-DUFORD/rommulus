package com.romm.androidtv.library.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [SavePreLaunchState].
 * Validates scope isolation, recomposition survival semantics (via MutableVar),
 * duplicate-submission guard, and clear() behavior.
 */
@DisplayName("SavePreLaunchState — scoped pre-launch overlay state")
class SavePreLaunchStateTest {

    @Nested
    @DisplayName("Scope matching")
    inner class ScopeMatching {

        @Test
        fun `matchesScope returns true for same romId and null sessionId`() {
            val state = SavePreLaunchState(romId = 42, sessionId = null)
            assertThat(state.matchesScope(42, null)).isTrue()
            assertThat(state.matchesScope(42L, 99L)).isTrue() // null sessionId matches any
        }

        @Test
        fun `matchesScope returns true for same romId and matching sessionId`() {
            val state = SavePreLaunchState(romId = 42, sessionId = 7L)
            assertThat(state.matchesScope(42, 7)).isTrue()
        }

        @Test
        fun `matchesScope returns false for different romId`() {
            val state = SavePreLaunchState(romId = 42, sessionId = 7L)
            assertThat(state.matchesScope(99, 7)).isFalse()
        }

        @Test
        fun `matchesScope returns false for different sessionId`() {
            val state = SavePreLaunchState(romId = 42, sessionId = 7L)
            assertThat(state.matchesScope(42, 8)).isFalse()
        }

        @Test
        fun `sameScope returns true for identical scopes`() {
            val a = SavePreLaunchState(romId = 42, sessionId = 7L)
            val b = SavePreLaunchState(romId = 42, sessionId = 7L)
            assertThat(SavePreLaunchState.sameScope(a, b)).isTrue()
        }

        @Test
        fun `sameScope returns false for different romId`() {
            val a = SavePreLaunchState(romId = 42, sessionId = 7L)
            val b = SavePreLaunchState(romId = 99, sessionId = 7L)
            assertThat(SavePreLaunchState.sameScope(a, b)).isFalse()
        }

        @Test
        fun `sameScope returns false for different sessionId`() {
            val a = SavePreLaunchState(romId = 42, sessionId = 7L)
            val b = SavePreLaunchState(romId = 42, sessionId = 8L)
            assertThat(SavePreLaunchState.sameScope(a, b)).isFalse()
        }

        @Test
        fun `sameScope returns false when either is null`() {
            val state = SavePreLaunchState(romId = 42)
            assertThat(SavePreLaunchState.sameScope(null, state)).isFalse()
            assertThat(SavePreLaunchState.sameScope(state, null)).isFalse()
            assertThat(SavePreLaunchState.sameScope(null, null)).isFalse()
        }
    }

    @Nested
    @DisplayName("Overlay presence")
    inner class OverlayPresence {

        @Test
        fun `hasOverlay is false initially`() {
            val state = SavePreLaunchState(romId = 42)
            assertThat(state.hasOverlay).isFalse()
        }

        @Test
        fun `hasOverlay is true when conflictModel is set`() {
            val state = SavePreLaunchState(romId = 42)
            state.conflictModel = SaveConflictUiModel(
                description = "test",
                local = SaveConflictSide(label = "Local", saveId = null, fileName = "x.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
                server = SaveConflictSide(label = "Server", saveId = null, fileName = "y.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
            )
            assertThat(state.hasOverlay).isTrue()
        }

        @Test
        fun `hasOverlay is true when quarantineModel is set`() {
            val state = SavePreLaunchState(romId = 42)
            state.quarantineModel = SaveQuarantineUiModel(
                reason = "size-mismatch",
                description = "test",
                quarantined = SaveConflictSide(label = "Q", saveId = null, fileName = "z.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
                quarantinedPath = "/tmp/z.srm",
            )
            assertThat(state.hasOverlay).isTrue()
        }

        @Test
        fun `hasOverlay is true when only errorMessage is set`() {
            val state = SavePreLaunchState(romId = 42)
            state.errorMessage = "some error"
            assertThat(state.hasOverlay).isTrue()
        }

        @Test
        fun `hasBlockingOverlay is false when only errorMessage is set`() {
            val state = SavePreLaunchState(romId = 42)
            state.errorMessage = "some error"
            assertThat(state.hasBlockingOverlay).isFalse()
        }

        @Test
        fun `hasBlockingOverlay is true when conflictModel is set`() {
            val state = SavePreLaunchState(romId = 42)
            state.conflictModel = SaveConflictUiModel(
                description = "test",
                local = SaveConflictSide(label = "Local", saveId = null, fileName = "x.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
                server = SaveConflictSide(label = "Server", saveId = null, fileName = "y.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
            )
            assertThat(state.hasBlockingOverlay).isTrue()
        }

        @Test
        fun `hasBlockingOverlay is true when quarantineModel is set`() {
            val state = SavePreLaunchState(romId = 42)
            state.quarantineModel = SaveQuarantineUiModel(
                reason = "size-mismatch",
                description = "test",
                quarantined = SaveConflictSide(label = "Q", saveId = null, fileName = "z.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
                quarantinedPath = "/tmp/z.srm",
            )
            assertThat(state.hasBlockingOverlay).isTrue()
        }

        @Test
        fun `hasBlockingOverlay is false initially`() {
            val state = SavePreLaunchState(romId = 42)
            assertThat(state.hasBlockingOverlay).isFalse()
        }
    }

    @Nested
    @DisplayName("clear() resets all transient state")
    inner class ClearBehavior {

        @Test
        fun `clear removes conflictModel`() {
            val state = SavePreLaunchState(romId = 42)
            state.conflictModel = SaveConflictUiModel(
                description = "test",
                local = SaveConflictSide(label = "Local", saveId = null, fileName = "x.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
                server = SaveConflictSide(label = "Server", saveId = null, fileName = "y.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
            )
            state.clear()
            assertThat(state.conflictModel).isNull()
        }

        @Test
        fun `clear removes quarantineModel`() {
            val state = SavePreLaunchState(romId = 42)
            state.quarantineModel = SaveQuarantineUiModel(
                reason = "x", description = "d",
                quarantined = SaveConflictSide(label = "Q", saveId = null, fileName = "z.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
                quarantinedPath = "/tmp/z.srm",
            )
            state.clear()
            assertThat(state.quarantineModel).isNull()
        }

        @Test
        fun `clear removes errorMessage`() {
            val state = SavePreLaunchState(romId = 42)
            state.errorMessage = "error"
            state.clear()
            assertThat(state.errorMessage).isNull()
        }

        @Test
        fun `clear resets isResolving`() {
            val state = SavePreLaunchState(romId = 42)
            state.isResolving = true
            state.clear()
            assertThat(state.isResolving).isFalse()
        }

        @Test
        fun `clear resets isStaging`() {
            val state = SavePreLaunchState(romId = 42)
            state.isStaging = true
            state.clear()
            assertThat(state.isStaging).isFalse()
        }

        @Test
        fun `clear resets resolvedEntity`() {
            val state = SavePreLaunchState(romId = 42)
            state.resolvedEntity = com.romm.androidtv.romm.save.SaveReplicaEntity(
                serverKey = "s", userKey = "u", romId = 42, romHash = "h",
                slot = "autosave", coreId = "c", coreBuildRevision = "r",
            )
            state.clear()
            assertThat(state.resolvedEntity).isNull()
        }

        @Test
        fun `clear preserves immutable scope fields`() {
            val state = SavePreLaunchState(romId = 42, sessionId = 7L, romHash = "abc")
            state.conflictModel = SaveConflictUiModel(
                description = "test",
                local = SaveConflictSide(label = "Local", saveId = null, fileName = "x.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
                server = SaveConflictSide(label = "Server", saveId = null, fileName = "y.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
            )
            state.isResolving = true
            state.clear()
            assertThat(state.romId).isEqualTo(42L)
            assertThat(state.sessionId).isEqualTo(7L)
            assertThat(state.romHash).isEqualTo("abc")
        }
    }

    @Nested
    @DisplayName("Duplicate submission guard")
    inner class DuplicateGuard {

        @Test
        fun `isResolving defaults to false`() {
            val state = SavePreLaunchState(romId = 42)
            assertThat(state.isResolving).isFalse()
        }

        @Test
        fun `isResolving can be set and read`() {
            val state = SavePreLaunchState(romId = 42)
            state.isResolving = true
            assertThat(state.isResolving).isTrue()
            state.isResolving = false
            assertThat(state.isResolving).isFalse()
        }

        @Test
        fun `isStaging defaults to false`() {
            val state = SavePreLaunchState(romId = 42)
            assertThat(state.isStaging).isFalse()
        }

        @Test
        fun `isStaging can be set and read`() {
            val state = SavePreLaunchState(romId = 42)
            state.isStaging = true
            assertThat(state.isStaging).isTrue()
            state.isStaging = false
            assertThat(state.isStaging).isFalse()
        }

        @Test
        fun `isStaging and isResolving are independent`() {
            val state = SavePreLaunchState(romId = 42)
            state.isStaging = true
            assertThat(state.isStaging).isTrue()
            assertThat(state.isResolving).isFalse()
            state.isResolving = true
            assertThat(state.isStaging).isTrue()
            assertThat(state.isResolving).isTrue()
        }

        @Test
        fun `staging then error state transition`() {
            val state = SavePreLaunchState(romId = 42)
            // Initial: no overlay, not staging
            assertThat(state.hasOverlay).isFalse()
            assertThat(state.isStaging).isFalse()

            // Start staging
            state.isStaging = true
            assertThat(state.isStaging).isTrue()
            assertThat(state.hasOverlay).isFalse()

            // Staging fails with error
            state.isStaging = false
            state.errorMessage = "Session expired; please log in again"
            assertThat(state.isStaging).isFalse()
            assertThat(state.hasOverlay).isTrue()
            assertThat(state.hasBlockingOverlay).isFalse()
        }

        @Test
        fun `staging then success clears staging`() {
            val state = SavePreLaunchState(romId = 42)
            state.isStaging = true
            // Simulate success: clear staging, no error set
            state.isStaging = false
            assertThat(state.isStaging).isFalse()
            assertThat(state.hasOverlay).isFalse()
        }

        @Test
        fun `clear resets isStaging`() {
            val state = SavePreLaunchState(romId = 42)
            state.isStaging = true
            state.errorMessage = "error"
            state.clear()
            assertThat(state.isStaging).isFalse()
            assertThat(state.errorMessage).isNull()
        }

        @Test
        fun `hasOverlay with both error and conflict prefers combined`() {
            val state = SavePreLaunchState(romId = 42)
            state.conflictModel = SaveConflictUiModel(
                description = "test",
                local = SaveConflictSide(label = "Local", saveId = null, fileName = "x.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
                server = SaveConflictSide(label = "Server", saveId = null, fileName = "y.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
            )
            state.errorMessage = "also an error"
            assertThat(state.hasOverlay).isTrue()
            assertThat(state.hasBlockingOverlay).isTrue()
        }
    }

    @Nested
    @DisplayName("Defect 1: conflictOperation preserves original SyncOperation metadata")
    inner class ConflictOperationPreservation {

        @Test
        fun `conflictOperation defaults to null`() {
            val state = SavePreLaunchState(romId = 42)
            assertThat(state.conflictOperation).isNull()
        }

        @Test
        fun `conflictOperation preserves full serverContentHash and serverUpdatedAt`() {
            val operation = com.romm.androidtv.romm.SyncOperation(
                action = com.romm.androidtv.romm.SyncAction.CONFLICT,
                romId = 42L, saveId = 11L, fileName = "autosave.srm", slot = "autosave",
                emulator = "sameboy", reason = "both changed",
                serverUpdatedAt = java.time.Instant.parse("2026-06-15T12:30:00Z"),
                serverContentHash = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            )
            val state = SavePreLaunchState(romId = 42, sessionId = 7L)
            state.conflictOperation = operation

            assertThat(state.conflictOperation).isNotNull
            assertThat(state.conflictOperation!!.serverContentHash).isEqualTo("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890")
            assertThat(state.conflictOperation!!.serverUpdatedAt).isEqualTo(java.time.Instant.parse("2026-06-15T12:30:00Z"))
            // Operation is the exact same instance, not a reconstruction.
            assertThat(state.conflictOperation).isSameAs(operation)
        }

        @Test
        fun `clear removes conflictOperation`() {
            val operation = com.romm.androidtv.romm.SyncOperation(
                action = com.romm.androidtv.romm.SyncAction.CONFLICT,
                romId = 42L, saveId = 11L, fileName = "autosave.srm", slot = "autosave",
                emulator = "sameboy", reason = "both changed",
                serverUpdatedAt = null, serverContentHash = null,
            )
            val state = SavePreLaunchState(romId = 42)
            state.conflictOperation = operation
            state.clear()
            assertThat(state.conflictOperation).isNull()
        }

        @Test
        fun `conflictOperation survives alongside conflictModel`() {
            val operation = com.romm.androidtv.romm.SyncOperation(
                action = com.romm.androidtv.romm.SyncAction.CONFLICT,
                romId = 42L, saveId = 11L, fileName = "autosave.srm", slot = "autosave",
                emulator = "sameboy", reason = "both changed",
                serverUpdatedAt = java.time.Instant.parse("2026-06-15T12:30:00Z"),
                serverContentHash = "fullhash",
            )
            val uiModel = SaveConflictUiModel(
                description = "test",
                local = SaveConflictSide(label = "Local", saveId = null, fileName = "x.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
                server = SaveConflictSide(label = "Server", saveId = null, fileName = "y.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
            )
            val state = SavePreLaunchState(romId = 42)
            state.conflictModel = uiModel
            state.conflictOperation = operation

            // Both are preserved independently.
            assertThat(state.conflictModel).isNotNull
            assertThat(state.conflictOperation).isNotNull
            assertThat(state.conflictOperation!!.serverContentHash).isEqualTo("fullhash")
        }
    }

    @Nested
    @DisplayName("Immutable scope fields")
    inner class ImmutableScope {

        @Test
        fun `romId is preserved`() {
            val state = SavePreLaunchState(romId = 42)
            assertThat(state.romId).isEqualTo(42L)
        }

        @Test
        fun `sessionId defaults to null`() {
            val state = SavePreLaunchState(romId = 42)
            assertThat(state.sessionId).isNull()
        }

        @Test
        fun `sessionId is preserved when set`() {
            val state = SavePreLaunchState(romId = 42, sessionId = 7L)
            assertThat(state.sessionId).isEqualTo(7L)
        }

        @Test
        fun `romHash defaults to empty string`() {
            val state = SavePreLaunchState(romId = 42)
            assertThat(state.romHash).isEmpty()
        }

        @Test
        fun `romHash is preserved when set`() {
            val state = SavePreLaunchState(romId = 42, romHash = "abc123")
            assertThat(state.romHash).isEqualTo("abc123")
        }
    }

    @Nested
    @DisplayName("Auth-expired state")
    inner class AuthExpiredState {

        @Test
        fun `isAuthExpired defaults to false`() {
            val state = SavePreLaunchState(romId = 42)
            assertThat(state.isAuthExpired).isFalse()
        }

        @Test
        fun `isAuthExpired can be set and read`() {
            val state = SavePreLaunchState(romId = 42)
            state.isAuthExpired = true
            assertThat(state.isAuthExpired).isTrue()
            state.isAuthExpired = false
            assertThat(state.isAuthExpired).isFalse()
        }

        @Test
        fun `hasOverlay is true when only isAuthExpired is set`() {
            val state = SavePreLaunchState(romId = 42)
            state.isAuthExpired = true
            assertThat(state.hasOverlay).isTrue()
        }

        @Test
        fun `hasBlockingOverlay is false when only isAuthExpired is set`() {
            val state = SavePreLaunchState(romId = 42)
            state.isAuthExpired = true
            assertThat(state.hasBlockingOverlay).isFalse()
        }

        @Test
        fun `clear resets isAuthExpired`() {
            val state = SavePreLaunchState(romId = 42)
            state.isAuthExpired = true
            state.clear()
            assertThat(state.isAuthExpired).isFalse()
        }

        @Test
        fun `auth-expired state does not interfere with conflictModel`() {
            val state = SavePreLaunchState(romId = 42)
            state.isAuthExpired = true
            state.conflictModel = SaveConflictUiModel(
                description = "test",
                local = SaveConflictSide(label = "Local", saveId = null, fileName = "x.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
                server = SaveConflictSide(label = "Server", saveId = null, fileName = "y.srm", hashPrefix = null, sizeText = null, coreId = null, slot = null, romId = null, updatedAtText = null),
            )
            assertThat(state.isAuthExpired).isTrue()
            assertThat(state.conflictModel).isNotNull
            assertThat(state.hasBlockingOverlay).isTrue()
        }

        @Test
        fun `auth-expired and error message are independent`() {
            val state = SavePreLaunchState(romId = 42)
            state.isAuthExpired = true
            state.errorMessage = "some error"
            assertThat(state.isAuthExpired).isTrue()
            assertThat(state.errorMessage).isEqualTo("some error")
            assertThat(state.hasOverlay).isTrue()
        }
    }
}
