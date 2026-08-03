package com.romm.androidtv.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.romm.androidtv.R
import com.romm.androidtv.controller.config.CoreControllerProfile
import com.romm.androidtv.library.ui.RommTvColors

/**
 * Stateless console-selection screen for controller settings.
 *
 * Renders a single-column, D-pad-friendly list of console cards sourced from
 * [CoreControllerProfile]. Each card shows the console name (never a core ID) and
 * an optional subtitle. Tapping/selecting a card invokes [onSelectCore].
 *
 * Matches the dark RomM TV styling conventions (NightHi background, purple
 * Romm500 focus highlight).
 */
@Composable
fun ControllerConsoleListScreen(
    profiles: List<CoreControllerProfile>,
    onSelectCore: (coreId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val firstItemFocusRequester = remember { FocusRequester() }

    // Request initial focus on the first list item.
    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        firstItemFocusRequester.requestFocus()
    }

    // Stable reference to the latest onSelectCore lambda.
    val currentOnSelectCore by rememberUpdatedState(onSelectCore)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi)
            .onPreviewKeyEvent { keyEvent ->
                // Intercept Back key at the composable level so the caller's
                // onBack is invoked before navigation consumes it.
                if (keyEvent.key == Key.Back && keyEvent.type == KeyEventType.KeyDown) {
                    onBack()
                    true
                } else {
                    false
                }
            },
    ) {
        // ---- Title ----
        Text(
            text = stringResource(R.string.controller_settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = RommTvColors.TextPrimary,
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 24.dp),
        )

        // ---- Console list ----
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(profiles, key = { it.coreId }) { profile ->
                ConsoleCard(
                    profile = profile,
                    isFirst = profile == profiles.firstOrNull(),
                    firstItemFocusRequester = firstItemFocusRequester,
                    onClick = { currentOnSelectCore(profile.coreId) },
                )
            }
        }
    }
}

/**
 * A single focusable console card row.
 *
 * Reuses the GameCard-style focus highlight: a purple Romm500 border when
 * the row is focused, NightLo background otherwise.
 */
@Composable
private fun ConsoleCard(
    profile: CoreControllerProfile,
    isFirst: Boolean,
    firstItemFocusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val cardFocusRequester = if (isFirst) firstItemFocusRequester else remember { FocusRequester() }

    val portSuffix = if (profile.playerCount > 1) "s" else ""
    val cardContentDescription = stringResource(
        R.string.controller_console_card_content_description,
        profile.consoleName,
        profile.playerCount,
        portSuffix,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) RommTvColors.Romm500.copy(alpha = 0.15f)
                else RommTvColors.NightLo
            )
            .then(
                if (isFocused) {
                    Modifier.border(3.dp, RommTvColors.Romm500, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .focusRequester(cardFocusRequester)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("console_card_${profile.coreId}")
            .semantics { contentDescription = cardContentDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.consoleName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFocused) RommTvColors.Romm300 else RommTvColors.TextPrimary,
                maxLines = 1,
            )
            profile.consoleSubtitle?.let { subtitle ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = RommTvColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }

        // Player count badge
        Text(
            text = stringResource(R.string.controller_console_port_count, profile.playerCount, portSuffix),
            style = MaterialTheme.typography.labelMedium,
            color = RommTvColors.TextSecondary,
        )
    }
}
