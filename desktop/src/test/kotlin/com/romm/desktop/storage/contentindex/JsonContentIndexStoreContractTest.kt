package com.romm.desktop.storage.contentindex

import com.romm.androidtv.storage.contract.ContentIndexStoreContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Shared [ContentIndexStoreContract] suite wired against the JSON-file implementation. */
class JsonContentIndexStoreContractTest {

    @TempDir
    lateinit var tempDir: Path

    private var caseNumber = 0

    private val contract = ContentIndexStoreContract {
        // Each contract case gets its own isolated file so cases do not leak state.
        val dir = Files.createDirectories(tempDir.resolve("case-${caseNumber++}"))
        JsonContentIndexStore(dir.resolve("content-index.json"))
    }

    @Test
    fun `contract - upsert and get`() = contract.upsert_and_get()

    @Test
    fun `contract - remove`() = contract.remove()

    @Test
    fun `contract - evictionCandidates LRU order and limit`() = contract.evictionCandidates_LRU_order_and_limit()

    @Test
    fun `contract - totalSizeBytes sum`() = contract.totalSizeBytes_sum()

    @Test
    fun `contract - replace updates size sum`() = contract.replace_updates_size_sum()
}
