package com.romm.androidtv.library.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.romm.androidtv.library.CollectionDialogState
import com.romm.androidtv.library.CollectionLoadState
import com.romm.androidtv.library.FavoriteOperation
import com.romm.androidtv.library.FavoriteUiState
import com.romm.androidtv.library.GameDetailAlert
import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.library.RomDetailViewModel
import com.romm.androidtv.library.RomDetailUiState
import com.romm.androidtv.library.SectionState
import com.romm.androidtv.library.isPlatformNativelySupported
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

sealed interface RequiredBiosState {
    data object Checking : RequiredBiosState
    data object Ready : RequiredBiosState
    data object Missing : RequiredBiosState
    data object UnverifiedAvailable : RequiredBiosState
    data class Error(val message: String) : RequiredBiosState
}

/**
 * [FocusRequester.requestFocus], swallowing the `IllegalStateException` Compose throws when no
 * currently-composed node holds this requester. The rail's Add/Favorite buttons *are* normally
 * always composed alongside this screen's content, but these focus-restore calls run one frame
 * after a dialog/alert closes (via `LaunchedEffect`) — a real-device timing race (e.g. the rail
 * momentarily detaching/reattaching mid-recomposition right as an add-to-collection mutation
 * completes) can land the call in a frame where the requester isn't attached yet, which used to
 * crash the whole screen even though the underlying action (e.g. adding the rom to the
 * collection) had already succeeded. Losing the focus-restore in that rare case is harmless;
 * crashing is not.
 */
private fun FocusRequester.requestFocusSafely() {
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        // Requester not attached to a composed node this frame — nothing to focus, ignore.
    }
}

/**
 * Native game detail screen (UI_REFACTOR.md section 7.2): hero cover, title
 * and platform, metadata chips, summary, a screenshot shelf, a fixed action
 * rail (Favorite / Add-to-collection / Back), and a Play button. The Play
 * button invokes [onPlay] with the RomM ROM ID; the caller is responsible for
 * staging, sync negotiation, conflict/quarantine handling, and native launch
 * (LIBRETRO_REFACTOR.md sections 10–13).
 *
 * @param isStaging When true, the Play button shows "Preparing…" and is disabled.
 * @param errorMessage Transient error message rendered inline below the Play button;
 *   does NOT replace this screen (caller handles blocking overlays separately).
 * @param onDismissError Called when user dismisses the inline error. Retrying via Play also clears it.
 * @param isAuthExpired When true, replaces the Play button with a "Session expired" state
 *   and a "Log in" action. Takes precedence over [errorMessage].
 * @param onLogin Called when user taps "Log in" from the auth-expired state. Does NOT auto-submit credentials.
 * @param onChooseSave Called when the user picks the "Choose Save" affordance next to Play,
 *   to open the save-picker screen (browse all server saves for this ROM and adopt one before launch).
 * @param onChooseVersion Called when the user picks the "Choose Version" affordance (only shown
 *   when the ROM has one or more sibling versions — e.g. multi-disc, region, or revision variants),
 *   to open the version-picker screen (browse sibling roms and launch a specific one).
 * @param onOpenScreenshot Called when the user selects a screenshot from the shelf, with the full
 *   list of screenshot URLs and the tapped index — the caller opens a full-screen viewer
 *   ([ScreenshotViewerScreen]) seeded at that index.
 * @param onBack Called when the user selects the Back icon in the action rail.
 */
