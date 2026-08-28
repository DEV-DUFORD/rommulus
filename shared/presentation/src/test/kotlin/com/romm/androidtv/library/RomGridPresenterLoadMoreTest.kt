package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [RomGridPresenter.loadMore] pagination, covering a real-device crash:
 * `group_by_meta_id=true` (see [LibraryApi.fetchRoms]'s doc) can shift which sibling rom
 * represents a group across a page boundary, so the server occasionally repeats the same rom
 * id across two consecutive offset pages. `RomGridScreen`'s `items(..., key = { it.id })` used
 * to crash with a duplicate-key `IllegalArgumentException` the instant that page rendered —
 * i.e. right when the user scrolled a collection/platform grid far enough to trigger the next
 * page load. These tests pin the fix: the displayed list is de-duplicated by id, and the next
 * page's offset tracks the raw fetched count (not the de-duplicated list size) so pagination
 * still terminates correctly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("RomGridPresenter — loadMore pagination")
class RomGridPresenterLoadMoreTest {

    private class RecordingMockRepository : LibraryRepository {
        val requestedOffsets = mutableListOf<Int>()
        private val pageResults: MutableList<RomPage> = mutableListOf()

        fun enqueue(page: RomPage) = pageResults.add(page)

        override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> {
            requestedOffsets.add(offset)
            return if (pageResults.isNotEmpty()) {
                LibraryResult.Success(pageResults.removeAt(0))
            } else {
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            }
        }

        override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchFavorites(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchPlatforms() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchCollections() = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchRomDetail(romId: Long) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
    }

    private fun rom(id: Long) = LibraryRom(
        id = id,
        title = "Rom $id",
        platformDisplayName = "Platform",
        coverUrl = null,
        lastPlayedIso = null,
        nowPlaying = false,
    )

    @Test
    fun `loadMore de-dupes a rom id repeated across consecutive pages instead of crashing`() {
        val repo = RecordingMockRepository()
        // First page: ids 1..3. Second page repeats id 3 (group_by_meta_id shifted the
        // representative sibling) then continues with id 4 — the server-reported total (5)
        // still counts the repeat once.
        repo.enqueue(RomPage(roms = listOf(rom(1), rom(2), rom(3)), total = 5))
        repo.enqueue(RomPage(roms = listOf(rom(3), rom(4)), total = 5))

        val vm = RomGridPresenter(
            scope = TestScope(UnconfinedTestDispatcher()),
            repository = repo,
            query = RomQuery.ByCollection(1),
            hideUnsupportedSystems = { false },
        )
        vm.loadMore()

        val loaded = vm.uiState.value.section as SectionState.Loaded
        val ids = loaded.data.map { it.id }
        assertThat(ids).containsExactly(1L, 2L, 3L, 4L)
        assertThat(ids).doesNotHaveDuplicates()
    }

    @Test
    fun `loadMore requests the next offset from raw fetched count, not the de-duped list size`() {
        val repo = RecordingMockRepository()
        // Constructor's automatic initial refresh() consumes the first page.
        repo.enqueue(RomPage(roms = listOf(rom(1), rom(2), rom(3)), total = 9))
        repo.enqueue(RomPage(roms = listOf(rom(3), rom(4), rom(5)), total = 9))
        repo.enqueue(RomPage(roms = listOf(rom(6), rom(7), rom(8)), total = 9))

        val vm = RomGridPresenter(
            scope = TestScope(UnconfinedTestDispatcher()),
            repository = repo,
            query = RomQuery.ByCollection(1),
            hideUnsupportedSystems = { false },
        )
        vm.loadMore()
        vm.loadMore()

        // Offsets: initial refresh (0), first loadMore (3 raw), second loadMore (6 raw) —
        // never re-derived from the de-duped list size (which is only 8 after de-dup of id 3).
        assertThat(repo.requestedOffsets).containsExactly(0, 3, 6)
    }

    @Test
    fun `loadMore stops once raw fetched count reaches total`() {
        val repo = RecordingMockRepository()
        repo.enqueue(RomPage(roms = listOf(rom(1), rom(2)), total = 2))

        val vm = RomGridPresenter(
            scope = TestScope(UnconfinedTestDispatcher()),
            repository = repo,
            query = RomQuery.ByCollection(1),
            hideUnsupportedSystems = { false },
        )
        vm.loadMore()

        assertThat(repo.requestedOffsets).containsExactly(0)
    }
}
