package com.romm.desktop

import com.romm.androidtv.storage.records.ContentIndexKind
import com.romm.androidtv.storage.records.ContentIndexRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CachedContentIdentityTest {

    private val record = ContentIndexRecord(
        key = "rom",
        kind = ContentIndexKind.ROM,
        serverKey = "server",
        userKey = "user",
        remoteId = 1,
        fileIdsKey = "",
        contentHash = "verified-hash",
        absolutePath = "/cache/game.iso",
        sizeBytes = 1024,
        lastAccessedEpochMs = 1,
        lastModifiedEpochMs = 50,
    )

    @Test
    fun `reuses verified hash only when size and modification time match`() {
        assertThat(cachedContentIdentityMatches(record, 1024, 50)).isTrue()
        assertThat(cachedContentIdentityMatches(record, 2048, 50)).isFalse()
        assertThat(cachedContentIdentityMatches(record, 1024, 51)).isFalse()
    }

    @Test
    fun `legacy record without modification time is reverified once`() {
        assertThat(
            cachedContentIdentityMatches(record.copy(lastModifiedEpochMs = 0), 1024, 50)
        ).isFalse()
    }
}
