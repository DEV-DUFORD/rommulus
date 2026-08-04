package com.romm.androidtv.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.romm.androidtv.library.ui.RommTvColors

/** Error text color with WCAG AA contrast against the NightHi background. */
private val OnboardingErrorColor = Color(0xFFF87171)

/**
 * Full-screen onboarding frame: NightHi background with a subtle
 * top-left → bottom-right StageHi → StageLo gradient, and a centered content
 * panel capped at ~640.dp with safe margins. No nav rail, no step dots.
 */
@Composable
fun OnboardingScreenShell(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(RommTvColors.StageHi, RommTvColors.StageLo),
                    start = Offset.Zero,
                    end = Offset(1400f, 1400f),
                ),
            )
            .padding(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.widthIn(max = 640.dp)) {
            content()
        }
    }
}

/**
 * Primary CTA button for the onboarding flow.
 *
 * - Min 48.dp height, stable width between idle and loading so layout doesn't
 *   jump.
 * - Focused/pressed/disabled states use the RomM accent treatment.
 * - While [loading], shows [loadingText] plus an indeterminate progress
 *   indicator, exposes [ProgressBarRangeInfo.Indeterminate] semantics, and
 *   ignores activation (no double-submit).
 */
@Composable
fun OnboardingPrimaryButton(
    text: String,
    loadingText: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val containerColor = when {
        !enabled || loading -> RommTvColors.NightLo
        isPressed -> RommTvColors.Romm600
        isFocused -> RommTvColors.Romm500
        else -> RommTvColors.StageLo
    }

    Box(
        modifier = modifier
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .then(
                if (testTag != null) {
                    Modifier.testTag(testTag)
                } else {
                    Modifier
                },
            )
            .widthIn(min = 240.dp)
            .heightIn(min = 48.dp)
            .background(containerColor, RoundedCornerShape(8.dp))
            .then(
                if (isFocused && enabled && !loading) {
                    Modifier.border(2.dp, RommTvColors.Romm500, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !loading,
                onClick = onClick,
            )
            .padding(horizontal = 32.dp, vertical = 12.dp)
            .semantics {
                role = Role.Button
                if (loading) {
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = RommTvColors.Romm500,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = loadingText,
                    color = RommTvColors.TextPrimary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        } else {
            Text(
                text = text,
                color = if (enabled) RommTvColors.TextPrimary else RommTvColors.TextSecondary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Inline error message: theme error color, error icon, polite live region,
 * and no focus capture.
 */
@Composable
fun showError(
    error: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = OnboardingErrorColor,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(
            text = error,
            color = OnboardingErrorColor,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
