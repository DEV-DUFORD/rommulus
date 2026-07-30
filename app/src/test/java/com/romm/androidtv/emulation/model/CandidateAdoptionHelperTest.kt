package com.romm.androidtv.emulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException

/**
 * JVM tests for [CandidateAdoptionHelper] backup-before-restore ordering semantics.
 * Exercises the interface contract without Android/instrumentation dependencies:
 * - validate exact size/provenance/hash/path
 * - backup canonical local bytes durably before restore
 * - on backup failure, do not restore candidate
 * - preserve candidate throughout
 * - idempotent repeated attempts (no overwriting prior backups)
 */
class CandidateAdoptionHelperTest {

    private lateinit var tempDir: File
    private lateinit var helper: CandidateAdoptionHelper
    private lateinit var fakeStore: InMemorySaveBackupStore

    @BeforeEach
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "adoption-test-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        helper = FilesystemCandidateAdoptionHelper()
        fakeStore = InMemorySaveBackupStore()
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun candidateMetadata(
        rommSessionId: Long = 7L,
        rommSaveId: Long = 10L,
        downloadedSizeBytes: Long = 32768L,
        serverContentHash: String? = "hash-abc",
        emulator: String? = "sameboy",
        romId: Long = 42L,
        romHash: String = "sha256-of-rom-content",
        coreId: String = "sameboy",
        coreBuildRevision: String = "8230189896a8bb6598574d302ba0ad3658f98ab4",
    ) = CandidateSaveMetadata(
        rommSessionId = rommSessionId,
        rommSaveId = rommSaveId,
        candidatePath = File(tempDir, "quarantine/candidate.srm").absolutePath,
        downloadedSizeBytes = downloadedSizeBytes,
        serverContentHash = serverContentHash,
        emulator = emulator,
        romId = romId,
        romHash = romHash,
        coreId = coreId,
        coreBuildRevision = coreBuildRevision,
    )

    @Test
    fun `exact size match with existing canonical backup then restore then checkpoint`() {
        // Use a path that does NOT match scope parsing pattern, forcing directFileBackup.
        val canonicalFile = File(tempDir, "my-saves/canonical.srm")
        canonicalFile.parentFile?.mkdirs()
        canonicalFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val candidateFile = File(tempDir, "quarantine/candidate.srm")
        candidateFile.parentFile?.mkdirs()
        candidateFile.writeBytes(byteArrayOf(9, 8, 7, 6, 5))

        var restoredPath: String? = null
        var checkpointedPath: String? = null

        val meta = candidateMetadata(downloadedSizeBytes = 5L)
        val result = helper.adoptCandidate(
            candidateMetadata = meta,
            canonicalSavePath = canonicalFile.absolutePath,
            nativeSramSizeBytes = 5L,
            backupStore = fakeStore,
            nativeRestore = { path -> restoredPath = path; true },
            nativeCheckpoint = { path -> checkpointedPath = path; true },
        )

        assertThat(result).isInstanceOf(AdoptionResult.Adopted::class.java)
        val adopted = result as AdoptionResult.Adopted
        assertThat(adopted.backupPath).isNotNull()
        assertThat(File(adopted.backupPath!!)).exists()
        assertThat(restoredPath).isEqualTo(meta.candidatePath)
        assertThat(checkpointedPath).isEqualTo(canonicalFile.absolutePath)
        assertThat(candidateFile.readBytes()).isEqualTo(byteArrayOf(9, 8, 7, 6, 5))
    }

    @Test
    fun `exact size match without existing canonical no backup proceed directly`() {
        // Canonical file does not exist — nothing to back up.
        val canonicalFile = File(tempDir, "my-saves/canonical.srm")
        assertThat(canonicalFile.exists()).isFalse()

        var restoredPath: String? = null
        var checkpointedPath: String? = null

        // Mock nativeCheckpoint to actually write bytes so the post-checkpoint hash read succeeds.
        val meta = candidateMetadata(downloadedSizeBytes = 3L)
        val result = helper.adoptCandidate(
            candidateMetadata = meta,
            canonicalSavePath = canonicalFile.absolutePath,
            nativeSramSizeBytes = 3L,
            backupStore = fakeStore,
            nativeRestore = { path -> restoredPath = path; true },
            nativeCheckpoint = { path ->
                checkpointedPath = path
                File(path).parentFile?.mkdirs()
                File(path).writeBytes(byteArrayOf(9, 8, 7))
                true
            },
        )

        assertThat(result).isInstanceOf(AdoptionResult.Adopted::class.java)
        val adopted = result as AdoptionResult.Adopted
        assertThat(adopted.backupPath).isNull()
        assertThat(restoredPath).isEqualTo(meta.candidatePath)
    }

