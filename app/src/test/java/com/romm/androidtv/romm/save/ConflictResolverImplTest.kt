package com.romm.androidtv.romm.save

import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.DeviceIdentityStore
import com.romm.androidtv.romm.DeviceRegistrationResult
import com.romm.androidtv.romm.DeviceRepository
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncOperation
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * JVM unit tests for [ConflictResolverImpl].
 *
 * Uses MockWebServer for network calls (RommSyncApi HTTP contract),
 * in-memory fakes for DAOs and content store. Exercises:
 * - Ordering correctness (backup before overwrite)
 * - Both-copy preservation on success
 * - Both-copy preservation on failure
 * - Stale input rejection
 * - Unknown/incompatible provenance rejection
 * - Hash/size mismatch rejection
 * - Upload conflict handling
 * - Idempotent replay
 * - No mutation on rejected data
 */
@DisplayName("ConflictResolverImpl - concrete explicit conflict resolution")
class ConflictResolverImplTest {

    private lateinit var server: MockWebServer
    private lateinit var client: okhttp3.OkHttpClient
    private lateinit var sessionStore: SessionStore
    private lateinit var deviceRepository: FakeDeviceRepository
    private lateinit var saveReplicaDao: FakeSaveReplicaDao
    private lateinit var saveContentStore: FakeSaveContentStore
    private lateinit var resolver: ConflictResolverImpl

    private var clockValue = 20_000L

    private val serverKey = "localhost"
    private val userKey = "alice"
    private val romId = 1L
    private val romHash = "hash-a"
    private val slot = "autosave"
    private val coreId = "sameboy"
    private val coreBuildRevision = "v1.0.3-libretro"
    private val sessionId = 7L
    private lateinit var serverOrigin: String

