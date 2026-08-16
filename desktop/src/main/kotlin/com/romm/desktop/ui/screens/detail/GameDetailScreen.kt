package com.romm.desktop.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.romm.androidtv.library.CollectionDialogState
import com.romm.androidtv.library.CollectionLoadState
import com.romm.androidtv.library.FavoriteOperation
import com.romm.androidtv.library.FavoriteUiState
import com.romm.androidtv.library.GameDetailAlert
import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.library.SiblingRomInfo
import com.romm.androidtv.library.SectionState
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.Screen
import com.romm.desktop.ui.components.DesktopTextField
import com.romm.desktop.ui.components.ErrorBanner
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.LoadingIndicator
import com.romm.desktop.ui.components.TvButton
import com.romm.desktop.ui.components.TvOutlinedButton
import com.romm.desktop.ui.image.RommAsyncImage
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.keyboardShortcuts
import com.romm.desktop.ui.screens.library.RetryButton
import com.romm.desktop.ui.screens.library.errorMessage
import kotlin.math.roundToInt

/**
 * Desktop game detail screen (Phase 6, browser-only product): hero cover, title and
 * platform, metadata chips, summary, a screenshot shelf with a full-screen viewer
 * overlay, sibling version rows, a fixed action rail (Favorite / Add-to-collection /
 * Back), and a Play button that is rendered but DISABLED — the desktop product has no
 * emulator process yet (plans/PHASE6.md work item 8: "Linux player not installed yet").
 *
 * Drives the shared [com.romm.androidtv.library.RomDetailPresenter] obtained from the
 * [DesktopAppCoordinator] (`coordinator.romDetailPresenter(romId)`), remembered per ROM id
 * because the factory constructs a fresh presenter (and kicks off its initial fetch) on
 * every call. The ROM id comes from [DesktopAppCoordinator.selectedRomId], which
 * [DesktopAppCoordinator.openGameDetail] sets before switching to [Screen.GAME_DETAIL].
 *
 * Mouse, keyboard, and controller all work during compose: clickable items are focusable
 * (standard Compose interaction sources), Escape/Ctrl+F/Ctrl+Q are wired via
 * [keyboardShortcuts] on the root, and the screenshot viewer additionally handles
 * arrow-key stepping.
 */
@Composable
fun GameDetailScreen(
    coordinator: DesktopAppCoordinator,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRommulusColors.current
    val romId = coordinator.selectedRomId

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.nightHi)
            .keyboardShortcuts(
                onBack = { coordinator.onBack() },
                onSearch = { coordinator.navigate(Screen.SEARCH) },
                onQuit = { /* window close is owned by the desktop shell */ },
            ),
    ) {
        if (romId == null) {
            // Defensive: GAME_DETAIL is only reachable via openGameDetail, which always sets
            // selectedRomId first. Render a recoverable state rather than crashing.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No game selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textSecondary,
                    )
                    TvOutlinedButton(
                        onClick = { coordinator.onBack() },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text("Back")
                    }
                }
            }
        } else {
            GameDetailContent(coordinator = coordinator, romId = romId)
        }
    }
}

/**
 * The screen body for one ROM: presenter + state collection + the detail/rail/overlay
 * composition. A separate composable so [romId] can key the [remember] that swaps the
 * presenter when a sibling version is opened (openGameDetail changes selectedRomId).
 */
