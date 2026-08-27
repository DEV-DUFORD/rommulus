package com.romm.desktop.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
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
import com.romm.androidtv.romm.SaveListResult
import com.romm.androidtv.storage.records.SaveSyncStatus
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.PlayerLaunchResult
import com.romm.desktop.PlayerSessionEvent
import com.romm.desktop.RequiredBiosState
import com.romm.desktop.Screen
import com.romm.desktop.player.PlayerExitKind
import com.romm.desktop.player.PlayerExitReport
import com.romm.desktop.sync.SaveConflictResolutionResult
import com.romm.desktop.ui.components.DesktopTextField
import com.romm.desktop.ui.components.ErrorBanner
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.LocalRommulusTheme
import com.romm.desktop.ui.components.LoadingIndicator
import com.romm.desktop.ui.components.TvButton
import com.romm.desktop.ui.components.TvOutlinedButton
import com.romm.desktop.ui.image.RommAsyncImage
import com.romm.desktop.ui.navigation.FocusNavigator
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.keyboardShortcuts
import com.romm.desktop.ui.screens.library.RetryButton
import com.romm.desktop.ui.screens.library.errorMessage
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop game detail screen (Phase 6): hero cover, title and platform, metadata chips,
 * summary, a screenshot shelf with a full-screen viewer overlay, sibling version rows,
 * a fixed action rail (Favorite / Add-to-collection / Back), and a Play button that
 * launches the desktop player process via [DesktopAppCoordinator.launchPlayer] and
 * shows the outcome — a "Launching player…" status line on success, or the failure
 * reason in error color on failure.
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

    // Read-only save-sync status (first piece of the Linux saves UI): refresh on show / ROM
    // change, and after every player session ends (see the playerSessionEvents collector below).
    // Dispatched to a worker — currentProfile() touches the JSON settings store and the replica
    // read is local I/O that should not sit on the compose thread.
    val saveStatusPresenter = remember { coordinator.saveSyncStatusPresenter() }
    val saveUiState by saveStatusPresenter.uiState.collectAsState()
    // Failure reason from the last save action (Keep-local / Keep-server); null when idle or after
    // a success. Reset per ROM so a stale message never follows the user to a sibling version.
    var saveActionMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(romId) {
        saveActionMessage = null
        launch(Dispatchers.Default) { saveStatusPresenter.refresh(romId) }
    }

    // Quarantine drill-down (F2): "View quarantine" on the status line opens a read-only dialog
    // for this ROM's quarantined save. The model is built off the compose thread (store read +
    // quarantine-dir scan) and cleared when the dialog closes. Dismissing is non-mutating — the
    // quarantined copy stays preserved on disk.
    var showQuarantineDialog by remember { mutableStateOf(false) }
    var quarantineModel by remember { mutableStateOf<SaveQuarantineUiModel?>(null) }
    LaunchedEffect(showQuarantineDialog) {
        if (showQuarantineDialog) {
            quarantineModel = withContext(Dispatchers.Default) { saveStatusPresenter.quarantineView(romId) }
        } else {
            quarantineModel = null
        }
    }

    // Full-screen screenshot viewer (local overlay — Phase 6 desktop has no separate
    // ScreenshotViewerScreen; mirrors the Android viewer's left/right stepping).
    var screenshotsToView by remember { mutableStateOf<List<String>?>(null) }
    var initialScreenshotIndex by remember { mutableStateOf(0) }

    // Version picker ("Choose File" — Android parity): lists this ROM's sibling versions;
    // picking an entry re-scopes the detail screen to that ROM (no auto-launch).
    var showVersionPicker by remember { mutableStateOf(false) }

    // Outcome of the last Play click (null until the user clicks Play).
    var playStatus by remember { mutableStateOf<PlayerLaunchResult?>(null) }
    // The sessionId the current playStatus was set from (null until a launch starts). A stale
    // PlayerSessionEvent.Ended from an earlier session that is still exiting must not clear a
    // newer launch's status, so we only react to Ended for the session we actually launched.
    var launchedSessionId by remember { mutableStateOf<String?>(null) }
    // launchPlayer does file I/O + a process spawn — run it off the compose UI thread and
    // publish the result back as state (snapshot state is safe to write from any thread).
    val launchScope = rememberCoroutineScope()

    // Save-sync actions (actionable half of the saves UI). "Sync now" just kicks the scheduler's
    // drain (async on its worker thread); conflict resolution does network I/O, so both dispatch
    // off the compose thread and re-read the status when done.
    fun resolveSaveConflict(keepLocal: Boolean) {
        launchScope.launch {
            saveActionMessage = null
            val result = withContext(Dispatchers.Default) { coordinator.resolveSaveConflict(romId, keepLocal) }
            saveActionMessage = (result as? SaveConflictResolutionResult.Failure)?.reason
            // Success settles the replica (SYNCED); failure leaves it CONFLICT — re-read either way.
            launch(Dispatchers.Default) { saveStatusPresenter.refresh(romId) }
        }
    }

    // The coordinator publishes PlayerSessionEvent.Ended (from its daemon exit-watcher thread)
    // when the supervised player process exits and its journal is reconciled. Clean exits clear
    // "Launching player…"; launch/runtime failures remain visible. LaunchedEffect cancels on
    // dispose, so the flow is collected only while this detail screen is composed.
    LaunchedEffect(Unit) {
        coordinator.playerSessionEvents.collect { event ->
            if (event is PlayerSessionEvent.Ended) {
                // An ended session may have just adopted a checkpoint (the post-play enqueue runs
                // right after this event publishes), so re-read the autosave status. Any Ended
                // qualifies — the refresh is an idempotent local read keyed by [romId]. If this
                // read wins the race against the enqueue, the next refresh corrects it.
                launch(Dispatchers.Default) { saveStatusPresenter.refresh(romId) }
                if (event.sessionId == launchedSessionId) {
                    playStatus = when (val report = event.report) {
                        is PlayerExitReport.Reconciled -> if (
                            report.result.exitKind == PlayerExitKind.LAUNCH_FAILED ||
                                report.result.exitKind == PlayerExitKind.RUNTIME_FAILED
                        ) {
                            // A failed exit with a null errorMessage must still surface a visible error —
                            // mapping it to null would silently swallow the failure.
                            PlayerLaunchResult.Failed(
                                report.result.errorMessage ?: "Player exited with ${report.result.exitKind}",
                            )
                        } else {
                            null
                        }
                        is PlayerExitReport.CrashInterrupted -> PlayerLaunchResult.Failed(report.reason)
                        is PlayerExitReport.ReconcileFailed -> PlayerLaunchResult.Failed(report.reason)
                        is PlayerExitReport.JournalMissing -> PlayerLaunchResult.Failed("player exited without a launch journal")
                    }
                }
            }
        }
    }

    // The primary action is the first controller/keyboard focus target, matching Android.
    val playFocusRequester = remember { FocusRequester() }
    val headerFocusRequester = remember { FocusRequester() }
    val firstScreenshotFocusRequester = remember { FocusRequester() }
    val detailLoaded = uiState.detail is SectionState.Loaded
    LaunchedEffect(detailLoaded) {
        if (detailLoaded) playFocusRequester.requestFocusSafely()
    }

    val loadedDetail = (uiState.detail as? SectionState.Loaded)?.data

    // Launch state from the coordinator (Android `preLaunchState` parity): "Preparing…" staging
    // and "Session expired" + Log in. Scoped by romId so a stale state for another ROM never
    // applies when a sibling version is opened.
    val launchUiState by coordinator.gameLaunchState.collectAsState()
    val isStaging = launchUiState?.let { it.matchesScope(romId) && it.isStaging } ?: false
    val isAuthExpired = launchUiState?.let { it.matchesScope(romId) && it.isAuthExpired } ?: false

    // Required-BIOS availability for SEGA CD / PlayStation (Android `checkRequiredBiosAvailability`
    // parity): checked once per loaded ROM; the inline state renders only while not Ready. Runs
    // off the compose thread (catalog fetch is network I/O).
    var biosState by remember(romId) { mutableStateOf<RequiredBiosState>(RequiredBiosState.Ready) }
    LaunchedEffect(loadedDetail?.id, loadedDetail?.platformSlug) {
        val slug = loadedDetail?.platformSlug ?: return@LaunchedEffect
        if (coordinator.requiresBios(slug)) {
            biosState = RequiredBiosState.Checking
            biosState = withContext(Dispatchers.Default) {
                coordinator.checkRequiredBiosAvailability(slug)
            }
        }
    }

    // Save picker ("Choose Save" — Android parity): lists this ROM's server saves via the
    // coordinator; picking an entry records the choice for the upcoming launch
    // (coordinator.chooseSaveForLaunch) — the user then presses Play, no auto-launch.
    var savePickerState by remember { mutableStateOf<SavePickerState?>(null) }
    var loadedSaveFileName by remember(romId) {
        mutableStateOf(coordinator.chosenSaveForLaunch(romId)?.fileName)
    }
    var serverSaveAvailability by remember(romId) {
        mutableStateOf(ServerSaveAvailability.Checking)
    }

    LaunchedEffect(romId) {
        serverSaveAvailability = when (
            val result = withContext(Dispatchers.Default) { coordinator.listSavesForRom(romId) }
        ) {
            is SaveListResult.Success ->
                if (result.saves.isEmpty()) ServerSaveAvailability.None
                else ServerSaveAvailability.Available
            is SaveListResult.Failure -> ServerSaveAvailability.Unavailable
        }
    }

    fun loadSavesForPicker() {
        savePickerState = SavePickerState.Loading
        launchScope.launch {
            // listSaves does network I/O — keep it off the compose thread.
            val result = withContext(Dispatchers.Default) { coordinator.listSavesForRom(romId) }
            savePickerState = when (result) {
                is SaveListResult.Success -> SavePickerState.Loaded(
                    SavePickerUiModel(
                        romTitle = loadedDetail?.title ?: "Game #$romId",
                        entries = buildSavePickerEntries(
                            result.saves,
                            selectedSaveId = coordinator.chosenSaveForLaunch(romId)?.saveId,
                        ),
                    ),
                )
                is SaveListResult.Failure -> SavePickerState.Error("Couldn't load saves (${result.error})")
            }
        }
    }

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
                playEnabled = coordinator.isPlatformPlayable(section.data.platformSlug),
                isOffline = !coordinator.serverReachable,
                isAvailableOffline = coordinator.isRomDownloaded(romId),
                onPlayClick = {
                    launchScope.launch {
                        // Android `nativeLibraryOnPlay` parity: publish the staging state (with
                        // its duplicate-entry guard) BEFORE the blocking launch work; finish maps
                        // the outcome back to UI state (staging cleared / auth-expired surfaced).
                        if (!coordinator.beginGameLaunch(romId)) return@launch
                        val result = withContext(Dispatchers.Default) { coordinator.launchPlayer(romId) }
                        playStatus = result
                        launchedSessionId = (result as? PlayerLaunchResult.Started)?.sessionId
                        coordinator.finishGameLaunch(romId, result)
                    }
                },
                isStaging = isStaging,
                isAuthExpired = isAuthExpired,
                biosState = biosState,
                onLogin = { coordinator.openLogin() },
                onDismissLaunchState = { coordinator.dismissGameLaunchState() },
                playStatus = playStatus,
                saveUiState = saveUiState,
                serverSaveAvailability = serverSaveAvailability,
                loadedSaveFileName = loadedSaveFileName,
                saveActionMessage = saveActionMessage,
                onSaveSyncNow = {
                    launchScope.launch {
                        withContext(Dispatchers.Default) { coordinator.requestSaveSync() }
                        // The drain runs async on the scheduler's worker thread — re-read once so a
                        // fast settle is visible immediately; later refreshes keep correcting.
                        launch(Dispatchers.Default) { saveStatusPresenter.refresh(romId) }
                    }
                },
                onSaveKeepLocal = { resolveSaveConflict(keepLocal = true) },
                onSaveKeepServer = { resolveSaveConflict(keepLocal = false) },
                onViewQuarantine = { showQuarantineDialog = true },
                onOpenScreenshot = { urls, index ->
                    initialScreenshotIndex = index
                    screenshotsToView = urls
                },
                // Sibling versions open their own detail; Back then returns to the same
                // parent screen that opened this detail (coordinator.gameDetailParent).
                onOpenSibling = { siblingId ->
                    coordinator.openGameDetail(siblingId, coordinator.gameDetailParent)
                },
                onChooseSaveClick = { loadSavesForPicker() },
                onChooseFileClick = { showVersionPicker = true },
                playFocusRequester = playFocusRequester,
                playUpFocusRequester = headerFocusRequester,
                firstScreenshotFocusRequester = firstScreenshotFocusRequester,
                screenshotUpFocusRequester = playFocusRequester,
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
                downFocusRequester = playFocusRequester,
                favoriteFocusRequester = headerFocusRequester,
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

        // ── Version picker overlay ("Choose File" — re-scopes to the picked ROM) ──
        if (showVersionPicker && loadedDetail != null) {
            VersionPickerOverlay(
                model = VersionPickerUiModel(
                    gameTitle = loadedDetail.title,
                    entries = buildVersionPickerEntries(loadedDetail),
                ),
                onSelect = { entry ->
                    showVersionPicker = false
                    // Re-scope the detail screen to the chosen ROM (Android parity: sets
                    // selectedRomId and stays on GAME_DETAIL — the user presses Play there).
                    coordinator.openGameDetail(entry.romId, coordinator.gameDetailParent)
                },
                onDismiss = { showVersionPicker = false },
            )
        }

        // ── Save picker overlay ("Choose Save" — records the launch's save) ──
        savePickerState?.let { state ->
            if (loadedDetail != null) {
                SavePickerOverlay(
                    state = state,
                    onSelect = { entry ->
                        savePickerState = null
                        coordinator.chooseSaveForLaunch(romId, entry)
                        loadedSaveFileName = entry.fileName
                    },
                    onRetry = { loadSavesForPicker() },
                    onDismiss = { savePickerState = null },
                )
            }
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

        // ── Quarantine drill-down (read-only; acknowledge only) ──────────
        if (showQuarantineDialog) {
            quarantineModel?.let { model ->
                SaveQuarantineDialog(
                    model = model,
                    theme = LocalRommulusTheme.current,
                    onDismiss = { showQuarantineDialog = false },
                )
            }
        }
    }
}

