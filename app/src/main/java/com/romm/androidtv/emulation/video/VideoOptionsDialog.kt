package com.romm.androidtv.emulation.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

@Composable
internal fun VideoOptionsDialog(
    scanlinesEnabled: Boolean,
    persistenceError: Boolean,
    onScanlinesChanged: (Boolean) -> Boolean,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val toggleInteractionSource = remember { MutableInteractionSource() }
    val toggleIsFocused by toggleInteractionSource.collectIsFocusedAsState()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f

    // Resolve string resources outside semantics blocks
    val scanlinesLabel = stringResource(R.string.video_options_scanlines)
    val stateDescription = if (scanlinesEnabled) {
        stringResource(R.string.video_options_state_on)
    } else {
        stringResource(R.string.video_options_state_off)
    }

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
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Title
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

                // Toggle row
                val toggleBorderColor =
                    if (toggleIsFocused) RommTvColors.Romm300 else Color.Transparent

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .focusRequester(focusRequester)
                        .toggleable(
                            value = scanlinesEnabled,
                            interactionSource = toggleInteractionSource,
                            indication = null,
                            role = Role.Switch,
                            onValueChange = { onScanlinesChanged(it) },
                        )
                        .border(
                            BorderStroke(3.dp, toggleBorderColor),
                            RoundedCornerShape(8.dp),
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = "$scanlinesLabel - $stateDescription"
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
                                text = scanlinesLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.video_options_scanlines_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = RommTvColors.TextSecondary,
                            )
                        }

                        // Passive visual-only Switch (onCheckedChange = null)
                        Switch(
                            checked = scanlinesEnabled,
                            onCheckedChange = null,
                            interactionSource = remember { MutableInteractionSource() },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = RommTvColors.Romm600,
                                checkedThumbColor = Color.White,
                                uncheckedTrackColor = RommTvColors.TextSecondary.copy(alpha = 0.35f),
                                uncheckedThumbColor = RommTvColors.TextSecondary.copy(alpha = 0.7f),
                                checkedBorderColor = toggleBorderColor,
                                uncheckedBorderColor = toggleBorderColor,
                            ),
                        )
                    }
                }

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

                // Return button
                TvOutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.video_options_return)) }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
