package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [SearchViewModel] state transitions, debounce,
 * pagination, error handling, retry, blank-query cancellation, and stale
 * pagination race conditions. Uses a mock repository so no network calls are
 * made. All @Test methods return Unit explicitly to avoid JUnit5 silently
 * skipping expression-bodied runBlocking tests.
 */
@DisplayName("SearchViewModel — state, debounce, pagination, error, retry")
class SearchViewModelTest {

    private fun makeRoms(count: Int, startId: Long = 1): List<LibraryRom> =
        List(count) { i ->
            LibraryRom(
                id = startId + i,
                title = "Game ${startId + i}",
                platformDisplayName = "Platform",
                coverUrl = null,
                lastPlayedIso = null,
                nowPlaying = false,
            )
        }

    /** Test scope with Unconfined dispatcher so launched coroutines run inline (avoids Android Main).
     * Includes a root Job so @AfterEach can cancel all child coroutines. */
    private lateinit var testJob: Job
    private lateinit var testScope: CoroutineScope

    @BeforeEach
    fun setUp() {
        testJob = Job()
        testScope = CoroutineScope(Dispatchers.Unconfined + testJob)
    }

    /** Cancel the test scope after each test to avoid leaking coroutine resources. */
    @AfterEach
    fun tearDown() {
        testJob.cancel()
    }

    /**
     * A [LibraryRepository] whose [fetchRomsPage] result is controlled via
     * [nextResult]. Each call to fetchRomsPage pops the next value from the list.
     */
    class MockRepository(
        private val results: MutableList<LibraryResult<RomPage>> = mutableListOf(),
    ) : LibraryRepository {
        fun enqueue(result: LibraryResult<RomPage>) {
            results.add(result)
        }

        override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> =
            results.removeAt(0)

        override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchFavorites(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchPlatforms() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchCollections() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchRomDetail(romId: Long) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
    }

    /** Variant of [MockRepository] that records every [RomQuery] passed to [fetchRomsPage]. */
    class MockRepositoryWithQueries(
        private val results: MutableList<LibraryResult<RomPage>> = mutableListOf(),
    ) : LibraryRepository {
        val queries: MutableList<RomQuery> = mutableListOf()

        fun enqueue(result: LibraryResult<RomPage>) {
            results.add(result)
        }

        override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
            queries.add(query)
            return results.removeAt(0)
        }

        override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchFavorites(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchPlatforms() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchCollections() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchRomDetail(romId: Long) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
    }

    @Test
    fun `initial state is idle with empty query`() {
        val vm = SearchViewModel(MockRepository(), testScope)
        val state = vm.uiState.value
        assertThat(state.query).isEmpty()
        assertThat(state.isLoading).isFalse()
        assertThat(state.roms).isEmpty()
        assertThat(state.total).isEqualTo(0)
        assertThat(state.error).isNull()
    }

    @Test
    fun `searching transitions through loading to loaded state`() {
        runBlocking {
            val repo = MockRepository()
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(15), 42)))
            val vm = SearchViewModel(repo, testScope)

            vm.onQueryChanged("Zelda")
            vm.submitQuery()

