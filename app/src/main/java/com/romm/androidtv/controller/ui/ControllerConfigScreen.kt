package com.romm.androidtv.controller.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.romm.androidtv.R
import com.romm.androidtv.controller.config.ControllerHighlightRegion
import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.HighlightShape
import com.romm.androidtv.library.ui.RommTvColors

/**
 * Shared, host-agnostic controller-configuration screen (CONTROLLER_SETTINGS.md §7).
 *
 * Receives state and callbacks only; it must not know whether it is hosted by
 * MainActivity or EmulationActivity. Handles player tabs, focus restoration,
 * the 40/60 artwork/binding split, capture/conflict/reset-all modals, and the
 * brief confirmation message.
 */
@Composable
@androidx.compose.material3.ExperimentalMaterial3Api
fun ControllerConfigScreen(
    state: ControllerConfigUiState,
    onBack: () -> Unit,
    onSelectTab: (playerIndex: Int) -> Unit,
    onRowFocused: (controlId: CoreControlId) -> Unit,
    onRowSelected: (controlId: CoreControlId) -> Unit,
    onCaptureDialogDismiss: () -> Unit,
    onConflictResolution: (resolution: ConflictResolution) -> Unit,
    onResetPlayer: () -> Unit,
    onResetAllConfirm: () -> Unit,
    onResetAllRequest: () -> Unit,
    onResetAllCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentPlayer = state.selectedPlayerIndex
    val currentOnRowSelected by rememberUpdatedState(onRowSelected)
    val currentOnSelectTab by rememberUpdatedState(onSelectTab)
    val currentOnBack by rememberUpdatedState(onBack)

    // Focus restoration: one FocusRequester per tab; track the last-focused row per tab.
    val tabFocusRequesters = remember { Array(4) { FocusRequester() } }
    val rowFocusRequesters = remember { mutableStateOf<Map<Int, FocusRequester>>(emptyMap()) }
    val lastFocusedRowByPlayer = remember { IntArray(4) }
    val focusedTabIndex = remember { mutableStateOf<Int?>(null) }
    val focusedRowIndex = remember { mutableStateOf<Int?>(null) }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    // Initial focus goes to the first binding row.
    LaunchedEffect(currentPlayer, rowFocusRequesters.value.size) {
        val requester = rowFocusRequesters.value[lastFocusedRowByPlayer[currentPlayer]]
        if (requester != null) {
            focusManager.clearFocus()
            requester.requestFocus()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(RommTvColors.NightHi)
                .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                when (keyEvent.key) {
                    Key.DirectionDown -> {
                        // Down from the selected tab returns to the last-focused row for that tab.
                        if (focusedTabIndex.value == currentPlayer) {
                            val idx = lastFocusedRowByPlayer[currentPlayer]
                            rowFocusRequesters.value[idx]?.requestFocus()
                            focusedTabIndex.value = null
                            true
                        } else false
                    }
                    Key.DirectionUp -> {
                        // Up from the first row enters the selected tab.
                        if (focusedRowIndex.value == 0) {
                            tabFocusRequesters[currentPlayer].requestFocus()
                            focusedRowIndex.value = null
                            true
                        } else false
                    }
                    Key.Back -> {
                        if (state.capture != null) {
                            onCaptureDialogDismiss()
                        } else {
                            currentOnBack()
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
        ControllerHeader(
            title = state.consoleName,
            onBack = currentOnBack,
            onResetPlayer = onResetPlayer,
        )

        PlayerTabRow(
            playerCount = state.playerCount,
            selectedIndex = currentPlayer,
            tabFocusRequesters = tabFocusRequesters,
            onFocused = { playerIndex -> focusedTabIndex.value = playerIndex },
            onSelectTab = currentOnSelectTab,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            // Left 40%: artwork panel (base vector + focused highlight overlay).
            val focusedRegion = state.rows.firstOrNull { it.controlId == state.focusedControlId }?.highlightRegion
            ArtworkPlaceholder(
                consoleName = state.consoleName,
                artworkResourceId = state.artworkResourceId,
                focusedRegion = focusedRegion,
                modifier = Modifier.weight(0.4f).padding(end = 20.dp),
            )

            // Right 60%: scrollable binding rows + Reset All Controllers.
            BindingList(
                rows = state.rows,
                listState = listState,
                rowFocusRequesters = rowFocusRequesters,
                onFocusChanged = { index, isFocused ->
                    if (isFocused) {
                        focusedRowIndex.value = index
                        lastFocusedRowByPlayer[currentPlayer] = index
                        state.rows.getOrNull(index)?.controlId?.let(onRowFocused)
                    }
                },
                onRowSelected = { controlId -> currentOnRowSelected(controlId) },
                onResetAllRequest = onResetAllRequest,
                modifier = Modifier.weight(0.6f),
            )
        }

        // Persistent footer.
        Text(
            text = stringResource(R.string.controller_config_footer_hint),
            style = MaterialTheme.typography.labelSmall,
            color = RommTvColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
    }

    // Brief non-blocking confirmation.
    state.lastAppliedMessage?.let { message ->
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(RommTvColors.Romm500)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(text = message, color = RommTvColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
        }
    }
    }

    // Capture dialog overlay (owned by the parallel worker).
    state.capture?.let { capture ->
        ControllerCaptureDialog(
            controlLabel = capture.controlLabel,
            playerLabel = capture.playerLabel,
            captureState = capture.captureState,
            connectedDeviceName = capture.connectedDeviceName,
            onDismiss = onCaptureDialogDismiss,
        )
    }

    // Conflict dialog.
    state.conflict?.let { conflict ->
        ConflictDialog(
            targetLabel = conflict.targetControlLabel,
            conflictingLabel = conflict.conflictingControlLabel,
            onSwap = { onConflictResolution(ConflictResolution.SWAP) },
            onReplace = { onConflictResolution(ConflictResolution.REPLACE) },
            onCancel = { onConflictResolution(ConflictResolution.CANCEL) },
        )
    }

    // Reset All Controllers confirmation.
    if (state.resetAllAwaitingConfirmation) {
        AlertDialog(
            onDismissRequest = onResetAllCancel,
            title = { Text(stringResource(R.string.controller_config_reset_all_title)) },
            text = { Text(stringResource(R.string.controller_config_reset_all_body)) },
            confirmButton = {
                TextButton(onClick = onResetAllConfirm) { Text(stringResource(R.string.controller_config_reset_all_confirm), color = RommTvColors.Romm300) }
            },
            dismissButton = {
                TextButton(onClick = onResetAllCancel) { Text(stringResource(R.string.controller_config_reset_all_cancel), color = RommTvColors.TextSecondary) }
            },
        )
    }
}

@Composable
private fun ControllerHeader(
    title: String,
    onBack: () -> Unit,
    onResetPlayer: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.controller_config_back), color = RommTvColors.TextSecondary) }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = RommTvColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        val resetControllerLabel = stringResource(R.string.controller_config_reset_controller)
        TextButton(
            onClick = onResetPlayer,
            modifier = Modifier.semantics { contentDescription = resetControllerLabel },
        ) { Text(resetControllerLabel, color = RommTvColors.TextSecondary) }
    }
}

