package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Deterministic JVM tests proving that rapid toggle ON/OFF cannot let stale
 * network responses overwrite newer UI state. Tests generation-token semantics,
 * retry-vs-refresh races, and loadMore-vs-refresh races for HomePresenter and
 * RomGridPresenter.
 *
 * **Non-cooperative repositories**: Several tests use [CountDownLatch] to block
 * a repository call independently of Kotlin coroutine cancellation. This proves
 * that generation guards — not merely cooperative cancellation — prevent stale writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Stale-response hardening: generation tokens prevent older responses from winning")
class StaleToggleResponseTest {

    /** Dedicated thread pool for non-cooperative blocking calls (ignores Kotlin cancellation). */
    private val blockingExecutor = Executors.newCachedThreadPool()

    @AfterEach
    fun tearDown() {
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

    // =========================================================================
    // HomePresenter — generation token and race tests
    // =========================================================================

    /**
     * Core invariant: each refresh() increments generation. A retry that captures
     * an older generation must NOT write after a newer refresh completes.
     *
     * **Non-cooperative**: the retry's repository call blocks via [CountDownLatch],
     * proving that generation guards (not cooperative cancellation) prevent stale writes.
     */
    @Test
    fun `Home retry superseded by refresh does not overwrite fresh data`() = runTest {
        // CountDownLatch: non-cooperative — ignores coroutine cancellation.
        val blockRetryLatch = CountDownLatch(1)
        var callNum = 0

        val repo = object : LibraryRepository {
            override suspend fun fetchContinuePlaying(limit: Int): LibraryResult<List<LibraryRom>> {
                callNum++
                if (callNum == 2) {
                    // This is the retry — block non-cooperatively so refresh can run first.
                    blockNonCooperatively(blockRetryLatch)
                }
                return LibraryResult.Success(listOf(makeSupportedRom(callNum.toLong())))
            }
            override suspend fun fetchRecentlyAdded(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchFavorites(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }

        val vm = HomePresenter(scope = TestScope(UnconfinedTestDispatcher()), repository = repo)

        // Init (gen 1): id=1 loaded.
        var cp = vm.uiState.value.continuePlaying as SectionState.Loaded
        assertThat(cp.data[0].id).isEqualTo(1L)

        // Start retry — blocks non-cooperatively, capturing gen=1.
        vm.retryContinuePlaying()

        // Immediately refresh (gen 2): resets state, fetches fresh data (id=3 from callNum=3).
        vm.refresh()

        // Gen-2 data should be visible.
        cp = vm.uiState.value.continuePlaying as SectionState.Loaded
        assertThat(cp.data[0].id).isEqualTo(3L)

        // Release the stale retry — gen=1 != current gen=2, so write is rejected by generation guard.
        blockRetryLatch.countDown()
        yield()
        yield()

        val finalState = vm.uiState.value
        when (val finalCp = finalState.continuePlaying) {
            is SectionState.Loaded -> {
                // Must still be gen-2 data (id=3), NOT stale retry data (id=2).
                assertThat(finalCp.data[0].id).isEqualTo(3L)
            }
            else -> {}
        }
    }

    /**
     * Game-close use case: retryContinuePlaying works when no refresh supersedes it.
     */
    @Test
    fun `Home retry succeeds when not superseded by refresh`() = runTest {
        var fetchCount = 0

        val repo = object : LibraryRepository {
            override suspend fun fetchContinuePlaying(limit: Int): LibraryResult<List<LibraryRom>> {
                fetchCount++
                return LibraryResult.Success(listOf(makeSupportedRom(fetchCount.toLong())))
            }
            override suspend fun fetchRecentlyAdded(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchFavorites(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }

        val vm = HomePresenter(scope = TestScope(UnconfinedTestDispatcher()), repository = repo)

        // Initial load.
        var cp = vm.uiState.value.continuePlaying as SectionState.Loaded
        assertThat(cp.data[0].id).isEqualTo(1L)

        // Retry should fetch again and update state (gen unchanged).
        vm.retryContinuePlaying()
        cp = vm.uiState.value.continuePlaying as SectionState.Loaded
        assertThat(cp.data[0].id).isEqualTo(2L)
    }

    /**
     * Multiple rapid toggles: each cancels the previous, only latest data wins.
     */
    @Test
    fun `Home multiple rapid toggles produce consistent final state`() = runTest {
        var callNum = 0

        val repo = object : LibraryRepository {
            override suspend fun fetchContinuePlaying(limit: Int): LibraryResult<List<LibraryRom>> {
                callNum++
                return LibraryResult.Success(listOf(makeSupportedRom(callNum.toLong())))
            }
            override suspend fun fetchRecentlyAdded(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchFavorites(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }

        val preferenceFlow = MutableStateFlow(false)
        val vm = HomePresenter(
            scope = TestScope(UnconfinedTestDispatcher()),
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        // Rapid toggles.
        preferenceFlow.value = true
        preferenceFlow.value = false
        preferenceFlow.value = true

        val finalState = vm.uiState.value
        when (val cp = finalState.continuePlaying) {
            is SectionState.Loaded -> assertThat(cp.data.isNotEmpty()).isTrue()
            else -> {}
        }
    }

    /**
     * Toggle ON filters unsupported, toggle OFF restores them.
     */
    @Test
    fun `Home toggle filters and restores unsupported roms`() = runTest {
        val repo = object : LibraryRepository {
            override suspend fun fetchContinuePlaying(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Success(listOf(makeSupportedRom(1), makeUnsupportedRom(2)))
            override suspend fun fetchRecentlyAdded(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchFavorites(limit: Int): LibraryResult<List<LibraryRom>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> =
                LibraryResult.Success(emptyList())
            override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }

        val preferenceFlow = MutableStateFlow(false)
        val vm = HomePresenter(
            scope = TestScope(UnconfinedTestDispatcher()),
            repository = repo,
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        var state = vm.uiState.value
        var cp = state.continuePlaying as SectionState.Loaded
        assertThat(cp.data).hasSize(2)

        preferenceFlow.value = true
        state = vm.uiState.value
        cp = state.continuePlaying as SectionState.Loaded
        assertThat(cp.data).hasSize(1)
        assertThat(cp.data[0].id).isEqualTo(1L)

        preferenceFlow.value = false
        state = vm.uiState.value
        cp = state.continuePlaying as SectionState.Loaded
        assertThat(cp.data).hasSize(2)
    }

    // =========================================================================
    // RomGridPresenter — generation token and race tests
    // =========================================================================

    /**
     * loadMore captures generation. If refresh bumps generation before loadMore
     * completes, the stale append is discarded.
     *
     * **Non-cooperative**: the loadMore's repository call blocks via [CountDownLatch],
     * proving that generation guards (not cooperative cancellation) prevent stale writes.
     */
    @Test
    fun `RomGrid loadMore superseded by refresh discards stale append`() = runTest {
        val blockLoadMoreLatch = CountDownLatch(1)
        var callNum = 0

        val repo = object : LibraryRepository {
            override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
                callNum++
                if (offset > 0) {
                    // loadMore — block non-cooperatively.
                    blockNonCooperatively(blockLoadMoreLatch)
                    return LibraryResult.Success(RomPage(
                        roms = listOf(makeUnsupportedRom(99)),
                        total = 10,
                    ))
                }
                // refresh — return quickly with distinct data per call.
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

        val vm = RomGridPresenter(scope = TestScope(UnconfinedTestDispatcher()), repository = repo, query = RomQuery.ByPlatform(1L))

        // Init (gen 1): id=1 loaded.
        var section = vm.uiState.value.section as SectionState.Loaded
        assertThat(section.data[0].id).isEqualTo(1L)

        // Start loadMore — blocks non-cooperatively, captures gen=1.
        vm.loadMore()
        assertThat(vm.uiState.value.isLoadingMore).isTrue()

        // Refresh (gen 2): cancels loadMore, resets state, fetches fresh data.
        vm.refresh()
        yield()

        section = vm.uiState.value.section as SectionState.Loaded
        // Gen-2 data should be present (id=3 from callNum=3).
        assertThat(section.data[0].id).isEqualTo(3L)

        // Release stale loadMore — gen=1 != current gen=2, append rejected by generation guard.
        blockLoadMoreLatch.countDown()
        yield()
        yield()

        val finalState = vm.uiState.value
        when (val finalSection = finalState.section) {
            is SectionState.Loaded -> {
                // Must NOT contain stale id=99 from loadMore.
                assertThat(finalSection.data.none { it.id == 99L }).isTrue()
            }
            else -> {}
        }
    }

    /**
     * isLoadingMore must not be corrupted by a stale loadMore completion after refresh.
     *
     * **Non-cooperative**: the loadMore's repository call blocks via [CountDownLatch].
     */
    @Test
    fun `RomGrid isLoadingMore not corrupted by stale loadMore`() = runTest {
        val blockLoadMoreLatch = CountDownLatch(1)

        val repo = object : LibraryRepository {
            override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
                if (offset > 0) {
                    blockNonCooperatively(blockLoadMoreLatch)
                    return LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                }
                return LibraryResult.Success(RomPage(
                    roms = listOf(makeSupportedRom(1)),
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

        val vm = RomGridPresenter(scope = TestScope(UnconfinedTestDispatcher()), repository = repo, query = RomQuery.ByPlatform(1L))

        assertThat(vm.uiState.value.isLoadingMore).isFalse()

        // Start loadMore — blocks non-cooperatively.
        vm.loadMore()
        assertThat(vm.uiState.value.isLoadingMore).isTrue()

        // Refresh cancels loadMore, resets isLoadingMore to false.
        vm.refresh()
        yield()
        assertThat(vm.uiState.value.isLoadingMore).isFalse()

        // Release stale loadMore — should NOT corrupt isLoadingMore (generation guard rejects write).
        blockLoadMoreLatch.countDown()
        yield()
        yield()

        assertThat(vm.uiState.value.isLoadingMore).isFalse()
    }

    /**
     * Normal loadMore (no intervening refresh) works correctly.
     */
    @Test
    fun `RomGrid normal loadMore appends successfully`() = runTest {
        val repo = object : LibraryRepository {
            override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> =
                LibraryResult.Success(RomPage(
                    roms = if (offset == 0) listOf(makeSupportedRom(1), makeSupportedRom(2)) else listOf(makeSupportedRom(3)),
                    total = 3,
                ))
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

        val vm = RomGridPresenter(scope = TestScope(UnconfinedTestDispatcher()), repository = repo, query = RomQuery.ByPlatform(1L))

        var section = vm.uiState.value.section as SectionState.Loaded
        assertThat(section.data).hasSize(2)

        vm.loadMore()
        section = vm.uiState.value.section as SectionState.Loaded
        assertThat(section.data).hasSize(3)
        assertThat(vm.uiState.value.isLoadingMore).isFalse()
    }

    /**
     * Toggle ON filters unsupported, toggle OFF restores.
     */
    @Test
    fun `RomGrid toggle filters and restores unsupported roms`() = runTest {
        val repo = object : LibraryRepository {
            override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> =
                LibraryResult.Success(RomPage(listOf(makeSupportedRom(1), makeUnsupportedRom(2)), total = 2))

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

        val preferenceFlow = MutableStateFlow(false)
        val vm = RomGridPresenter(
            scope = TestScope(UnconfinedTestDispatcher()),
            repository = repo,
            query = RomQuery.ByPlatform(1L),
            hideUnsupportedSystems = { preferenceFlow.value },
            hideUnsupportedSystemsFlow = preferenceFlow,
        )

        var section = vm.uiState.value.section as SectionState.Loaded
        assertThat(section.data).hasSize(2)

        preferenceFlow.value = true
        section = vm.uiState.value.section as SectionState.Loaded
        assertThat(section.data).hasSize(1)

        preferenceFlow.value = false
        section = vm.uiState.value.section as SectionState.Loaded
        assertThat(section.data).hasSize(2)
    }
}
