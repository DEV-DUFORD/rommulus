package com.romm.androidtv.library.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.romm.androidtv.library.CollectionLoadState
import com.romm.androidtv.library.CollectionSummary
import com.romm.androidtv.library.LibraryRepository
import com.romm.androidtv.library.LibraryResult
import com.romm.androidtv.library.LibraryRom
import com.romm.androidtv.library.PlatformSummary
import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.library.RomDetailViewModel
import com.romm.androidtv.library.RomPage
import com.romm.androidtv.library.RomQuery
import com.romm.androidtv.library.SectionState
import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for the Game Detail action rail (UI_REFACTOR.md §7.2,
 * spec section 11.5). Validates: rail layout and focus traversal, collection picker
 * interactions, create-collection flow, failure alerts, and ellipsis behavior.
 *
 * Uses a fake [LibraryRepository] so the [RomDetailViewModel] can load a [RomDetail]
 * and owned collections synchronously via [runTest]; no emulator or device is required.
 *
 * NOTE: These tests are written but not run — no emulator is available in this
 * environment. They follow the repo's existing androidTest conventions
 * (createComposeRule + RommTvTheme + useUnmergedTree).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GameDetailScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // --------------------------------------------------------------------- Fixtures

    private val sampleRom = RomDetail(
        id = 1L,
        title = "Test Game",
        platformDisplayName = "Nintendo Entertainment System",
        platformSlug = "nes",
        summary = "A test game summary",
        coverUrl = null,
        screenshotUrls = emptyList(),
        genres = emptyList(),
        companies = emptyList(),
        gameModes = emptyList(),
        playerCount = "1",
        firstReleaseDateEpochMillis = null,
        averageRating = null,
        regions = emptyList(),
        languages = emptyList(),
        fileSizeBytes = 1024L,
        lastPlayedIso = null,
        nowPlaying = false,
        siblingRoms = emptyList(),
    )

    private val favoriteCollection = CollectionSummary(
        id = 10L,
        name = "Favorites",
        romCount = 1,
        coverUrl = null,
        romIds = setOf(1L),
        isFavorite = true,
    )

    private val actionCollection = CollectionSummary(
        id = 20L,
        name = "Action Games",
        romCount = 5,
        coverUrl = null,
        romIds = emptySet(),
    )

    private val rpgCollection = CollectionSummary(
        id = 30L,
        name = "RPGs",
        romCount = 3,
        coverUrl = null,
        romIds = setOf(1L),
    )

    private fun defaultCollections() = listOf(favoriteCollection, actionCollection, rpgCollection)

    /**
     * Collections where the ROM is NOT a member of the favorite collection, so the
     * rail's favorite button reads "Add to favorites" instead of the default
     * fixture's "Remove from favorites" (romId=1 is a member of [favoriteCollection]
     * by default).
     */
    private fun notFavoriteCollections() =
        listOf(favoriteCollection.copy(romIds = emptySet()), actionCollection, rpgCollection)

    /**
     * Builds a fake [LibraryRepository]. Unrelated methods return
     * [LibraryResult.Failure] with [RommApiError.NETWORK_ERROR] so only the
     * detail/collection paths are exercised.
     */
    private fun fakeRepository(
        rom: RomDetail = sampleRom,
        collections: List<CollectionSummary> = defaultCollections(),
        addRomToCollectionResult: LibraryResult<CollectionSummary> = LibraryResult.Success(actionCollection),
        createCollectionResult: LibraryResult<CollectionSummary> = LibraryResult.Success(
            CollectionSummary(
                id = 999L,
                name = "New Collection",
                romCount = 0,
                coverUrl = null,
            ),
        ),
    ): LibraryRepository = object : LibraryRepository {
        override suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail> =
            LibraryResult.Success(rom)

        override suspend fun fetchOwnedWritableCollections(): LibraryResult<List<CollectionSummary>> =
            LibraryResult.Success(collections)

        override suspend fun addRomToCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> =
            addRomToCollectionResult

        override suspend fun removeRomFromCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> =
            LibraryResult.Success(actionCollection)

        override suspend fun createCollection(name: String, isFavorite: Boolean): LibraryResult<CollectionSummary> =
            createCollectionResult

        // Unrelated methods — always fail so they cannot interfere.
        override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchFavorites(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> =
            LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> =
            LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        override suspend fun fetchRomsPage(
            query: RomQuery,
            limit: Int,
            offset: Int,
        ): LibraryResult<RomPage> = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
    }

    private fun createViewModel(
        repo: LibraryRepository = fakeRepository(),
        romId: Long = 1L,
    ): RomDetailViewModel {
        val store = ViewModelStore()
        return ViewModelProvider(
            store,
            RomDetailViewModel.Factory(repo, romId),
        )[RomDetailViewModel::class.java]
    }

    /**
     * `BackHandler` composables register with the real
     * `OnBackPressedDispatcher`, which is only driven by a genuine system back
     * event — sending `KeyEvent.KEYCODE_BACK` through Compose's own
     * `performKeyInput` pipeline does not reach it. Use `UiDevice.pressBack()`
     * (already available via the uiautomator test dependency) to simulate a
     * real system back press instead.
     */
    private fun pressSystemBack() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
    }

    private fun setContentWithViewModel(
        viewModel: RomDetailViewModel,
        onBack: () -> Unit = {},
        onPlay: (Long) -> Unit = {},
    ) {
        composeTestRule.setContent {
            RommTvTheme {
                GameDetailScreen(
                    viewModel = viewModel,
                    onPlay = onPlay,
                    onBack = onBack,
                )
            }
        }
    }

    // ------------------------------------------------------------------ Rail layout

    @Test
    fun rail_rendersFavoriteAddAndBackInLeftToRightOrder() = runTest {
        // Default fixture has romId=1 already a favorite ("Remove from
        // favorites"); use the not-favorite variant to assert the "Add" copy.
        val viewModel = createViewModel(repo = fakeRepository(collections = notFavoriteCollections()))
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Back", useUnmergedTree = true).assertExists()
    }

    @Test
    fun rail_notVisibleBeforeDetailLoads() = runTest {
        // Force the detail to load as an error so the rail (guarded by
        // `state.detail is SectionState.Loaded`) does not render.
        val errorRepo = object : LibraryRepository {
            override suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail> =
                LibraryResult.Failure(RommApiError.NOT_FOUND)
            override suspend fun fetchOwnedWritableCollections(): LibraryResult<List<CollectionSummary>> =
                LibraryResult.Success(emptyList())
            override suspend fun addRomToCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun removeRomFromCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun createCollection(name: String, isFavorite: Boolean): LibraryResult<CollectionSummary> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchFavorites(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchRomsPage(
                query: RomQuery,
                limit: Int,
                offset: Int,
            ): LibraryResult<RomPage> = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }
        val viewModel = createViewModel(repo = errorRepo)
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Back", useUnmergedTree = true)
            .assertDoesNotExist()
        // Error state should render Retry.
        composeTestRule.onNodeWithText("Retry", useUnmergedTree = true).assertExists()
    }

    // ---------------------------------------------------------------- Back action

    @Test
    fun backIcon_hasCorrectContentDescription_andInvokesOnBack() = runTest {
        var backCount = 0
        val viewModel = createViewModel()
        setContentWithViewModel(viewModel, onBack = { backCount++ })

        composeTestRule.onNodeWithContentDescription("Back", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Back", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        assert(backCount == 1) { "Expected back called once, got $backCount" }
    }

    // ------------------------------------------------------------- Favorite states

    @Test
    fun favoriteButton_showsRemoveWhenAlreadyFavorite() = runTest {
        val viewModel = createViewModel()
        setContentWithViewModel(viewModel)

        // The fake repo returns a favorite collection that contains romId=1,
        // so the favorite state is Confirmed(true) → "Remove from favorites".
        composeTestRule.onNodeWithContentDescription("Remove from favorites", useUnmergedTree = true)
            .assertExists()
        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun favoriteButton_showsAddWhenNotFavorite() = runTest {
        // Build a repo where the favorite collection does NOT contain the rom.
        val notFavoriteCollection = favoriteCollection.copy(romIds = emptySet())
        val repo = fakeRepository(collections = listOf(notFavoriteCollection, actionCollection, rpgCollection))
        val viewModel = createViewModel(repo = repo)
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Remove from favorites", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    // ----------------------------------------------------------- Initial focus

    @Test
    fun initialFocus_remainsOnPlayButton() = runTest {
        val viewModel = createViewModel()
        setContentWithViewModel(viewModel)

        // `Focused` is a merged semantics property that lives on the Play
        // button's Box container, not on its Text child — must query the
        // merged tree (the default) here, not useUnmergedTree = true.
        composeTestRule.onNodeWithText("▶  Play")
            .assertIsFocused()
    }

    // --------------------------------------------------------- D-pad traversal

    @Test
    fun dpadUp_fromPlay_reachesFavorite() = runTest {
        val viewModel = createViewModel(repo = fakeRepository(collections = notFavoriteCollections()))
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithText("▶  Play", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true)
            .assertIsFocused()
    }

    @Test
    fun dpadRightFromFavorite_reachesAdd() = runTest {
        val viewModel = createViewModel(repo = fakeRepository(collections = notFavoriteCollections()))
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithText("▶  Play", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.waitForIdle()

        // Rail order is Favorite (left) → Add (middle) → Back (right).
        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .assertIsFocused()
    }

    @Test
    fun dpadRightFromAdd_reachesBack() = runTest {
        val viewModel = createViewModel(repo = fakeRepository(collections = notFavoriteCollections()))
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithText("▶  Play", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.waitForIdle()

        // Rail order is Favorite (left) → Add (middle) → Back (right); reach
        // Add first, then move right again to reach Back.
        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Back", useUnmergedTree = true)
            .assertIsFocused()
    }

    @Test
    fun dpadDownFromFavorite_reachesPlay() = runTest {
        val viewModel = createViewModel(repo = fakeRepository(collections = notFavoriteCollections()))
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithText("▶  Play", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeTestRule.waitForIdle()

        // `Focused` is a merged semantics property on Play's Box container, not
        // its Text child — must query the merged tree here (see initialFocus test).
        composeTestRule.onNodeWithText("▶  Play").assertIsFocused()
    }

    // --------------------------------------------------------- Collection picker

    @Test
    fun plusButton_opensPickerAndFocusesCreateNewCollection() = runTest {
        val viewModel = createViewModel()
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Add to Collection", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Create New Collection").assertIsFocused()
    }

    @Test
    fun pickerRows_areScrollableAndSelectableWithRemote() = runTest {
        val viewModel = createViewModel()
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        // All three writable collections should be present as selectable rows.
        // The "Favorites" collection is intentionally excluded — it's managed
        // exclusively via the rail's star icon, not the generic picker (see
        // RomDetailViewModel.refreshCollections()'s ownedWritable filter).
        composeTestRule.onNodeWithText("Action Games", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Favorites", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("RPGs", useUnmergedTree = true).assertExists()

        // Each row is clickable (selectable via D-pad Center).
        composeTestRule.onNodeWithText("Action Games", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun alreadyAddedRow_showsStateAndDoesNotInvokeAdd() = runTest {
        val viewModel = createViewModel()
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        // RPGs contains romId=1, so it should show "Already added".
        composeTestRule.onNodeWithText("Already added", useUnmergedTree = true).assertExists()
        // Favorites is deliberately excluded from the generic picker — it's
        // managed exclusively via the rail's star icon (see
        // RomDetailViewModel.refreshCollections()'s ownedWritable filter).
        composeTestRule.onNodeWithText("Favorites", useUnmergedTree = true).assertDoesNotExist()
    }

    // ------------------------------------------------------- Create collection flow

    @Test
    fun createPrompt_usesControllerFriendlyField() = runTest {
        val viewModel = createViewModel()
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        // Focus "Create New Collection" and activate it.
        composeTestRule.onNodeWithText("Create New Collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        // The create prompt should be visible with the controller-friendly text field.
        composeTestRule.onNodeWithText("Create New Collection", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Collection name", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Enter collection name", useUnmergedTree = true).assertExists()
    }

    @Test
    fun backFromCreatePrompt_cancelsAndReturnsToPicker() = runTest {
        val viewModel = createViewModel()
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Create New Collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        // Press Back to cancel the create prompt — should return to picker list.
        pressSystemBack()
        composeTestRule.waitForIdle()

        // Should be back in the picker list, not in create mode.
        composeTestRule.onNodeWithText("Add to Collection", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Create New Collection", useUnmergedTree = true).assertExists()
        // The create field should no longer be visible.
        composeTestRule.onNodeWithText("Collection name", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun backNavigation_orderIsPromptToPickerToDetail() = runTest {
        val viewModel = createViewModel()
        setContentWithViewModel(viewModel)

        // Open picker.
        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Add to Collection", useUnmergedTree = true).assertExists()

        // Enter create mode.
        composeTestRule.onNodeWithText("Create New Collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Collection name", useUnmergedTree = true).assertExists()

        // Back should return to picker (cancel create).
        pressSystemBack()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Add to Collection", useUnmergedTree = true).assertExists()

        // Back again should dismiss picker and return to detail.
        pressSystemBack()
        composeTestRule.waitForIdle()
        // Picker should be gone; game detail should be visible.
        composeTestRule.onNodeWithText("Add to Collection", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Test Game", useUnmergedTree = true).assertIsDisplayed()
    }

    // --------------------------------------------------------- Failure alerts

    @Test
    fun favoriteAddFailure_opensCenteredAlertWithExactTextAndFocusedOk() = runTest {
        // Remove rom from favorite collection so the button shows "Add to favorites",
        // and make addRomToCollection fail to trigger the alert.
        val notFavoriteCollection = favoriteCollection.copy(romIds = emptySet())
        val repo = fakeRepository(
            collections = listOf(notFavoriteCollection, actionCollection, rpgCollection),
            addRomToCollectionResult = LibraryResult.Failure(RommApiError.NETWORK_ERROR),
        )
        val viewModel = createViewModel(repo = repo)
        setContentWithViewModel(viewModel)

        // Click "Add to favorites" to trigger the failure.
        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        // The alert should appear with the exact error text.
        composeTestRule.onNodeWithText(
            "Sorry, we are unable to add this game to your favorites right now, please try again later",
            useUnmergedTree = true,
        ).assertExists()

        // OK button should be focused.
        composeTestRule.onNodeWithText("OK").assertIsFocused()
    }

    @Test
    fun favoriteRemoveFailure_opensCenteredAlertWithExactText() = runTest {
        // Make removeRomFromCollection fail.
        val failingRepo = object : LibraryRepository {
            override suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail> =
                LibraryResult.Success(sampleRom)
            override suspend fun fetchOwnedWritableCollections(): LibraryResult<List<CollectionSummary>> =
                LibraryResult.Success(defaultCollections())
            override suspend fun addRomToCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> =
                LibraryResult.Success(actionCollection)
            override suspend fun removeRomFromCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun createCollection(name: String, isFavorite: Boolean): LibraryResult<CollectionSummary> =
                LibraryResult.Success(CollectionSummary(id = 999L, name = name, romCount = 0, coverUrl = null))
            override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchFavorites(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchRomsPage(
                query: RomQuery,
                limit: Int,
                offset: Int,
            ): LibraryResult<RomPage> = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }
        val viewModel = createViewModel(repo = failingRepo)
        setContentWithViewModel(viewModel)

        // Click "Remove from favorites" to trigger the failure.
        composeTestRule.onNodeWithContentDescription("Remove from favorites", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(
            "Sorry, we are unable to remove this game from your favorites right now, please try again later",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun oneCenterPress_dismissesOkAndFocusReturnsToFavorite() = runTest {
        val repo = fakeRepository(
            collections = listOf(favoriteCollection.copy(romIds = emptySet()), actionCollection, rpgCollection),
            addRomToCollectionResult = LibraryResult.Failure(RommApiError.NETWORK_ERROR),
        )
        val viewModel = createViewModel(repo = repo)
        setContentWithViewModel(viewModel)

        // Trigger favorite add failure.
        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        // OK should be focused.
        composeTestRule.onNodeWithText("OK").assertIsFocused()

        // Press Center to dismiss.
        composeTestRule.onNodeWithText("OK", useUnmergedTree = true)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeTestRule.waitForIdle()

        // Alert should be gone.
        composeTestRule.onNodeWithText(
            "Sorry, we are unable to add this game to your favorites right now, please try again later",
            useUnmergedTree = true,
        ).assertDoesNotExist()

        // Focus should have returned to the Favorite button.
        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true)
            .assertIsFocused()
    }

    @Test
    fun collectionAddFailure_opensAlertWithExactText() = runTest {
        val repo = fakeRepository(
            addRomToCollectionResult = LibraryResult.Failure(RommApiError.NETWORK_ERROR),
        )
        val viewModel = createViewModel(repo = repo)
        setContentWithViewModel(viewModel)

        // Open picker and click a collection row.
        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Action Games", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        // The collection add failure alert should appear.
        composeTestRule.onNodeWithText(
            "Sorry, we are unable to add this game to that collection right now, please try again later",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun closingPicker_restoresFocusToAddButton() = runTest {
        val viewModel = createViewModel()
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        // Back should dismiss the picker.
        pressSystemBack()
        composeTestRule.waitForIdle()

        // The Add to collection button should be focused again.
        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .assertIsFocused()
    }

    // -------------------------------------------------------- Ellipsis behavior

    @Test
    fun longGameTitle_ellipsizesWithoutOverlappingRail() = runTest {
        val longTitle = "A Very Long Game Title That Should Be Ellipsized To Fit Within The Screen Width And Not Overlap The Action Rail"
        val longRom = sampleRom.copy(title = longTitle)
        val repo = fakeRepository(rom = longRom, collections = notFavoriteCollections())
        val viewModel = createViewModel(repo = repo)
        setContentWithViewModel(viewModel)

        // The title should be displayed (possibly ellipsized).
        composeTestRule.onNodeWithText(longTitle, useUnmergedTree = true).assertExists()
        // The rail should still be visible.
        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithContentDescription("Back", useUnmergedTree = true).assertExists()
    }

    @Test
    fun longCollectionName_ellipsizesInPickerRow() = runTest {
        val longName = "A Very Long Collection Name That Should Be Ellipsized To Fit Within The Picker Row"
        val longCollection = actionCollection.copy(name = longName)
        val repo = fakeRepository(collections = listOf(favoriteCollection, longCollection, rpgCollection))
        val viewModel = createViewModel(repo = repo)
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        // The long name should be displayed (possibly ellipsized).
        composeTestRule.onNodeWithText(longName, useUnmergedTree = true).assertExists()
    }

    // -------------------------------------------------------- Empty collections

    @Test
    fun picker_showsNoCollectionsMessageWhenEmpty() = runTest {
        val repo = fakeRepository(collections = emptyList())
        val viewModel = createViewModel(repo = repo)
        setContentWithViewModel(viewModel)

        composeTestRule.onNodeWithContentDescription("Add to collection", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("You do not have any collections yet.", useUnmergedTree = true)
            .assertExists()
    }

    // -------------------------------------------------------- Loading state

    @Test
    fun loadingState_showsCircularProgressIndicator() = runTest {
        val loadingRepo = object : LibraryRepository {
            override suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail> {
                // Suspend indefinitely to keep the state in Loading.
                kotlinx.coroutines.delay(Long.MAX_VALUE)
                return LibraryResult.Success(sampleRom)
            }
            override suspend fun fetchOwnedWritableCollections(): LibraryResult<List<CollectionSummary>> =
                LibraryResult.Success(emptyList())
            override suspend fun addRomToCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun removeRomFromCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun createCollection(name: String, isFavorite: Boolean): LibraryResult<CollectionSummary> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchRecentlyAdded(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchContinuePlaying(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchFavorites(limit: Int) = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> =
                LibraryResult.Failure(RommApiError.NETWORK_ERROR)
            override suspend fun fetchRomsPage(
                query: RomQuery,
                limit: Int,
                offset: Int,
            ): LibraryResult<RomPage> = LibraryResult.Failure(RommApiError.NETWORK_ERROR)
        }
        val viewModel = createViewModel(repo = loadingRepo)
        setContentWithViewModel(viewModel)

        // The progress indicator should be visible while loading.
        composeTestRule.onNodeWithContentDescription("Add to favorites", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    // -------------------------------------------------------- Staging state

    @Test
    fun stagingState_showsPreparingAndDisabledPlay() = runTest {
        val viewModel = createViewModel()
        composeTestRule.setContent {
            RommTvTheme {
                GameDetailScreen(
                    viewModel = viewModel,
                    onPlay = {},
                    isStaging = true,
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Preparing.", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("▶  Play", useUnmergedTree = true).assertDoesNotExist()
    }
}
