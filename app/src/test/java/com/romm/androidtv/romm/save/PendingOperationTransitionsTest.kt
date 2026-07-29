package com.romm.androidtv.romm.save

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit test for [PendingOperationTransitions] — no Android/Room
 * dependency, so this runs as an ordinary JUnit5 local unit test, unlike
 * [PendingOperationEntity]/[PendingOperationDao] themselves (see the
 * `app/src/androidTest` instrumented test for those; HANDOFF.md's Session 8
 * sitrep explains why this repo can't exercise Room from `app/src/test`).
 */
class PendingOperationTransitionsTest {

    @Test
    fun `pending can only advance to running`() {
        assertThat(PendingOperationTransitions.isValidTransition(
            PendingOperationStatus.PENDING, PendingOperationStatus.RUNNING,
        )).isTrue()

        for (invalid in PendingOperationStatus.entries.filter { it != PendingOperationStatus.RUNNING }) {
            assertThat(PendingOperationTransitions.isValidTransition(PendingOperationStatus.PENDING, invalid))
                .`as`("PENDING -> %s should be invalid", invalid)
                .isFalse()
        }
    }

    @Test
    fun `running can advance to every diagram-listed outcome`() {
        val expected = setOf(
            PendingOperationStatus.SUCCEEDED,
            PendingOperationStatus.RETRYABLE_FAILURE,
            PendingOperationStatus.AUTH_REQUIRED,
            PendingOperationStatus.CONFLICT,
            PendingOperationStatus.PERMANENT_FAILURE,
        )

        for (to in expected) {
            assertThat(PendingOperationTransitions.isValidTransition(PendingOperationStatus.RUNNING, to)).isTrue()
        }
        assertThat(PendingOperationTransitions.isValidTransition(
            PendingOperationStatus.RUNNING, PendingOperationStatus.PENDING,
        )).isFalse()
    }

    @Test
    fun `retryable failure can only loop back to pending`() {
        assertThat(PendingOperationTransitions.isValidTransition(
            PendingOperationStatus.RETRYABLE_FAILURE, PendingOperationStatus.PENDING,
        )).isTrue()
        assertThat(PendingOperationTransitions.isValidTransition(
            PendingOperationStatus.RETRYABLE_FAILURE, PendingOperationStatus.RUNNING,
        )).isFalse()
    }

    @Test
    fun `terminal statuses have no valid outgoing transition`() {
        val terminal = listOf(
            PendingOperationStatus.SUCCEEDED,
            PendingOperationStatus.AUTH_REQUIRED,
            PendingOperationStatus.CONFLICT,
            PendingOperationStatus.PERMANENT_FAILURE,
        )

        for (status in terminal) {
            assertThat(PendingOperationTransitions.isTerminal(status)).isTrue()
            for (to in PendingOperationStatus.entries) {
                assertThat(PendingOperationTransitions.isValidTransition(status, to))
                    .`as`("%s -> %s should be invalid (terminal)", status, to)
                    .isFalse()
            }
        }
    }

    @Test
    fun `pending and retryable failure and running are not terminal`() {
        assertThat(PendingOperationTransitions.isTerminal(PendingOperationStatus.PENDING)).isFalse()
        assertThat(PendingOperationTransitions.isTerminal(PendingOperationStatus.RUNNING)).isFalse()
        assertThat(PendingOperationTransitions.isTerminal(PendingOperationStatus.RETRYABLE_FAILURE)).isFalse()
    }
}