            // submitQuery launches synchronously with Unconfined dispatcher in tests
            val state = vm.uiState.value
            assertThat(state.isLoading).isFalse()
            assertThat(state.roms).hasSize(15)
            assertThat(state.total).isEqualTo(42)
            assertThat(state.error).isNull()
        }
    }

    @Test
    fun `search failure transitions to error state`() {
        runBlocking {
            val repo = MockRepository()
            repo.enqueue(LibraryResult.Failure(RommApiError.NETWORK_ERROR))
            val vm = SearchViewModel(repo, testScope)

            vm.onQueryChanged("fail")
            vm.submitQuery()

            val state = vm.uiState.value
            assertThat(state.isLoading).isFalse()
            assertThat(state.error).isEqualTo(RommApiError.NETWORK_ERROR)
            assertThat(state.roms).isEmpty()
        }
    }

    @Test
    fun `retry re-issues the last search and recovers from error`() {
        runBlocking {
            val repo = MockRepository()
            repo.enqueue(LibraryResult.Failure(RommApiError.NETWORK_ERROR))
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(5), 5)))
            val vm = SearchViewModel(repo, testScope)

            // First search fails
            vm.onQueryChanged("retry")
            vm.submitQuery()
            assertThat(vm.uiState.value.error).isEqualTo(RommApiError.NETWORK_ERROR)

            // Retry succeeds
            vm.retry()
            val state = vm.uiState.value
            assertThat(state.error).isNull()
            assertThat(state.roms).hasSize(5)
        }
    }

    @Test
    fun `blank query resets results and stays idle`() {
        runBlocking {
            val repo = MockRepository()
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(10), 10)))
            val vm = SearchViewModel(repo, testScope)

            // Load results
            vm.onQueryChanged("something")
            vm.submitQuery()
            assertThat(vm.uiState.value.roms).hasSize(10)

            // Clear query
            vm.onQueryChanged("")
            val state = vm.uiState.value
            assertThat(state.query).isEmpty()
            assertThat(state.roms).isEmpty()
            assertThat(state.total).isEqualTo(0)
        }
    }

    @Test
    fun `new query cancels previous in-flight search`() {
        runBlocking {
            val repo = MockRepository()
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(1, 100), 1)))
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(1, 200), 1)))
            val vm = SearchViewModel(repo, testScope)

            // First search
            vm.onQueryChanged("first")
            vm.submitQuery()
            assertThat(vm.uiState.value.roms[0].title).isEqualTo("Game 100")

            // Second search (cancels first)
            vm.onQueryChanged("second")
            vm.submitQuery()
            assertThat(vm.uiState.value.roms[0].title).isEqualTo("Game 200")
        }
    }

    @Test
    fun `loadMore appends next page of results`() {
        runBlocking {
            val repo = MockRepository()
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(20, 1), 60)))
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(20, 21), 60)))
            val vm = SearchViewModel(repo, testScope)

            // First page
            vm.onQueryChanged("multi")
            vm.submitQuery()
            assertThat(vm.uiState.value.roms).hasSize(20)
            assertThat(vm.uiState.value.total).isEqualTo(60)

            // Load more
            vm.loadMore()
            val state = vm.uiState.value
            assertThat(state.roms).hasSize(40)
            assertThat(state.isLoading).isFalse()
        }
    }

    @Test
    fun `loadMore is a no-op when all results are loaded`() {
        runBlocking {
            val repo = MockRepository()
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(5), 5)))
            val vm = SearchViewModel(repo, testScope)

            vm.onQueryChanged("small")
            vm.submitQuery()
            assertThat(vm.uiState.value.roms).hasSize(5)
            assertThat(vm.uiState.value.total).isEqualTo(5)

            // loadMore should be a no-op (5 >= 5)
            vm.loadMore()
            assertThat(vm.uiState.value.roms).hasSize(5)
        }
    }

    @Test
    fun `empty results list with zero total shows empty state`() {
        runBlocking {
            val repo = MockRepository()
            repo.enqueue(LibraryResult.Success(RomPage(emptyList(), 0)))
            val vm = SearchViewModel(repo, testScope)

            vm.onQueryChanged("nothing")
            vm.submitQuery()

            val state = vm.uiState.value
            assertThat(state.roms).isEmpty()
            assertThat(state.total).isEqualTo(0)
            assertThat(state.error).isNull()
            assertThat(state.isLoading).isFalse()
        }
    }

    @Test
    fun `query is reflected immediately in uiState before debounce`() {
        val vm = SearchViewModel(MockRepository(), testScope)

        vm.onQueryChanged("partial")
        assertThat(vm.uiState.value.query).isEqualTo("partial")
        // No network call should have been made yet (debounce not triggered).
    }

    @Test
    fun `debounced auto-search fires after 300ms of inactivity`() {
        runBlocking {
            val repo = MockRepository()
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(3), 3)))
            val vm = SearchViewModel(repo, testScope)

            // Set query — debounce timer starts
            vm.onQueryChanged("debounce")

            // Before 300ms: no search should have fired
            delay(250)
            assertThat(vm.uiState.value.roms).isEmpty()

            // After 300ms: search fires
            delay(100)
            val state = vm.uiState.value
            assertThat(state.roms).hasSize(3)
        }
    }

    @Test
    fun `rapid query changes only fire one search for the final value`() {
        runBlocking {
            val repo = MockRepository()
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(1, 999), 1)))
            val vm = SearchViewModel(repo, testScope)

            // Rapid changes — each resets the debounce timer
            vm.onQueryChanged("a")
            delay(100)
            vm.onQueryChanged("ab")
            delay(100)
            vm.onQueryChanged("abc")

            // Third change at T=200 starts a fresh 300ms debounce → fires at T=500.
            // We've already waited 200ms, so wait 400ms more.
            delay(400)

            // Only one search should have been made (for "abc")
            val state = vm.uiState.value
            assertThat(state.query).isEqualTo("abc")
            assertThat(state.roms).hasSize(1)
        }
    }

    @Test
    fun `submitQuery trims and searches the current query`() {
        runBlocking {
            val repo = MockRepository()
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(3), 3)))
            val vm = SearchViewModel(repo, testScope)

            vm.onQueryChanged("  trimmed  ")
            vm.submitQuery()

            assertThat(vm.uiState.value.roms).hasSize(3)
        }
    }

    // ---- Blank-query cancellation tests ----

    @Test
    fun `blank query cancels in-flight search so stale results do not overwrite idle`() {
        runBlocking {
            // Use a blocking latch so the first search hangs until we clear the query.
            val job = Job()
            val repo = object : LibraryRepository {
                override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
                    // Suspend indefinitely until cancelled
                    job.join()
                    return LibraryResult.Success(RomPage(makeRoms(99, 999), 99))
                }
                override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchFavorites(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchPlatforms() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchCollections() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchRomDetail(romId: Long) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            }

            val vm = SearchViewModel(repo, testScope)
            vm.onQueryChanged("hung")
            vm.submitQuery()
            // With Unconfined the searchJob starts fetching immediately but blocks on job.join().

            // Clear query — should cancel the in-flight search.
            vm.onQueryChanged("")

            val state = vm.uiState.value
            assertThat(state.query).isEmpty()
            assertThat(state.roms).isEmpty()
            assertThat(state.isLoading).isFalse()

            // Complete the hanging job (simulate the old response arriving) — it should be ignored.
            job.complete()
            // Give any residual coroutines a chance to run.
            delay(50)

            val finalState = vm.uiState.value
            assertThat(finalState.query).isEmpty()
            assertThat(finalState.roms).isEmpty()
        }
    }

    @Test
    fun `stale loadMore response is discarded when query changes mid-pagination`() {
        runBlocking {
            val hangJob = Job()
            // Custom repo: call 1 returns first page, call 2 (loadMore) hangs, call 3 (new search) returns.
            val blockingRepo = object : LibraryRepository {
                var callCount = 0
                override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
                    callCount++
                    if (callCount == 1) {
                        return LibraryResult.Success(RomPage(makeRoms(10, 1), 50))
                    }
                    if (callCount == 2) {
                        // loadMore — hang until cancelled or completed
                        hangJob.join()
                        return LibraryResult.Success(RomPage(makeRoms(40, 100), 999))
                    }
                    // call 3: new search after query change
                    return LibraryResult.Success(RomPage(makeRoms(3, 200), 3))
                }
                override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchFavorites(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchPlatforms() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchCollections() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchRomDetail(romId: Long) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            }

            val vm = SearchViewModel(blockingRepo, testScope)
            vm.onQueryChanged("original")
            vm.submitQuery()
            assertThat(vm.uiState.value.roms).hasSize(10)

            // Start loadMore — it will hang on hangJob.join()
            vm.loadMore()

            // Change query while loadMore is in-flight — this bumps generation and cancels searchJob.
            vm.onQueryChanged("newquery")
            vm.submitQuery()

            val state = vm.uiState.value
            assertThat(state.query).isEqualTo("newquery")
            assertThat(state.roms).hasSize(3)
            assertThat(state.roms[0].title).isEqualTo("Game 200")

            // Simulate the old loadMore completing — it should be discarded by generation check.
            hangJob.complete()
            delay(50)

            val finalState = vm.uiState.value
            assertThat(finalState.roms).hasSize(3)
            assertThat(finalState.query).isEqualTo("newquery")
        }
    }

    @Test
    fun `stale loadMore response is discarded when query is cleared mid-pagination`() {
        runBlocking {
            val hangJob = Job()
            val blockingRepo = object : LibraryRepository {
                var callCount = 0
                override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
                    callCount++
                    if (callCount == 1) {
                        return LibraryResult.Success(RomPage(makeRoms(10, 1), 50))
                    }
                    hangJob.join()
                    return LibraryResult.Success(RomPage(makeRoms(40, 100), 999))
                }
                override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchFavorites(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchPlatforms() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchCollections() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
                override suspend fun fetchRomDetail(romId: Long) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            }

            val vm = SearchViewModel(blockingRepo, testScope)
            vm.onQueryChanged("loadtest")
            vm.submitQuery()
            assertThat(vm.uiState.value.roms).hasSize(10)

            // Start loadMore — it will hang
            vm.loadMore()

            // Clear query while loadMore is in-flight
            vm.onQueryChanged("")

            val state = vm.uiState.value
            assertThat(state.query).isEmpty()
            assertThat(state.roms).isEmpty()

            // Old loadMore completes — should be discarded
            hangJob.complete()
            delay(50)

            val finalState = vm.uiState.value
            assertThat(finalState.query).isEmpty()
            assertThat(finalState.roms).isEmpty()
        }
    }

    // ---- Whitespace UX regression tests ----

    @Test
    fun `raw trailing space survives debounce completion`() {
        runBlocking {
            val repo = MockRepositoryWithQueries()
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(3), 3)))
            val vm = SearchViewModel(repo, testScope)

            vm.onQueryChanged("sonic advance ")
            delay(350) // Past debounce

            val state = vm.uiState.value
            assertThat(state.query).isEqualTo("sonic advance ") // Raw input preserved
            assertThat(state.activeQuery).isEqualTo("sonic advance") // Normalized for API
            assertThat(state.roms).hasSize(3)
        }
    }

    @Test
    fun `raw input survives submit and retry`() {
        runBlocking {
            val repo = MockRepositoryWithQueries()
            repo.enqueue(LibraryResult.Failure(RommApiError.NETWORK_ERROR))
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(2), 2)))
            val vm = SearchViewModel(repo, testScope)

            // Submit with trailing space
            vm.onQueryChanged("mega man ")
            vm.submitQuery()
            assertThat(vm.uiState.value.query).isEqualTo("mega man ")
            assertThat(vm.uiState.value.activeQuery).isEqualTo("mega man")
            assertThat(vm.uiState.value.error).isEqualTo(RommApiError.NETWORK_ERROR)

            // Retry preserves raw input
            vm.retry()
            val state = vm.uiState.value
            assertThat(state.query).isEqualTo("mega man ")
            assertThat(state.activeQuery).isEqualTo("mega man")
            assertThat(state.roms).hasSize(2)
        }
    }

    @Test
    fun `API receives trimmed multi-word term`() {
        runBlocking {
            val repo = MockRepositoryWithQueries()
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(1), 1)))
            val vm = SearchViewModel(repo, testScope)

            vm.onQueryChanged("  sonic advance  ")
            vm.submitQuery()

            assertThat(repo.queries).hasSize(1)
            val sent = (repo.queries[0] as RomQuery.Search).term
            assertThat(sent).isEqualTo("sonic advance") // No leading/trailing spaces
        }
    }

    @Test
    fun `whitespace-only input resets to idle with no API call`() {
        runBlocking {
            val repo = MockRepositoryWithQueries()
            val vm = SearchViewModel(repo, testScope)

            vm.onQueryChanged("   ")

            val state = vm.uiState.value
            assertThat(state.query).isEmpty()
            assertThat(state.activeQuery).isNull()
            assertThat(state.roms).isEmpty()
            assertThat(repo.queries).isEmpty() // No API call made
        }
    }

    @Test
    fun `pagination uses normalized active term`() {
        runBlocking {
            val repo = MockRepositoryWithQueries()
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(20, 1), 60)))
            repo.enqueue(LibraryResult.Success(RomPage(makeRoms(20, 21), 60)))
            val vm = SearchViewModel(repo, testScope)

            vm.onQueryChanged("gta ")
            vm.submitQuery()
            assertThat(vm.uiState.value.query).isEqualTo("gta ")
            assertThat(vm.uiState.value.activeQuery).isEqualTo("gta")

            // loadMore uses activeQuery (normalized "gta"), not raw "gta "
            vm.loadMore()
            val state = vm.uiState.value
            assertThat(state.roms).hasSize(40)
            assertThat(state.query).isEqualTo("gta ") // Raw still preserved after pagination

            // Verify API received normalized term for both calls
            assertThat(repo.queries).hasSize(2)
            for (q in repo.queries) {
                assertThat((q as RomQuery.Search).term).isEqualTo("gta")
            }
        }
    }
}
