package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Deterministic JVM tests proving that rapid query changes cannot let stale
 * network responses overwrite newer UI state in [SearchPresenter].
 * (The HomePresenter/RomGridPresenter counterparts of this suite live next to
 * this file as `StaleToggleResponseTest`.)
 *
 * **Non-cooperative repositories**: Several tests use [CountDownLatch] to block
 * a repository call independently of Kotlin coroutine cancellation. This proves
 * that generation guards — not merely cooperative cancellation — prevent stale writes.
 *
 * The presenter runs on a virtual-time [StandardTestDispatcher]; after a real
 * thread pool releases a latch, the stale coroutine re-dispatches onto the
 * virtual scheduler, which is then drained with advanceUntilIdle().
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@DisplayName("Stale-response hardening: SearchPresenter generation tokens prevent older responses from winning")
class SearchStaleToggleResponseTest {

    /** Dedicated thread pool for non-cooperative blocking calls (ignores Kotlin cancellation). */
    private val blockingExecutor = Executors.newCachedThreadPool()

    private lateinit var testScope: TestScope

    @BeforeEach
    fun setUp() {
        testScope = TestScope(StandardTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        testScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        blockingDispatcher.close()
        if (!blockingExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
            blockingExecutor.shutdownNow()
            blockingExecutor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makeSupportedRom(id: Long): LibraryRom =
        LibraryRom(id = id, title = "Supported $id", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false)

    private fun makeUnsupportedRom(id: Long): LibraryRom =
        LibraryRom(id = id, title = "Unsupported $id", platformDisplayName = "PSP", platformSlug = "psp", coverUrl = null, lastPlayedIso = null, nowPlaying = false)

    /** Dedicated dispatcher for non-cooperative blocking (ignores Kotlin cancellation). */
    private val blockingDispatcher = blockingExecutor.asCoroutineDispatcher()

    /**
     * Blocks on a [CountDownLatch] using a separate thread so that Kotlin coroutine
     * cancellation has no effect. This simulates a genuinely non-cooperative network call.
     */
    private suspend fun blockNonCooperatively(latch: CountDownLatch): Unit =
        withContext(blockingDispatcher) {
            latch.await()  // java.util.concurrent — ignores Kotlin cancellation
        }

    /**
     * After a real thread pool releases a latch, the stale coroutine resumes on that
     * thread and re-dispatches onto the virtual scheduler. Give it a brief moment to
     * be enqueued, then drain the virtual scheduler so its (guarded) write is actually
     * processed before asserting.
     */
    private fun drainAfterRealThreadRelease() {
        Thread.sleep(100) // Wait for the pool thread to re-dispatch onto the virtual scheduler.
        testScope.testScheduler.advanceUntilIdle()
    }

    // =========================================================================
    // SearchPresenter — regression tests for generation protection
    // =========================================================================

    /**
     * Verify that a search whose response arrives after a newer query was issued
     * is correctly discarded by the generation check.
     */
    @Test
    fun `Search stale response discarded when new query supersedes`() {
        val blockFirstSearchLatch = CountDownLatch(1)
        var callNum = 0

        val repo = object : LibraryRepository {
            override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
                callNum++
                if (callNum == 1) blockNonCooperatively(blockFirstSearchLatch)
                return LibraryResult.Success(RomPage(
                    roms = if (callNum == 1) listOf(makeUnsupportedRom(99)) else listOf(makeSupportedRom(42)),
                    total = 1,
                ))
            }
            override suspend fun fetchRecentlyAdded(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchContinuePlaying(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchFavorites(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }

        val presenter = SearchPresenter(scope = testScope, repository = repo)

        // First search — blocks non-cooperatively.
        presenter.onQueryChanged("first")
        presenter.submitQuery()
        testScope.testScheduler.advanceUntilIdle()
        assertThat(callNum).isEqualTo(1)

        // New query — cancels first search, starts new gen.
        presenter.onQueryChanged("second")
        presenter.submitQuery()
        testScope.testScheduler.advanceUntilIdle()
        assertThat(callNum).isEqualTo(2)

        // Gen-2 data loaded.
        assertThat(presenter.uiState.value.roms[0].id).isEqualTo(42L)

        // Release stale gen-1 response — should be discarded by generation check.
        blockFirstSearchLatch.countDown()
        drainAfterRealThreadRelease()

        val finalState = presenter.uiState.value
        assertThat(finalState.roms.none { it.id == 99L }).isTrue()
        assertThat(finalState.query).isEqualTo("second")
    }

    /**
     * Regression: old loadMore FAILS after a new search starts. The stale failure
     * must NOT clear the newer query's isLoading or overwrite its results.
     */
    @Test
    fun `Search stale loadMore failure does not corrupt new query state`() {
        val blockLoadMoreLatch = CountDownLatch(1)
        var callNum = 0

        val repo = object : LibraryRepository {
            override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
                callNum++
                if (offset > 0) {
                    // This is the loadMore — block non-cooperatively, then fail.
                    blockNonCooperatively(blockLoadMoreLatch)
                    return LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                }
                // Searches: return quickly.
                return LibraryResult.Success(RomPage(
                    roms = listOf(makeSupportedRom(callNum.toLong())),
                    total = 10,
                ))
            }
            override suspend fun fetchRecentlyAdded(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchContinuePlaying(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchFavorites(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }

        val presenter = SearchPresenter(scope = testScope, repository = repo)

        // First search (gen 1): id=1 loaded, total=10.
        presenter.onQueryChanged("first")
        presenter.submitQuery()
        testScope.testScheduler.advanceUntilIdle()
        assertThat(presenter.uiState.value.isLoading).isFalse()
        assertThat(presenter.uiState.value.roms[0].id).isEqualTo(1L)

        // Start loadMore (gen 1) — blocks non-cooperatively.
        presenter.loadMore()
        testScope.testScheduler.advanceUntilIdle()
        assertThat(presenter.uiState.value.isLoading).isTrue()

        // New search (gen 2): cancels old loadMore, sets own loading state, completes quickly.
        presenter.onQueryChanged("second")
        presenter.submitQuery()
        testScope.testScheduler.advanceUntilIdle()

        // Gen-2 data loaded: id=3 (callNum=3), no error, not loading.
        val gen2State = presenter.uiState.value
        assertThat(gen2State.isLoading).isFalse()
        assertThat(gen2State.roms[0].id).isEqualTo(3L)
        assertThat(gen2State.error).isNull()

        // Release stale loadMore — it fails, but generation guard must prevent corruption.
        blockLoadMoreLatch.countDown()
        drainAfterRealThreadRelease()

        val finalState = presenter.uiState.value
        // Gen-2 results intact: no stale error injected, isLoading not flipped by stale failure.
        assertThat(finalState.error).isNull()
        assertThat(finalState.isLoading).isFalse()
        assertThat(finalState.roms[0].id).isEqualTo(3L)
        assertThat(finalState.query).isEqualTo("second")
    }
}
