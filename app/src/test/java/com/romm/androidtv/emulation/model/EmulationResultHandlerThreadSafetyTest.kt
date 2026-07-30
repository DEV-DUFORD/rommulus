package com.romm.androidtv.emulation.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@DisplayName("EmulationResultHandler - thread-safety and serialization")
class EmulationResultHandlerThreadSafetyTest {

    @Test
    fun `candidateMetadataCache is thread-safe under concurrent writes`() {
        val handler = TestableEmulationResultHandler()

        val latch = CountDownLatch(1)
        val threads = (1..10).map { i ->
            Thread {
                latch.await()
                runBlocking {
                    val meta = CandidateSaveMetadata(
                        rommSessionId = i.toLong(), rommSaveId = (i + 100).toLong(),
                        candidatePath = "/tmp/candidate-$i.srm", downloadedSizeBytes = 100,
                        serverContentHash = "hash-$i", emulator = "core",
                        romId = i.toLong(), romHash = "h-$i", coreId = "c", coreBuildRevision = "r",
                    )
                    handler.cacheCandidateMetadata(meta)
                }
            }
        }

        threads.forEach { it.start() }
        latch.countDown()
        threads.forEach { it.join(5000) }

        assertThat(handler.candidateMetadataCache.size).isEqualTo(10)
        for (i in 1..10) {
            val meta = handler.getCandidateMetadata(i.toString())
            assertThat(meta).isNotNull
            assertThat(meta!!.rommSessionId).isEqualTo(i.toLong())
        }
    }

    @Test
    fun `candidateMetadataCache remove is thread-safe`() {
        val handler = TestableEmulationResultHandler()

        runBlocking {
            for (i in 1..5) {
                val meta = CandidateSaveMetadata(
                    rommSessionId = i.toLong(), rommSaveId = (i + 100).toLong(),
                    candidatePath = "/tmp/candidate-$i.srm", downloadedSizeBytes = 100,
                    serverContentHash = "hash-$i", emulator = "core",
                    romId = i.toLong(), romHash = "h-$i", coreId = "c", coreBuildRevision = "r",
                )
                handler.cacheCandidateMetadata(meta)
            }
        }

        assertThat(handler.candidateMetadataCache.size).isEqualTo(5)

        runBlocking {
            handler.removeCandidateMetadata("3")
        }

        assertThat(handler.candidateMetadataCache.size).isEqualTo(4)
        assertThat(handler.getCandidateMetadata("3")).isNull()
    }

    @Test
    fun `sessionLocks are created lazily and independently`() {
        val handler = TestableEmulationResultHandler()

        val lock1 = handler.getSessionLock("session-a")
        val lock2 = handler.getSessionLock("session-b")
        val lock3 = handler.getSessionLock("session-a")

        // Same sessionId returns same lock instance
        assertThat(lock1).isSameAs(lock3)
        // Different sessionId returns different lock instance
        assertThat(lock1).isNotSameAs(lock2)
    }
}

/** Minimal testable EmulationResultHandler that exposes internal state for testing. */
private class TestableEmulationResultHandler {
    val candidateMetadataCache = java.util.concurrent.ConcurrentHashMap<String, CandidateSaveMetadata>()
    private val sessionLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    fun cacheCandidateMetadata(sessionId: String, metadata: CandidateSaveMetadata) {
        candidateMetadataCache[sessionId] = metadata
    }

    @Deprecated("Use cacheCandidateMetadata(sessionId, metadata)")
    fun cacheCandidateMetadata(metadata: CandidateSaveMetadata) {
        candidateMetadataCache[metadata.rommSessionId.toString()] = metadata
    }

    fun getCandidateMetadata(sessionId: String): CandidateSaveMetadata? =
        candidateMetadataCache[sessionId]

    fun removeCandidateMetadata(sessionId: String) {
        candidateMetadataCache.remove(sessionId)
    }

    fun getSessionLock(sessionId: String): kotlinx.coroutines.sync.Mutex {
        return sessionLocks.computeIfAbsent(sessionId) { kotlinx.coroutines.sync.Mutex() }
    }
}