@Composable
private fun GameDetailContent(
    coordinator: DesktopAppCoordinator,
    romId: Long,
) {
    val presenter = remember(romId) { coordinator.romDetailPresenter(romId) }
    val uiState by presenter.uiState.collectAsState()
    val colors = LocalRommulusColors.current

    // Full-screen screenshot viewer (local overlay — Phase 6 desktop has no separate
    // ScreenshotViewerScreen; mirrors the Android viewer's left/right stepping).
    var screenshotsToView by remember { mutableStateOf<List<String>?>(null) }
    var initialScreenshotIndex by remember { mutableStateOf(0) }

    // Initial focus: once the detail loads, the rail composes — land keyboard/controller
    // focus on its Favorite button (the Android reference lands it on Play, which is
    // disabled here).
    val favoriteFocusRequester = remember { FocusRequester() }
    val detailLoaded = uiState.detail is SectionState.Loaded
    LaunchedEffect(detailLoaded) {
        if (detailLoaded) favoriteFocusRequester.requestFocusSafely()
    }

    val loadedDetail = (uiState.detail as? SectionState.Loaded)?.data

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Main scrollable content ──────────────────────────────────────
        when (val section = uiState.detail) {
            is SectionState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator()
            }

            is SectionState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Couldn't load this game (${errorMessage(section.error)})",
                    color = colors.textSecondary,
                )
                RetryButton(onRetry = presenter::refresh)
            }

            is SectionState.Loaded -> GameDetailBody(
                rom = section.data,
                onOpenScreenshot = { urls, index ->
                    initialScreenshotIndex = index
                    screenshotsToView = urls
                },
                // Sibling versions open their own detail; Back then returns to the same
                // parent screen that opened this detail (coordinator.gameDetailParent).
                onOpenSibling = { siblingId ->
                    coordinator.openGameDetail(siblingId, coordinator.gameDetailParent)
                },
            )
        }

        // ── Fixed action rail overlay ────────────────────────────────────
        if (loadedDetail != null) {
            GameDetailActionRail(
                favoriteState = uiState.favorite,
                onFavoriteClick = presenter::onFavoriteSelected,
                onAddClick = presenter::onCollectionPickerRequested,
                onBackClick = { coordinator.onBack() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 32.dp, top = 24.dp),
                favoriteFocusRequester = favoriteFocusRequester,
            )
        }

        // ── Collection picker overlay (dialog only opens when detail is loaded) ──
        val dialog = uiState.collectionDialog
        if (dialog != null && loadedDetail != null) {
            CollectionPickerOverlay(
                rom = loadedDetail,
                dialogState = dialog,
                collections = uiState.collections,
                alert = uiState.alert,
                onCollectionSelected = presenter::onCollectionSelected,
                onCreateNewRequested = presenter::onCreateCollectionRequested,
                onCollectionNameChanged = presenter::onCollectionNameChanged,
                onCreateSubmit = presenter::onCreateCollectionSubmitted,
                onCancelCreate = presenter::onCreateCollectionCancelled,
                onCollectionRetry = presenter::onCollectionRetry,
                onAlertDismissed = presenter::onAlertDismissed,
                onDismiss = presenter::onDialogDismissed,
            )
        }

        // ── Favorite failure alert (rendered outside the dialog) ─────────
        if (uiState.alert is GameDetailAlert.FavoriteFailure) {
            val operation = (uiState.alert as GameDetailAlert.FavoriteFailure).operation
            ModalAlertPanel(
                message = when (operation) {
                    FavoriteOperation.ADD ->
                        "Sorry, we are unable to add this game to your favorites right now, please try again later"
                    FavoriteOperation.REMOVE ->
                        "Sorry, we are unable to remove this game from your favorites right now, please try again later"
                },
                onOk = presenter::onAlertDismissed,
            )
        }

        // ── Full-screen screenshot viewer ────────────────────────────────
        screenshotsToView?.let { urls ->
            ScreenshotViewer(
                screenshotUrls = urls,
                initialIndex = initialScreenshotIndex,
                onClose = { screenshotsToView = null },
            )
        }
    }
}

