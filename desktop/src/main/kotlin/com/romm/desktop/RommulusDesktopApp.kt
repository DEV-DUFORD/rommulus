package com.romm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.romm.androidtv.library.LibraryResult
import com.romm.androidtv.library.RomQuery
import com.romm.androidtv.onboarding.OnboardingRoutingDecision.AppMode
import com.romm.desktop.controller.DesktopControllerRouter
import com.romm.desktop.controller.FocusAction
import com.romm.desktop.controller.JInputControllerSource
import com.romm.desktop.ui.components.RommulusTheme
import com.romm.desktop.ui.navigation.DesktopFocusScope
import com.romm.desktop.ui.navigation.FocusNavigator
import com.romm.desktop.ui.screens.detail.BiosConfigurationScreen
import com.romm.desktop.ui.screens.detail.GameDetailScreen
import com.romm.desktop.ui.screens.detail.LicensesDialog
import com.romm.desktop.ui.screens.detail.SettingsScreen
import com.romm.desktop.ui.screens.library.HomeScreen
import com.romm.desktop.ui.screens.library.RomGridScreen
import com.romm.desktop.ui.screens.library.SearchScreen
import com.romm.desktop.ui.screens.onboarding.OnboardingScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Compose Desktop shell (plans/LINUX_X64.md §8.1): owns the app-wide [RommulusTheme],
 * the top-level [AppMode] gate, single-[Screen] routing to the real screens, the keyboard
 * shortcuts, and the JInput controller router.
 *
 * Theme: the shell wraps everything in [RommulusTheme] with the active theme read from
 * `coordinator.settingsAdapter.currentTheme` (observable), so picking a theme in Settings
 * re-themes the whole app in place — screens must NOT self-wrap anymore.
 *
 * Controller focus navigation: one shared [FocusNavigator] is provided to every screen via
 * [DesktopFocusScope]/[com.romm.desktop.ui.navigation.LocalFocusNavigator]. The
 * [DesktopControllerRouter] (JInput, ~60 Hz poll) emits platform-neutral [FocusAction]s from
 * the primary controller; this shell maps them onto the navigator:
 *  - `Move(dir)` → [FocusNavigator.moveFocus] (D-pad / left-stick moves focus)
 *  - `Activate`  → [FocusNavigator.activateFocused] (A presses the focused item's action)
 *  - `Back`      → [DesktopAppCoordinator.onBack] (B / Escape semantics)
 *
 * Keyboard (plans/LINUX_X64.md §8.1):
 *  - Escape → back (parent-based; at HOME it requests exit)
 *  - Ctrl+F → Search screen
 *  - Ctrl+Q → request shutdown
 *
 * @param coordinator the fully-wired [DesktopAppCoordinator].
 * @param onCloseRequest invoked when the shell decides the window should close (Ctrl+Q or
 *                        Escape at the root screen).
 */
@Composable
fun RommulusDesktopApp(
    coordinator: DesktopAppCoordinator,
    onCloseRequest: () -> Unit,
) {
    // Exit-on-request: when the coordinator flags a root exit, close the window.
    LaunchedEffect(coordinator.exitRequested) {
        if (coordinator.exitRequested) onCloseRequest()
    }

    // ── JInput controller router: created once; poll loop lives on the composition scope ──
    val pollScope = rememberCoroutineScope()
    val router = remember { DesktopControllerRouter(JInputControllerSource(), pollScope) }
    DisposableEffect(router) {
        router.start()
        onDispose { router.stop() }
    }

    // ── Shared focus navigator: the single navigation authority for controller input ──
    val focusNavigator = remember { FocusNavigator() }

    LaunchedEffect(router, focusNavigator) {
        router.focusActions.collect { action ->
            when (action) {
                is FocusAction.Move -> focusNavigator.moveFocus(action.direction.toComposeDirection())
                FocusAction.Activate -> focusNavigator.activateFocused()
                FocusAction.Back -> coordinator.onBack()
            }
        }
    }

    // Shell-owned theme: reading the settings adapter's observable state here makes the shell
    // re-compose (and re-theme the whole app) when the user picks a new theme in Settings.
    val theme = coordinator.settingsAdapter.currentTheme.value

    RommulusTheme(theme = theme) {
        DesktopFocusScope(navigator = focusNavigator) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Escape -> {
                                coordinator.onBack()
                                true
                            }
                            event.isCtrlPressed && event.key == Key.F -> {
                                coordinator.navigate(Screen.SEARCH)
                                true
                            }
                            event.isCtrlPressed && event.key == Key.Q -> {
                                onCloseRequest()
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                when (coordinator.appMode) {
                    AppMode.ONBOARDING -> OnboardingScreen(coordinator)

                    AppMode.MAIN -> when (coordinator.currentScreen) {
                        Screen.HOME -> HomeScreen(coordinator)

                        // The shared RomQuery model has no "all ROMs" variant; RecentlyAdded
                        // (created_at desc, no filter) is the full-library grid used for the
                        // top-level Platforms/Collections browse screens.
                        Screen.PLATFORMS -> RomGridScreen(
                            coordinator = coordinator,
                            title = "Platforms",
                            query = RomQuery.RecentlyAdded,
                        )

                        Screen.COLLECTIONS -> RomGridScreen(
                            coordinator = coordinator,
                            title = "Collections",
                            query = RomQuery.RecentlyAdded,
                        )

                        Screen.SEARCH -> SearchScreen(coordinator)
                        Screen.SETTINGS -> SettingsScreen(coordinator)

                        Screen.PLATFORM_DETAIL -> {
                            val platformId = coordinator.selectedPlatformId
                            RomGridScreen(
                                coordinator = coordinator,
                                title = if (platformId != null) {
                                    platformDetailTitle(platformId, coordinator)
                                } else {
                                    "Platform"
                                },
                                query = platformId?.let { RomQuery.ByPlatform(it) }
                                    ?: RomQuery.RecentlyAdded,
                            )
                        }

                        Screen.COLLECTION_DETAIL -> {
                            val collectionId = coordinator.selectedCollectionId
                            RomGridScreen(
                                coordinator = coordinator,
                                title = if (collectionId != null) {
                                    collectionDetailTitle(collectionId, coordinator)
                                } else {
                                    "Collection"
                                },
                                query = collectionId?.let { RomQuery.ByCollection(it) }
                                    ?: RomQuery.RecentlyAdded,
                            )
                        }

                        Screen.GAME_DETAIL -> GameDetailScreen(coordinator)
                        Screen.BIOS_CONFIGURATION -> BiosConfigurationScreen(coordinator)

                        // The licenses dialog is a separate desktop window (its own
                        // composition); rendering it as the screen content is acceptable —
                        // the main window sits empty behind it while it is open.
                        Screen.LICENSE -> LicensesDialog(onDismiss = { coordinator.onBack() })

                        // Defensive: onboarding inside MAIN mode (should not occur — the
                        // AppMode gate owns that state).
                        Screen.ONBOARDING -> OnboardingScreen(coordinator)
                    }
                }
            }
        }
    }
}