@Composable
@androidx.compose.material3.ExperimentalMaterial3Api
private fun PlayerTabRow(
    playerCount: Int,
    selectedIndex: Int,
    tabFocusRequesters: Array<FocusRequester>,
    onFocused: (playerIndex: Int) -> Unit,
    onSelectTab: (playerIndex: Int) -> Unit,
) {
    PrimaryTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        for (playerIndex in 0 until playerCount) {
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            val playerNumber = playerIndex + 1
            val tabContentDescription = stringResource(
                R.string.controller_config_player_tab_content_description,
                playerNumber,
            )
            Tab(
                selected = playerIndex == selectedIndex,
                onClick = { onSelectTab(playerIndex) },
                interactionSource = interactionSource,
                text = {
                    Text(
                        text = stringResource(R.string.controller_config_player_tab_label, playerNumber),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isFocused) RommTvColors.Romm300 else RommTvColors.TextSecondary,
                    )
                },
                modifier = Modifier
                    .focusRequester(tabFocusRequesters[playerIndex])
                    .then(
                        if (isFocused) Modifier.border(3.dp, RommTvColors.Romm500, RoundedCornerShape(8.dp))
                        else Modifier,
                    )
                    .onFocusChanged { if (it.isFocused) onFocused(playerIndex) }
                    .semantics { contentDescription = tabContentDescription },
            )
        }
    }
}