    @Test
    fun `size mismatch candidate NOT restored preserved intact`() {
        val canonicalFile = File(tempDir, "my-saves/canonical.srm")
        canonicalFile.parentFile?.mkdirs()
        canonicalFile.writeBytes(byteArrayOf(1, 2, 3))

        var restoredPath: String? = null

        val meta = candidateMetadata(downloadedSizeBytes = 5L)
        val result = helper.adoptCandidate(
            candidateMetadata = meta,
            canonicalSavePath = canonicalFile.absolutePath,
            nativeSramSizeBytes = 32768L,
            backupStore = fakeStore,
            nativeRestore = { path -> restoredPath = path; true },
            nativeCheckpoint = { true },
        )

        assertThat(result).isInstanceOf(AdoptionResult.RejectedSizeMismatch::class.java)
        val rejected = result as AdoptionResult.RejectedSizeMismatch
        assertThat(rejected.nativeSramSizeBytes).isEqualTo(32768L)
        assertThat(rejected.downloadedSizeBytes).isEqualTo(5L)
        assertThat(restoredPath).isNull()
    }

    @Test
    fun `no SRAM available candidate NOT restored`() {
        var restoredPath: String? = null

        val meta = candidateMetadata(downloadedSizeBytes = 5L)
        val result = helper.adoptCandidate(
            candidateMetadata = meta,
            canonicalSavePath = File(tempDir, "nonexistent.srm").absolutePath,
            nativeSramSizeBytes = 0L,
            backupStore = fakeStore,
            nativeRestore = { path -> restoredPath = path; true },
            nativeCheckpoint = { true },
        )

        assertThat(result).isInstanceOf(AdoptionResult.NoSram::class.java)
        val noSram = result as AdoptionResult.NoSram
        assertThat(noSram.nativeSramSizeBytes).isEqualTo(0L)
        assertThat(restoredPath).isNull()
    }

    @Test
    fun `backup failure candidate NOT restored abort adoption`() {
        val canonicalFile = File(tempDir, "saves/server/alice/42/hash-a/autosave/srm.srm")
        canonicalFile.parentFile?.mkdirs()
        canonicalFile.writeBytes(byteArrayOf(1, 2, 3))

        val failingStore = object : SaveBackupStore {
            override fun readCanonical(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): ByteArray? = null
            override fun backupCanonical(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String, candidateIdentifier: Long, nowEpochMs: Long): String {
                throw IOException("Simulated backup failure")
            }
            override fun readBackup(backupPath: String): ByteArray? = null
            override fun writeCanonicalAtomically(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String, bytes: ByteArray) {}
        }

        var restoredPath: String? = null

        val meta = candidateMetadata(downloadedSizeBytes = 3L)
        val result = helper.adoptCandidate(
            candidateMetadata = meta,
            canonicalSavePath = canonicalFile.absolutePath,
            nativeSramSizeBytes = 3L,
            backupStore = failingStore,
            nativeRestore = { path -> restoredPath = path; true },
            nativeCheckpoint = { true },
        )

        assertThat(result).isInstanceOf(AdoptionResult.BackupFailed::class.java)
        val backupFailed = result as AdoptionResult.BackupFailed
        assertThat(backupFailed.error).contains("Simulated backup failure")
        assertThat(restoredPath).isNull()
    }

    @Test
    fun `native restore failure candidate preserved canonical untouched`() {
        val canonicalFile = File(tempDir, "my-saves/canonical.srm")
        canonicalFile.parentFile?.mkdirs()
        val originalCanonicalBytes = byteArrayOf(1, 2, 3)
        canonicalFile.writeBytes(originalCanonicalBytes)

        var checkpointedPath: String? = null

        val meta = candidateMetadata(downloadedSizeBytes = 3L)
        val result = helper.adoptCandidate(
            candidateMetadata = meta,
            canonicalSavePath = canonicalFile.absolutePath,
            nativeSramSizeBytes = 3L,
            backupStore = fakeStore,
            nativeRestore = { false },
            nativeCheckpoint = { path -> checkpointedPath = path; true },
        )

        assertThat(result).isInstanceOf(AdoptionResult.RestoreFailed::class.java)
        assertThat(checkpointedPath).isNull()
        assertThat(canonicalFile.readBytes()).isEqualTo(originalCanonicalBytes)
    }

