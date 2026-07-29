package com.romm.androidtv.library.ui

import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncOperation
import com.romm.androidtv.romm.save.SaveReplicaEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("ConflictResolutionMapper — pure UI model mapping")
class ConflictResolutionMapperTest {

    private val baseLocalEntity = SaveReplicaEntity(
        serverKey = "test-server",
        userKey = "testuser",
        romId = 42,
        romHash = "abc123hash",
        slot = "autosave",
        coreId = "sameboy",
        coreBuildRevision = "v0.19",
        expectedSramSizeBytes = 32768,
        localHash = "aabbccdd1122334455667788",
        localSizeBytes = 32768,
        localWrittenAtEpochMs = 1700000000000L,
        rommSaveId = 99,
        serverHash = null,
        serverSizeBytes = null,
        serverUpdatedAtEpochMs = null,
    )

    private val baseServerOp = SyncOperation(
        action = SyncAction.CONFLICT,
        romId = 42,
        saveId = 100,
        fileName = "autosave.srm",
        slot = "autosave",
        emulator = "sameboy",
        reason = "both changed since last sync",
        serverUpdatedAt = Instant.parse("2026-06-15T12:00:00Z"),
        serverContentHash = "ddeeff001122334455667788",
    )

    @Nested
    @DisplayName("formatSize")
    inner class FormatSize {
        @Test
        fun `null returns null`() {
            assertThat(ConflictResolutionMapper.formatSize(null)).isNull()
        }

        @Test
        fun `zero returns null`() {
            assertThat(ConflictResolutionMapper.formatSize(0)).isNull()
        }

        @Test
        fun `bytes under 1 KB returns bytes`() {
            assertThat(ConflictResolutionMapper.formatSize(512)).isEqualTo("512 B")
        }

        @Test
        fun `exactly 1024 returns 1 KB`() {
            assertThat(ConflictResolutionMapper.formatSize(1024)).isEqualTo("1 KB")
        }

        @Test
        fun `kilobyte range`() {
            assertThat(ConflictResolutionMapper.formatSize(32768)).isEqualTo("32 KB")
        }

        @Test
        fun `megabyte range`() {
            assertThat(ConflictResolutionMapper.formatSize(2_097_152)).isEqualTo("2 MB")
        }
    }

    @Nested
    @DisplayName("formatInstant")
    inner class FormatInstantTests {
        @Test
        fun `null epoch ms returns null`() {
            val nullMs: Long? = null
            assertThat(ConflictResolutionMapper.formatInstant(nullMs)).isNull()
        }

        @Test
        fun `zero epoch ms returns null`() {
            assertThat(ConflictResolutionMapper.formatInstant(0)).isNull()
        }

        @Test
        fun `valid epoch ms returns deterministic UTC datetime`() {
            // 1700000000000 ms = 2023-11-14T22:13:20Z
            val result = ConflictResolutionMapper.formatInstant(1700000000000L)
            assertThat(result).isEqualTo("2023-11-14 22:13:20 UTC")
        }

        @Test
        fun `epoch zero returns null via guard clause`() {
            val result = ConflictResolutionMapper.formatInstant(0L)
            // epochMs <= 0 returns null per the guard clause
            assertThat(result).isNull()
        }

        @Test
        fun `midnight UTC formats correctly`() {
            // 1700000000000 + some offset for a clean time
            val result = ConflictResolutionMapper.formatInstant(1700006400000L)
            assertThat(result).isEqualTo("2023-11-15 00:00:00 UTC")
        }

        @Test
        fun `null Instant returns null`() {
            val nullInstant: Instant? = null
            assertThat(ConflictResolutionMapper.formatInstant(nullInstant)).isNull()
        }

        @Test
        fun `valid Instant returns deterministic UTC datetime`() {
            val instant = Instant.parse("2026-06-15T12:00:00Z")
            val result = ConflictResolutionMapper.formatInstant(instant)
            assertThat(result).isEqualTo("2026-06-15 12:00:00 UTC")
        }
    }