/** Hero cover + title/platform + metadata chips + summary + (disabled) Play. */
@Composable
private fun GameDetailBody(
    rom: RomDetail,
    onOpenScreenshot: (List<String>, Int) -> Unit,
    onOpenSibling: (Long) -> Unit,
) {
    val colors = LocalRommulusColors.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 24.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(220.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.nightLo),
                    contentAlignment = Alignment.Center,
                ) {
                    val coverUrl = rom.coverUrl
                    if (coverUrl != null && coverUrl.isNotBlank()) {
                        // RommAsyncImage sizes its own box via `size`; 330dp matches the
                        // 220dp-wide cover's height so the bitmap fills the clipped area.
                        RommAsyncImage(
                            model = coverUrl,
                            contentDescription = rom.title,
                            size = 330.dp,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rom.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = rom.platformDisplayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.romm300,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    MetadataChips(rom)
                    val summaryText = rom.summary
                    if (summaryText != null) {
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    DisabledPlayState()
                }
            }
        }

        if (rom.screenshotUrls.isNotEmpty()) {
            item {
                Text(
                    text = "Screenshots",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(top = 32.dp, bottom = 12.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(rom.screenshotUrls) { index, url ->
                        ScreenshotThumbnail(url = url, onClick = { onOpenScreenshot(rom.screenshotUrls, index) })
                    }
                }
            }
        }

        if (rom.siblingRoms.isNotEmpty()) {
            item {
                Text(
                    text = "Versions",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(top = 32.dp, bottom = 12.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rom.siblingRoms.forEach { sibling ->
                        SiblingVersionRow(sibling = sibling, onClick = { onOpenSibling(sibling.id) })
                    }
                }
            }
        }
    }
}

/**
 * The Play affordance in its Phase 6 desktop state: rendered but disabled — the
 * browser-only product has no emulator process yet (plans/PHASE6.md work item 8).
 * Mirrors the Android reference's Play position with an explicit "Linux player not
 * installed yet" caption. Deliberately NOT focusable/clickable so keyboard and
 * controller navigation skip it entirely; no staging/save-sync/conflict flows exist.
 */
@Composable
private fun DisabledPlayState() {
    val colors = LocalRommulusColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.nightLo)
                .border(
                    width = 1.dp,
                    color = colors.textSecondary.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 28.dp, vertical = 12.dp),
        ) {
            Text(
                text = "▶  Play",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textSecondary,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Linux player not installed yet — this build is browse-only (Phase 6)",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

/** Fixed action rail: Favorite / Add-to-collection / Back (Android parity). */
@Composable
private fun GameDetailActionRail(
    favoriteState: FavoriteUiState,
    onFavoriteClick: () -> Unit,
    onAddClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    favoriteFocusRequester: FocusRequester,
) {
    val navigator = LocalFocusNavigator.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TvOutlinedButton(
            onClick = onFavoriteClick,
            enabled = favoriteState !is FavoriteUiState.Loading && favoriteState !is FavoriteUiState.Updating,
            modifier = Modifier
                .focusRequester(favoriteFocusRequester)
                .focusableItem("detail:favorite", navigator, onFavoriteClick),
        ) {
            Text(favoriteLabel(favoriteState))
        }
        TvOutlinedButton(
            onClick = onAddClick,
            modifier = Modifier.focusableItem("detail:add-collection", navigator, onAddClick),
        ) {
            Text("Add to Collection")
        }
        TvOutlinedButton(
            onClick = onBackClick,
            modifier = Modifier.focusableItem("detail:back", navigator, onBackClick),
        ) {
            Text("Back")
        }
    }
}

/** Maps the shared presenter's [FavoriteUiState] to the rail button label. */
private fun favoriteLabel(state: FavoriteUiState): String = when (state) {
    is FavoriteUiState.Loading -> "Favorite…"
    is FavoriteUiState.Confirmed -> if (state.isFavorite) "★ Favorited" else "☆ Favorite"
    is FavoriteUiState.Updating -> if (state.target) "Adding…" else "Removing…"
}

/**
 * A single focusable/clickable screenshot thumbnail in the shelf. Selecting it opens the
 * full-screen [ScreenshotViewer] overlay at this item's index.
 */
@Composable
private fun ScreenshotThumbnail(url: String, onClick: () -> Unit) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .width(280.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.nightLo)
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) colors.romm500 else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusableItem("detail:screenshot:$url", navigator, onClick),
        contentAlignment = Alignment.Center,
    ) {
        RommAsyncImage(model = url, contentDescription = "Screenshot")
    }
}

