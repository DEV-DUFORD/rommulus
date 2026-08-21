package com.romm.desktop.ui.screens.detail

import com.romm.androidtv.romm.ServerSaveInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Focused tests for the desktop save picker ("Choose Save" — Android parity): the pure
 * entry-building logic (newest-first ordering, default marking on the newest autosave,
 * display formatting) and the button's gating helper. Mirrors GameDetailVersionPickerTest.
 */
@DisplayName("SavePicker — Choose Save")
class GameDetailSavePickerTest {

    private companion object {
        const val ROM_ID = 7L
        /** Fixed "now" so relative-timestamp assertions are deterministic. */
        const val NOW_MS = 1_700_000_000_000L
    }

    private fun save(
        saveId: Long,
        fileName: String = "autosave.srm",
        emulator: String? = "gambatte",
        contentHash: String? = "hash-$saveId",
        updatedAt: Instant? = null,
        fileSizeBytes: Long = 12_345L,
    ) = ServerSaveInfo(
        saveId = saveId,
        romId = ROM_ID,
        fileName = fileName,
        slot = "autosave",
        emulator = emulator,
        contentHash = contentHash,
        updatedAt = updatedAt,
        fileSizeBytes = fileSizeBytes,
    )

    @Nested
    inner class BuildSavePickerEntries {

        @Test
        fun `entries are sorted newest first with the newest marked as default`() {
            val entries = buildSavePickerEntries(
                listOf(
                    save(saveId = 1L, updatedAt = Instant.ofEpochMilli(NOW_MS - 3_600_000)),
                    save(saveId = 2L, updatedAt = Instant.ofEpochMilli(NOW_MS - 60_000)),
                    save(saveId = 3L, updatedAt = Instant.ofEpochMilli(NOW_MS - 86_400_000)),
                ),
                nowEpochMs = NOW_MS,
            )

            assertThat(entries.map { it.saveId }).containsExactly(2L, 1L, 3L)
            assertThat(entries.map { it.isDefaultSelection }).containsExactly(true, false, false)
        }

        @Test
        fun `server fields map onto the ui model`() {
            val entries = buildSavePickerEntries(
                listOf(save(saveId = 9L, fileName = "autosave [2026-07-31_00-55-06].srm", emulator = "sameboy")),
                nowEpochMs = NOW_MS,
            )

            val entry = entries.single()
            assertThat(entry.saveId).isEqualTo(9L)
            assertThat(entry.fileName).isEqualTo("autosave [2026-07-31_00-55-06].srm")
            assertThat(entry.coreId).isEqualTo("sameboy")
            assertThat(entry.contentHash).isEqualTo("hash-9")
            assertThat(entry.sizeText).isNotNull()
        }

        @Test
        fun `a single save is the default`() {
            val entries = buildSavePickerEntries(listOf(save(saveId = 5L)), nowEpochMs = NOW_MS)
            assertThat(entries).hasSize(1)
            assertThat(entries[0].isDefaultSelection).isTrue()
        }

        @Test
        fun `no saves - no entries and no default`() {
            val entries = buildSavePickerEntries(emptyList(), nowEpochMs = NOW_MS)
            assertThat(entries).isEmpty()
        }

        @Test
        fun `saves without a timestamp sort last and never take the default when dated saves exist`() {
            val entries = buildSavePickerEntries(
                listOf(
                    save(saveId = 1L, updatedAt = null),
                    save(saveId = 2L, updatedAt = Instant.ofEpochMilli(NOW_MS - 60_000)),
                ),
                nowEpochMs = NOW_MS,
            )

            assertThat(entries.map { it.saveId }).containsExactly(2L, 1L)
            assertThat(entries.map { it.isDefaultSelection }).containsExactly(true, false)
        }

        @Test
        fun `all saves without timestamps - first listed is the default`() {
            val entries = buildSavePickerEntries(
                listOf(save(saveId = 1L), save(saveId = 2L)),
                nowEpochMs = NOW_MS,
            )
            assertThat(entries.map { it.saveId }).containsExactly(1L, 2L)
            assertThat(entries.map { it.isDefaultSelection }).containsExactly(true, false)
        }
    }

    @Nested
    inner class DisplayFormatting {

        @Test
        fun `size text is human readable`() {
            assertThat(formatSaveSize(0)).isEqualTo("0 B")
            assertThat(formatSaveSize(512)).isEqualTo("512 B")
            assertThat(formatSaveSize(12_345)).isEqualTo("12.1 KB")
            assertThat(formatSaveSize(5 * 1024 * 1024)).isEqualTo("5.0 MB")
        }

        @Test
        fun `recent timestamps are relative, older ones absolute`() {
            val now = Instant.ofEpochMilli(NOW_MS)
            assertThat(formatSaveTimestamp(now.minusSeconds(30), NOW_MS)).isEqualTo("just now")
            assertThat(formatSaveTimestamp(now.minusSeconds(5 * 60), NOW_MS)).isEqualTo("5 min ago")
            assertThat(formatSaveTimestamp(now.minusSeconds(3 * 3600), NOW_MS)).isEqualTo("3 h ago")
            val old = formatSaveTimestamp(now.minusSeconds(90 * 86_400), NOW_MS)
            assertThat(old).isNotNull()
            assertThat(old!!.contains(':')).isFalse() // a date (e.g. "2025-11-02"), not a time
        }

        @Test
        fun `missing timestamp renders as null`() {
            assertThat(formatSaveTimestamp(null, NOW_MS)).isNull()
        }
    }

    @Nested
    inner class ChooseSaveButtonGating {

        @Test
        fun `shown when the rom has saves`() {
            assertThat(shouldShowChooseSaveButton(hasSaves = true)).isTrue()
        }

        @Test
        fun `hidden when the rom has no saves`() {
            assertThat(shouldShowChooseSaveButton(hasSaves = false)).isFalse()
        }
    }
}
