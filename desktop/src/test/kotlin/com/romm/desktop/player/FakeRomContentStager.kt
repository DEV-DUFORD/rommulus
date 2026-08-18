package com.romm.desktop.player

import com.romm.androidtv.emulation.model.sha256Hex
import java.nio.file.Files
import java.nio.file.Path

/**
 * Test double for [RomContentStager]: writes deterministic bytes to a temp cache dir, records
 * every call, and exposes the last staged result. Set [failure] to make staging fail closed.
 */
class FakeRomContentStager(
    private val contentBytes: ByteArray = "fake-rom-content".toByteArray(),
) : RomContentStager {

    data class Call(
        val romId: Long,
        val fileName: String,
        val expectedSizeBytes: Long,
        val supportedExtensions: Set<String>,
    )

    /** Every [stage] call, in order. */
    val calls: List<Call> get() = recordedCalls.toList()

    private val recordedCalls = mutableListOf<Call>()

    /** When non-null, the next (and every subsequent) [stage] call throws this instead of staging. */
    var failure: Exception? = null

    /** The result of the most recent successful [stage] call, or null before the first one. */
    var lastStaged: StagedContent? = null
        private set

    private val cacheDir: Path by lazy { Files.createTempDirectory("fake-rom-stager") }

    override fun stage(
        romId: Long,
        fileName: String,
        expectedSizeBytes: Long,
        supportedExtensions: Set<String>,
    ): StagedContent {
        recordedCalls += Call(romId, fileName, expectedSizeBytes, supportedExtensions)
        failure?.let { throw it }
        val safeName = fileName.replace('/', '_').replace('\\', '_').ifBlank { "rom" }
        val path = cacheDir.resolve("fake-$romId-$safeName")
        Files.write(path, contentBytes)
        val staged = StagedContent(path, sha256Hex(contentBytes))
        lastStaged = staged
        return staged
    }
}