/**
 * Full-screen screenshot viewer overlay (desktop adaptation of the Android
 * `ScreenshotViewerScreen`): left/right steps between screenshots without leaving the
 * detail screen, Escape closes. Handles both keyboard arrows and controller D-pad keys.
 */
@Composable
private fun ScreenshotViewer(
    screenshotUrls: List<String>,
    initialIndex: Int,
    onClose: () -> Unit,
) {
    val colors = LocalRommulusColors.current
    var index by remember(screenshotUrls) {
        mutableStateOf(initialIndex.coerceIn(0, (screenshotUrls.size - 1).coerceAtLeast(0)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocusSafely() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        onClose()
                        true
                    }
                    // On Compose Desktop physical arrow keys arrive as Key.Direction* (there is
                    // no LeftArrow/RightArrow in the desktop Key enum), which also covers the
                    // controller D-pad.
                    Key.DirectionLeft -> {
                        if (index > 0) index--
                        true
                    }
                    Key.DirectionRight -> {
                        if (index < screenshotUrls.lastIndex) index++
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val url = screenshotUrls.getOrNull(index)
        if (url != null) {
            RommAsyncImage(
                model = url,
                contentDescription = "Screenshot ${index + 1} of ${screenshotUrls.size}",
                size = 1280.dp,
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (screenshotUrls.size > 1) {
                Text(
                    text = "${index + 1} / ${screenshotUrls.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Text(
                text = "← / → to navigate · Esc to close",
                color = colors.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Add-to-collection picker overlay (desktop adaptation of the Android
 * `AddToCollectionDialog`): a scrim + centered panel driven entirely by the shared
 * presenter's [CollectionDialogState] / [CollectionLoadState]. Selecting a row toggles
 * membership (add or remove, matching the checkmark semantics); "New collection…"
 * switches to the create form. Escape dismisses — the overlay consumes it before the
 * screen-level back shortcut does.
 */
@Composable
private fun CollectionPickerOverlay(
    rom: RomDetail,
    dialogState: CollectionDialogState,
    collections: CollectionLoadState,
    alert: GameDetailAlert?,
    onCollectionSelected: (Long) -> Unit,
    onCreateNewRequested: () -> Unit,
    onCollectionNameChanged: (String) -> Unit,
    onCreateSubmit: () -> Unit,
    onCancelCreate: () -> Unit,
    onCollectionRetry: () -> Unit,
    onAlertDismissed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRommulusColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // Swallow stray clicks so the scrim doesn't pass through to the rail behind it.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            // Escape dismisses the picker (must win over the screen-level back shortcut).
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.nightLo)
                .padding(20.dp),
        ) {
            Text(
                text = "Add to Collection",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (dialogState) {
                is CollectionDialogState.List -> when (collections) {
                    is CollectionLoadState.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }

                    is CollectionLoadState.Error -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ErrorBanner(message = "Couldn't load collections (${errorMessage(collections.error)})")
                        RetryButton(onRetry = onCollectionRetry)
                    }

                    is CollectionLoadState.Loaded -> {
                        if (collections.ownedWritable.isEmpty()) {
                            Text(
                                text = "You don't have any writable collections yet — create one below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().height(320.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(collections.ownedWritable, key = { it.id }) { collection ->
                                    CollectionRow(
                                        name = collection.name,
                                        romCount = collection.romCount,
                                        isMember = collection.romIds.contains(rom.id),
                                        onClick = { onCollectionSelected(collection.id) },
                                    )
                                }
                            }
                        }
                        TextButton(onClick = onCreateNewRequested, modifier = Modifier.fillMaxWidth()) {
                            Text("+ New collection…", color = colors.romm300)
                        }
                    }
                }

                is CollectionDialogState.Creating -> Column {
                    DesktopTextField(
                        value = dialogState.name,
                        onValueChange = onCollectionNameChanged,
                        label = "Collection name",
                        onDone = onCreateSubmit,
                    )
                    val validationError = dialogState.validationError
                    if (validationError != null) {
                        ErrorBanner(message = validationError)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvButton(onClick = onCreateSubmit, enabled = !dialogState.submitting) {
                            Text(if (dialogState.submitting) "Creating…" else "Create")
                        }
                        TvOutlinedButton(onClick = onCancelCreate, enabled = !dialogState.submitting) {
                            Text("Cancel")
                        }
                    }
                }
            }

            val alertMessage = when (alert) {
                is GameDetailAlert.CollectionAddFailure ->
                    "Sorry, we are unable to add this game to that collection right now, please try again later"
                is GameDetailAlert.CollectionRemoveFailure ->
                    "Sorry, we are unable to remove this game from that collection right now, please try again later"
                is GameDetailAlert.CreatedButAddFailed ->
                    "The collection was created, but we could not add this game to it. Please try again."
                else -> null
            }
            if (alertMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                ErrorBanner(message = alertMessage)
                TextButton(onClick = onAlertDismissed) {
                    Text("OK", color = colors.romm300)
                }
            }
        }
    }
}

/** One focusable/clickable collection row in the picker (checkmark = current membership). */
@Composable
private fun CollectionRow(
    name: String,
    romCount: Int,
    isMember: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRommulusColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isMember) "✓" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.romm300,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFocused) colors.romm300 else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$romCount games",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
    }
}