@Composable
fun GameDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: RomDetailViewModel,
    onPlay: (Long) -> Unit,
    isStaging: Boolean = false,
    errorMessage: String? = null,
    onDismissError: () -> Unit = {},
    isAuthExpired: Boolean = false,
    onLogin: () -> Unit = {},
    onChooseSave: (Long) -> Unit = {},
    onChooseVersion: (Long) -> Unit = {},
    biosState: RequiredBiosState = RequiredBiosState.Ready,
    onCheckBios: (String) -> Unit = {},
    onOpenScreenshot: (List<String>, Int) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val state: RomDetailUiState by viewModel.state.collectAsState()

    // Shared focus requesters for the rail ↔ Play focus link.
    val playButtonFocusRequester = remember { FocusRequester() }
    val favoriteFocusRequester = remember { FocusRequester() }
    val addFocusRequester = remember { FocusRequester() }

    // Shared with the content's LazyColumn so the fixed rail overlay (which sits
    // outside the scrollable list) can scroll the page back to the top on request.
    // Focus navigation (up/down between rail, Play, screenshots, etc.) is untouched;
    // this only drives the scroll position, never focus.
    val contentListState = rememberLazyListState()
    val contentScrollScope = rememberCoroutineScope()

    // Compose's Dialog does not automatically restore focus to whatever was
    // focused before it opened, so explicitly return focus to the rail's Add
    // button whenever the collection picker transitions from open → closed
    // (dismiss, cancel, or a successful add). Guarded by `wasDialogOpen` so
    // this never fires on first composition (there's nothing to "return" to
    // yet — Play owns initial focus at that point).
    var wasDialogOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.collectionDialog) {
        if (state.collectionDialog == null && wasDialogOpen) {
            addFocusRequester.requestFocusSafely()
        }
        wasDialogOpen = state.collectionDialog != null
    }

    // Same rationale for the standalone Favorite-failure alert: return focus
    // to the rail's Favorite button once the alert transitions from open →
    // closed (its own OK/back dismissal).
    var wasFavoriteAlertOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.alert) {
        val isFavoriteAlertOpen = state.alert is GameDetailAlert.FavoriteFailure
        if (!isFavoriteAlertOpen && wasFavoriteAlertOpen) {
            favoriteFocusRequester.requestFocusSafely()
        }
        wasFavoriteAlertOpen = isFavoriteAlertOpen
    }

    // Safe extraction for the collection dialog; dialog only opens when detail is loaded.
    val loadedDetail = (state.detail as? SectionState.Loaded)?.data
    val collectionsList = (state.collections as? CollectionLoadState.Loaded)?.ownedWritable ?: emptyList()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi),
    ) {
        // ── Main scrollable content ──────────────────────────────────────
        when (val section = state.detail) {
            is SectionState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = RommTvColors.Romm500)
            }
            is SectionState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Couldn't load this game (${section.error.name.lowercase().replace('_', ' ')})",
                    color = RommTvColors.TextSecondary,
                )
                TextButton(onClick = viewModel::refresh, modifier = Modifier.tvButtonFocus()) {
                    Text("Retry", color = RommTvColors.Romm300)
                }
            }
            is SectionState.Loaded -> GameDetailContent(
                rom = section.data,
                onPlay = onPlay,
                isStaging = isStaging,
                errorMessage = errorMessage,
                onDismissError = onDismissError,
                isAuthExpired = isAuthExpired,
                onLogin = onLogin,
                onChooseSave = onChooseSave,
                onChooseVersion = onChooseVersion,
                biosState = biosState,
                onCheckBios = onCheckBios,
                onOpenScreenshot = onOpenScreenshot,
                playButtonFocusRequester = playButtonFocusRequester,
                upFocusTarget = favoriteFocusRequester,
                listState = contentListState,
            )
        }

        // ── Fixed action rail overlay ────────────────────────────────────
        if (state.detail is SectionState.Loaded) {
            GameDetailActionRail(
                favoriteState = mapFavoriteRailState(state.favorite),
                onFavoriteClick = viewModel::onFavoriteSelected,
                onAddClick = viewModel::onCollectionPickerRequested,
                onBackClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 32.dp, top = 24.dp),
                favoriteFocusRequester = favoriteFocusRequester,
                addFocusRequester = addFocusRequester,
                downFocusTarget = playButtonFocusRequester,
                onScrollToTop = {
                    contentScrollScope.launch { contentListState.animateScrollToItem(0) }
                },
            )
        }

        // ── Collection picker dialog ─────────────────────────────────────
        if (state.collectionDialog != null && loadedDetail != null) {
            AddToCollectionDialog(
                gameTitle = loadedDetail.title,
                collections = collectionsList,
                currentRomId = loadedDetail.id,
                isLoading = state.collections is CollectionLoadState.Loading,
                loadError = (state.collections as? CollectionLoadState.Error)?.error,
                createState = (state.collectionDialog as? CollectionDialogState.Creating)?.let {
                    CreateCollectionUiState(it.name, it.validationError, it.submitting)
                },
                alertMessage = when (state.alert) {
                    is GameDetailAlert.CollectionAddFailure ->
                        "Sorry, we are unable to add this game to that collection right now, please try again later"
                    is GameDetailAlert.CollectionRemoveFailure ->
                        "Sorry, we are unable to remove this game from that collection right now, please try again later"
                    is GameDetailAlert.CreatedButAddFailed ->
                        "The collection was created, but we could not add this game to it. Please try again."
                    else -> null
                },
                onCreateNew = viewModel::onCreateCollectionRequested,
                onSelectCollection = viewModel::onCollectionSelected,
                onNameChange = viewModel::onCollectionNameChanged,
                onCreateSubmit = viewModel::onCreateCollectionSubmitted,
                onCreateCancel = viewModel::onCreateCollectionCancelled,
                onAlertDismissed = viewModel::onAlertDismissed,
                onRetry = viewModel::onCollectionRetry,
                onDismiss = viewModel::onDialogDismissed,
            )
        }

        // ── Favorite failure alert (rendered outside the dialog) ─────────
        if (state.alert is GameDetailAlert.FavoriteFailure) {
            val operation = (state.alert as GameDetailAlert.FavoriteFailure).operation
            GameDetailErrorAlert(
                message = when (operation) {
                    FavoriteOperation.ADD ->
                        "Sorry, we are unable to add this game to your favorites right now, please try again later"
                    FavoriteOperation.REMOVE ->
                        "Sorry, we are unable to remove this game from your favorites right now, please try again later"
                },
                onOk = viewModel::onAlertDismissed,
            )
        }
    }
}

