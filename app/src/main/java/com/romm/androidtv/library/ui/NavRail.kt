package com.romm.androidtv.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One destination reachable from the [NavRail]. */
enum class NavDestination(val label: String) {
    HOME("Home"),
    PLATFORMS("Platforms"),
    COLLECTIONS("Collections"),
    SEARCH("Search"),
    SETTINGS("Settings"),
}

/**
 * Collapsible left navigation rail, styled after the standard Android TV /
 * Leanback "headers panel" pattern (as used by apps like Jellyfin): a narrow
 * icon-only rail by default that widens to show labels once the rail (or any
 * item in it) has D-pad focus, rather than a permanently-expanded tvOS-style
 * sidebar.
 */
@Composable
fun NavRail(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    icons: Map<NavDestination, ImageVector>,
    modifier: Modifier = Modifier,
) {
    var railHasFocus by remember { mutableStateOf(false) }
    val railWidth = if (railHasFocus) 200.dp else 72.dp

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(railWidth)
            .background(RommTvColors.StageHi)
            .onFocusChanged { railHasFocus = it.hasFocus }
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        NavDestination.entries.forEach { destination ->
            NavRailItem(
                destination = destination,
                icon = icons.getValue(destination),
                expanded = railHasFocus,
                isSelected = destination == selected,
                onClick = { onSelect(destination) },
            )
        }
    }
}

@Composable
private fun NavRailItem(
    destination: NavDestination,
    icon: ImageVector,
    expanded: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accent = isFocused || isSelected

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) RommTvColors.Romm500 else if (isSelected) RommTvColors.Romm600.copy(alpha = 0.4f) else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = destination.label,
            tint = if (accent) RommTvColors.TextPrimary else RommTvColors.TextSecondary,
        )
        if (expanded) {
            Text(
                text = destination.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (accent) RommTvColors.TextPrimary else RommTvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
