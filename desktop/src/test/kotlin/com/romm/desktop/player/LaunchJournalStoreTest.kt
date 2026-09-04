package com.romm.desktop.player

import com.romm.desktop.PosixTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.streams.asSequence

/**
 * LaunchJournalStore tests: atomic writes (temp + fsync + rename), 0600 journal files,
 * fail-closed behavior on write failure (previous file intact, no temp leftovers), and
 * session-ID path-traversal rejection.
 */
class LaunchJournalStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun store(): LaunchJournalStore = LaunchJournalStore(tempDir.resolve("journals"))

    private fun journal(state: JournalState = JournalState.PENDING): LaunchJournal {
        val dir = tempDir.resolve("journals").resolve("abc-123")
        return LaunchJournal(
            sessionId = "abc-123",
            requestPath = dir.resolve("request.json"),
            resultPath = dir.resolve("result.json"),
            candidateSavePath = dir.resolve("candidate.srm"),
            state = state,
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
        )
    }

    @Test
    fun `write then read round-trips all fields`() {
        val store = store()
        store.write(journal(JournalState.INTERRUPTED))
        val read = store.read("abc-123")
        assertThat(read.isSuccess).isTrue()
        assertThat(read.getOrNull()).isEqualTo(journal(JournalState.INTERRUPTED))
    }

    @Test
    fun `read of absent session succeeds with null`() {
        assertThat(store().read("nope").getOrNull()).isNull()
    }

    @Test
    fun `journal file is mode 0600`() {
        PosixTestSupport.assumePosixFilesystem(tempDir)
        val store = store()
        store.write(journal())
        val perms = Files.getPosixFilePermissions(store.journalPath("abc-123"))
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }

    @Test
    fun `no temp files remain after a successful write`() {
        val store = store()
        store.write(journal())
        val entries = Files.list(store.sessionDir("abc-123")).use { it.asSequence().map { p -> p.fileName.toString() }.toList() }
        assertThat(entries).containsExactly(LaunchJournalStore.JOURNAL_FILE_NAME)
    }

    @Test
    fun `failed overwrite preserves previous file and leaves no temp files`() {
        // The failure is simulated with POSIX read-only directory bits — a mechanism that does
        // not exist on NTFS, so the test only applies where POSIX attributes are supported.
        PosixTestSupport.assumePosixFilesystem(tempDir)
        val store = store()
        store.write(journal(JournalState.PENDING))
        val root = tempDir.resolve("journals")

        // Make the SESSION directory (where the temp file is created) read-only so the
        // temp-file create fails mid-write.
        val sessionDir = root.resolve("abc-123")
        Files.setPosixFilePermissions(sessionDir, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        try {
            assertThatThrownBy { store.write(journal(JournalState.RECONCILED)) }
                .isInstanceOf(IOException::class.java)
        } finally {
            Files.setPosixFilePermissions(sessionDir, setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ))
        }

        // Previous content intact; no temp leftovers.
        assertThat(store.read("abc-123").getOrNull()?.state).isEqualTo(JournalState.PENDING)
        val entries = Files.list(root.resolve("abc-123")).use { it.asSequence().map { p -> p.fileName.toString() }.toList() }
        assertThat(entries).containsExactly(LaunchJournalStore.JOURNAL_FILE_NAME)
    }

    @Test
    fun `malformed journal file is preserved and surfaces a failure`() {
        val store = store()
        store.write(journal())
        Files.writeString(store.journalPath("abc-123"), "not json at all")
        val read = store.read("abc-123")
        assertThat(read.isFailure).isTrue()
        // The malformed file is preserved untouched (fail-closed).
        assertThat(Files.readString(store.journalPath("abc-123"))).isEqualTo("not json at all")
    }

    @Test
    fun `session id path traversal is rejected`() {
        val store = store()
        assertThat(store.read("../evil").isFailure).isTrue()
        assertThatThrownBy { store.ensureSessionDir("..") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.listSessionIds()).isEmpty()
    }
}