/** One focusable/clickable sibling version row; selecting it opens that ROM's detail. */
@Composable
private fun SiblingVersionRow(
    sibling: SiblingRomInfo,
    onClick: () -> Unit,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusableItem("detail:sibling:${sibling.id}", navigator, onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = sibling.fileName.ifBlank { sibling.title },
            style = MaterialTheme.typography.bodyMedium,
            color = if (isFocused) colors.romm300 else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (sibling.isMainSibling) {
            Text(
                text = "default",
                style = MaterialTheme.typography.labelSmall,
                color = colors.romm300,
            )
        }
    }
}

/** Centered modal alert panel with a single OK action (favorite-failure alerts). */
@Composable
private fun ModalAlertPanel(
    message: String,
    onOk: () -> Unit,
) {
    val colors = LocalRommulusColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.nightLo)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(16.dp))
            TvButton(onClick = onOk) {
                Text("OK")
            }
        }
    }
}

/** Metadata chip line: year • genres • players • regions • file size • rating (Android parity). */
@Composable
private fun MetadataChips(rom: RomDetail) {
    val colors = LocalRommulusColors.current
    val chips = buildList {
        rom.firstReleaseDateEpochMillis?.let { add(formatYear(it)) }
        if (rom.genres.isNotEmpty()) add(rom.genres.joinToString(", "))
        rom.playerCount?.let { add(if (it == "1") "1 player" else "$it players") }
        if (rom.regions.isNotEmpty()) add(rom.regions.joinToString(", "))
        add(formatFileSize(rom.fileSizeBytes))
        rom.averageRating?.let { add("${it.roundToInt()}% rating") }
    }
    if (chips.isEmpty()) return

    Text(
        text = chips.joinToString("  •  "),
        style = MaterialTheme.typography.labelMedium,
        color = colors.textSecondary,
    )
}

private fun formatYear(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .year
        .toString()

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${value.toInt()} ${units[unitIndex]}" else "%.1f %s".format(value, units[unitIndex])
}

/**
 * [FocusRequester.requestFocus], swallowing the `IllegalStateException` Compose throws when
 * no currently-composed node holds this requester. The focus-restore calls run one frame
 * after state transitions (detail load, viewer open); a timing race can land the call in a
 * frame where the requester isn't attached yet. Losing the focus request is harmless;
 * crashing is not (mirrors the Android reference's `requestFocusSafely`).
 */
private fun FocusRequester.requestFocusSafely() {
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        // Requester not attached to a composed node this frame — nothing to focus, ignore.
    }
}
