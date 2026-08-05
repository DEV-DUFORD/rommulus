package com.romm.androidtv.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Sealed state for the Favorite action on the game-detail action rail.
 * Deliberately decoupled from any ViewModel type.
 */
sealed interface FavoriteRailState {
    data object Loading : FavoriteRailState
    data class Confirmed(val isFavorite: Boolean) : FavoriteRailState
    data class Updating(val previous: Boolean, val target: Boolean) : FavoriteRailState
}

private val buttonShape: Shape = RoundedCornerShape(12.dp)
private val unfocusedBorderColor = RommTvColors.TextSecondary.copy(alpha = 0.15f)
private val focusedFillColor = RommTvColors.Romm600.copy(alpha = 0.35f)

private data class FavoriteButtonConfig(
    val icon: ImageVector,
    val contentDescription: String,
    val showProgress: Boolean,
    val enabled: Boolean,
)

@Composable
private fun GameDetailIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showProgress: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val iconTint = when {
        !enabled -> RommTvColors.TextSecondary
        isFocused -> Color.White
        else -> RommTvColors.TextPrimary
    }

    val borderColor = if (isFocused) RommTvColors.Romm500 else unfocusedBorderColor
    val borderWidth = if (isFocused) 2.dp else 1.dp

    Box(
        modifier = modifier
            .size(48.dp)
            .background(
                color = if (isFocused && enabled) focusedFillColor else RommTvColors.NightLo,
                shape = buttonShape,
            )
            .border(
                border = BorderStroke(width = borderWidth, color = borderColor),
                shape = buttonShape,
            )
            .scale(if (isFocused && enabled) 1.06f else 1f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .then(if (!enabled) Modifier.alpha(0.4f) else Modifier)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = iconTint,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = iconTint,
            )
        }
    }
}

/**
 * Horizontal action rail for the game-detail screen.
 *
 * Renders three icon buttons (Favorite, Add to collection, Back).
 * The caller is responsible for positioning this rail (e.g. TopEnd overlay).
 */
@Composable
fun GameDetailActionRail(
    favoriteState: FavoriteRailState,
    onFavoriteClick: () -> Unit,
    onAddClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    favoriteFocusRequester: FocusRequester? = null,
    addFocusRequester: FocusRequester? = null,
    downFocusTarget: FocusRequester? = null,
    onScrollToTop: (() -> Unit)? = null,
) {
    val favConfig = when (favoriteState) {
        FavoriteRailState.Loading ->
            FavoriteButtonConfig(Icons.Filled.StarBorder, "Checking favorite status", true, false)
        is FavoriteRailState.Confirmed ->
            if (favoriteState.isFavorite) {
                FavoriteButtonConfig(Icons.Filled.Star, "Remove from favorites", false, true)
            } else {
                FavoriteButtonConfig(Icons.Filled.StarBorder, "Add to favorites", false, true)
            }
        is FavoriteRailState.Updating ->
            FavoriteButtonConfig(Icons.Filled.Star, "Updating favorite status", true, false)
    }

    Row(
        modifier = modifier
            .focusGroup()
            // The rail sits outside the content's LazyColumn (a fixed overlay), so
            // when Play/screenshot focus has scrolled the page down, focus moving
            // back up here doesn't naturally reveal the top of the page again. This
            // only nudges the scroll position back to the top; it never consumes the
            // event or touches focus, so normal up/down traversal (rail <-> Play <->
            // screenshots, etc.) elsewhere on the page is unaffected.
            .then(
                if (onScrollToTop != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            onScrollToTop()
                        }
                        false
                    }
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameDetailIconButton(
            icon = favConfig.icon,
            contentDescription = favConfig.contentDescription,
            onClick = onFavoriteClick,
            modifier = Modifier
                .then(favoriteFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(
                    if (downFocusTarget != null) {
                        Modifier.focusProperties { down = downFocusTarget }
                    } else {
                        Modifier
                    },
                ),
            enabled = favConfig.enabled,
            showProgress = favConfig.showProgress,
        )

        GameDetailIconButton(
            icon = Icons.Filled.Add,
            contentDescription = "Add to collection",
            onClick = onAddClick,
            modifier = Modifier
                .then(addFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(
                    if (downFocusTarget != null) {
                        Modifier.focusProperties { down = downFocusTarget }
                    } else {
                        Modifier
                    },
                ),
        )

        GameDetailIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            onClick = onBackClick,
            modifier = Modifier.then(
                if (downFocusTarget != null) {
                    Modifier.focusProperties { down = downFocusTarget }
                } else {
                    Modifier
                },
            ),
        )
    }
}
