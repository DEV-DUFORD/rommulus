package com.romm.androidtv.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.romm.androidtv.library.CollectionSummary
import com.romm.androidtv.romm.RommApiError

data class CreateCollectionUiState(
    val name: String = "",
    val validationError: String? = null,
    val submitting: Boolean = false,
)

@Composable
fun GameDetailErrorAlert(
    message: String,
    onOk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val okFocusRequester = remember { FocusRequester() }
    // Deferred via a readiness flag: a Dialog hosts its content in its own
    // window/sub-composition, so requesting focus on the very first frame
    // (before the OK button is actually attached and laid out) throws
    // "FocusRequester is not initialized". Mirrors PlayButton's pattern in
    // GameDetailScreen.kt.
    var okReady by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onOk,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        // Registered inside the Dialog's content so it subscribes to the
        // Dialog window's own OnBackPressedDispatcher. A BackHandler placed
        // outside the Dialog (in the caller's composition) binds to the host
        // Activity's dispatcher instead, which never receives the back-press
        // key event while the Dialog window has input focus.
        BackHandler { onOk() }
        Box(
            modifier = modifier
                .wrapContentWidth()
                .widthIn(min = 280.dp, max = 480.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(RommTvColors.NightHi)
                .padding(24.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = message, color = RommTvColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(20.dp))
                TvButton(
                    onClick = onOk,
                    modifier = Modifier
                        .focusRequester(okFocusRequester)
                        .onGloballyPositioned { okReady = true },
                ) {
                    Text("OK")
                }
            }
        }
    }
    LaunchedEffect(okReady) {
        if (okReady) okFocusRequester.requestFocus()
    }
}

