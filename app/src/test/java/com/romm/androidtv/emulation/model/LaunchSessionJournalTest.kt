package com.romm.androidtv.emulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class LaunchSessionJournalTest {

    private lateinit var tempDir: File
    private lateinit var journal: LaunchSessionJournal

    @BeforeEach
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "journal-test-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        journal = LaunchSessionJournal(tempDir)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `createOrGet creates a new LAUNCHED descriptor`() {
        val desc = journal.createOrGet("session-1")

        assertThat(desc.sessionId).isEqualTo("session-1")
        assertThat(desc.state).isEqualTo(DescriptorState.LAUNCHED)
        assertThat(desc.candidatePath).isNull()
    }

    @Test
    fun `createOrGet is idempotent — returns existing descriptor`() {
        val first = journal.createOrGet("session-2")
        val second = journal.createOrGet("session-2")

        assertThat(second.sessionId).isEqualTo(first.sessionId)
        assertThat(second.state).isEqualTo(DescriptorState.LAUNCHED)
    }

    @Test
    fun `patchIdentity updates fields without advancing state`() {
        journal.createOrGet("s1")
        val patched = journal.patchIdentity("s1", SessionDescriptorPatch(
            rommSessionId = 99L,
            romId = 42L,
            romHash = "sha256abc",
            coreId = "sameboy",
            coreBuildRevision = "v1.0.3-libretro",
        ))

        assertThat(patched.state).isEqualTo(DescriptorState.LAUNCHED) // State unchanged
        assertThat(patched.rommSessionId).isEqualTo(99L)
        assertThat(patched.romId).isEqualTo(42L)
        assertThat(patched.romHash).isEqualTo("sha256abc")
        assertThat(patched.coreId).isEqualTo("sameboy")
        assertThat(patched.coreBuildRevision).isEqualTo("v1.0.3-libretro")
    }

    @Test
    fun `advance LAUNCHED to CORE_LOADED succeeds`() {
        journal.createOrGet("s1")
        val updated = journal.advance("s1", DescriptorState.CORE_LOADED, SessionDescriptorPatch(canonicalSavePath = "/data/save.srm"))

        assertThat(updated.state).isEqualTo(DescriptorState.CORE_LOADED)
        assertThat(updated.canonicalSavePath).isEqualTo("/data/save.srm")
    }

    @Test
    fun `advance CORE_LOADED to ADOPTED succeeds`() {
        journal.createOrGet("s1")
        journal.advance("s1", DescriptorState.CORE_LOADED, SessionDescriptorPatch(canonicalSavePath = "/data/save.srm"))
        val updated = journal.advance("s1", DescriptorState.ADOPTED, SessionDescriptorPatch(
            candidatePath = "/data/candidate.srm",
            rommSaveId = 42L,
            checkpointedHash = "abc123",
        ))

        assertThat(updated.state).isEqualTo(DescriptorState.ADOPTED)
        assertThat(updated.rommSaveId).isEqualTo(42L)
        assertThat(updated.checkpointedHash).isEqualTo("abc123")
    }

    @Test
    fun `advance CORE_LOADED to REJECTED succeeds`() {
        journal.createOrGet("s1")
        journal.advance("s1", DescriptorState.CORE_LOADED, SessionDescriptorPatch(canonicalSavePath = "/data/save.srm"))
        val updated = journal.advance("s1", DescriptorState.REJECTED, SessionDescriptorPatch(
            errorDetail = "size-mismatch: native=32768 downloaded=16384",
        ))

        assertThat(updated.state).isEqualTo(DescriptorState.REJECTED)
        assertThat(updated.errorDetail).contains("size-mismatch")
    }

    @Test
    fun `advance LAUNCHED to REJECTED succeeds`() {
        journal.createOrGet("s1")
        val updated = journal.advance("s1", DescriptorState.REJECTED, SessionDescriptorPatch(errorDetail = "player busy"))

        assertThat(updated.state).isEqualTo(DescriptorState.REJECTED)
    }

    @Test
    fun `advance to invalid transition throws`() {
        journal.createOrGet("s1")
        journal.advance("s1", DescriptorState.CORE_LOADED, SessionDescriptorPatch())

        assertThatThrownBy {
            journal.advance("s1", DescriptorState.LAUNCHED, SessionDescriptorPatch())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `terminal state cannot advance`() {
        journal.createOrGet("s1")
        journal.advance("s1", DescriptorState.CORE_LOADED, SessionDescriptorPatch())
        journal.advance("s1", DescriptorState.ADOPTED, SessionDescriptorPatch(checkpointedHash = "hash"))

        assertThatThrownBy {
            journal.advance("s1", DescriptorState.REJECTED, SessionDescriptorPatch(errorDetail = "should not happen"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `descriptor survives process-death simulation — re-read from disk`() {
        val sessionId = "persist-test"
        journal.createOrGet(sessionId)
        journal.advance(sessionId, DescriptorState.CORE_LOADED, SessionDescriptorPatch(
            canonicalSavePath = "/data/save.srm",
            rommSaveId = 99L,
        ))

        // Simulate process restart: create a new journal instance reading from the same directory.
        val recoveredJournal = LaunchSessionJournal(tempDir)
        val recovered = recoveredJournal.read(sessionId)

        assertThat(recovered).isNotNull
        assertThat(recovered!!.state).isEqualTo(DescriptorState.CORE_LOADED)
        assertThat(recovered.canonicalSavePath).isEqualTo("/data/save.srm")
        assertThat(recovered.rommSaveId).isEqualTo(99L)
    }

    @Test
    fun `listPending returns only non-terminal descriptors`() {
        journal.createOrGet("pending-1")
        journal.createOrGet("pending-2")
        journal.advance("pending-2", DescriptorState.CORE_LOADED, SessionDescriptorPatch())
        // pending-1 is LAUNCHED (non-terminal), pending-2 is CORE_LOADED (non-terminal)

        val pending = journal.listPending()
        assertThat(pending.map { it.sessionId }).containsExactlyInAnyOrder("pending-1", "pending-2")

        // Now make one terminal: LAUNCHED -> CORE_LOADED -> ADOPTED.
        journal.advance("pending-1", DescriptorState.CORE_LOADED, SessionDescriptorPatch())
        journal.advance("pending-1", DescriptorState.ADOPTED, SessionDescriptorPatch(checkpointedHash = "h"))

        val pendingAfter = journal.listPending()
        assertThat(pendingAfter.map { it.sessionId }).containsExactlyInAnyOrder("pending-2")
    }

    @Test
    fun `remove deletes the descriptor file`() {
        journal.createOrGet("rm-test")
        assertThat(journal.read("rm-test")).isNotNull

        journal.remove("rm-test")
        assertThat(journal.read("rm-test")).isNull()
    }

    @Test
    fun `remove on non-existent session returns false`() {
        assertThat(journal.remove("does-not-exist")).isFalse()
    }

    @Test
    fun `corrupted file is treated as missing`() {
        val sessionId = "corrupt-test"
        journal.createOrGet(sessionId)

        // Corrupt the file on disk. sanitizeSessionId keeps hyphens, so filename is "corrupt-test.json".
        val descriptorFile = tempDir.resolve("corrupt-test.json")
        descriptorFile.writeText("{this is not valid json")

        assertThat(journal.read(sessionId)).isNull()
    }

    @Test
    fun `blank sessionId throws`() {
        assertThatThrownBy { journal.createOrGet("") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { journal.createOrGet("   ") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ---- JSON round-trip with special characters (Moshi replaces hand-built parser) ----

    @Test
    fun `descriptor round-trip preserves commas in errorDetail`() {
        val sessionId = "comma-test"
        journal.createOrGet(sessionId)
        journal.advance(sessionId, DescriptorState.REJECTED, SessionDescriptorPatch(
            errorDetail = "size-mismatch: native=32768, downloaded=16384, core=sameboy",
        ))

        val recovered = LaunchSessionJournal(tempDir).read(sessionId)
        assertThat(recovered).isNotNull
        assertThat(recovered!!.errorDetail).isEqualTo("size-mismatch: native=32768, downloaded=16384, core=sameboy")
    }

    @Test
    fun `descriptor round-trip preserves double quotes in errorDetail`() {
        val sessionId = "quote-test"
        journal.createOrGet(sessionId)
        journal.advance(sessionId, DescriptorState.REJECTED, SessionDescriptorPatch(
            errorDetail = "path \"/data/save.srm\" not found",
        ))

        val recovered = LaunchSessionJournal(tempDir).read(sessionId)
        assertThat(recovered).isNotNull
        assertThat(recovered!!.errorDetail).isEqualTo("path \"/data/save.srm\" not found")
    }

    @Test
    fun `descriptor round-trip preserves backslashes in paths`() {
        val sessionId = "backslash-test"
        journal.createOrGet(sessionId)
        val pathWithBackslashes = """\server\share\save.srm"""
        journal.advance(sessionId, DescriptorState.CORE_LOADED, SessionDescriptorPatch(
            canonicalSavePath = pathWithBackslashes,
        ))

        val recovered = LaunchSessionJournal(tempDir).read(sessionId)
        assertThat(recovered).isNotNull
        assertThat(recovered!!.canonicalSavePath).isEqualTo(pathWithBackslashes)
    }

    @Test
    fun `descriptor round-trip preserves newlines in errorDetail`() {
        val sessionId = "newline-test"
        journal.createOrGet(sessionId)
        journal.advance(sessionId, DescriptorState.REJECTED, SessionDescriptorPatch(
            errorDetail = "line1\nline2\ttabbed",
        ))

        val recovered = LaunchSessionJournal(tempDir).read(sessionId)
        assertThat(recovered).isNotNull
        assertThat(recovered!!.errorDetail).isEqualTo("line1\nline2\ttabbed")
    }

    // ---- Process-death exact metadata recovery (no fabrication) ----

    @Test
    fun `process-death recovery preserves ALL authoritative identity fields`() {
        val sessionId = "identity-test"
        journal.createOrGet(sessionId)

        // Patch identity early (before core load).
        journal.patchIdentity(sessionId, SessionDescriptorPatch(
            rommSessionId = 77L,
            romId = 42L,
            romHash = "sha256-abcdef123456",
            coreId = "sameboy",
            coreBuildRevision = "v1.0.3-libretro",
        ))

        // Advance to CORE_LOADED with more data.
        journal.advance(sessionId, DescriptorState.CORE_LOADED, SessionDescriptorPatch(
            canonicalSavePath = "/data/user/0/com.romm/files/saves/server/alice/42/sha256-abcdef123456/autosave/save.srm",
            rommSaveId = 10L,
            candidateDownloadedSizeBytes = 32768L,
        ))

        // Advance to ADOPTED with checkpoint data.
        journal.advance(sessionId, DescriptorState.ADOPTED, SessionDescriptorPatch(
            candidatePath = "/data/quarantine/candidate.srm",
            checkpointedHash = "checkpoint-hash-xyz",
            expectedSramSizeBytes = 32768L,
        ))

        // Simulate process death: new journal instance reads from same directory.
        val recovered = LaunchSessionJournal(tempDir).read(sessionId)

        assertThat(recovered).isNotNull
        assertThat(recovered!!.state).isEqualTo(DescriptorState.ADOPTED)
        // RomM session ID (distinct from app launch sessionId) — used for finalization, NOT parsed from sessionId.
        assertThat(recovered.rommSessionId).isEqualTo(77L)
        // Exact ROM identity — no fabrication.
        assertThat(recovered.romId).isEqualTo(42L)
        assertThat(recovered.romHash).isEqualTo("sha256-abcdef123456")
        // Core identity from authoritative CoreManifest entry.
        assertThat(recovered.coreId).isEqualTo("sameboy")
        assertThat(recovered.coreBuildRevision).isEqualTo("v1.0.3-libretro")
        // Candidate data preserved.
        assertThat(recovered.rommSaveId).isEqualTo(10L)
        assertThat(recovered.candidateDownloadedSizeBytes).isEqualTo(32768L)
        assertThat(recovered.checkpointedHash).isEqualTo("checkpoint-hash-xyz")
        assertThat(recovered.expectedSramSizeBytes).isEqualTo(32768L)
    }

    // ---- No fabrication: missing fields remain null, never guessed ----

    @Test
    fun `recovered descriptor has null identity fields when not patched`() {
        val sessionId = "no-fabrication"
        journal.createOrGet(sessionId)
        journal.advance(sessionId, DescriptorState.CORE_LOADED, SessionDescriptorPatch(
            canonicalSavePath = "/data/save.srm",
        ))

        val recovered = LaunchSessionJournal(tempDir).read(sessionId)

        assertThat(recovered).isNotNull
        // All identity fields must be null — never fabricated.
        assertThat(recovered!!.rommSessionId).isNull()
        assertThat(recovered.romId).isNull()
        assertThat(recovered.romHash).isNull()
        assertThat(recovered.coreId).isNull()
        assertThat(recovered.coreBuildRevision).isNull()
        assertThat(recovered.expectedSramSizeBytes).isNull()
    }

    private fun assertThatThrownBy(block: () -> Unit) = org.assertj.core.api.Assertions.assertThatThrownBy(block)
}
