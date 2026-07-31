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
            assertThat(vm.uiState.value.rawFetchedCount).isEqualTo(20)
            assertThat(vm.uiState.value.total).isEqualTo(60)

            // Load more
            vm.loadMore()
            val state = vm.uiState.value
            assertThat(state.roms).hasSize(40)
            assertThat(state.rawFetchedCount).isEqualTo(40)
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

    // ---- Hide unsupported systems toggle tests ----

    /** Mock that records every (query, limit, offset) triple passed to [fetchRomsPage]. */
    class MockRepositoryWithOffsets(
        private val results: MutableList<LibraryResult<RomPage>> = mutableListOf(),
    ) : LibraryRepository {
        val calls: MutableList<Triple<RomQuery, Int, Int>> = mutableListOf()

        fun enqueue(result: LibraryResult<RomPage>) {
            results.add(result)
        }

        override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
            calls.add(Triple(query, limit, offset))
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
    fun `toggle OFF preserves all search results including unsupported platforms`() {
        runBlocking {
            val repo = MockRepository()
            // 4 supported (gb) + 4 unsupported (snes) = 8 total
            val mixedRoms = listOf(
                LibraryRom(id = 1, title = "Pokemon", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Sonic 2", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 3, title = "Zelda LOR", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 4, title = "Streets of Rage", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(mixedRoms, 8)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { false })
            vm.onQueryChanged("retro")
            vm.submitQuery()

            val state = vm.uiState.value
            assertThat(state.roms).hasSize(4) // All results preserved
            assertThat(state.roms.map { it.title }).containsExactly("Pokemon", "Sonic 2", "Zelda LOR", "Streets of Rage")
        }
    }

    @Test
    fun `toggle ON filters out unsupported platform results`() {
        runBlocking {
            val repo = MockRepository()
            val mixedRoms = listOf(
                LibraryRom(id = 1, title = "Pokemon", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Sonic 2", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 3, title = "Zelda LOR", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 4, title = "Streets of Rage", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(mixedRoms, 8)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { true })
            vm.onQueryChanged("retro")
            vm.submitQuery()

            val state = vm.uiState.value
            assertThat(state.roms).hasSize(2) // Only supported platforms
            assertThat(state.roms.map { it.title }).containsExactly("Pokemon", "Zelda LOR")
        }
    }

    @Test
    fun `toggle ON filters unsupported from paginated loadMore`() {
        runBlocking {
            val repo = MockRepository()
            // Page 1: 2 supported + 2 unsupported
            val page1 = listOf(
                LibraryRom(id = 1, title = "Pokemon", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Sonic 2", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 3, title = "Zelda LOR", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 4, title = "Phantasy Star", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            // Page 2: 1 supported + 1 unsupported
            val page2 = listOf(
                LibraryRom(id = 5, title = "Kirby Dream", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 6, title = "NBA Jam", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(page1, 6)))
            repo.enqueue(LibraryResult.Success(RomPage(page2, 6)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { true })
            vm.onQueryChanged("all")
            vm.submitQuery()

            // After first page: 2 supported out of 4 raw
            assertThat(vm.uiState.value.roms).hasSize(2)
            assertThat(vm.uiState.value.rawFetchedCount).isEqualTo(4)
            assertThat(vm.uiState.value.roms.map { it.title }).containsExactly("Pokemon", "Zelda LOR")

            // Load more — both pages filtered consistently, offset uses raw count (4), not display (2)
            vm.loadMore()
            val state = vm.uiState.value
            assertThat(state.roms).hasSize(3) // 2 from page1 + 1 from page2
            assertThat(state.rawFetchedCount).isEqualTo(6) // 4+2 raw items received
            assertThat(state.roms.map { it.title }).containsExactly("Pokemon", "Zelda LOR", "Kirby Dream")
        }
    }

    @Test
    fun `toggle ON with all unsupported results returns empty`() {
        runBlocking {
            val repo = MockRepository()
            val unsupportedOnly = listOf(
                LibraryRom(id = 1, title = "Sonic 2", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Crash Bandicoot", platformDisplayName = "PSX", platformSlug = "psx", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(unsupportedOnly, 2)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { true })
            vm.onQueryChanged("console")
            vm.submitQuery()

            val state = vm.uiState.value
            assertThat(state.roms).isEmpty()
        }
    }

    @Test
    fun `toggle lambda is evaluated at search time not construction time`() {
        runBlocking {
            var hideFlag = false
            val repo = MockRepository()
            val mixedRoms = listOf(
                LibraryRom(id = 1, title = "Pokemon", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Sonic 2", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )

            // First search: toggle OFF — all results preserved
            repo.enqueue(LibraryResult.Success(RomPage(mixedRoms, 2)))
            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { hideFlag })
            vm.onQueryChanged("first")
            vm.submitQuery()
            assertThat(vm.uiState.value.roms).hasSize(2)

            // Flip toggle ON — next search should filter
            hideFlag = true
            repo.enqueue(LibraryResult.Success(RomPage(mixedRoms, 2)))
            vm.onQueryChanged("second")
            vm.submitQuery()
            assertThat(vm.uiState.value.roms).hasSize(1)
            assertThat(vm.uiState.value.roms[0].title).isEqualTo("Pokemon")

            // Flip toggle OFF — next search should show all again
            hideFlag = false
            repo.enqueue(LibraryResult.Success(RomPage(mixedRoms, 2)))
            vm.onQueryChanged("third")
            vm.submitQuery()
            assertThat(vm.uiState.value.roms).hasSize(2)
        }
    }

    @Test
    fun `toggle ON filters blank platformSlug as unsupported`() {
        runBlocking {
            val repo = MockRepository()
            val romsWithBlankSlug = listOf(
                LibraryRom(id = 1, title = "Pokemon", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Unknown Game", platformDisplayName = "Mystery", platformSlug = "", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(romsWithBlankSlug, 2)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { true })
            vm.onQueryChanged("test")
            vm.submitQuery()

            val state = vm.uiState.value
            assertThat(state.roms).hasSize(1)
            assertThat(state.roms[0].title).isEqualTo("Pokemon")
        }
    }

    // ---- Pagination regression tests (hide-unsupported + raw offset) ----

    @Test
    fun `pagination uses raw offset not filtered display size, no duplicate refetch`() {
        runBlocking {
            val repo = MockRepositoryWithOffsets()
            // Page 1: 4 items, 2 supported (gb) + 2 unsupported (snes), total=8
            val page1 = listOf(
                LibraryRom(id = 1, title = "GB1", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Gen1", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 3, title = "GB2", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 4, title = "Gen2", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            // Page 2: next 4 items at raw offset 4
            val page2 = listOf(
                LibraryRom(id = 5, title = "GB3", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 6, title = "Gen3", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 7, title = "GB4", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 8, title = "Gen4", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(page1, 8)))
            repo.enqueue(LibraryResult.Success(RomPage(page2, 8)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { true })
            vm.onQueryChanged("mixed")
            vm.submitQuery()

            // After first page: 2 visible (filtered), rawFetchedCount=4
            assertThat(vm.uiState.value.roms).hasSize(2)
            assertThat(vm.uiState.value.rawFetchedCount).isEqualTo(4)

            // loadMore must use raw offset=4, NOT filtered size=2
            vm.loadMore()

            val state = vm.uiState.value
            assertThat(state.roms).hasSize(4) // 2 from page1 + 2 from page2 (filtered)
            assertThat(state.rawFetchedCount).isEqualTo(8) // 4+4 raw items received

            // Verify the API was called with offset=0 then offset=4 (raw, not filtered=2)
            assertThat(repo.calls).hasSize(2)
            assertThat(repo.calls[0].third).isEqualTo(0)
            assertThat(repo.calls[1].third).isEqualTo(4) // NOT 2 (filtered size)
        }
    }

    @Test
    fun `all unsupported pages progress via raw offset and eventually exhaust`() {
        runBlocking {
            val repo = MockRepositoryWithOffsets()
            // Page 1: all unsupported, total=4
            val page1 = listOf(
                LibraryRom(id = 1, title = "Gen1", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Gen2", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            // Page 2: all unsupported
            val page2 = listOf(
                LibraryRom(id = 3, title = "Gen3", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 4, title = "Gen4", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(page1, 4)))
            repo.enqueue(LibraryResult.Success(RomPage(page2, 4)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { true })
            vm.onQueryChanged("allunsupported")
            vm.submitQuery()

            // After first page: 0 visible, rawFetchedCount=2
            assertThat(vm.uiState.value.roms).isEmpty()
            assertThat(vm.uiState.value.rawFetchedCount).isEqualTo(2)

            // loadMore should proceed (rawFetchedCount 2 < total 4), offset=2
            vm.loadMore()

            val state = vm.uiState.value
            assertThat(state.roms).isEmpty() // Still all filtered out
            assertThat(state.rawFetchedCount).isEqualTo(4) // All raw items consumed

            // Verify offsets: 0, then 2 (raw, not 0 which would be the filtered size)
            assertThat(repo.calls).hasSize(2)
            assertThat(repo.calls[0].third).isEqualTo(0)
            assertThat(repo.calls[1].third).isEqualTo(2)

            // Third loadMore should be a no-op: rawFetchedCount(4) >= total(4)
            vm.loadMore()
            assertThat(repo.calls).hasSize(2) // No additional call
        }
    }

    @Test
    fun `no further API call once raw total is consumed`() {
        runBlocking {
            val repo = MockRepositoryWithOffsets()
            // Single page: 3 supported + 1 unsupported, total=4
            val page1 = listOf(
                LibraryRom(id = 1, title = "GB1", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Gen1", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 3, title = "GB2", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 4, title = "GB3", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(page1, 4)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { true })
            vm.onQueryChanged("done")
            vm.submitQuery()

            // rawFetchedCount=4 >= total=4 → loadMore is a no-op
            assertThat(vm.uiState.value.rawFetchedCount).isEqualTo(4)
            vm.loadMore()
            assertThat(repo.calls).hasSize(1) // Only the initial search call
        }
    }

    @Test
    fun `new query resets raw pagination`() {
        runBlocking {
            val repo = MockRepositoryWithOffsets()
            // First search results
            repo.enqueue(LibraryResult.Success(RomPage(
                listOf(
                    LibraryRom(id = 1, title = "A1", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                    LibraryRom(id = 2, title = "G1", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                ),
                total = 4,
            )))
            // Second search results (different query)
            repo.enqueue(LibraryResult.Success(RomPage(
                listOf(
                    LibraryRom(id = 10, title = "B1", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                ),
                total = 1,
            )))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { true })
            vm.onQueryChanged("first")
            vm.submitQuery()
            assertThat(vm.uiState.value.rawFetchedCount).isEqualTo(2)

            // New query resets rawFetchedCount to 0 (fresh first page)
            vm.onQueryChanged("second")
            vm.submitQuery()

            val state = vm.uiState.value
            assertThat(state.rawFetchedCount).isEqualTo(1) // Reset and set from new page
            assertThat(state.roms).hasSize(1)
            assertThat(state.roms[0].title).isEqualTo("B1")

            // Verify the second search used offset=0 (not residual rawFetchedCount from first query)
            assertThat(repo.calls).hasSize(2)
            assertThat(repo.calls[1].third).isEqualTo(0)
        }
    }

    @Test
    fun `toggle OFF preserves rawFetchedCount equal to display count`() {
        runBlocking {
            val repo = MockRepositoryWithOffsets()
            val page1 = makeRoms(3, 1)
            val page2 = makeRoms(3, 4)
            repo.enqueue(LibraryResult.Success(RomPage(page1, 6)))
            repo.enqueue(LibraryResult.Success(RomPage(page2, 6)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { false })
            vm.onQueryChanged("all")
            vm.submitQuery()

            // With toggle OFF: roms.size == rawFetchedCount
            assertThat(vm.uiState.value.roms).hasSize(3)
            assertThat(vm.uiState.value.rawFetchedCount).isEqualTo(3)

            vm.loadMore()
            val state = vm.uiState.value
            assertThat(state.roms).hasSize(6)
            assertThat(state.rawFetchedCount).isEqualTo(6)

            // Offsets should be 0, then 3 (same as filtered size since no filtering)
            assertThat(repo.calls).hasSize(2)
            assertThat(repo.calls[0].third).isEqualTo(0)
            assertThat(repo.calls[1].third).isEqualTo(3)
        }
    }

    @Test
    fun `preference change between search and loadMore uses current toggle value`() {
        runBlocking {
            var hideFlag = false
            val repo = MockRepositoryWithOffsets()
            // Page 1: mixed
            val page1 = listOf(
                LibraryRom(id = 1, title = "GB1", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Gen1", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            // Page 2: mixed
            val page2 = listOf(
                LibraryRom(id = 3, title = "GB2", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 4, title = "Gen2", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(page1, 4)))
            repo.enqueue(LibraryResult.Success(RomPage(page2, 4)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { hideFlag })
            vm.onQueryChanged("flip")
            vm.submitQuery()

            // Toggle was OFF during search: all 2 items visible
            assertThat(vm.uiState.value.roms).hasSize(2)
            assertThat(vm.uiState.value.rawFetchedCount).isEqualTo(2)

            // Flip toggle ON before loadMore
            hideFlag = true
            vm.loadMore()

            val state = vm.uiState.value
            // Page1 had 2 visible (no filter), page2 had 1 visible (filtered)
            assertThat(state.roms).hasSize(3) // 2 + 1
            assertThat(state.rawFetchedCount).isEqualTo(4) // 2 + 2 raw

            // Verify the filtered page2 only contributed 1 visible item
            assertThat(state.roms.map { it.title }).containsExactly("GB1", "Gen1", "GB2")
        }
    }

    // ---- Result-count label semantics tests ----
    // These verify that SearchUiState fields produce correct values for the
    // SearchScreen result-count label logic. The Screen reads hideUnsupportedSystems
    // directly from state (no longer inferring from a size comparison):
    //   - When hideUnsupportedSystems == true: UI always shows visible count only
    //     (e.g. "11 results"), never raw server total.
    //   - When hideUnsupportedSystems == false: UI shows server total
    //     (e.g. "77 results").

    @Test
    fun `label semantics toggle ON first page — explicit state true, visible count less than rawFetched`() {
        runBlocking {
            val repo = MockRepository()
            // Server returns 4 items, total=77; only 2 are supported (gb/gbc)
            val page1 = listOf(
                LibraryRom(id = 1, title = "Pokemon", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Sonic 2", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 3, title = "Zelda LOR", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 4, title = "Streets of Rage", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(page1, 77)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { true })
            vm.onQueryChanged("pokemon")
            vm.submitQuery()

            val state = vm.uiState.value
            // Explicit state: hideUnsupportedSystems == true → label shows "2 results"
            assertThat(state.hideUnsupportedSystems).isTrue()
            assertThat(state.roms).hasSize(2)
            assertThat(state.rawFetchedCount).isEqualTo(4)
            // total(77) is the server unfiltered count; UI must NOT display this when filtering
            assertThat(state.total).isEqualTo(77)
        }
    }

    @Test
    fun `label semantics toggle ON with pagination — visible count updates as supported results load`() {
        runBlocking {
            val repo = MockRepository()
            // Page 1: 2 supported + 2 unsupported, server total=8
            val page1 = listOf(
                LibraryRom(id = 1, title = "GB1", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Gen1", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 3, title = "GB2", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 4, title = "Gen2", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            // Page 2: 3 supported + 1 unsupported
            val page2 = listOf(
                LibraryRom(id = 5, title = "GB3", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 6, title = "Gen3", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 7, title = "GB4", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 8, title = "GB5", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(page1, 8)))
            repo.enqueue(LibraryResult.Success(RomPage(page2, 8)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { true })
            vm.onQueryChanged("all")
            vm.submitQuery()

            // After page 1: explicit state true, label would show "2 results"
            assertThat(vm.uiState.value.hideUnsupportedSystems).isTrue()
            assertThat(vm.uiState.value.roms).hasSize(2)
            assertThat(vm.uiState.value.rawFetchedCount).isEqualTo(4)

            vm.loadMore()
            val state = vm.uiState.value
            // After page 2: visible count grows to 5, label updates to "5 results"
            assertThat(state.hideUnsupportedSystems).isTrue()
            assertThat(state.roms).hasSize(5) // 2 + 3 supported
            assertThat(state.rawFetchedCount).isEqualTo(8) // all raw consumed

            // All pages exhausted: rawFetchedCount == total, but hideUnsupportedSystems still true.
            // Label correctly shows visible count (5), not server total (8).
            assertThat(state.total).isEqualTo(8)
        }
    }

    @Test
    fun `label semantics toggle OFF — explicit state false, server total is displayed`() {
        runBlocking {
            val repo = MockRepository()
            val page1 = listOf(
                LibraryRom(id = 1, title = "GB1", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Gen1", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(page1, 77)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { false })
            vm.onQueryChanged("all")
            vm.submitQuery()

            val state = vm.uiState.value
            // Explicit state: hideUnsupportedSystems == false → label shows server total "77 results"
            assertThat(state.hideUnsupportedSystems).isFalse()
            assertThat(state.roms).hasSize(2)
            assertThat(state.rawFetchedCount).isEqualTo(2)
            assertThat(state.total).isEqualTo(77)
        }
    }

    @Test
    fun `label semantics toggle ON all unsupported filtered — roms empty, label hidden`() {
        runBlocking {
            val repo = MockRepository()
            val page1 = listOf(
                LibraryRom(id = 1, title = "Sonic 2", platformDisplayName = "N64", platformSlug = "n64", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Crash", platformDisplayName = "PSX", platformSlug = "psx", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(page1, 10)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { true })
            vm.onQueryChanged("console")
            vm.submitQuery()

            val state = vm.uiState.value
            // All filtered out: roms empty → label is hidden entirely (if block guard)
            assertThat(state.hideUnsupportedSystems).isTrue()
            assertThat(state.roms).isEmpty()
            assertThat(state.rawFetchedCount).isEqualTo(2)
            assertThat(state.total).isEqualTo(10)
        }
    }

    // ---- Regression: toggle ON, first page all supported, server total larger ----
    // This is the exact scenario the old heuristic (visibleCount < rawFetchedCount) failed on:
    // hide-unsupported is ON but every fetched item happens to be supported. The old code
    // would see roms.size == rawFetchedCount and incorrectly display the raw server total.

    @Test
    fun `REGRESSION label semantics toggle ON first page all supported — must show visible count not server total`() {
        runBlocking {
            val repo = MockRepository()
            // Server returns 4 SUPPORTED items, but total=100 (many more pages exist).
            // Old heuristic: roms.size(4) == rawFetchedCount(4) → would display "100 results" (WRONG)
            // New explicit state: hideUnsupportedSystems == true → displays "4 results" (CORRECT)
            val page1 = listOf(
                LibraryRom(id = 1, title = "Pokemon", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "Zelda LOR", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 3, title = "Kirby", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 4, title = "Metroid", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(page1, 100)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { true })
            vm.onQueryChanged("gb")
            vm.submitQuery()

            val state = vm.uiState.value
            // Explicit state is true → label shows "4 results", NOT "100 results"
            assertThat(state.hideUnsupportedSystems).isTrue()
            assertThat(state.roms).hasSize(4)
            assertThat(state.rawFetchedCount).isEqualTo(4)
            assertThat(state.total).isEqualTo(100) // Preserved for pagination, not for label
        }
    }

    @Test
    fun `preference change between search and loadMore updates explicit state`() {
        runBlocking {
            var hideFlag = false
            val repo = MockRepositoryWithOffsets()
            // Page 1: all supported
            val page1 = listOf(
                LibraryRom(id = 1, title = "GB1", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 2, title = "GB2", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            // Page 2: all supported
            val page2 = listOf(
                LibraryRom(id = 3, title = "GB3", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
                LibraryRom(id = 4, title = "GB4", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            )
            repo.enqueue(LibraryResult.Success(RomPage(page1, 4)))
            repo.enqueue(LibraryResult.Success(RomPage(page2, 4)))

            val vm = SearchViewModel(repo, testScope, hideUnsupportedSystems = { hideFlag })
            vm.onQueryChanged("flip")
            vm.submitQuery()

            // Toggle was OFF during search
            assertThat(vm.uiState.value.hideUnsupportedSystems).isFalse()
            assertThat(vm.uiState.value.roms).hasSize(2)

            // Flip toggle ON before loadMore
            hideFlag = true
            vm.loadMore()

            val state = vm.uiState.value
            // Explicit state updated to match current preference
            assertThat(state.hideUnsupportedSystems).isTrue()
            assertThat(state.roms).hasSize(4) // 2 + 2 (all supported so filtering had no effect on count)
            assertThat(state.rawFetchedCount).isEqualTo(4)
        }
    }
}