/**
 * Hero cover + title/platform + metadata chips + summary + the action row (+ save-sync
 * status/actions). The action row mirrors Android's branch order: the auth-expired state
 * ("Session expired" + Log in) takes precedence, then the proactive "not supported yet" state,
 * then the required-BIOS-unavailable state (SEGA CD / PlayStation), else the normal
 * Play / Choose Save / Choose File row (Play shows "Preparing…" and is disabled while staging).
 */
@Composable
private fun GameDetailBody(
    rom: RomDetail,
    playEnabled: Boolean,
    isOffline: Boolean,
    isAvailableOffline: Boolean,
    onPlayClick: () -> Unit,
    isStaging: Boolean,
    isAuthExpired: Boolean,
    biosState: RequiredBiosState,
    onLogin: () -> Unit,
    onDismissLaunchState: () -> Unit,
    playStatus: PlayerLaunchResult?,
    saveUiState: SaveSyncUiState,
    serverSaveAvailability: ServerSaveAvailability,
    loadedSaveFileName: String?,
    saveActionMessage: String?,
    onSaveSyncNow: () -> Unit,
    onSaveKeepLocal: () -> Unit,
    onSaveKeepServer: () -> Unit,
    onViewQuarantine: () -> Unit,
    onOpenScreenshot: (List<String>, Int) -> Unit,
    onOpenSibling: (Long) -> Unit,
    onChooseSaveClick: () -> Unit,
    onChooseFileClick: () -> Unit,
    playFocusRequester: FocusRequester,
    playUpFocusRequester: FocusRequester,
    firstScreenshotFocusRequester: FocusRequester,
    screenshotUpFocusRequester: FocusRequester,
) {
    val colors = LocalRommulusColors.current
    val chooseSaveFocusRequester = remember { FocusRequester() }
    val chooseFileFocusRequester = remember { FocusRequester() }

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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // The fixed top-right action row shares this header's vertical band.
                        // Keep title/platform text clear of it rather than drawing beneath it.
                        .padding(end = 420.dp),
                ) {
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
                    when {
                        // Auth-expired takes precedence over everything (Android parity): the Play
                        // row is replaced by the "Session expired" state with a Log in action.
                        isAuthExpired -> AuthExpiredState(
                            onLogin = onLogin,
                            onDismiss = onDismissLaunchState,
                        )

                        // Proactive "not supported yet" state (Android parity): checked up front
                        // from CoreManifest, so the user never presses Play to discover a failure.
                        !playEnabled -> UnsupportedSystemState(platformDisplayName = rom.platformDisplayName)

                        // Required-BIOS-unavailable state for SEGA CD / PlayStation (Android
                        // `RequiredBiosUnavailableState` parity): disabled Play + per-state message.
                        biosState !is RequiredBiosState.Ready -> RequiredBiosUnavailableState(biosState)

                        isOffline && !isAvailableOffline -> Column {
                            DisabledPlayButton()
                            Text(
                                text = "Offline - download this game before playing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }

                        else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PlayButton(
                                enabled = playEnabled,
                                isStaging = isStaging,
                                status = playStatus,
                                onPlayClick = onPlayClick,
                                focusRequester = playFocusRequester,
                                upFocusRequester = playUpFocusRequester,
                                modifier = Modifier.weight(1f),
                            )
                            // "Choose Save" (Android parity): opens the save picker overlay listing
                            // this ROM's server saves; picking one re-scopes the next launch.
                            // Disabled while staging (Android parity).
                            ChooseSaveButton(
                                onClick = onChooseSaveClick,
                                enabled = !isStaging,
                                focusRequester = chooseSaveFocusRequester,
                                leftFocusRequester = playFocusRequester,
                                upFocusRequester = playUpFocusRequester,
                                modifier = Modifier.weight(1f),
                            )
                            // "Choose File" (Android parity): only when the ROM has sibling versions.
                            if (shouldShowChooseFileButton(rom)) {
                                ChooseFileButton(
                                    onClick = onChooseFileClick,
                                    enabled = !isStaging,
                                    focusRequester = chooseFileFocusRequester,
                                    leftFocusRequester = chooseSaveFocusRequester,
                                    upFocusRequester = playUpFocusRequester,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    SaveStatusLine(
                        state = saveUiState,
                        isOffline = isOffline,
                        serverAvailability = serverSaveAvailability,
                        loadedSaveFileName = loadedSaveFileName,
                        actionMessage = saveActionMessage,
                        onSyncNow = onSaveSyncNow,
                        onKeepLocal = onSaveKeepLocal,
                        onKeepServer = onSaveKeepServer,
                        onViewQuarantine = onViewQuarantine,
                    )
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
                        ScreenshotThumbnail(
                            url = url,
                            onClick = { onOpenScreenshot(rom.screenshotUrls, index) },
                            modifier = Modifier
                                .then(
                                    if (index == 0) {
                                        Modifier
                                            .focusRequester(firstScreenshotFocusRequester)
                                            .focusProperties { up = screenshotUpFocusRequester }
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
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
 * The Play affordance: a focusable/clickable primary button that launches the desktop
 * player process ([DesktopAppCoordinator.launchPlayer], wired in by the caller). Below
 * the button it renders the last launch outcome — "Launching player…" for
 * [PlayerLaunchResult.Started], or the failure reason in the theme error color for
 * [PlayerLaunchResult.Failed] (nothing until the first click).
 *
 * When [isStaging] is true (content being staged for launch), the button shows "Preparing…" and
 * is disabled — not focusable, not clickable (Android `PlayButton` parity).
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun PlayButton(
    enabled: Boolean,
    status: PlayerLaunchResult?,
    onPlayClick: () -> Unit,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    isStaging: Boolean = false,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // Staging disables the button exactly like an unsupported platform (Android parity).
    val active = enabled && !isStaging

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (active) colors.romm500 else colors.textSecondary.copy(alpha = 0.25f),
                )
                .border(
                    width = if (active && isFocused) 2.dp else 0.dp,
                    color = if (active && isFocused) colors.romm300 else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                )
                .focusRequester(focusRequester)
                .focusProperties { up = upFocusRequester }
                .then(
                    if (active) {
                        Modifier.focusableItem("detail:play", navigator, onPlayClick)
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = active,
                    onClick = onPlayClick,
                )
                .padding(horizontal = 28.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isStaging) "Preparing…" else "▶  Play",
                style = MaterialTheme.typography.titleMedium,
                color = if (active) Color.White else Color.White.copy(alpha = 0.4f),
            )
        }
        if (status != null) {
            Spacer(modifier = Modifier.height(8.dp))
            when (status) {
                is PlayerLaunchResult.Started -> Text(
                    text = "Launching player…",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                is PlayerLaunchResult.Failed -> Text(
                    text = status.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = PlayButtonErrorColor,
                )
            }
        }
    }
}

/**
 * Inline auth-expired state replacing the Play row (Android `AuthExpiredState` parity): a
 * "Session expired" message with a Log in action (routes to onboarding/login via
 * [DesktopAppCoordinator.openLogin] — nothing is auto-submitted) and Dismiss. Takes precedence
 * over every other action-row state.
 */
@Composable
private fun AuthExpiredState(
    onLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val navigator = LocalFocusNavigator.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Session expired; please log in to continue",
            style = MaterialTheme.typography.bodySmall,
            color = PlayButtonErrorColor,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(
                onClick = onLogin,
                modifier = Modifier.focusableItem("detail:login", navigator, onLogin),
            ) {
                Text("Log in")
            }
            TvOutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.focusableItem("detail:dismiss-auth-expired", navigator, onDismiss),
            ) {
                Text("Dismiss")
            }
        }
    }
}

/**
 * Proactive "not supported yet" state (Android `UnsupportedSystemState` parity): a disabled Play
 * button + the reason, shown instead of the Play row when no approved desktop core exists for the
 * platform — the user never has to press Play to discover a launch will fail.
 */
@Composable
private fun UnsupportedSystemState(platformDisplayName: String) {
    val colors = LocalRommulusColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        DisabledPlayButton()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Not supported yet — no native emulator core for $platformDisplayName",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

/**
 * Inline required-BIOS-unavailable state (Android `RequiredBiosUnavailableState` parity): a
 * disabled Play button + the per-state message for SEGA CD / PlayStation while the BIOS is not
 * [RequiredBiosState.Ready].
 */
@Composable
private fun RequiredBiosUnavailableState(state: RequiredBiosState) {
    val colors = LocalRommulusColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        DisabledPlayButton()
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = requiredBiosUnavailableMessage(state),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

/** The user-facing message for each non-Ready [RequiredBiosState] (Android parity). */
internal fun requiredBiosUnavailableMessage(state: RequiredBiosState): String = when (state) {
    RequiredBiosState.Checking -> "Checking for required BIOS files…"
    RequiredBiosState.Missing ->
        "Missing BIOS files on server. Please contact your RomM administrator."
    RequiredBiosState.UnverifiedAvailable ->
        "No verified BIOS file found. Please choose one in Settings."
    is RequiredBiosState.Error -> state.message
    RequiredBiosState.Ready -> ""
}

/** A disabled (non-focusable, non-clickable) Play button for the unavailable states. */
@Composable
private fun DisabledPlayButton() {
    val colors = LocalRommulusColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.textSecondary.copy(alpha = 0.25f))
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Text(
            text = "▶  Play",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.4f),
        )
    }
}

/**
 * The "Choose File" affordance next to Play, rendered only when the ROM has sibling versions
 * (Android parity with `ChooseVersionButton`): opens the version picker overlay, which lists
 * this ROM and its siblings for re-scoping.
 */
@Composable
private fun ChooseFileButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.nightLo)
            .border(
                width = if (isFocused && enabled) 2.dp else 1.dp,
                color = if (isFocused && enabled) colors.romm300 else colors.textSecondary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .focusRequester(focusRequester)
            .focusProperties {
                left = leftFocusRequester
                up = upFocusRequester
            }
            .then(
                // Disabled while staging (Android parity): not focusable, not clickable.
                if (enabled) Modifier.focusableItem("detail:choose-file", navigator, onClick) else Modifier,
            )
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Choose File",
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) colors.textPrimary else colors.textSecondary,
        )
    }
}