    @Test
    fun `checkpoint failure adoption rejected`() {
        val canonicalFile = File(tempDir, "my-saves/canonical.srm")
        canonicalFile.parentFile?.mkdirs()
        canonicalFile.writeBytes(byteArrayOf(1, 2, 3))

        var restoredPath: String? = null

        val meta = candidateMetadata(downloadedSizeBytes = 3L)
        val result = helper.adoptCandidate(
            candidateMetadata = meta,
            canonicalSavePath = canonicalFile.absolutePath,
            nativeSramSizeBytes = 3L,
            backupStore = fakeStore,
            nativeRestore = { path -> restoredPath = path; true },
            nativeCheckpoint = { false },
        )

        assertThat(result).isInstanceOf(AdoptionResult.CheckpointFailed::class.java)
        assertThat(restoredPath).isEqualTo(meta.candidatePath)
    }

    @Test
    fun `idempotent repeated attempts second attempt reuses existing backup`() {
        val canonicalFile = File(tempDir, "my-saves/canonical.srm")
        canonicalFile.parentFile?.mkdirs()
        canonicalFile.writeBytes(byteArrayOf(1, 2, 3))

        val meta = candidateMetadata(downloadedSizeBytes = 3L)

        var restored1: String? = null
        val result1 = helper.adoptCandidate(
            candidateMetadata = meta,
            canonicalSavePath = canonicalFile.absolutePath,
            nativeSramSizeBytes = 3L,
            backupStore = fakeStore,
            nativeRestore = { path -> restored1 = path; true },
            nativeCheckpoint = { true },
        )

        assertThat(result1).isInstanceOf(AdoptionResult.Adopted::class.java)
        val firstBackupPath = (result1 as AdoptionResult.Adopted).backupPath

        Thread.sleep(10)
        var restored2: String? = null
        val result2 = helper.adoptCandidate(
            candidateMetadata = meta,
            canonicalSavePath = canonicalFile.absolutePath,
            nativeSramSizeBytes = 3L,
            backupStore = fakeStore,
            nativeRestore = { path -> restored2 = path; true },
            nativeCheckpoint = { true },
        )

        assertThat(result2).isInstanceOf(AdoptionResult.Adopted::class.java)
        val secondBackupPath = (result2 as AdoptionResult.Adopted).backupPath

        if (firstBackupPath != null && secondBackupPath != null) {
            assertThat(secondBackupPath).isEqualTo(firstBackupPath)
        }
    }

    /** In-memory [SaveBackupStore] for JVM testing. No filesystem dependency. */
    private class InMemorySaveBackupStore : SaveBackupStore {
        private val canonicalData = mutableMapOf<String, ByteArray>()
        private val backups = mutableMapOf<Long, Pair<String, ByteArray>>()

        override fun readCanonical(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): ByteArray? {
            return canonicalData["$serverKey|$userKey|$romId|$romHash|$slot"]
        }

        override fun backupCanonical(
            serverKey: String,
            userKey: String,
            romId: Long,
            romHash: String,
            slot: String,
            candidateIdentifier: Long,
            nowEpochMs: Long,
        ): String {
            val key = "$serverKey|$userKey|$romId|$romHash|$slot"
            val bytes = canonicalData[key] ?: throw IllegalStateException("No canonical data to back up")

            val existing = backups[candidateIdentifier]
            if (existing != null) return existing.first

            val path = "in-memory-backup/candidate-${candidateIdentifier}-$nowEpochMs"
            backups[candidateIdentifier] = path to bytes.copyOf()
            return path
        }

        override fun readBackup(backupPath: String): ByteArray? {
            return backups.values.find { it.first == backupPath }?.second
        }

        override fun writeCanonicalAtomically(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String, bytes: ByteArray) {
            canonicalData["$serverKey|$userKey|$romId|$romHash|$slot"] = bytes
        }
    }
}
