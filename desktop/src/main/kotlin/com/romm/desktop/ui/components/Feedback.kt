package com.romm.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.romm.desktop.ui.components.LocalRommulusColors

/** Error text color with WCAG AA contrast against the NightHi background. */
private val FeedbackErrorColor = Color(0xFFF87171)

/**
 * Inline error banner: theme error color, error icon, polite live region.
 * Suitable for placement below form fields or at the top of screens.
 */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Error indicator: the same Outlined.ErrorOutline icon Android's onboarding showError uses.
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = FeedbackErrorColor,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(
            text = message,
            color = FeedbackErrorColor,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Centered loading indicator: a small indeterminate circular progress
 * spinner in the theme accent color, with accessible role=progressbar.
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .widthIn(max = 400.dp)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = LocalRommulusColors.current.romm500,
            modifier = Modifier.size(32.dp),
        )
    }
}