    @Nested
    @DisplayName("mapConflict")
    inner class MapConflict {
        @Test
        fun `maps local entity and server operation into SaveConflictUiModel`() {
            val model = ConflictResolutionMapper.mapConflict(baseLocalEntity, baseServerOp)

            assertThat(model.title).isEqualTo("Save Conflict")
            assertThat(model.description).isEqualTo("both changed since last sync")
            assertThat(model.local.label).isEqualTo("Local")
            assertThat(model.server.label).isEqualTo("Server")
        }

        @Test
        fun `local side carries local metadata`() {
            val model = ConflictResolutionMapper.mapConflict(baseLocalEntity, baseServerOp)

            assertThat(model.local.saveId).isEqualTo(99)
            assertThat(model.local.fileName).isEqualTo("autosave.srm")
            assertThat(model.local.hashPrefix).isEqualTo("aabbccdd1122")
            assertThat(model.local.sizeText).isEqualTo("32 KB")
            assertThat(model.local.coreId).isEqualTo("sameboy")
            assertThat(model.local.slot).isEqualTo("autosave")
            assertThat(model.local.romId).isEqualTo(42)
        }

        @Test
        fun `server side carries server operation metadata`() {
            val model = ConflictResolutionMapper.mapConflict(baseLocalEntity, baseServerOp)

            assertThat(model.server.saveId).isEqualTo(100)
            assertThat(model.server.fileName).isEqualTo("autosave.srm")
            assertThat(model.server.hashPrefix).isEqualTo("ddeeff001122")
            // SyncOperation does not carry server_size_bytes; display "Not reported"
            assertThat(model.server.sizeText).isEqualTo("Not reported")
            assertThat(model.server.coreId).isEqualTo("sameboy")
            assertThat(model.server.slot).isEqualTo("autosave")
        }

        @Test
        fun `server updatedAt is formatted as UTC datetime`() {
            val model = ConflictResolutionMapper.mapConflict(baseLocalEntity, baseServerOp)
            assertThat(model.server.updatedAtText).isEqualTo("2026-06-15 12:00:00 UTC")
        }

        @Test
        fun `local updatedAt is formatted as UTC datetime`() {
            val model = ConflictResolutionMapper.mapConflict(baseLocalEntity, baseServerOp)
            assertThat(model.local.updatedAtText).isEqualTo("2023-11-14 22:13:20 UTC")
        }

        @Test
        fun `blank reason uses default description`() {
            val op = baseServerOp.copy(reason = "")
            val model = ConflictResolutionMapper.mapConflict(baseLocalEntity, op)

            assertThat(model.description).isEqualTo(
                "Both the local and server copies have changed since last sync."
            )
        }

        @Test
        fun `null local hash produces null hashPrefix`() {
            val entity = baseLocalEntity.copy(localHash = null)
            val model = ConflictResolutionMapper.mapConflict(entity, baseServerOp)

            assertThat(model.local.hashPrefix).isNull()
        }

        @Test
        fun `null server content hash produces null hashPrefix`() {
            val op = baseServerOp.copy(serverContentHash = null)
            val model = ConflictResolutionMapper.mapConflict(baseLocalEntity, op)

            assertThat(model.server.hashPrefix).isNull()
        }

        @Test
        fun `null local size produces null sizeText`() {
            val entity = baseLocalEntity.copy(localSizeBytes = null)
            val model = ConflictResolutionMapper.mapConflict(entity, baseServerOp)

            assertThat(model.local.sizeText).isNull()
        }

        @Test
        fun `romId is nullable in local side`() {
            val model = ConflictResolutionMapper.mapConflict(baseLocalEntity, baseServerOp)
            // romId from entity is always present (non-null by domain constraint), but type is nullable
            assertThat(model.local.romId).isEqualTo(42)
        }

        @Test
        fun `server side romId comes from SyncOperation`() {
            val model = ConflictResolutionMapper.mapConflict(baseLocalEntity, baseServerOp)
            assertThat(model.server.romId).isEqualTo(42)
        }
    }

    @Nested
    @DisplayName("mapQuarantine")
    inner class MapQuarantine {
        @Test
        fun `size-mismatch reason produces quarantine model with appropriate description`() {
            val model = ConflictResolutionMapper.mapQuarantine(
                reason = "size-mismatch",
                quarantinedPath = "/data/quarantine/save.srm",
                localEntity = baseLocalEntity,
            )

            assertThat(model.title).isEqualTo("Incompatible Save")
            assertThat(model.reason).isEqualTo("size-mismatch")
            assertThat(model.description).contains("SRAM size")
            assertThat(model.quarantinedPath).isEqualTo("/data/quarantine/save.srm")
        }

        @Test
        fun `unknown-provenance reason produces quarantine model`() {
            val model = ConflictResolutionMapper.mapQuarantine(
                reason = "unknown-provenance",
                quarantinedPath = "/data/quarantine/legacy.srm",
                localEntity = baseLocalEntity,
            )

            assertThat(model.reason).isEqualTo("unknown-provenance")
            assertThat(model.description).contains("core provenance")
        }

        @Test
        fun `unrecognized reason uses generic description`() {
            val model = ConflictResolutionMapper.mapQuarantine(
                reason = "some-other-reason",
                quarantinedPath = "/data/quarantine/other.srm",
                localEntity = baseLocalEntity,
            )

            assertThat(model.description).contains("some-other-reason")
        }

        @Test
        fun `quarantined side derives file name from path when no entity`() {
            val model = ConflictResolutionMapper.mapQuarantine(
                reason = "size-mismatch",
                quarantinedPath = "/data/quarantine/mystery.srm",
                localEntity = null,
            )

            assertThat(model.quarantined.fileName).isEqualTo("mystery.srm")
            assertThat(model.quarantined.label).isEqualTo("Quarantined")
        }

        @Test
        fun `quarantined side uses entity data when available`() {
            val model = ConflictResolutionMapper.mapQuarantine(
                reason = "size-mismatch",
                quarantinedPath = "/data/quarantine/save.srm",
                localEntity = baseLocalEntity,
            )

            assertThat(model.quarantined.coreId).isEqualTo("sameboy")
            assertThat(model.quarantined.slot).isEqualTo("autosave")
            assertThat(model.quarantined.romId).isEqualTo(42)
        }

        @Test
        fun `quarantined side has null romId when entity is absent`() {
            val model = ConflictResolutionMapper.mapQuarantine(
                reason = "size-mismatch",
                quarantinedPath = "/data/quarantine/mystery.srm",
                localEntity = null,
            )

            assertThat(model.quarantined.romId).isNull()
        }

        @Test
        fun `quarantined side has null saveId when entity is absent`() {
            val model = ConflictResolutionMapper.mapQuarantine(
                reason = "size-mismatch",
                quarantinedPath = "/data/quarantine/mystery.srm",
                localEntity = null,
            )

            assertThat(model.quarantined.saveId).isNull()
        }
    }
}
