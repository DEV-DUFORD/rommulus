package com.romm.androidtv.emulation.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.romm.androidtv.R
import com.romm.androidtv.library.ui.RommTvColors
import com.romm.androidtv.library.ui.TvOutlinedButton
import com.romm.androidtv.platform.rememberDeviceProfile

@Composable
private fun VideoOptionToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onChanged: (Boolean) -> Boolean,
    focusRequester: FocusRequester,
    onReady: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor = if (isFocused) RommTvColors.Romm300 else Color.Transparent
    val stateDescription = if (checked) {
        stringResource(R.string.video_options_state_on)
    } else {
        stringResource(R.string.video_options_state_off)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .onGloballyPositioned { onReady() }
            .focusRequester(focusRequester)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = { onChanged(it) },
            )
            .border(
                BorderStroke(3.dp, borderColor),
                RoundedCornerShape(8.dp),
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "$label - $stateDescription"
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = RommTvColors.TextSecondary,
                )
            }

            Switch(
                checked = checked,
                onCheckedChange = null,
                interactionSource = remember { MutableInteractionSource() },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = RommTvColors.Romm600,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = RommTvColors.TextSecondary.copy(alpha = 0.35f),
                    uncheckedThumbColor = RommTvColors.TextSecondary.copy(alpha = 0.7f),
                    checkedBorderColor = borderColor,
                    uncheckedBorderColor = borderColor,
                ),
            )
        }
    }
}

@Composable
internal fun VideoOptionsDialog(
    scanlinesEnabled: Boolean,
    integerScalingEnabled: Boolean,
    persistenceError: Boolean,
    onScanlinesChanged: (Boolean) -> Boolean,
    onIntegerScalingChanged: (Boolean) -> Boolean,
    onDismiss: () -> Unit,
) {
    val deviceProfile = rememberDeviceProfile()
    val scrollState = rememberScrollState()
    val shouldScroll = deviceProfile.hasTouchscreen && deviceProfile.isCompactHeight
    val scanlinesFocusRequester = remember { FocusRequester() }
    val integerScalingFocusRequester = remember { FocusRequester() }
    var firstToggleReady by remember { mutableStateOf(false) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BackHandler(onBack = onDismiss)

        Box(
            modifier = Modifier
                .width(540.dp)
                .height(maxHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(RommTvColors.NightLo)
                .border(
                    BorderStroke(1.dp, RommTvColors.TextSecondary.copy(alpha = 0.25f)),
                    RoundedCornerShape(16.dp),
                )
                .padding(28.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (shouldScroll) {
                            Modifier.verticalScroll(scrollState)
                        } else {
                            Modifier
                        },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.video_options_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { heading() },
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(20.dp))

                VideoOptionToggleRow(
                    label = stringResource(R.string.video_options_scanlines),
                    description = stringResource(R.string.video_options_scanlines_description),
                    checked = scanlinesEnabled,
                    onChanged = onScanlinesChanged,
                    focusRequester = scanlinesFocusRequester,
                    onReady = { firstToggleReady = true },
                )

                Spacer(modifier = Modifier.height(12.dp))

                VideoOptionToggleRow(
                    label = stringResource(R.string.video_options_integer_scaling),
                    description = stringResource(R.string.video_options_integer_scaling_description),
                    checked = integerScalingEnabled,
                    onChanged = onIntegerScalingChanged,
                    focusRequester = integerScalingFocusRequester,
                    onReady = { /* no-op, only track first */ },
                )

                // Persistence error
                if (persistenceError) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.video_options_save_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF5252),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                TvOutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.video_options_return)) }
            }
        }
    }

    LaunchedEffect(firstToggleReady) {
        if (firstToggleReady) {
            scanlinesFocusRequester.requestFocus()
        }
    }
}
