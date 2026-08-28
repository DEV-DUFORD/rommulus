package com.romm.androidtv.storage.android

import com.romm.androidtv.romm.ClientToken
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pure-mapping unit tests for the Android-storage adapters (no Android Context/Keystore/WorkManager). */
class AndroidStorageMappingsTest {

    @Test
    fun `ClientToken maps to ClientTokenRecord with payload and default scopeVersion`() {
        val token = ClientToken("opaque-serialized-token")
        val record = token.toRecord()
        assertThat(record.payload).isEqualTo("opaque-serialized-token")
        assertThat(record.scopeVersion).isEqualTo(2)
    }

    @Test
    fun `exponential backoff grows by powers of two from a 30s base`() {
        assertThat(WorkManagerBackgroundSyncScheduler.exponentialBackoffDelayMs(1)).isEqualTo(30_000L)
        assertThat(WorkManagerBackgroundSyncScheduler.exponentialBackoffDelayMs(2)).isEqualTo(60_000L)
        assertThat(WorkManagerBackgroundSyncScheduler.exponentialBackoffDelayMs(3)).isEqualTo(120_000L)
        assertThat(WorkManagerBackgroundSyncScheduler.exponentialBackoffDelayMs(4)).isEqualTo(240_000L)
    }

    @Test
    fun `exponential backoff clamps at the max exponent`() {
        assertThat(WorkManagerBackgroundSyncScheduler.exponentialBackoffDelayMs(0)).isEqualTo(30_000L)
        // 2^6 * 30s = 1,920,000 ms cap; a huge attempt count must not overflow.
        assertThat(WorkManagerBackgroundSyncScheduler.exponentialBackoffDelayMs(1000))
            .isEqualTo(1_920_000L)
    }
}