/**
 * The "Choose Save" affordance next to Play/Choose File (Android parity with `ChooseSaveButton`):
 * opens the save picker overlay, which lists every server save for this ROM (newest first, the
 * newest autosave checked as the default). Picking a row records the choice for the upcoming
 * launch — the user then presses Play.
 */
@Composable
private fun ChooseSaveButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.nightLo)
            .border(
                width = if (isFocused && enabled) 2.dp else 1.dp,
                color = if (isFocused && enabled) colors.romm300 else colors.textSecondary.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .focusRequester(focusRequester)
            .focusProperties {
                left = leftFocusRequester
                up = upFocusRequester
            }
            .then(
                // Disabled while staging (Android parity): not focusable, not clickable.
                if (enabled) Modifier.focusableItem("detail:choose-save", navigator, onClick) else Modifier,
            )
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Choose Save",
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) colors.textPrimary else colors.textSecondary,
        )
    }
}

/**
 * Save picker overlay (desktop adaptation of Android's full-screen `SavePickerScreen`,
 * "Choose Save"): a scrim + centered panel listing every server save for the current ROM —
 * newest first, each row showing file name, core badge, size and timestamp, with the newest
 * autosave checked as the default. Escape dismisses; picking a row records the choice for the
 * upcoming launch via [onSelect] (no auto-launch — Android parity: the user presses Play).
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun SavePickerOverlay(
    state: SavePickerState,
    onSelect: (SavePickerEntryUiModel) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val focusManager = LocalFocusManager.current
    val focusOverrideOwner = remember { Any() }
    val overlayFocusRequester = remember { FocusRequester() }
    val initialFocusRequester = remember { FocusRequester() }

    DisposableEffect(navigator, focusManager, focusOverrideOwner, onDismiss) {
        navigator.installSpatialFocusOverride(
            owner = focusOverrideOwner,
            moveFocus = focusManager::moveFocus,
            onBack = onDismiss,
        )
        onDispose { navigator.removeSpatialFocusOverride(focusOverrideOwner) }
    }
    LaunchedEffect(Unit) { overlayFocusRequester.requestFocusSafely() }
    LaunchedEffect(state) { initialFocusRequester.requestFocusSafely() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .focusRequester(overlayFocusRequester)
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
            when (val pickerState = state) {
                is SavePickerState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator()
                }

                is SavePickerState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ErrorBanner(message = pickerState.message)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvButton(
                            onClick = onRetry,
                            modifier = Modifier
                                .focusRequester(initialFocusRequester)
                                .focusableItem("save-picker:retry", navigator, onRetry),
                        ) {
                            Text("Retry")
                        }
                        TvOutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.focusableItem("save-picker:back", navigator, onDismiss),
                        ) {
                            Text("Back")
                        }
                    }
                }

                is SavePickerState.Loaded -> {
                    Text(
                        text = "Choose Save",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = pickerState.model.romTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.romm300,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (pickerState.model.entries.isEmpty()) {
                        Text(
                            text = "No saves found for this game yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            itemsIndexed(pickerState.model.entries, key = { _, entry -> entry.saveId }) { index, entry ->
                                SavePickerRow(
                                    entry = entry,
                                    onClick = { onSelect(entry) },
                                    modifier = Modifier.then(
                                        if (index == 0) {
                                            Modifier.focusRequester(initialFocusRequester)
                                        } else {
                                            Modifier
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One focusable/clickable save row: file name, checkmark on the default (newest autosave), core • size • date meta line. */
@Composable
private fun SavePickerRow(
    entry: SavePickerEntryUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) colors.romm600.copy(alpha = 0.3f) else Color.Transparent)
            .focusableItem("save-picker:${entry.saveId}", navigator, onClick)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp)) {
            if (entry.isDefaultSelection) {
                Text(text = "✓", color = colors.romm300, style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFocused) colors.romm300 else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(entry.coreId, entry.sizeText, entry.updatedAtText)
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString("  •  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Matches the theme error color used by [ErrorBanner] (Feedback.kt). */
private val PlayButtonErrorColor = Color(0xFFF87171)

/** Sync states that require explicit user action — rendered in the theme error color. */
private val NEEDS_ATTENTION_SYNC_STATUSES = setOf(SaveSyncStatus.CONFLICT, SaveSyncStatus.QUARANTINED)

/**
 * Save-sync status line under the Play button, with actions (actionable half of the Linux saves UI).
 * Neutral secondary text for healthy/in-flight states; the theme error color for CONFLICT and
 * QUARANTINED. Actions are offered per [saveSyncUiActions]: "Sync now" (force a drain) whenever a
 * replica exists in any non-conflict, non-quarantined status; Keep-local / Keep-server ONLY on
 * CONFLICT — the user's explicit choice of which copy wins ("conflict preserves both copies"); and
 * "View quarantine" ONLY on QUARANTINED — a quarantined save needs an explicit compatibility/import
 * decision and is never auto-redrained. An optional second line carries the last action failure
 * ([actionMessage]) or [SaveSyncUiState.Replica.lastError] when the drain recorded one.
 */
@Composable
private fun SaveStatusLine(
    state: SaveSyncUiState,
    isOffline: Boolean,
    serverAvailability: ServerSaveAvailability,
    loadedSaveFileName: String?,
    actionMessage: String?,
    onSyncNow: () -> Unit,
    onKeepLocal: () -> Unit,
    onKeepServer: () -> Unit,
    onViewQuarantine: () -> Unit,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val actions = saveSyncUiActions(state)
    val needsAttention = state is SaveSyncUiState.Replica &&
        state.syncStatus in NEEDS_ATTENTION_SYNC_STATUSES
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (isOffline) {
                "Save: Offline - progress is checkpointed locally"
            } else {
                saveStatusLabel(state, serverAvailability, loadedSaveFileName)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (needsAttention) PlayButtonErrorColor else colors.textSecondary,
        )
        if (!isOffline && actions.canSyncNow) {
            SaveActionButton("Sync now", "detail:save-sync", onSyncNow, navigator)
        }
        if (!isOffline && actions.canResolveConflict) {
            SaveActionButton("Keep local", "detail:keep-local", onKeepLocal, navigator)
            SaveActionButton("Keep server", "detail:keep-server", onKeepServer, navigator)
        }
        if (!isOffline && actions.canViewQuarantine) {
            // QUARANTINED offers ONLY this action — never "Sync now" (a quarantined save needs an
            // explicit compatibility/import decision; auto-redraining could undo that choice).
            SaveActionButton("View quarantine", "detail:view-quarantine", onViewQuarantine, navigator)
        }
    }
    val detailLine = actionMessage ?: (state as? SaveSyncUiState.Replica)?.lastError
    if (detailLine != null) {
        Text(
            text = detailLine,
            style = MaterialTheme.typography.labelSmall,
            color = PlayButtonErrorColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Small focusable action chip for the save-status line: mouse-clickable and a stop on the
 * D-pad/keyboard/controller focus path (same wiring pattern as [PlayButton]).
 */
@Composable
private fun SaveActionButton(
    label: String,
    focusKey: String,
    onClick: () -> Unit,
    navigator: FocusNavigator,
) {
    val colors = LocalRommulusColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isFocused) colors.romm500 else colors.romm500.copy(alpha = 0.45f))
            .then(Modifier.focusableItem(focusKey, navigator, onClick))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

/** Header actions: Favorite / Add-to-collection / Back. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun GameDetailActionRail(
    favoriteState: FavoriteUiState,
    onFavoriteClick: () -> Unit,
    onAddClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    downFocusRequester: FocusRequester,
    favoriteFocusRequester: FocusRequester,
) {
    val navigator = LocalFocusNavigator.current
    val favorite = remember(favoriteState) { favoriteRailUi(favoriteState) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvOutlinedButton(
            onClick = onFavoriteClick,
            enabled = favoriteState !is FavoriteUiState.Loading && favoriteState !is FavoriteUiState.Updating,
            modifier = Modifier
                .focusRequester(favoriteFocusRequester)
                .focusProperties {
                    down = downFocusRequester
                    left = FocusRequester.Cancel
                }
                .focusableItem("detail:favorite", navigator, onFavoriteClick),
        ) {
            Icon(imageVector = favorite.icon, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(favorite.label)
        }
        TvOutlinedButton(
            onClick = onAddClick,
            modifier = Modifier
                .focusProperties {
                    down = downFocusRequester
                }
                .focusableItem("detail:add-collection", navigator, onAddClick),
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add to Collection")
        }
        TvOutlinedButton(
            onClick = onBackClick,
            modifier = Modifier
                .focusProperties {
                    down = downFocusRequester
                }
                .focusableItem("detail:back", navigator, onBackClick),
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Back")
        }
    }
}

/** The favorite rail button's display: icon + label (desktop port of Android's `FavoriteButtonConfig`). */
data class FavoriteRailUi(
    val icon: ImageVector,
    val label: String,
)

/**
 * Maps the shared presenter's [FavoriteUiState] to the favorite rail button's icon + label,
 * mirroring Android's `GameDetailActionRail` state→config table: a filled [Icons.Filled.Star]
 * while favorited or updating (Android always shows the filled star mid-update), and
 * [Icons.Filled.StarBorder] otherwise.
 */
internal fun favoriteRailUi(state: FavoriteUiState): FavoriteRailUi = when (state) {
    is FavoriteUiState.Loading -> FavoriteRailUi(Icons.Filled.StarBorder, "Favorite…")
    is FavoriteUiState.Confirmed ->
        if (state.isFavorite) FavoriteRailUi(Icons.Filled.Star, "Favorited")
        else FavoriteRailUi(Icons.Filled.StarBorder, "Favorite")
    // Android shows the filled star for both add- and remove-in-flight.
    is FavoriteUiState.Updating ->
        if (state.target) FavoriteRailUi(Icons.Filled.Star, "Adding…")
        else FavoriteRailUi(Icons.Filled.Star, "Removing…")
}

/**
 * A single focusable/clickable screenshot thumbnail in the shelf. Selecting it opens the
 * full-screen [ScreenshotViewer] overlay at this item's index.
 */
@Composable
private fun ScreenshotThumbnail(
    url: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .width(280.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.nightLo)
            .border(
                width = if (isFocused) 3.dp else 0.dp,
                color = if (isFocused) colors.romm500 else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .focusableItem("detail:screenshot:$url", navigator, onClick)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
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
    val navigator = LocalFocusNavigator.current
    var index by remember(screenshotUrls) {
        mutableStateOf(initialIndex.coerceIn(0, (screenshotUrls.size - 1).coerceAtLeast(0)))
    }
    val focusRequester = remember { FocusRequester() }
    val focusOverrideOwner = remember { Any() }
    DisposableEffect(navigator, focusOverrideOwner, screenshotUrls, onClose) {
        navigator.installSpatialFocusOverride(
            owner = focusOverrideOwner,
            moveFocus = { direction ->
                when (direction) {
                    FocusDirection.Left -> if (index > 0) index--
                    FocusDirection.Right -> if (index < screenshotUrls.lastIndex) index++
                }
                true
            },
            onBack = onClose,
        )
        onDispose { navigator.removeSpatialFocusOverride(focusOverrideOwner) }
    }
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
                size = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
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
@OptIn(ExperimentalComposeUiApi::class)
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
    val navigator = LocalFocusNavigator.current
    val focusManager = LocalFocusManager.current
    val focusOverrideOwner = remember { Any() }
    val overlayFocusRequester = remember { FocusRequester() }
    val initialFocusRequester = remember { FocusRequester() }

    DisposableEffect(navigator, focusManager, focusOverrideOwner, onDismiss) {
        navigator.installSpatialFocusOverride(
            owner = focusOverrideOwner,
            moveFocus = focusManager::moveFocus,
            onBack = onDismiss,
        )
        onDispose { navigator.removeSpatialFocusOverride(focusOverrideOwner) }
    }
    LaunchedEffect(Unit) {
        overlayFocusRequester.requestFocusSafely()
    }
    LaunchedEffect(dialogState, collections) {
        initialFocusRequester.requestFocusSafely()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .focusRequester(overlayFocusRequester)
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
                        RetryButton(
                            onRetry = onCollectionRetry,
                            modifier = Modifier
                                .focusRequester(initialFocusRequester)
                                .focusableItem("collection:retry", navigator, onCollectionRetry),
                        )
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
                                itemsIndexed(collections.ownedWritable, key = { _, item -> item.id }) { index, collection ->
                                    CollectionRow(
                                        name = collection.name,
                                        romCount = collection.romCount,
                                        isMember = collection.romIds.contains(rom.id),
                                        onClick = { onCollectionSelected(collection.id) },
                                        modifier = Modifier
                                            .then(
                                                if (index == 0) {
                                                    Modifier.focusRequester(initialFocusRequester)
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .focusableItem("collection:${collection.id}", navigator) {
                                                onCollectionSelected(collection.id)
                                            },
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = onCreateNewRequested,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (collections.ownedWritable.isEmpty()) {
                                        Modifier.focusRequester(initialFocusRequester)
                                    } else {
                                        Modifier
                                    },
                                )
                                .focusableItem("collection:new", navigator, onCreateNewRequested),
                        ) {
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
                        modifier = Modifier.focusRequester(initialFocusRequester),
                    )
                    val validationError = dialogState.validationError
                    if (validationError != null) {
                        ErrorBanner(message = validationError)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvButton(
                            onClick = onCreateSubmit,
                            enabled = !dialogState.submitting,
                            modifier = Modifier.focusableItem("collection:create", navigator, onCreateSubmit),
                        ) {
                            Text(if (dialogState.submitting) "Creating…" else "Create")
                        }
                        TvOutlinedButton(
                            onClick = onCancelCreate,
                            enabled = !dialogState.submitting,
                            modifier = Modifier.focusableItem("collection:cancel", navigator, onCancelCreate),
                        ) {
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
                TextButton(
                    onClick = onAlertDismissed,
                    modifier = Modifier.focusableItem(
                        "collection:alert-ok",
                        navigator,
                        onAlertDismissed,
                    ),
                ) {
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
    modifier: Modifier = Modifier,
) {
    val colors = LocalRommulusColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
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

/**
 * Version picker overlay (desktop adaptation of Android's full-screen `VersionPickerScreen`,
 * "Choose Game File"): a scrim + centered panel listing the current ROM and its sibling
 * versions. Each row shows the per-file name (tags like "(Disc 1)" kept), a checkmark on the
 * currently-open version, and a "Default version" subtitle for the group's default. Escape
 * dismisses; picking a row re-scopes the detail screen to that ROM via [onSelect] — no
 * auto-launch (Android parity: the user presses Play there when ready).
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun VersionPickerOverlay(
    model: VersionPickerUiModel,
    onSelect: (VersionPickerEntryUiModel) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val focusManager = LocalFocusManager.current
    val focusOverrideOwner = remember { Any() }
    val overlayFocusRequester = remember { FocusRequester() }
    val initialFocusRequester = remember { FocusRequester() }

    DisposableEffect(navigator, focusManager, focusOverrideOwner, onDismiss) {
        navigator.installSpatialFocusOverride(
            owner = focusOverrideOwner,
            moveFocus = focusManager::moveFocus,
            onBack = onDismiss,
        )
        onDispose { navigator.removeSpatialFocusOverride(focusOverrideOwner) }
    }
    LaunchedEffect(Unit) { overlayFocusRequester.requestFocusSafely() }
    LaunchedEffect(model) { initialFocusRequester.requestFocusSafely() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .focusRequester(overlayFocusRequester)
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
                text = "Choose Game File",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Text(
                text = model.gameTitle,
                style = MaterialTheme.typography.labelMedium,
                color = colors.romm300,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (model.entries.isEmpty()) {
                Text(
                    text = "No other versions found for this game.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(model.entries, key = { _, entry -> entry.romId }) { index, entry ->
                        VersionPickerRow(
                            entry = entry,
                            onClick = { onSelect(entry) },
                            modifier = Modifier.then(
                                if (index == 0) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** One focusable/clickable version row: per-file name, checkmark for the open version, "Default version" subtitle. */
@Composable
private fun VersionPickerRow(
    entry: VersionPickerEntryUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) colors.romm600.copy(alpha = 0.3f) else Color.Transparent)
            .focusableItem("version:${entry.romId}", navigator, onClick)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp)) {
            if (entry.isCurrentVersion) {
                Text(text = "✓", color = colors.romm300, style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFocused) colors.romm300 else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.isMainSibling) {
                Text(
                    text = "Default version",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
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
            .focusableItem("detail:sibling:${sibling.id}", navigator, onClick)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
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
@OptIn(ExperimentalComposeUiApi::class)
private fun ModalAlertPanel(
    message: String,
    onOk: () -> Unit,
) {
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val focusOverrideOwner = remember { Any() }

    DisposableEffect(navigator, focusManager, focusOverrideOwner, onOk) {
        navigator.installSpatialFocusOverride(
            owner = focusOverrideOwner,
            moveFocus = focusManager::moveFocus,
            onBack = onOk,
        )
        onDispose { navigator.removeSpatialFocusOverride(focusOverrideOwner) }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocusSafely() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
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
            TvButton(
                onClick = onOk,
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .focusableItem("alert:ok", navigator, onOk),
            ) {
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
