package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for [RomDetailViewModel]: ROM detail rendering, favorite
 * derivation/toggling, collection picker behavior and the create-collection
 * flow, including optimistic updates, rollback alerts and stale-response
 * protection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("RomDetailViewModel")
class RomDetailViewModelTest {

    private val romId = 7L

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // -----------------------------------------------------------------------
    // Fakes & helpers
    // -----------------------------------------------------------------------

    private val detail: RomDetail = RomDetail(
        id = 7, title = "Zelda", platformDisplayName = "SNES", platformSlug = "snes",
        summary = null, coverUrl = "http://x/cover.jpg", screenshotUrls = emptyList(),
        genres = emptyList(), companies = emptyList(), gameModes = emptyList(),
        playerCount = null, firstReleaseDateEpochMillis = null, averageRating = null,
        regions = emptyList(), languages = emptyList(), fileSizeBytes = 0,
        lastPlayedIso = null, nowPlaying = false,
    )

    private fun coll(
        id: Long,
        name: String = "c$id",
        romIds: Set<Long> = emptySet(),
        isFavorite: Boolean = false,
        isSmart: Boolean = false,
        isVirtual: Boolean = false,
        owner: String = "me",
    ) = CollectionSummary(
        id = id, name = name, romCount = romIds.size, coverUrl = null,
        romIds = romIds, isPublic = true, isFavorite = isFavorite,
        isVirtual = isVirtual, isSmart = isSmart, ownerUsername = owner,
    )

    private class FakeRepo : LibraryRepository {
        val currentUser = "me"
        var detailResult: LibraryResult<RomDetail> = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        var ownedResult: LibraryResult<List<CollectionSummary>> = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        var ownedQueue = ArrayDeque<LibraryResult<List<CollectionSummary>>>()
        var createResult: LibraryResult<CollectionSummary> = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        var addResult: LibraryResult<CollectionSummary> = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        var removeResult: LibraryResult<CollectionSummary> = LibraryResult.Failure(RommApiError.NETWORK_ERROR)

        var ownedGate: CompletableDeferred<Unit>? = null
        var addGate: CompletableDeferred<Unit>? = null
        var removeGate: CompletableDeferred<Unit>? = null

        var fetchOwnedCount = 0
        var createCount = 0
        var addCount = 0
        var removeCount = 0
        val addLog = mutableListOf<Pair<Long, Long>>()
        val removeLog = mutableListOf<Pair<Long, Long>>()
        val createLog = mutableListOf<Pair<String, Boolean>>()

        override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Success(emptyList<LibraryRom>())
        override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Success(emptyList<LibraryRom>())
        override suspend fun fetchFavorites(limit: Int) = LibraryResult.Success(emptyList<LibraryRom>())
        override suspend fun fetchPlatforms() = LibraryResult.Success(emptyList<PlatformSummary>())
        override suspend fun fetchCollections() = LibraryResult.Success(emptyList<CollectionSummary>())
        override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int) =
            LibraryResult.Failure(RommApiError.NETWORK_ERROR)

        override suspend fun fetchRomDetail(romId: Long) = detailResult

        override suspend fun fetchOwnedWritableCollections(): LibraryResult<List<CollectionSummary>> {
            fetchOwnedCount++
            ownedGate?.await()
            val result = if (ownedQueue.isNotEmpty()) ownedQueue.removeFirst() else ownedResult
            return when (result) {
                is LibraryResult.Success -> LibraryResult.Success(result.data.filter { it.ownerUsername == currentUser })
                else -> result
            }
        }

        override suspend fun createCollection(name: String, isFavorite: Boolean): LibraryResult<CollectionSummary> {
            createCount++
            createLog.add(name to isFavorite)
            return createResult
        }

        override suspend fun addRomToCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> {
            addCount++
            addLog.add(collectionId to romId)
            addGate?.await()
            return addResult
        }

