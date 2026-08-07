package com.romm.androidtv.library.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings

/** One destination reachable from the [NavRail]. */
enum class NavDestination(val label: String) {
    HOME("Home"),
    PLATFORMS("Platforms"),
    COLLECTIONS("Collections"),
    SEARCH("Search"),
    SETTINGS("Settings"),
}

private val navIcons: Map<NavDestination, ImageVector> = mapOf(
    NavDestination.HOME to Icons.Filled.Home,
    NavDestination.PLATFORMS to Icons.Filled.Apps,
    NavDestination.COLLECTIONS to Icons.Filled.Collections,
    NavDestination.SEARCH to Icons.Filled.Search,
    NavDestination.SETTINGS to Icons.Filled.Settings,
)

private val CollapsedRailWidth = 72.dp
private val ExpandedRailWidth = 200.dp

/**
 * Shared top-level scaffold for the four sidebar-navigable native screens
 * (Home/Platforms/Collections/Search). Fixes two bugs found on-device
 * (UI_REFACTOR.md section 7.1): the sidebar was previously only present on
 * the Home screen (Platforms/Collections/Search were bare full-screen
 * composables with no way to navigate away except Back), and the sidebar's
 * selected-item highlight was hardcoded to Home regardless of the actual
 * current screen. Detail screens (platform/collection/game detail) are
 * intentionally NOT wrapped in this scaffold — like Leanback detail
 * fragments, they are full-bleed "drill-down" screens reached via Select,
 * not sibling destinations reachable via the sidebar.
 */
@Composable
fun LibraryScaffold(
    current: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var railHasFocus by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState(
        targetValue = if (railHasFocus) ExpandedRailWidth else CollapsedRailWidth,
        animationSpec = tween(durationMillis = 160),
        label = "navRailWidth",
    )
    val contentOffset = railWidth - CollapsedRailWidth

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = CollapsedRailWidth)
                .offset(x = contentOffset),
        ) {
            content()
        }
        NavRail(
            selected = current,
            icons = navIcons,
            onSelect = onNavigate,
            expanded = railHasFocus,
            width = railWidth,
            onExpandedChange = { railHasFocus = it },
        )
    }
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
    expanded: Boolean,
    width: androidx.compose.ui.unit.Dp,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val labelAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "navRailLabelAlpha",
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .zIndex(1f)
            .clipToBounds()
            .background(RommTvColors.StageHi)
            .onFocusChanged { onExpandedChange(it.hasFocus) }
            .onPreviewKeyEvent { event ->
                if (expanded && event.key == Key.Back && event.type == KeyEventType.KeyDown) {
                    if (!focusManager.moveFocus(FocusDirection.Right)) {
                        focusManager.clearFocus()
                    }
                    true
                } else {
                    false
                }
            }
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        NavDestination.entries.forEach { destination ->
            NavRailItem(
                destination = destination,
                icon = icons.getValue(destination),
                labelAlpha = labelAlpha,
                isSelected = destination == selected,
                onClick = {
                    onSelect(destination)
                    if (destination != NavDestination.SETTINGS) {
                        view.postOnAnimation {
                            view.postOnAnimation {
                                focusManager.clearFocus(force = true)
                                focusManager.moveFocus(FocusDirection.Next)
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun NavRailItem(
    destination: NavDestination,
    icon: ImageVector,
    labelAlpha: Float,
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
        Text(
            text = destination.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (accent) RommTvColors.TextPrimary else RommTvColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 12.dp)
                .alpha(labelAlpha),
        )
    }
}
