package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * Drives the native Home screen (UI_REFACTOR.md). Each section is fetched
 * independently so a slow or failed section (e.g. Favorites on a server with
 * none configured) never blocks the others from rendering.
 *
 * **Latest-refresh-wins semantics**: [generation] is incremented on every
 * [refresh]. Every fetch coroutine captures its generation at launch time and
 * checks it before writing to [_uiState]. A non-cooperative repository that
 * returns stale data after cancellation cannot overwrite newer state because
 * the generation check rejects the write. Individual retry methods also capture
 * the current generation; they succeed only if no newer full refresh has begun.
 *
 * Platform-neutral: all async work runs in the injected [scope] so the whole
 * presenter is exercisable by plain JVM unit tests (Linux port Phase 4).
 */
class HomePresenter(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
    private val hideUnsupportedSystems: () -> Boolean = { true },
    hideUnsupportedSystemsFlow: Flow<Boolean>? = null,
    refreshEvents: Flow<Unit>? = null,
    private val onRetrySucceeded: () -> Unit = {},
) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Monotonically increasing token; bumped on every new refresh so that stale
     * in-flight responses for an older generation are discarded. */
    @Volatile private var generation: Int = 0

    /** Tracks the current bulk-refresh so it can be cancelled when a new refresh begins. */
    private var refreshJob: Job? = null

    init {
        refresh()
        // React to preference changes from Settings: re-fetch all sections when the
        // hide-unsupported-systems toggle flips. The initial emission is dropped because
        // we already called refresh() above with the current value.
        hideUnsupportedSystemsFlow?.let { flow ->
            scope.launch {
                flow.drop(1).collect { refresh() }
            }
        }
        refreshEvents?.let { events ->
            scope.launch {
                events.collect { refresh() }
            }
        }
    }

    /** Re-fetches every section from scratch (e.g. pull-to-refresh, or retry-all). */
    fun refresh() {
        // Cancel any in-flight bulk refresh so stale responses cannot overwrite newer state.
        refreshJob?.cancel()
        generation++
        val capturedGeneration = generation
        _uiState.value = HomeUiState()
        refreshJob = scope.launch {
            supervisorScope {
                launch { loadContinuePlayingInternal(capturedGeneration) }
                launch { loadRecentlyAddedInternal(capturedGeneration) }
                launch { loadFavoritesInternal(capturedGeneration) }
                launch { loadPlatformsInternal(capturedGeneration) }
                launch { loadCollectionsInternal(capturedGeneration) }
            }
        }
    }

    fun retryContinuePlaying() = loadContinuePlayingRetry()
    fun retryRecentlyAdded() = loadRecentlyAddedRetry()
    fun retryFavorites() = loadFavoritesRetry()
    fun retryPlatforms() = loadPlatformsRetry()
    fun retryCollections() = loadCollectionsRetry()

    // ---- Retry methods: top-level launches (not children of refreshJob) so they survive
    //     a subsequent refresh() cancellation. Each captures the current generation; if a
    //     newer refresh bumps it, this retry's result is silently discarded. This ensures
    //     game-close retryContinuePlaying() still works when no newer full refresh supersedes it.

    private fun loadContinuePlayingRetry() {
        val gen = generation
        scope.launch {
            _uiState.update { it.copy(continuePlaying = SectionState.Loading) }
            val state = loadSection(
                keySelector = LibraryRom::id,
                transform = { it.filterUnsupportedIfHidden(hideUnsupportedSystems()) },
                fetch = { repository.fetchContinuePlaying() },
            )
            if (generation == gen) {
                _uiState.update { it.copy(continuePlaying = state) }
                if (state is SectionState.Loaded) onRetrySucceeded()
            }
        }
    }

    private fun loadRecentlyAddedRetry() {
        val gen = generation
        scope.launch {
            _uiState.update { it.copy(recentlyAdded = SectionState.Loading) }
            val state = loadSection(
                keySelector = LibraryRom::id,
                transform = { it.filterUnsupportedIfHidden(hideUnsupportedSystems()) },
                fetch = { repository.fetchRecentlyAdded() },
            )
            if (generation == gen) {
                _uiState.update { it.copy(recentlyAdded = state) }
                if (state is SectionState.Loaded) onRetrySucceeded()
            }
        }
    }

    private fun loadFavoritesRetry() {
        val gen = generation
        scope.launch {
            _uiState.update { it.copy(favorites = SectionState.Loading) }
            val state = loadSection(
                keySelector = LibraryRom::id,
                transform = { it.filterUnsupportedIfHidden(hideUnsupportedSystems()) },
                fetch = { repository.fetchFavorites() },
            )
            if (generation == gen) {
                _uiState.update { it.copy(favorites = state) }
                if (state is SectionState.Loaded) onRetrySucceeded()
            }
        }
    }

    private fun loadPlatformsRetry() {
        val gen = generation
        scope.launch {
            _uiState.update { it.copy(platforms = SectionState.Loading) }
            val state = loadSection(
                keySelector = PlatformSummary::id,
                transform = { it.filterUnsupportedPlatformsIfHidden(hideUnsupportedSystems()) },
                fetch = { repository.fetchPlatforms() },
            )
            if (generation == gen) {
                _uiState.update { it.copy(platforms = state) }
                if (state is SectionState.Loaded) onRetrySucceeded()
            }
        }
    }

    private fun loadCollectionsRetry() {
        val gen = generation
        scope.launch {
            _uiState.update { it.copy(collections = SectionState.Loading) }
            val state = loadSection(CollectionSummary::id) { repository.fetchCollections() }
            if (generation == gen) {
                _uiState.update { it.copy(collections = state) }
                if (state is SectionState.Loaded) onRetrySucceeded()
            }
        }
    }

    // ---- Internal section loaders: launched as children of refreshJob so they are
    //     cancelled together. Each captures generation and checks before write, providing
    //     defense-in-depth against non-cooperative repos that return after cancellation.

    private suspend fun loadContinuePlayingInternal(gen: Int) {
        _uiState.update { it.copy(continuePlaying = SectionState.Loading) }
        val state = loadSection(
            keySelector = LibraryRom::id,
            transform = { it.filterUnsupportedIfHidden(hideUnsupportedSystems()) },
            fetch = { repository.fetchContinuePlaying() },
        )
        if (generation == gen) {
            _uiState.update { it.copy(continuePlaying = state) }
        }
    }

    private suspend fun loadRecentlyAddedInternal(gen: Int) {
        _uiState.update { it.copy(recentlyAdded = SectionState.Loading) }
        val state = loadSection(
            keySelector = LibraryRom::id,
            transform = { it.filterUnsupportedIfHidden(hideUnsupportedSystems()) },
            fetch = { repository.fetchRecentlyAdded() },
        )
        if (generation == gen) {
            _uiState.update { it.copy(recentlyAdded = state) }
        }
    }

    private suspend fun loadFavoritesInternal(gen: Int) {
        _uiState.update { it.copy(favorites = SectionState.Loading) }
        val state = loadSection(
            keySelector = LibraryRom::id,
            transform = { it.filterUnsupportedIfHidden(hideUnsupportedSystems()) },
            fetch = { repository.fetchFavorites() },
        )
        if (generation == gen) {
            _uiState.update { it.copy(favorites = state) }
        }
    }

    private suspend fun loadPlatformsInternal(gen: Int) {
        _uiState.update { it.copy(platforms = SectionState.Loading) }
        val state = loadSection(
            keySelector = PlatformSummary::id,
            transform = { it.filterUnsupportedPlatformsIfHidden(hideUnsupportedSystems()) },
            fetch = { repository.fetchPlatforms() },
        )
        if (generation == gen) {
            _uiState.update { it.copy(platforms = state) }
        }
    }

    private suspend fun loadCollectionsInternal(gen: Int) {
        _uiState.update { it.copy(collections = SectionState.Loading) }
        val state = loadSection(CollectionSummary::id) { repository.fetchCollections() }
        if (generation == gen) {
            _uiState.update { it.copy(collections = state) }
        }
    }

    /**
     * Compose lazy layouts require unique keys. RomM can return duplicate rows for
     * some libraries, so normalize every Home section before it reaches the UI.
     */
    private inline fun <T, K> LibraryResult<List<T>>.toSection(
        keySelector: (T) -> K,
        transform: (List<T>) -> List<T> = { it },
    ): SectionState<List<T>> = when (this) {
        is LibraryResult.Success -> SectionState.Loaded(transform(data).distinctBy(keySelector))
        is LibraryResult.Failure -> SectionState.Error(error)
    }

    private suspend fun <T, K> loadSection(
        keySelector: (T) -> K,
        transform: (List<T>) -> List<T> = { it },
        fetch: suspend () -> LibraryResult<List<T>>,
    ): SectionState<List<T>> = try {
        fetch().toSection(keySelector, transform)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        SectionState.Error(RommApiError.SERVER_ERROR)
    }

    private inline fun MutableStateFlow<HomeUiState>.update(transform: (HomeUiState) -> HomeUiState) {
        value = transform(value)
    }
}
