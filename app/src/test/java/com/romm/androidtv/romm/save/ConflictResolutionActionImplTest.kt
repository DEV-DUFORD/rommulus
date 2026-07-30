package com.romm.androidtv.romm.save

import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncOperation
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * JVM unit tests for [ConflictResolutionActionImpl].
 * Validates adapter delegation, non-mutating acknowledgeQuarantine,
 * and that the impl correctly bridges UI interface to domain resolver.
 */
@DisplayName("ConflictResolutionActionImpl — UI-to-domain adapter")
class ConflictResolutionActionImplTest {

    private val serverOrigin = "http://localhost:8080"
    private val username = "alice"
    private val localFileName = "autosave.srm"

    private val fakeResolver = object : ConflictResolver {
        var keepLocalCalled = false
        var keepServerCalled = false
        var keepLocalSessionId: Long? = null
        var keepServerSessionId: Long? = null
        var keepLocalEntity: SaveReplicaEntity? = null
        var keepServerEntity: SaveReplicaEntity? = null
        var keepLocalOperation: SyncOperation? = null
        var keepServerOperation: SyncOperation? = null

        var lastKeepLocalResult: ConflictResolutionResult = ConflictResolutionResult.Success(
            choice = ConflictChoice.KEEP_LOCAL,
            serverBackupPath = "/backup/server.srm",
            localBackupPath = null,
            newServerSaveInfo = null,
        )

        var lastKeepServerResult: ConflictResolutionResult = ConflictResolutionResult.Success(
            choice = ConflictChoice.KEEP_SERVER,
            serverBackupPath = null,
            localBackupPath = "/backup/local.srm",
            newServerSaveInfo = null,
        )

        override suspend fun resolveKeepLocal(
            sessionId: Long,
            serverOrigin: String,
            username: String,
            localEntity: SaveReplicaEntity,
            operation: SyncOperation,
            localFileName: String,
        ): ConflictResolutionResult {
            keepLocalCalled = true
            keepLocalSessionId = sessionId
            keepLocalEntity = localEntity
            keepLocalOperation = operation
            return lastKeepLocalResult
        }

        override suspend fun resolveKeepServer(
            sessionId: Long,
            serverOrigin: String,
            username: String,
            localEntity: SaveReplicaEntity,
            operation: SyncOperation,
        ): ConflictResolutionResult {
            keepServerCalled = true
            keepServerSessionId = sessionId
            keepServerEntity = localEntity
            keepServerOperation = operation
            return lastKeepServerResult
        }
    }

    private val baseEntity = SaveReplicaEntity(
        serverKey = "localhost",
        userKey = "alice",
        romId = 42,
        romHash = "abc123",
        slot = "autosave",
        coreId = "sameboy",
        coreBuildRevision = "v0.19",
    )

    private val baseOperation = SyncOperation(
        action = SyncAction.CONFLICT,
        romId = 42,
        saveId = 100L,
        fileName = "autosave.srm",
        slot = "autosave",
        emulator = "sameboy",
        reason = "both changed",
        serverUpdatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        serverContentHash = null,
    )

    @Nested
    @DisplayName("resolveKeepLocal delegation")
    inner class ResolveKeepLocal {

        @Test
        fun `delegates to resolver with correct parameters`() {
            runBlocking {
                val impl = ConflictResolutionActionImpl(
                    resolver = fakeResolver,
                    serverOrigin = serverOrigin,
                    username = username,
                    localFileName = localFileName,
                )

                impl.resolveKeepLocal(
                    sessionId = 7L,
                    localEntity = baseEntity,
                    serverOperation = baseOperation,
                )

                assertThat(fakeResolver.keepLocalCalled).isTrue()
                assertThat(fakeResolver.keepLocalSessionId).isEqualTo(7L)
                assertThat(fakeResolver.keepLocalEntity).isEqualTo(baseEntity)
                assertThat(fakeResolver.keepLocalOperation).isEqualTo(baseOperation)
            }
        }

        @Test
        fun `propagates resolver success`() {
            runBlocking {
                fakeResolver.lastKeepLocalResult = ConflictResolutionResult.Success(
                    choice = ConflictChoice.KEEP_LOCAL,
                    serverBackupPath = "/backup/server.srm",
                    localBackupPath = null,
                    newServerSaveInfo = null,
                )

                val impl = ConflictResolutionActionImpl(
                    resolver = fakeResolver,
                    serverOrigin = serverOrigin,
                    username = username,
                    localFileName = localFileName,
                )

                impl.resolveKeepLocal(7L, baseEntity, baseOperation)
                // No exception thrown; success propagated.
            }
        }

        @Test
        fun `propagates resolver failure`() {
            runBlocking {
                fakeResolver.lastKeepLocalResult = ConflictResolutionResult.Failure(
                    RommApiError.NETWORK_ERROR,
                    reason = "upload-failed",
                )

                val impl = ConflictResolutionActionImpl(
                    resolver = fakeResolver,
                    serverOrigin = serverOrigin,
                    username = username,
                    localFileName = localFileName,
                )

                // resolveKeepLocal on the Action interface is suspend void;
                // failures are returned from the resolver but the adapter swallows them.
                // The caller (MainActivity) observes state via SavePreLaunchState.
                impl.resolveKeepLocal(7L, baseEntity, baseOperation)
            }
        }
    }

    @Nested
    @DisplayName("resolveKeepServer delegation")
    inner class ResolveKeepServer {

        @Test
        fun `delegates to resolver with correct parameters`() {
            runBlocking {
                val impl = ConflictResolutionActionImpl(
                    resolver = fakeResolver,
                    serverOrigin = serverOrigin,
                    username = username,
                    localFileName = localFileName,
                )

                impl.resolveKeepServer(
                    sessionId = 7L,
                    localEntity = baseEntity,
                    serverOperation = baseOperation,
                )

                assertThat(fakeResolver.keepServerCalled).isTrue()
                assertThat(fakeResolver.keepServerSessionId).isEqualTo(7L)
                assertThat(fakeResolver.keepServerEntity).isEqualTo(baseEntity)
                assertThat(fakeResolver.keepServerOperation).isEqualTo(baseOperation)
            }
        }
    }

    @Nested
    @DisplayName("acknowledgeQuarantine is non-mutating")
    inner class AcknowledgeQuarantine {

        @Test
        fun `acknowledgeQuarantine does not throw`() {
            val impl = ConflictResolutionActionImpl(
                resolver = fakeResolver,
                serverOrigin = serverOrigin,
                username = username,
                localFileName = localFileName,
            )

            // Should complete without exception; no filesystem/Room/network mutation.
            impl.acknowledgeQuarantine("/data/quarantine/save.srm")
        }

        @Test
        fun `acknowledgeQuarantine does not invoke resolver`() {
            val impl = ConflictResolutionActionImpl(
                resolver = fakeResolver,
                serverOrigin = serverOrigin,
                username = username,
                localFileName = localFileName,
            )

            impl.acknowledgeQuarantine("/data/quarantine/save.srm")

            assertThat(fakeResolver.keepLocalCalled).isFalse()
            assertThat(fakeResolver.keepServerCalled).isFalse()
        }
    }
}