/** Maps the ViewModel's [FavoriteUiState] to the rail's [FavoriteRailState]. */
@Composable
private fun mapFavoriteRailState(state: FavoriteUiState): FavoriteRailState = when (state) {
    is FavoriteUiState.Loading -> FavoriteRailState.Loading
    is FavoriteUiState.Confirmed -> FavoriteRailState.Confirmed(state.isFavorite)
    is FavoriteUiState.Updating -> FavoriteRailState.Updating(state.previous, state.target)
}

@Composable
private fun GameDetailContent(
    rom: RomDetail,
    onPlay: (Long) -> Unit,
    isStaging: Boolean,
    errorMessage: String?,
    onDismissError: () -> Unit,
    isAuthExpired: Boolean,
    onLogin: () -> Unit,
    onChooseSave: (Long) -> Unit,
    onChooseVersion: (Long) -> Unit,
    biosState: RequiredBiosState,
    onCheckBios: (String) -> Unit,
    onOpenScreenshot: (List<String>, Int) -> Unit,
    playButtonFocusRequester: FocusRequester,
    upFocusTarget: FocusRequester?,
    listState: LazyListState,
) {
    LaunchedEffect(rom.id, rom.platformSlug) {
        if (rom.platformSlug == "segacd" || rom.platformSlug == "psx") {
            onCheckBios(rom.platformSlug)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(220.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(RommTvColors.NightLo),
                ) {
                    if (rom.coverUrl != null) {
                        AsyncImage(
                            model = rom.coverUrl,
                            contentDescription = rom.title,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(24.dp))
                // 192.dp end padding = rail width (168.dp) + 24.dp separation so title/
                // metadata text never renders under the fixed overlay rail.
                Column(modifier = Modifier.weight(1f).padding(end = 192.dp)) {
                    Text(
                        text = rom.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = RommTvColors.TextPrimary,
                    )
                    Text(
                        text = rom.platformDisplayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = RommTvColors.Romm300,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    MetadataChips(rom)
                    if (rom.summary != null) {
                        Text(
                            text = rom.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = RommTvColors.TextSecondary,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    if (isAuthExpired) {
                        AuthExpiredState(onLogin = onLogin, onDismiss = onDismissError)
                    } else if (!isPlatformNativelySupported(rom.platformSlug)) {
                        // Proactive native "not supported yet" state (LIBRETRO_REFACTOR.md
                        // section 13, Phase 6): checked up front from CoreManifest, not
                        // discovered reactively only after a failed Play attempt.
                        UnsupportedSystemState(platformDisplayName = rom.platformDisplayName)
                    } else if (rom.platformSlug == "segacd" && biosState !is RequiredBiosState.Ready) {
                        RequiredBiosUnavailableState(biosState)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlayButton(
                                onPlay = { onPlay(rom.id) },
                                isStaging = isStaging,
                                focusRequester = playButtonFocusRequester,
                                upFocusTarget = upFocusTarget,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            ChooseSaveButton(onClick = { onChooseSave(rom.id) }, enabled = !isStaging)
                            if (rom.siblingRoms.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(12.dp))
                                ChooseVersionButton(onClick = { onChooseVersion(rom.id) }, enabled = !isStaging)
                            }
                        }

                        if (errorMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFf44336),
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                                TextButton(onClick = onDismissError, modifier = Modifier.tvButtonFocus()) {
                                    Text("Dismiss", color = RommTvColors.Romm300)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (rom.screenshotUrls.isNotEmpty()) {
            item {
                Text(
                    text = "Screenshots",
                    style = MaterialTheme.typography.titleMedium,
                    color = RommTvColors.TextPrimary,
                    modifier = Modifier.padding(top = 32.dp, bottom = 12.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(rom.screenshotUrls) { index, url ->
                        ScreenshotThumbnail(
                            url = url,
                            onClick = { onOpenScreenshot(rom.screenshotUrls, index) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A single focusable/clickable screenshot thumbnail in the game detail screen's shelf.
 * Selecting it (D-pad center / click) opens [ScreenshotViewerScreen] full-screen at this
 * item's index — previously these were plain, non-interactive `AsyncImage`s with no way
 * to focus or select them at all.
 */
@Composable
private fun ScreenshotThumbnail(url: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    AsyncImage(
        model = url,
        contentDescription = "Screenshot",
        modifier = Modifier
            .width(280.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(RommTvColors.NightLo)
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) RommTvColors.Romm500 else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    )
}

@Composable
private fun RequiredBiosUnavailableState(state: RequiredBiosState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DisabledPlayButton()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = when (state) {
                RequiredBiosState.Checking -> "Checking for required BIOS files…"
                RequiredBiosState.Missing ->
                    "Missing BIOS files on server. Please contact your RomM administrator."
                RequiredBiosState.UnverifiedAvailable ->
                    "No verified BIOS file found. Please choose one in Settings."
                is RequiredBiosState.Error -> state.message
                RequiredBiosState.Ready -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = RommTvColors.TextSecondary,
        )
    }
}

/**
 * Proactive native "not supported yet" state (LIBRETRO_REFACTOR.md section
 * 13, Phase 6): shown instead of the Play/Choose Save row whenever
 * [isPlatformNativelySupported] is false for this ROM's platform, so the
 * user never has to press Play to discover a launch will fail. The Play
 * button itself is rendered disabled for a consistent, expected shape on
 * screen; there is no WebView hand-off (LIBRETRO_REFACTOR.md section 1
 * amendment — WebView is deprecated, not a maintained fallback).
 */
@Composable
private fun UnsupportedSystemState(platformDisplayName: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DisabledPlayButton()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Not supported yet — no native emulator core for $platformDisplayName",
            style = MaterialTheme.typography.bodySmall,
            color = RommTvColors.TextSecondary,
        )
    }
}

@Composable
private fun DisabledPlayButton() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(RommTvColors.NightLo)
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Text(
            text = "▶  Play",
            style = MaterialTheme.typography.titleMedium,
            color = RommTvColors.TextSecondary,
        )
    }
}

@Composable
private fun MetadataChips(rom: RomDetail) {
    val chips = buildList {
        rom.firstReleaseDateEpochMillis?.let { add(formatYear(it)) }
        if (rom.genres.isNotEmpty()) add(rom.genres.joinToString(", "))
        rom.playerCount?.let { add(if (it == "1") "1 player" else "$it players") }
        if (rom.regions.isNotEmpty()) add(rom.regions.joinToString(", "))
        add(formatFileSize(rom.fileSizeBytes))
        rom.averageRating?.let { add("${it.roundToInt()}% rating") }
    }
    if (chips.isEmpty()) return

    Row(
        modifier = Modifier,
    ) {
        Text(
            text = chips.joinToString("  •  "),
            style = MaterialTheme.typography.labelMedium,
            color = RommTvColors.TextSecondary,
        )
    }
}

/**
 * Inline auth-expired state rendered below the ROM metadata.
 * Shows "Session expired" message with a "Log in" action and Dismiss.
 */
@Composable
private fun AuthExpiredState(onLogin: () -> Unit, onDismiss: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Session expired; please log in to continue",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFf44336),
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onLogin, modifier = Modifier.tvButtonFocus()) {
                Text("Log in", color = RommTvColors.Romm300)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.tvButtonFocus()) {
                Text("Dismiss", color = RommTvColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun PlayButton(
    onPlay: () -> Unit,
    isStaging: Boolean = false,
    focusRequester: FocusRequester,
    upFocusTarget: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    var initialFocusRequested by remember { mutableStateOf(false) }

    // Request initial focus only once the button is actually attached and laid
    // out (it lives in a LazyColumn item, so requesting focus during the first
    // composition frame can race lazy composition and throw "FocusRequester is
    // not initialized"). Deferred via a LaunchedEffect so it never runs mid-layout.
    LaunchedEffect(initialFocusRequested) {
        if (initialFocusRequested) {
            focusRequester.requestFocus()
        }
    }

    val style = MaterialTheme.typography.titleMedium

    // When staging, the "Preparing..." text animates its dots, so the button width
    // must be locked to the widest variant ("Preparing...") to avoid jittering as
    // the dots grow/shrink. The horizontal button padding is added because it is
    // applied inside the min-width constraint below.
    val horizontalPadding = 56.dp
    val preparingMinWidth = if (isStaging) {
        val textMeasurer = rememberTextMeasurer()
        with(LocalDensity.current) {
            remember {
                listOf("Preparing.", "Preparing..", "Preparing...")
                    .maxOf { textMeasurer.measure(AnnotatedString(it), style).size.width }
                    .toDp() + horizontalPadding
            }
        }
    } else {
        Dp.Unspecified
    }

    Box(
        modifier = Modifier
            .focusRequester(focusRequester)
            .onGloballyPositioned { initialFocusRequested = true }
            .then(upFocusTarget?.let { Modifier.focusProperties { up = it } } ?: Modifier)
            .defaultMinSize(minWidth = preparingMinWidth)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isStaging) RommTvColors.NightLo
                else if (isFocused) RommTvColors.Romm500
                else RommTvColors.Romm600.copy(alpha = 0.6f),
            )
            .tvFocusRing(isFocused && !isStaging)
            .then(
                if (!isStaging) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onPlay,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Text(
            text = if (isStaging) "Preparing${PreparingDots()}" else "▶  Play",
            style = style,
            color = if (isStaging) RommTvColors.TextSecondary else Color.White,
        )
    }
}

/**
 * Cycles the trailing dots of the "Preparing" label between 1, 2 and 3 dots so
 * users can see the app is still working while a game is being staged.
 */
@Composable
private fun PreparingDots(): String {
    val transition = rememberInfiniteTransition(label = "preparing")
    val dots by transition.animateFloat(
        initialValue = 1f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "preparingDots",
    )
    return ".".repeat(dots.roundToInt())
}

@Composable
private fun ChooseSaveButton(onClick: () -> Unit, enabled: Boolean = true) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (!enabled) RommTvColors.NightLo
                else if (isFocused) RommTvColors.NightHi
                else RommTvColors.NightLo,
            )
            .border(
                width = if (isFocused && enabled) 3.dp else 1.dp,
                color = if (isFocused && enabled) RommTvColors.Romm300 else RommTvColors.TextSecondary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Choose Save",
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) RommTvColors.TextPrimary else RommTvColors.TextSecondary,
        )
    }
}

/**
 * "Choose Version" affordance next to Play/Choose Save, only rendered when
 * [RomDetail.siblingRoms] is non-empty (e.g. multi-disc, region, or revision
 * variants of this game). Opens [VersionPickerScreen], which mirrors the
 * "Choose Save" flow's UX for picking one specific rom entry to launch.
 */
@Composable
private fun ChooseVersionButton(onClick: () -> Unit, enabled: Boolean = true) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (!enabled) RommTvColors.NightLo
                else if (isFocused) RommTvColors.NightHi
                else RommTvColors.NightLo,
            )
            .border(
                width = if (isFocused && enabled) 3.dp else 1.dp,
                color = if (isFocused && enabled) RommTvColors.Romm300 else RommTvColors.TextSecondary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Choose File",
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) RommTvColors.TextPrimary else RommTvColors.TextSecondary,
        )
    }
}

private fun formatYear(epochMillis: Long): String =
    SimpleDateFormat("yyyy", Locale.US).format(epochMillis)

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
 * Full-screen screenshot viewer, reached by selecting a thumbnail in
 * [GameDetailScreen]'s screenshot shelf. D-pad left/right steps between
 * screenshots without leaving the viewer; system Back returns to the game
 * detail screen (wired by the caller's navigation — this composable has no
 * back handling of its own, matching every other native screen in the app).
 */
@Composable
fun ScreenshotViewerScreen(
    screenshotUrls: List<String>,
    initialIndex: Int,
    modifier: Modifier = Modifier,
) {
    var index by remember(screenshotUrls) {
        mutableStateOf(initialIndex.coerceIn(0, (screenshotUrls.size - 1).coerceAtLeast(0)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
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
            AsyncImage(
                model = url,
                contentDescription = "Screenshot ${index + 1} of ${screenshotUrls.size}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (screenshotUrls.size > 1) {
            Text(
                text = "${index + 1} / ${screenshotUrls.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