@Composable
private fun BindingList(
    rows: List<ControllerBindingRow>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    rowFocusRequesters: androidx.compose.runtime.MutableState<Map<Int, FocusRequester>>,
    onFocusChanged: (index: Int, isFocused: Boolean) -> Unit,
    onRowSelected: (CoreControlId) -> Unit,
    onResetAllRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows.size) { index ->
            val row = rows[index]
            BindingRow(
                row = row,
                onFocusRequesterCreated = { fr ->
                    rowFocusRequesters.value = rowFocusRequesters.value + (index to fr)
                },
                onFocusChanged = { focused -> onFocusChanged(index, focused) },
                onClick = { onRowSelected(row.controlId) },
            )
        }
        item(key = "reset_all") {
            ResetAllRow(onClick = onResetAllRequest)
        }
    }
}

@Composable
private fun BindingRow(
    row: ControllerBindingRow,
    onFocusRequesterCreated: (FocusRequester) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(focusRequester) {
        onFocusRequesterCreated(focusRequester)
    }

    val rowContentDescription = stringResource(
        R.string.controller_config_binding_row_content_description,
        row.label,
        row.bindingLabel,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) RommTvColors.Romm500.copy(alpha = 0.15f)
                else RommTvColors.NightLo,
            )
            .then(
                if (isFocused) Modifier.border(3.dp, RommTvColors.Romm500, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .semantics { contentDescription = rowContentDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isFocused) RommTvColors.Romm300 else RommTvColors.TextPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.bindingLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isFocused) RommTvColors.Romm300 else RommTvColors.TextSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ResetAllRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val resetAllLabel = stringResource(R.string.controller_config_reset_all)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) RommTvColors.Romm500.copy(alpha = 0.15f) else Color.Transparent)
            .then(
                if (isFocused) Modifier.border(3.dp, RommTvColors.Romm500, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { contentDescription = resetAllLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = resetAllLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isFocused) RommTvColors.Romm300 else RommTvColors.TextSecondary,
        )
    }
}

/**
 * Artwork panel. The focused region clips a second, tinted rendering of the same vector,
 * so highlights can only appear on actual artwork pixels and never as detached geometry.
 */
@Composable
private fun ArtworkPlaceholder(
    consoleName: String,
    artworkResourceId: Int,
    focusedRegion: ControllerHighlightRegion?,
    modifier: Modifier = Modifier,
) {
    val artworkPainter = painterResource(id = artworkResourceId)
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(RommTvColors.NightLo),
    ) {
        Text(
            text = consoleName,
            style = MaterialTheme.typography.titleMedium,
            color = RommTvColors.TextPrimary,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp),
        )
        Image(
            painter = artworkPainter,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(36.dp)
                .alpha(if (focusedRegion == null) 0.72f else 0.45f),
        )
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(36.dp),
        ) {
            focusedRegion?.let { region ->
                val artworkSide = minOf(size.width, size.height)
                val artworkLeft = (size.width - artworkSide) / 2f
                val artworkTop = (size.height - artworkSide) / 2f
                val width = artworkSide * region.width
                val height = artworkSide * region.height
                val left = artworkLeft + artworkSide * region.x
                val top = artworkTop + artworkSide * region.y
                val bounds = Rect(left, top, left + width, top + height)
                val clip = Path().apply {
                    when (region.shape) {
                        HighlightShape.CIRCLE, HighlightShape.OVAL -> addOval(bounds)
                        HighlightShape.RECT -> addRect(bounds)
                    }
                }
                clipPath(clip) {
                    translate(left = artworkLeft, top = artworkTop) {
                        with(artworkPainter) {
                            draw(
                                size = Size(artworkSide, artworkSide),
                                colorFilter = ColorFilter.tint(RommTvColors.Romm300),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictDialog(
    targetLabel: String,
    conflictingLabel: String,
    onSwap: () -> Unit,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.controller_conflict_title)) },
        text = {
            Text(
                stringResource(
                    R.string.controller_conflict_body,
                    conflictingLabel,
                    targetLabel,
                ),
            )
        },
        confirmButton = { TextButton(onClick = onSwap) { Text(stringResource(R.string.controller_conflict_swap), color = RommTvColors.Romm300) } },
        dismissButton = {
            Row {
                TextButton(onClick = onReplace) { Text(stringResource(R.string.controller_conflict_replace), color = RommTvColors.Romm300) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.controller_config_reset_all_cancel), color = RommTvColors.TextSecondary) }
            }
        },
    )
}