@Composable
fun AddToCollectionDialog(
    gameTitle: String,
    collections: List<CollectionSummary>,
    currentRomId: Long,
    isLoading: Boolean,
    loadError: RommApiError?,
    createState: CreateCollectionUiState?,
    alertMessage: String?,
    onCreateNew: () -> Unit,
    onSelectCollection: (Long) -> Unit,
    onNameChange: (String) -> Unit,
    onCreateSubmit: () -> Unit,
    onCreateCancel: () -> Unit,
    onAlertDismissed: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f
    val createRowFocusRequester = remember { FocusRequester() }
    val createFieldFocusRequester = remember { FocusRequester() }
    val sortedCollections = remember(collections) {
        collections.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    // Deferred via readiness flags: the row/field targeted below live inside a
    // Dialog's own sub-composition (and the row additionally lives inside a
    // LazyColumn item), so requesting focus on the very first frame — before
    // the target is actually attached and laid out — throws "FocusRequester is
    // not initialized". `remember(branchKey)` resets each flag back to false
    // whenever we (re-)enter that branch so a stale "ready" from a previous
    // visit can't be reused before the freshly (re)composed target attaches.
    val isListBranch = createState == null && alertMessage == null
    val isCreateBranch = createState != null
    var createRowReady by remember(isListBranch) { mutableStateOf(false) }
    var createFieldReady by remember(isCreateBranch) { mutableStateOf(false) }

    LaunchedEffect(createState, alertMessage, createRowReady, createFieldReady) {
        when {
            createState != null -> if (createFieldReady) createFieldFocusRequester.requestFocus()
            alertMessage != null -> Unit
            else -> if (createRowReady) createRowFocusRequester.requestFocus()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        // Registered inside the Dialog's content so it subscribes to the
        // Dialog window's own OnBackPressedDispatcher. A BackHandler placed
        // outside the Dialog (in the caller's composition) binds to the host
        // Activity's dispatcher instead, which never receives the back-press
        // key event while the Dialog window has input focus.
        BackHandler {
            when {
                alertMessage != null -> onAlertDismissed()
                createState != null -> onCreateCancel()
                else -> onDismiss()
            }
        }
        Box(
            modifier = Modifier
                .then(modifier)
                .width(620.dp)
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(RommTvColors.NightHi),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(text = "Add to Collection", style = MaterialTheme.typography.headlineSmall, color = RommTvColors.TextPrimary)
                Text(text = gameTitle, style = MaterialTheme.typography.titleSmall, color = RommTvColors.Romm300, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(16.dp))

                if (createState != null) {
                    CreateCollectionContent(
                        state = createState,
                        fieldFocusRequester = createFieldFocusRequester,
                        onFieldPositioned = { createFieldReady = true },
                        onNameChange = onNameChange,
                        onCreateSubmit = onCreateSubmit,
                        onCreateCancel = onCreateCancel,
                    )
                } else if (loadError != null) {
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "Unable to load collections.", style = MaterialTheme.typography.bodyMedium, color = RommTvColors.TextSecondary)
                        Spacer(modifier = Modifier.height(12.dp))
                        CollectionRetryRow(onClick = onRetry)
                    }
                } else if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RommTvColors.Romm500)
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            CollectionCreateRow(
                                onClick = onCreateNew,
                                focusRequester = createRowFocusRequester,
                                modifier = Modifier.onGloballyPositioned { createRowReady = true },
                            )
                        }
                        item {
                            Divider(color = RommTvColors.TextSecondary.copy(alpha = 0.15f), thickness = 1.dp)
                        }
                        if (sortedCollections.isEmpty()) {
                            item {
                                Text(
                                    text = "You do not have any collections yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = RommTvColors.TextSecondary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        } else {
                            items(sortedCollections, key = { it.id }) { collection ->
                                CollectionPickerRow(
                                    collection = collection,
                                    isMember = currentRomId in collection.romIds,
                                    onClick = { onSelectCollection(collection.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (alertMessage != null) {
        GameDetailErrorAlert(message = alertMessage, onOk = onAlertDismissed)
    }
}

@Composable
private fun CollectionCreateRow(
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) RommTvColors.Romm600.copy(alpha = 0.3f) else RommTvColors.NightLo)
            .tvFocusRing(isFocused)
            .focusRequester(focusRequester)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = RommTvColors.Romm300, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Create New Collection", style = MaterialTheme.typography.bodyMedium, color = RommTvColors.TextPrimary)
        }
    }
}

@Composable
private fun CollectionPickerRow(collection: CollectionSummary, isMember: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) RommTvColors.Romm600.copy(alpha = 0.3f) else RommTvColors.NightLo)
            .tvFocusRing(isFocused)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = collection.name, style = MaterialTheme.typography.bodyMedium, color = RommTvColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (isMember) "Already added" else "${collection.romCount} games",
                    style = MaterialTheme.typography.labelSmall,
                    color = RommTvColors.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (isMember) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = RommTvColors.Romm300, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CollectionRetryRow(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) RommTvColors.Romm600.copy(alpha = 0.3f) else RommTvColors.NightLo)
            .tvFocusRing(isFocused)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = "Retry", style = MaterialTheme.typography.bodyMedium, color = RommTvColors.Romm300)
    }
}

@Composable
private fun CreateCollectionContent(
    state: CreateCollectionUiState,
    fieldFocusRequester: FocusRequester,
    onFieldPositioned: () -> Unit,
    onNameChange: (String) -> Unit,
    onCreateSubmit: () -> Unit,
    onCreateCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "Create New Collection", style = MaterialTheme.typography.headlineSmall, color = RommTvColors.TextPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Collection name", style = MaterialTheme.typography.bodyMedium, color = RommTvColors.TextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        ControllerFriendlyTextField(
            value = state.name,
            onValueChange = onNameChange,
            placeholder = { Text("Enter collection name", color = RommTvColors.TextSecondary) },
            isError = state.validationError != null,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocusRequester)
                .onGloballyPositioned { onFieldPositioned() },
        )
        if (state.validationError != null) {
            Text(text = state.validationError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TvOutlinedButton(onClick = onCreateCancel) {
                Text("Cancel", color = RommTvColors.TextSecondary)
            }
            Spacer(modifier = Modifier.width(12.dp))
            TvButton(onClick = onCreateSubmit, enabled = !state.submitting) {
                if (state.submitting) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Create")
                }
            }
        }
    }
}