/**
 * Resolves the display name for a platform-detail grid from the coordinator's selection,
 * falling back to "Platform" until (or unless) the server lookup resolves.
 */
@Composable
private fun platformDetailTitle(platformId: Long, coordinator: DesktopAppCoordinator): String {
    var title by remember(platformId) { mutableStateOf("Platform") }
    LaunchedEffect(platformId) {
        val result = withContext(Dispatchers.IO) {
            runCatching { coordinator.network.libraryRepository.fetchPlatforms() }.getOrNull()
        }
        if (result is LibraryResult.Success) {
            result.data.firstOrNull { it.id == platformId }?.let { title = it.displayName }
        }
    }
    return title
}

/**
 * Resolves the name for a collection-detail grid from the coordinator's selection, falling
 * back to "Collection" until (or unless) the server lookup resolves.
 */
@Composable
private fun collectionDetailTitle(collectionId: Long, coordinator: DesktopAppCoordinator): String {
    var title by remember(collectionId) { mutableStateOf("Collection") }
    LaunchedEffect(collectionId) {
        val result = withContext(Dispatchers.IO) {
            runCatching { coordinator.network.libraryRepository.fetchCollections() }.getOrNull()
        }
        if (result is LibraryResult.Success) {
            result.data.firstOrNull { it.id == collectionId }?.let { title = it.name }
        }
    }
    return title
}

/** Maps the router's controller directions onto Compose focus directions. */
private fun FocusAction.Direction.toComposeDirection(): FocusDirection = when (this) {
    FocusAction.Direction.UP -> FocusDirection.Up
    FocusAction.Direction.DOWN -> FocusDirection.Down
    FocusAction.Direction.LEFT -> FocusDirection.Left
    FocusAction.Direction.RIGHT -> FocusDirection.Right
}
