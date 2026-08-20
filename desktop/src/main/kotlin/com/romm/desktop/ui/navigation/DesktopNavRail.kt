package com.romm.desktop.ui.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import com.romm.desktop.Screen
import com.romm.desktop.ui.components.LocalRommulusColors

internal enum class DesktopNavDestination(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
) {
    HOME(Screen.HOME, "Home", Icons.Filled.Home),
    PLATFORMS(Screen.PLATFORMS, "Platforms", Icons.Filled.Apps),
    COLLECTIONS(Screen.COLLECTIONS, "Collections", Icons.Filled.Collections),
    SEARCH(Screen.SEARCH, "Search", Icons.Filled.Search),
    SETTINGS(Screen.SETTINGS, "Settings", Icons.Filled.Settings),
    ;

    val focusKey: String
        get() = "nav:$name"
}

internal fun topLevelNavDestination(screen: Screen): DesktopNavDestination? =
    DesktopNavDestination.entries.firstOrNull { it.screen == screen }

internal fun libraryNavDestination(
    screen: Screen,
    gameDetailParent: Screen = Screen.HOME,
): DesktopNavDestination = when (screen) {
    Screen.HOME, Screen.ONBOARDING -> DesktopNavDestination.HOME
    Screen.PLATFORMS, Screen.PLATFORM_DETAIL -> DesktopNavDestination.PLATFORMS
    Screen.COLLECTIONS, Screen.COLLECTION_DETAIL -> DesktopNavDestination.COLLECTIONS
    Screen.SEARCH -> DesktopNavDestination.SEARCH
    Screen.SETTINGS, Screen.BIOS_CONFIGURATION, Screen.LICENSE,
    Screen.CONTROLLER_LIST, Screen.CONTROLLER_CONFIG -> DesktopNavDestination.SETTINGS
    Screen.GAME_DETAIL -> when (gameDetailParent) {
        Screen.GAME_DETAIL, Screen.ONBOARDING -> DesktopNavDestination.HOME
        else -> libraryNavDestination(gameDetailParent)
    }
}

/**
 * Desktop counterpart to Android's LibraryScaffold. The collapsible navigation rail remains
 * available throughout the main app, including browse and game detail screens.
 */
@Composable
fun DesktopLibraryScaffold(
    currentScreen: Screen,
    gameDetailParent: Screen = Screen.HOME,
    onNavigate: (Screen) -> Unit,
    content: @Composable () -> Unit,
) {
    val selected = libraryNavDestination(currentScreen, gameDetailParent)

    var railHasFocus by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState(
        targetValue = if (railHasFocus) 200.dp else 72.dp,
        animationSpec = tween(durationMillis = 160),
        label = "desktopNavRailWidth",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalRommulusColors.current.nightHi),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 72.dp)
                .offset(x = railWidth - 72.dp),
        ) {
            content()
        }
        DesktopNavRail(
            selected = selected,
            onNavigate = onNavigate,
            modifier = Modifier.width(railWidth),
            onFocusChanged = { railHasFocus = it },
        )
    }
}

@Composable
private fun DesktopNavRail(
    selected: DesktopNavDestination,
    onNavigate: (Screen) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalFocusNavigator.current
    val colors = LocalRommulusColors.current

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.stageHi)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .padding(horizontal = 10.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DesktopNavDestination.entries.forEach { destination ->
            var focused by remember(destination) { mutableStateOf(false) }
            val active = focused || destination == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            focused -> colors.romm500
                            destination == selected -> colors.romm600.copy(alpha = 0.4f)
                            else -> Color.Transparent
                        },
                    )
                    .onFocusChanged { focused = it.isFocused }
                    .focusableItem(destination.focusKey, navigator) {
                        onNavigate(destination.screen)
                    }
                    .clickable { onNavigate(destination.screen) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = destination.label,
                    tint = if (active) colors.textPrimary else colors.textSecondary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = destination.label,
                    color = if (active) colors.textPrimary else colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
    }
}