        override suspend fun removeRomFromCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> {
            removeCount++
            removeLog.add(collectionId to romId)
            removeGate?.await()
            return removeResult
        }
    }

    private fun makeVm(
        repo: FakeRepo,
        onLibraryMutated: () -> Unit = {},
    ) = RomDetailViewModel(repository = repo, romId = romId, onLibraryMutated = onLibraryMutated)

    private fun loaded(state: RomDetailUiState) = state.collections as CollectionLoadState.Loaded

    // -----------------------------------------------------------------------
    // Detail vs. collections
    // -----------------------------------------------------------------------

    @Test
    fun `ROM detail renders while collections are still loading`() {
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(emptyList())
            ownedGate = CompletableDeferred()
        }
        val vm = makeVm(repo)

        val state = vm.state.value
        assertThat(state.detail).isEqualTo(SectionState.Loaded(detail))
        assertThat(state.collections).isEqualTo(CollectionLoadState.Loading)
        assertThat(state.favorite).isEqualTo(FavoriteUiState.Loading)
    }

    @Test
    fun `Favorite derives filled state from is_favorite collection membership`() {
        val favoriteColl = coll(1, name = "Favorites", isFavorite = true, romIds = setOf(romId))
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(favoriteColl))
        }
        val vm = makeVm(repo)

        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Confirmed(true))
    }

    @Test
    fun `No favorite collection derives confirmed outline state`() {
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(coll(2, name = "Games")))
        }
        val vm = makeVm(repo)

        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Confirmed(false))
    }

    // -----------------------------------------------------------------------
    // Favorite toggle
    // -----------------------------------------------------------------------

    @Test
    fun `Favorite add creates favorite collection when absent then adds ROM`() {
        val created = coll(1, name = "Favorites", isFavorite = true)
        val added = created.copy(romIds = setOf(romId))
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(emptyList())
            createResult = LibraryResult.Success(created)
            addResult = LibraryResult.Success(added)
        }
        var mutations = 0
        val vm = makeVm(repo) { mutations++ }

        vm.onFavoriteSelected()

        assertThat(repo.createCount).isEqualTo(1)
        assertThat(repo.addCount).isEqualTo(1)
        assertThat(repo.addLog).containsExactly(created.id to romId)
        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Confirmed(true))
        assertThat(loaded(vm.state.value).favoriteCollection?.id).isEqualTo(created.id)
        assertThat(mutations).isEqualTo(1)
    }

    @Test
    fun `Existing favorite add skips creation`() {
        // Favorite exists but does not yet contain the ROM -> target is ADD.
        val favoriteColl = coll(1, name = "Favorites", isFavorite = true)
        val added = favoriteColl.copy(romIds = setOf(romId))
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(favoriteColl))
            addResult = LibraryResult.Success(added)
        }
        val vm = makeVm(repo)

        vm.onFavoriteSelected()

        assertThat(repo.createCount).isZero()
        assertThat(repo.addCount).isEqualTo(1)
        assertThat(repo.addLog).containsExactly(favoriteColl.id to romId)
        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Confirmed(true))
    }

    @Test
    fun `Favorite remove uses the favorite collection ID`() {
        val favoriteColl = coll(1, name = "Favorites", isFavorite = true, romIds = setOf(romId))
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(favoriteColl))
            removeResult = LibraryResult.Success(favoriteColl.copy(romIds = emptySet()))
        }
        val vm = makeVm(repo)

        vm.onFavoriteSelected()

        assertThat(repo.addCount).isZero()
        assertThat(repo.removeCount).isEqualTo(1)
        assertThat(repo.removeLog).containsExactly(favoriteColl.id to romId)
        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Confirmed(false))
    }

    @Test
    fun `Optimistic state appears immediately as Updating`() {
        val favoriteColl = coll(1, name = "Favorites", isFavorite = true, romIds = setOf(romId))
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(favoriteColl))
            removeResult = LibraryResult.Success(favoriteColl.copy(romIds = emptySet()))
            removeGate = CompletableDeferred()
        }
        val vm = makeVm(repo)

        vm.onFavoriteSelected()

        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Updating(previous = true, target = false))
        repo.removeGate!!.complete(Unit)
    }

    @Test
    fun `Success confirms optimistic state`() {
        val favoriteColl = coll(1, name = "Favorites", isFavorite = true)
        val added = favoriteColl.copy(romIds = setOf(romId))
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(favoriteColl))
            addResult = LibraryResult.Success(added)
        }
        val vm = makeVm(repo)

        vm.onFavoriteSelected()

        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Confirmed(true))
        assertThat(vm.state.value.alert).isNull()
    }

    @Test
    fun `Failure rolls back and exposes the exact add and remove alerts`() {
        // ADD failure
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(coll(1, name = "Favorites", isFavorite = true)))
            addResult = LibraryResult.Failure(RommApiError.SERVER_ERROR)
        }
        val vm = makeVm(repo)
        vm.onFavoriteSelected()
        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Confirmed(false))
        assertThat(vm.state.value.alert).isEqualTo(GameDetailAlert.FavoriteFailure(FavoriteOperation.ADD))

        // REMOVE failure
        val repo2 = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(coll(1, name = "Favorites", isFavorite = true, romIds = setOf(romId))))
            removeResult = LibraryResult.Failure(RommApiError.SERVER_ERROR)
        }
        val vm2 = makeVm(repo2)
        vm2.onFavoriteSelected()
        assertThat(vm2.state.value.favorite).isEqualTo(FavoriteUiState.Confirmed(true))
        assertThat(vm2.state.value.alert).isEqualTo(GameDetailAlert.FavoriteFailure(FavoriteOperation.REMOVE))
    }

    @Test
    fun `Repeated Select during mutation produces one request`() {
        val favoriteColl = coll(1, name = "Favorites", isFavorite = true)
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(favoriteColl))
            addResult = LibraryResult.Success(favoriteColl.copy(romIds = setOf(romId)))
            addGate = CompletableDeferred()
        }
        val vm = makeVm(repo)

        vm.onFavoriteSelected()
        vm.onFavoriteSelected()

        assertThat(repo.addCount).isEqualTo(1)
        repo.addGate!!.complete(Unit)
    }

    @Test
    fun `Stale success cannot overwrite a newer generation`() {
        // Favorite exists but is not a member -> ADD, target = true.
        val favoriteColl = coll(1, name = "Favorites", isFavorite = true)
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(favoriteColl))
            addResult = LibraryResult.Success(favoriteColl.copy(romIds = setOf(romId))) // would confirm true, but must be ignored
            addGate = CompletableDeferred()
        }
        var mutations = 0
        val vm = makeVm(repo) { mutations++ }

        vm.onFavoriteSelected() // starts add, suspended on gate
        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Updating(previous = false, target = true))

        // Refresh bumps generation + mutation token; new owned snapshot still has no membership.
        vm.refresh()
        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Confirmed(false))

        repo.addGate!!.complete(Unit) // late success must be ignored

        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Confirmed(false))
        assertThat(vm.state.value.alert).isNull()
        assertThat(mutations).isZero() // stale success never fires onLibraryMutated
    }

    @Test
    fun `Stale failure cannot overwrite a newer generation`() {
        val favoriteColl = coll(1, name = "Favorites", isFavorite = true)
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(favoriteColl))
            addResult = LibraryResult.Failure(RommApiError.SERVER_ERROR)
            addGate = CompletableDeferred()
        }
        val vm = makeVm(repo)

        vm.onFavoriteSelected()
        vm.refresh()
        repo.addGate!!.complete(Unit)

        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Confirmed(false))
        assertThat(vm.state.value.alert).isNull() // stale failure must not set alert
    }

    @Test
    fun `Favorite create duplicate race refetches and resumes with discovered favorite`() {
        val discovered = coll(1, name = "Favorites", isFavorite = true)
        val added = discovered.copy(romIds = setOf(romId))
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            // First fetch (init): no favorite. Refetch (after 409): discovered favorite.
            ownedQueue.add(LibraryResult.Success(emptyList()))
            ownedQueue.add(LibraryResult.Success(listOf(discovered)))
            createResult = LibraryResult.Failure(RommApiError.SERVER_ERROR, httpCode = 409)
            addResult = LibraryResult.Success(added)
        }
        val vm = makeVm(repo)

        vm.onFavoriteSelected()

        assertThat(repo.createCount).isEqualTo(1)
        assertThat(repo.fetchOwnedCount).isEqualTo(2)
        assertThat(repo.addCount).isEqualTo(1)
        assertThat(repo.addLog).containsExactly(discovered.id to romId)
        assertThat(vm.state.value.favorite).isEqualTo(FavoriteUiState.Confirmed(true))
        assertThat(loaded(vm.state.value).favoriteCollection?.id).isEqualTo(discovered.id)
    }

    // -----------------------------------------------------------------------
    // Collection picker
    // -----------------------------------------------------------------------

    @Test
    fun `Plus list excludes favorite smart virtual and non-owned collections`() {
        val owned = coll(2, name = "Games", romIds = setOf(romId))
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(
                listOf(
                    coll(1, name = "Favorites", isFavorite = true),
                    coll(3, name = "Smart", isSmart = true),
                    coll(4, name = "Virtual", isVirtual = true),
                    owned,
                    coll(5, name = "Theirs", owner = "other"),
                ),
            )
        }
        val vm = makeVm(repo)

        val loaded = loaded(vm.state.value)
        assertThat(loaded.ownedWritable.map { it.id }).containsExactly(owned.id)
        assertThat(loaded.allVisible.map { it.id }).doesNotContain(5L)
    }

    @Test
    fun `Selecting an already-member ordinary collection removes it (toggle off)`() {
        val owned = coll(2, name = "Games", romIds = setOf(romId))
        val removed = owned.copy(romIds = emptySet())
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(owned))
            removeResult = LibraryResult.Success(removed)
        }
        var mutations = 0
        val vm = makeVm(repo) { mutations++ }

        vm.onCollectionPickerRequested()
        vm.onCollectionSelected(owned.id)

        assertThat(repo.addCount).isZero()
        assertThat(repo.removeLog).containsExactly(owned.id to romId)
        assertThat(vm.state.value.collectionDialog).isNull()
        assertThat(loaded(vm.state.value).ownedWritable.single().romIds).doesNotContain(romId)
        assertThat(mutations).isEqualTo(1)
    }

    @Test
    fun `Failed toggle-off remove keeps the dialog open with a remove-specific alert`() {
        val owned = coll(2, name = "Games", romIds = setOf(romId))
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(owned))
            removeResult = LibraryResult.Failure(RommApiError.SERVER_ERROR)
        }
        val vm = makeVm(repo)

        vm.onCollectionPickerRequested()
        vm.onCollectionSelected(owned.id)

        assertThat(repo.addCount).isZero()
        assertThat(vm.state.value.collectionDialog).isEqualTo(CollectionDialogState.List)
        assertThat(vm.state.value.alert).isEqualTo(GameDetailAlert.CollectionRemoveFailure(owned.id))
        // Membership unchanged after a failed remove.
        assertThat(loaded(vm.state.value).ownedWritable.single().romIds).contains(romId)
    }

    @Test
    fun `Successful ordinary add closes the dialog and emits one refresh`() {
        val owned = coll(2, name = "Games")
        val added = owned.copy(romIds = setOf(romId))
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(owned))
            addResult = LibraryResult.Success(added)
        }
        var mutations = 0
        val vm = makeVm(repo) { mutations++ }

        vm.onCollectionPickerRequested()
        assertThat(vm.state.value.collectionDialog).isEqualTo(CollectionDialogState.List)
        vm.onCollectionSelected(owned.id)

        assertThat(repo.addCount).isEqualTo(1)
        assertThat(vm.state.value.collectionDialog).isNull()
        assertThat(loaded(vm.state.value).ownedWritable.single().romIds).contains(romId)
        assertThat(mutations).isEqualTo(1)
    }

    @Test
    fun `Failed ordinary add keeps the dialog open`() {
        val owned = coll(2, name = "Games")
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(owned))
            addResult = LibraryResult.Failure(RommApiError.SERVER_ERROR)
        }
        val vm = makeVm(repo)

        vm.onCollectionPickerRequested()
        vm.onCollectionSelected(owned.id)

        assertThat(vm.state.value.collectionDialog).isEqualTo(CollectionDialogState.List)
        assertThat(vm.state.value.alert).isEqualTo(GameDetailAlert.CollectionAddFailure(owned.id))
    }

    // -----------------------------------------------------------------------
    // Create collection flow
    // -----------------------------------------------------------------------

    @Test
    fun `Blank name does not call the API`() {
        val repo = baseCreateRepo()
        val vm = makeVm(repo)

        vm.onCreateCollectionRequested()
        vm.onCollectionNameChanged("")
        vm.onCreateCollectionSubmitted()

        assertThat(repo.createCount).isZero()
        assertDialogValidation(vm, "Please enter a collection name")
    }

    @Test
    fun `Whitespace name does not call the API`() {
        val repo = baseCreateRepo()
        val vm = makeVm(repo)

        vm.onCreateCollectionRequested()
        vm.onCollectionNameChanged("   ")
        vm.onCreateCollectionSubmitted()

        assertThat(repo.createCount).isZero()
        assertDialogValidation(vm, "Please enter a collection name")
    }

    @Test
    fun `Duplicate name does not call the API`() {
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(listOf(coll(2, name = "Games")))
        }
        val vm = makeVm(repo)

        vm.onCreateCollectionRequested()
        vm.onCollectionNameChanged("games")
        vm.onCreateCollectionSubmitted()

        assertThat(repo.createCount).isZero()
        assertDialogValidation(vm, "A collection with that name already exists")
    }

    @Test
    fun `Name longer than 400 characters does not call the API`() {
        val repo = baseCreateRepo()
        val vm = makeVm(repo)

        vm.onCreateCollectionRequested()
        vm.onCollectionNameChanged("x".repeat(401))
        vm.onCreateCollectionSubmitted()

        assertThat(repo.createCount).isZero()
        assertDialogValidation(vm, "Collection name must be 400 characters or fewer")
    }

    @Test
    fun `Name is trimmed before create`() {
        val created = coll(2, name = "MyColl")
        val added = created.copy(romIds = setOf(romId))
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(emptyList())
            createResult = LibraryResult.Success(created)
            addResult = LibraryResult.Success(added)
        }
        val vm = makeVm(repo)

        vm.onCreateCollectionRequested()
        vm.onCollectionNameChanged("  MyColl  ")
        vm.onCreateCollectionSubmitted()

        assertThat(repo.createCount).isEqualTo(1)
        assertThat(repo.createLog).containsExactly("MyColl" to false)
        assertThat(vm.state.value.collectionDialog).isNull()
    }

    @Test
    fun `Create then add success closes the flow`() {
        val created = coll(2, name = "MyColl")
        val added = created.copy(romIds = setOf(romId))
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(emptyList())
            createResult = LibraryResult.Success(created)
            addResult = LibraryResult.Success(added)
        }
        var mutations = 0
        val vm = makeVm(repo) { mutations++ }

        vm.onCreateCollectionRequested()
        vm.onCollectionNameChanged("MyColl")
        vm.onCreateCollectionSubmitted()

        assertThat(repo.createCount).isEqualTo(1)
        assertThat(repo.addCount).isEqualTo(1)
        assertThat(vm.state.value.collectionDialog).isNull()
        assertThat(loaded(vm.state.value).ownedWritable.single().id).isEqualTo(created.id)
        assertThat(mutations).isEqualTo(1)
    }

    @Test
    fun `Create succeeds then add fails preserves the new collection and emits refresh`() {
        val created = coll(2, name = "MyColl")
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Success(emptyList())
            createResult = LibraryResult.Success(created)
            addResult = LibraryResult.Failure(RommApiError.SERVER_ERROR)
        }
        var mutations = 0
        val vm = makeVm(repo) { mutations++ }

        vm.onCreateCollectionRequested()
        vm.onCollectionNameChanged("MyColl")
        vm.onCreateCollectionSubmitted()

        assertThat(vm.state.value.collectionDialog).isEqualTo(CollectionDialogState.List)
        assertThat(vm.state.value.alert).isEqualTo(GameDetailAlert.CreatedButAddFailed(created.id))
        assertThat(loaded(vm.state.value).ownedWritable.map { it.id }).contains(created.id)
        assertThat(mutations).isEqualTo(1)
    }

    @Test
    fun `Create failure preserves entered name and stays in Creating`() {
        val repo = baseCreateRepo()
        repo.createResult = LibraryResult.Failure(RommApiError.SERVER_ERROR)
        val vm = makeVm(repo)

        vm.onCreateCollectionRequested()
        vm.onCollectionNameChanged("MyColl")
        vm.onCreateCollectionSubmitted()

        val dialog = vm.state.value.collectionDialog
        assertThat(dialog).isInstanceOf(CollectionDialogState.Creating::class.java)
        dialog as CollectionDialogState.Creating
        assertThat(dialog.name).isEqualTo("MyColl")
        assertThat(dialog.validationError).isEqualTo(
            "Sorry, we are unable to create that collection right now, please try again later",
        )
    }

    @Test
    fun `Create duplicate failure shows safe actionable message`() {
        val repo = baseCreateRepo()
        repo.createResult = LibraryResult.Failure(RommApiError.SERVER_ERROR, httpCode = 409)
        val vm = makeVm(repo)

        vm.onCreateCollectionRequested()
        vm.onCollectionNameChanged("MyColl")
        vm.onCreateCollectionSubmitted()

        val dialog = vm.state.value.collectionDialog as CollectionDialogState.Creating
        assertThat(dialog.validationError).isEqualTo("A collection with that name already exists")
    }

    // -----------------------------------------------------------------------
    // Retry
    // -----------------------------------------------------------------------

    @Test
    fun `Collection retry transitions Error to Loading to Loaded`() {
        val repo = FakeRepo().apply {
            detailResult = LibraryResult.Success(detail)
            ownedResult = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }
        val vm = makeVm(repo)
        assertThat(vm.state.value.collections).isEqualTo(CollectionLoadState.Error(RommApiError.NETWORK_ERROR))

        repo.ownedResult = LibraryResult.Success(listOf(coll(2, name = "Games")))
        repo.ownedGate = CompletableDeferred()
        vm.onCollectionRetry()
        assertThat(vm.state.value.collections).isEqualTo(CollectionLoadState.Loading)

        repo.ownedGate!!.complete(Unit)
        assertThat(vm.state.value.collections).isInstanceOf(CollectionLoadState.Loaded::class.java)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun baseCreateRepo() = FakeRepo().apply {
        detailResult = LibraryResult.Success(detail)
        ownedResult = LibraryResult.Success(emptyList())
    }

    private fun assertDialogValidation(vm: RomDetailViewModel, message: String) {
        val dialog = vm.state.value.collectionDialog
        assertThat(dialog).isInstanceOf(CollectionDialogState.Creating::class.java)
        dialog as CollectionDialogState.Creating
        assertThat(dialog.validationError).isEqualTo(message)
        assertThat(dialog.submitting).isFalse()
    }
}