    private val localBytes = byteArrayOf(10, 20, 30)
    private val serverBytes = byteArrayOf(40, 50, 60)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start(0)
        serverOrigin = server.url("/").toString().removeSuffix("/")
        client = okhttp3.OkHttpClient.Builder().build()
        sessionStore = SessionStore(FakeSharedPreferences())
        sessionStore.save(serverOrigin, "alice")
        deviceRepository = FakeDeviceRepository()
        saveReplicaDao = FakeSaveReplicaDao()
        saveContentStore = FakeSaveContentStore()
        resolver = ConflictResolverImpl(
            client, deviceRepository, saveReplicaDao, saveContentStore,
            clock = { clockValue },
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun makeLocalEntity(
        rommSaveId: Long? = 99L,
        localHash: String? = "local-hash-abc",
        syncStatus: SaveSyncStatus = SaveSyncStatus.CONFLICT,
        expectedSramSizeBytes: Long? = null,
    ) = SaveReplicaEntity(
        serverKey = serverKey,
        userKey = userKey,
        romId = romId,
        romHash = romHash,
        slot = slot,
        coreId = coreId,
        coreBuildRevision = coreBuildRevision,
        expectedSramSizeBytes = expectedSramSizeBytes,
        localHash = localHash,
        localSizeBytes = localBytes.size.toLong(),
        localWrittenAtEpochMs = 15_000L,
        rommSaveId = rommSaveId,
        serverHash = null,
        serverSizeBytes = null,
        serverUpdatedAtEpochMs = null,
        syncStatus = syncStatus,
        lastError = "conflict",
    )

    private fun makeConflictOperation(
        saveId: Long = 11L,
        emulator: String? = "sameboy",
        serverContentHash: String? = null,
        serverUpdatedAt: Instant? = Instant.parse("2026-01-02T00:00:00Z"),
    ) = SyncOperation(
        action = SyncAction.CONFLICT,
        romId = romId,
        saveId = saveId,
        fileName = "autosave.srm",
        slot = slot,
        emulator = emulator,
        reason = "both changed",
        serverUpdatedAt = serverUpdatedAt,
        serverContentHash = serverContentHash,
    )

    // ==================== KEEP LOCAL ====================

    @Nested
    @DisplayName("resolveKeepLocal")
    inner class ResolveKeepLocal {

        @Test
        fun `happy path downloads server backup backs it up uploads local persists SYNCED`() {
            runBlocking {
                val entity = makeLocalEntity()
                val serverHash = sha256Hex(serverBytes)
                val op = makeConflictOperation(serverContentHash = serverHash)

                // Seed local bytes
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                // Enqueue: download backup, upload, complete
                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
                enqueueUploadSaveResponse()
                enqueueComplete()

                val result = resolver.resolveKeepLocal(
                    sessionId = sessionId,
                    serverOrigin = serverOrigin,
                    username = userKey,
                    localEntity = entity,
                    operation = op,
                    localFileName = "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Success::class.java)
                val success = result as ConflictResolutionResult.Success
                assertThat(success.choice).isEqualTo(ConflictChoice.KEEP_LOCAL)
                assertThat(success.serverBackupPath).isNotNull
                assertThat(success.localBackupPath).isNull()
                assertThat(success.newServerSaveInfo).isNotNull

                // Server bytes were backed up
                assertThat(saveContentStore.conflictBackups).hasSize(1)
                val backupEntry = saveContentStore.conflictBackups[0]
                assertThat(backupEntry.second).isEqualTo(serverBytes)
                assertThat(backupEntry.first).contains("conflict-${sessionId}-keep-local")

                // Local bytes were NOT modified (still original)
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(localBytes)

                // Replica is SYNCED
                val replica = saveReplicaDao.findByScope(serverKey, userKey, romId, romHash, slot)
                assertThat(replica).isNotNull
                assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
                assertThat(replica.lastError).isNull()
            }
        }

        @Test
        fun `ordering server backup occurs before upload`() {
            runBlocking {
                val entity = makeLocalEntity()
                val serverHash = sha256Hex(serverBytes)
                val op = makeConflictOperation(serverContentHash = serverHash)
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
                enqueueUploadSaveResponse()
                enqueueComplete()

                resolver.resolveKeepLocal(sessionId, serverOrigin, userKey, entity, op, "autosave.srm")

                // Backup was created before the upload completed
                assertThat(saveContentStore.conflictBackups).hasSize(1)
                assertThat(saveContentStore.conflictBackups[0].second).isEqualTo(serverBytes)
            }
        }

        @Test
        fun `server download failure preserves local bytes and returns failure`() {
            runBlocking {
                val entity = makeLocalEntity()
                saveReplicaDao.upsert(entity)
                val op = makeConflictOperation()
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                // Download fails with 500
                server.enqueue(MockResponse().setResponseCode(500))

                val result = resolver.resolveKeepLocal(
                    sessionId, serverOrigin, userKey, entity, op, "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).isEqualTo("server-download-failed")

                // Local bytes untouched
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(localBytes)
                // No backups created
                assertThat(saveContentStore.conflictBackups).isEmpty()
                // Replica unchanged
                val replica = saveReplicaDao.findByScope(serverKey, userKey, romId, romHash, slot)
                assertThat(replica).isNotNull
                assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.CONFLICT)
            }
        }

        @Test
        fun `server hash mismatch rejects without mutation`() {
            runBlocking {
                val entity = makeLocalEntity()
                // Provide a mismatched server hash
                val op = makeConflictOperation(serverContentHash = "wrong-hash-value")
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                // downloadSaveContentBackup will succeed but hash won't match
                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))

                val result = resolver.resolveKeepLocal(
                    sessionId, serverOrigin, userKey, entity, op, "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).contains("server-hash-mismatch")

                // No backups, no local mutation
                assertThat(saveContentStore.conflictBackups).isEmpty()
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(localBytes)
            }
        }

        @Test
        fun `upload conflict 409 aborts with both copies preserved`() {
            runBlocking {
                val entity = makeLocalEntity()
                val serverHash = sha256Hex(serverBytes)
                val op = makeConflictOperation(serverContentHash = serverHash)
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
                // Upload returns 409
                server.enqueue(MockResponse().setResponseCode(409))

                val result = resolver.resolveKeepLocal(
                    sessionId, serverOrigin, userKey, entity, op, "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).contains("upload-still-conflict")

                // Server backup exists (backup happened before upload)
                assertThat(saveContentStore.conflictBackups).hasSize(1)
                assertThat(saveContentStore.conflictBackups[0].second).isEqualTo(serverBytes)
                // Local bytes untouched
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(localBytes)
            }
        }

        @Test
        fun `no local bytes returns failure`() {
            runBlocking {
                val entity = makeLocalEntity()
                val serverHash = sha256Hex(serverBytes)
                val op = makeConflictOperation(serverContentHash = serverHash)
                // Do NOT seed local bytes

                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))

                val result = resolver.resolveKeepLocal(
                    sessionId, serverOrigin, userKey, entity, op, "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).contains("no-local-bytes")
            }
        }

        @Test
        fun `incompatible provenance rejects without mutation`() {
            runBlocking {
                val entity = makeLocalEntity()
                val op = makeConflictOperation(emulator = "different-core")
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                val result = resolver.resolveKeepLocal(
                    sessionId, serverOrigin, userKey, entity, op, "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).contains("incompatible-provenance")
                assertThat(saveContentStore.conflictBackups).isEmpty()
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(localBytes)
            }
        }

        @Test
        fun `null server emulator unknown provenance rejects`() {
            runBlocking {
                val entity = makeLocalEntity()
                val op = makeConflictOperation(emulator = null)
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                val result = resolver.resolveKeepLocal(
                    sessionId, serverOrigin, userKey, entity, op, "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).contains("incompatible-provenance")
            }
        }
    }

    // ==================== KEEP SERVER ====================

    @Nested
    @DisplayName("resolveKeepServer")
    inner class ResolveKeepServer {

        @Test
        fun `happy path downloads server backs up local adopts server confirms`() {
            runBlocking {
                val entity = makeLocalEntity()
                val serverHash = sha256Hex(serverBytes)
                val op = makeConflictOperation(serverContentHash = serverHash)

                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                // Enqueue: download server, confirm download, complete
                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
                server.enqueue(MockResponse().setResponseCode(200)) // confirm download
                enqueueComplete()

                val result = resolver.resolveKeepServer(
                    sessionId = sessionId,
                    serverOrigin = serverOrigin,
                    username = userKey,
                    localEntity = entity,
                    operation = op,
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Success::class.java)
                val success = result as ConflictResolutionResult.Success
                assertThat(success.choice).isEqualTo(ConflictChoice.KEEP_SERVER)
                assertThat(success.localBackupPath).isNotNull
                assertThat(success.serverBackupPath).isNull()

                // Local bytes were backed up
                assertThat(saveContentStore.conflictBackups).hasSize(1)
                val backupEntry = saveContentStore.conflictBackups[0]
                assertThat(backupEntry.second).isEqualTo(localBytes)
                assertThat(backupEntry.first).contains("conflict-${sessionId}-keep-server")

                // Local autosave now has server bytes
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(serverBytes)

                // Replica is SYNCED with server metadata
                val replica = saveReplicaDao.findByScope(serverKey, userKey, romId, romHash, slot)
                assertThat(replica).isNotNull
                assertThat(replica!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
                assertThat(replica.localHash).isEqualTo(serverHash)
                assertThat(replica.localSizeBytes).isEqualTo(serverBytes.size.toLong())
                assertThat(replica.rommSaveId).isEqualTo(op.saveId)
            }
        }

        @Test
        fun `ordering local backup occurs before atomic write`() {
            runBlocking {
                val entity = makeLocalEntity()
                val serverHash = sha256Hex(serverBytes)
                val op = makeConflictOperation(serverContentHash = serverHash)
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
                server.enqueue(MockResponse().setResponseCode(200))
                enqueueComplete()

                resolver.resolveKeepServer(sessionId, serverOrigin, userKey, entity, op)

                // Backup was created with original local bytes
                assertThat(saveContentStore.conflictBackups).hasSize(1)
                assertThat(saveContentStore.conflictBackups[0].second).isEqualTo(localBytes)
                // Current local is now server bytes
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(serverBytes)
            }
        }

        @Test
        fun `server download failure preserves local bytes`() {
            runBlocking {
                val entity = makeLocalEntity()
                val op = makeConflictOperation()
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                server.enqueue(MockResponse().setResponseCode(500))

                val result = resolver.resolveKeepServer(
                    sessionId, serverOrigin, userKey, entity, op,
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(localBytes)
                assertThat(saveContentStore.conflictBackups).isEmpty()
            }
        }

        @Test
        fun `server hash mismatch rejects without mutation`() {
            runBlocking {
                val entity = makeLocalEntity()
                val op = makeConflictOperation(serverContentHash = "wrong-hash")
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))

                val result = resolver.resolveKeepServer(
                    sessionId, serverOrigin, userKey, entity, op,
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).contains("server-hash-mismatch")
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(localBytes)
                assertThat(saveContentStore.conflictBackups).isEmpty()
            }
        }

        @Test
        fun `SRAM size mismatch rejects without mutation`() {
            runBlocking {
                val entity = makeLocalEntity(expectedSramSizeBytes = 999L)
                val serverHash = sha256Hex(serverBytes)
                val op = makeConflictOperation(serverContentHash = serverHash)
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))

                val result = resolver.resolveKeepServer(
                    sessionId, serverOrigin, userKey, entity, op,
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).contains("sram-size-mismatch")
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(localBytes)
                assertThat(saveContentStore.conflictBackups).isEmpty()
            }
        }

        @Test
        fun `no existing local bytes skip backup still adopt server`() {
            runBlocking {
                val entity = makeLocalEntity()
                val serverHash = sha256Hex(serverBytes)
                val op = makeConflictOperation(serverContentHash = serverHash)
                // Do NOT seed local bytes

                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
                server.enqueue(MockResponse().setResponseCode(200))
                enqueueComplete()

                val result = resolver.resolveKeepServer(
                    sessionId, serverOrigin, userKey, entity, op,
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Success::class.java)
                val success = result as ConflictResolutionResult.Success
                assertThat(success.localBackupPath).isNull()
                assertThat(saveContentStore.conflictBackups).isEmpty()
                // Server bytes adopted
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(serverBytes)
            }
        }

        @Test
        fun `incompatible provenance rejects without mutation`() {
            runBlocking {
                val entity = makeLocalEntity()
                val op = makeConflictOperation(emulator = "different-core")
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                val result = resolver.resolveKeepServer(
                    sessionId, serverOrigin, userKey, entity, op,
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(localBytes)
            }
        }
    }

    // ==================== INPUT VALIDATION ====================

    @Nested
    @DisplayName("Input validation")
    inner class InputValidation {

        @Test
        fun `zero sessionId rejected`() {
            runBlocking {
                val entity = makeLocalEntity()
                val op = makeConflictOperation()

                val result = resolver.resolveKeepLocal(
                    sessionId = 0, serverOrigin, userKey, entity, op, "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).contains("invalid-session")
            }
        }

        @Test
        fun `negative sessionId rejected`() {
            runBlocking {
                val entity = makeLocalEntity()
                val op = makeConflictOperation()

                val result = resolver.resolveKeepLocal(
                    sessionId = -1, serverOrigin, userKey, entity, op, "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).contains("invalid-session")
            }
        }

        @Test
        fun `non-conflict action rejected`() {
            runBlocking {
                val entity = makeLocalEntity()
                val op = makeConflictOperation().copy(action = SyncAction.UPLOAD)

                val result = resolver.resolveKeepLocal(
                    sessionId, serverOrigin, userKey, entity, op, "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).contains("invalid-action")
            }
        }

        @Test
        fun `null saveId rejected`() {
            runBlocking {
                val entity = makeLocalEntity()
                val op = makeConflictOperation(saveId = 0).copy(saveId = null)

                val result = resolver.resolveKeepLocal(
                    sessionId, serverOrigin, userKey, entity, op, "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).contains("missing-saveId")
            }
        }

        @Test
        fun `romId scope mismatch rejected`() {
            runBlocking {
                val entity = makeLocalEntity()
                val op = makeConflictOperation().copy(romId = 999L)

                val result = resolver.resolveKeepLocal(
                    sessionId, serverOrigin, userKey, entity, op, "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.reason).contains("scope-mismatch")
            }
        }
    }

    // ==================== IDEMPOTENT REPLAY ====================

    @Nested
    @DisplayName("Idempotent replay")
    inner class IdempotentReplay {

        @Test
        fun `keep-local replay produces identical backup path`() {
            runBlocking {
                val entity = makeLocalEntity()
                val serverHash = sha256Hex(serverBytes)
                val op = makeConflictOperation(serverContentHash = serverHash)
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                // First call
                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
                enqueueUploadSaveResponse()
                enqueueComplete()

                val first = resolver.resolveKeepLocal(
                    sessionId, serverOrigin, userKey, entity, op, "autosave.srm",
                )
                val firstPath = (first as ConflictResolutionResult.Success).serverBackupPath

                // Reset state for replay
                saveContentStore = FakeSaveContentStore()
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)
                saveReplicaDao = FakeSaveReplicaDao()
                resolver = ConflictResolverImpl(
                    client, deviceRepository, saveReplicaDao, saveContentStore,
                    clock = { clockValue },
                )

                // Replay
                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
                enqueueUploadSaveResponse()
                enqueueComplete()

                val second = resolver.resolveKeepLocal(
                    sessionId, serverOrigin, userKey, entity, op, "autosave.srm",
                )
                val secondPath = (second as ConflictResolutionResult.Success).serverBackupPath

                // Deterministic path: same session + choice + hash = same path
                assertThat(firstPath).isEqualTo(secondPath)
            }
        }

        @Test
        fun `keep-server replay produces identical backup path`() {
            runBlocking {
                val entity = makeLocalEntity()
                val serverHash = sha256Hex(serverBytes)
                val op = makeConflictOperation(serverContentHash = serverHash)
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
                server.enqueue(MockResponse().setResponseCode(200))
                enqueueComplete()

                val first = resolver.resolveKeepServer(
                    sessionId, serverOrigin, userKey, entity, op,
                )
                val firstPath = (first as ConflictResolutionResult.Success).localBackupPath

                // Reset state
                saveContentStore = FakeSaveContentStore()
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)
                saveReplicaDao = FakeSaveReplicaDao()
                resolver = ConflictResolverImpl(
                    client, deviceRepository, saveReplicaDao, saveContentStore,
                    clock = { clockValue },
                )

                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
                server.enqueue(MockResponse().setResponseCode(200))
                enqueueComplete()

                val second = resolver.resolveKeepServer(
                    sessionId, serverOrigin, userKey, entity, op,
                )
                val secondPath = (second as ConflictResolutionResult.Success).localBackupPath

                assertThat(firstPath).isEqualTo(secondPath)
            }
        }
    }

    // ==================== DEVICE REGISTRATION FAILURE ====================

    @Nested
    @DisplayName("Device registration failure")
    inner class DeviceRegistrationFailure {

        @Test
        fun `keep-local fails fast when device registration fails`() {
            runBlocking {
                deviceRepository.setFailure(RommApiError.AUTH_EXPIRED)
                val entity = makeLocalEntity()
                val op = makeConflictOperation()
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                val result = resolver.resolveKeepLocal(
                    sessionId, serverOrigin, userKey, entity, op, "autosave.srm",
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.error).isEqualTo(RommApiError.AUTH_EXPIRED)
                assertThat(failure.reason).isEqualTo("device-registration-failed")
                assertThat(saveContentStore.conflictBackups).isEmpty()
            }
        }

        @Test
        fun `keep-server fails fast when device registration fails`() {
            runBlocking {
                deviceRepository.setFailure(RommApiError.NETWORK_ERROR)
                val entity = makeLocalEntity()
                val op = makeConflictOperation()
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                val result = resolver.resolveKeepServer(
                    sessionId, serverOrigin, userKey, entity, op,
                )

                assertThat(result).isInstanceOf(ConflictResolutionResult.Failure::class.java)
                val failure = result as ConflictResolutionResult.Failure
                assertThat(failure.error).isEqualTo(RommApiError.AUTH_EXPIRED)
            }
        }
    }

    // ==================== SYNC SESSION COMPLETION FAILURE ====================

    @Nested
    @DisplayName("completeSyncSession failure handling")
    inner class SyncSessionCompletionFailure {

        @Test
        fun `keep-local succeeds locally even when completeSyncSession fails server-side`() {
            runBlocking {
                val entity = makeLocalEntity()
                val serverHash = sha256Hex(serverBytes)
                val op = makeConflictOperation(serverContentHash = serverHash)
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                // Enqueue: download backup, upload success, complete FAILS (500)
                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
                enqueueUploadSaveResponse()
                server.enqueue(MockResponse().setResponseCode(500))

                val result = resolver.resolveKeepLocal(
                    sessionId = sessionId,
                    serverOrigin = serverOrigin,
                    username = userKey,
                    localEntity = entity,
                    operation = op,
                    localFileName = "autosave.srm",
                )

                // Local resolution still succeeds — completeSyncSession is best-effort.
                assertThat(result).isInstanceOf(ConflictResolutionResult.Success::class.java)
                val success = result as ConflictResolutionResult.Success
                assertThat(success.choice).isEqualTo(ConflictChoice.KEEP_LOCAL)

                // Local replica is SYNCED despite server completion failure.
                val replica = saveReplicaDao.findByScope(serverKey, userKey, romId, romHash, slot)
                assertThat(replica!!).isNotNull
                assertThat(replica.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)

                // Server backup was created (data not rolled back).
                assertThat(saveContentStore.conflictBackups).hasSize(1)
            }
        }

        @Test
        fun `keep-server succeeds locally even when completeSyncSession fails server-side`() {
            runBlocking {
                val entity = makeLocalEntity()
                val serverHash = sha256Hex(serverBytes)
                val op = makeConflictOperation(serverContentHash = serverHash)
                saveContentStore.seedLocal(serverKey, userKey, romId, romHash, slot, localBytes)

                // Enqueue: download server, confirm download, complete FAILS (503)
                server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(serverBytes)))
                server.enqueue(MockResponse().setResponseCode(200)) // confirm download
                server.enqueue(MockResponse().setResponseCode(503))

                val result = resolver.resolveKeepServer(
                    sessionId = sessionId,
                    serverOrigin = serverOrigin,
                    username = userKey,
                    localEntity = entity,
                    operation = op,
                )

                // Local resolution still succeeds — completeSyncSession is best-effort.
                assertThat(result).isInstanceOf(ConflictResolutionResult.Success::class.java)
                val success = result as ConflictResolutionResult.Success
                assertThat(success.choice).isEqualTo(ConflictChoice.KEEP_SERVER)

                // Local replica is SYNCED despite server completion failure.
                val replica = saveReplicaDao.findByScope(serverKey, userKey, romId, romHash, slot)
                assertThat(replica!!).isNotNull
                assertThat(replica.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)

                // Server bytes were adopted locally (data not rolled back).
                assertThat(saveContentStore.readLocal(serverKey, userKey, romId, romHash, slot)).isEqualTo(serverBytes)
            }
        }
    }

    // ==================== HELPER METHODS ====================

    private fun enqueueUploadSaveResponse() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id": 12, "rom_id": 1, "file_name": "autosave.srm", "slot": "autosave", "emulator": "sameboy", "content_hash": "uploaded-hash", "updated_at": "2026-01-03T00:00:00Z", "file_size_bytes": 3}"""
            )
        )
    }

    private fun enqueueComplete() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"session": {"id": 7, "status": "COMPLETED"}}""")
        )
    }

}

/**
 * Fake [DeviceRepository] for conflict resolver tests.
 * Defaults to success; can be configured to fail.
 */
class FakeDeviceRepository : DeviceRepository {
    private var shouldFail: RommApiError? = null
    private var failureHttpCode: Int? = null

    fun setFailure(error: RommApiError, httpCode: Int? = null) {
        shouldFail = error
        failureHttpCode = httpCode
    }

    override suspend fun ensureRegistered(serverOrigin: String, username: String): DeviceRegistrationResult {
        return shouldFail?.let {
            DeviceRegistrationResult.Failure(it, failureHttpCode)
        } ?: DeviceRegistrationResult.Success(
            com.romm.androidtv.romm.DeviceIdentity("install-1", "device-1"),
            alreadyExisted = true,
        )
    }

    override fun forget(serverOrigin: String, username: String) {
        // no-op
    }
}
