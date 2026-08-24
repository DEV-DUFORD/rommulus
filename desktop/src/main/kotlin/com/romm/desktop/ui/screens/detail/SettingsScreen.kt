package com.romm.desktop.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.romm.androidtv.library.ConnectionCheckState
import com.romm.androidtv.library.RommTheme
import com.romm.androidtv.library.SettingsLoginState
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.Screen
import com.romm.desktop.ui.components.DesktopTextField
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.RommDesktopPalette
import com.romm.desktop.ui.components.RommulusTheme
import com.romm.desktop.ui.components.TvButton
import com.romm.desktop.ui.components.TvOutlinedButton
import com.romm.desktop.ui.components.TvSwitch
import com.romm.desktop.ui.navigation.FocusNavigator
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.keyboardShortcuts
import java.net.URI

/** Privacy notice URL (mirrors the Android constant). */
private const val PRIVACY_NOTICE_URL = "https://dev-duford.github.io/rommulus/privacy/"

/** Inline success-feedback color (mirrors the Android 0xFF4caf50). */
private val SuccessGreen = Color(0xFF4CAF50)

/** Inline error-feedback color (mirrors the Android 0xFFf44336). */
private val ErrorRed = Color(0xFFF44336)

/**
 * Desktop Settings screen (Phase 6): a vertically scrolling column of settings sections
 * (Server, Session, Library, Theme, BIOS Configuration, Advanced, App) that drives the
 * shared [com.romm.androidtv.library.SettingsPresenter] obtained from the
 * [DesktopAppCoordinator] (`coordinator.settingsPresenter()`, remembered once per
 * composition).
 *
 * Mirrors the Android `app/.../library/ui/SettingsScreen.kt` layout/behavior, adapted:
 *  - [DesktopTextField] instead of [com.romm.androidtv.library.ui.ControllerFriendlyTextField]
 *    (no IME orchestration on desktop; Enter triggers the field's `onDone`);
 *  - [TvButton] / [TvOutlinedButton] / [TvSwitch] with `focusRequester` + `focusProperties`
 *    up/down chains so the full page is D-pad/arrow navigable (same pattern as the Android
 *    screen's focusRequester chain);
 *  - "View Licenses" navigates to [Screen.LICENSE] via `coordinator.navigate` (the Android
 *    fused-licenses dialog has no desktop equivalent yet);
 *  - "Privacy Notice" opens the URL with `java.awt.Desktop.browse` (no Android intents);
 *  - the Android "Controllers" section (controller-layout settings screens) and the
 *    touch-only "On-screen game controls" switch are omitted — the desktop coordinator has
 *    no controller-settings navigation and desktop has no touchscreen;
 *  - theming: owned by the shell — RommulusDesktopApp wraps the whole app in [RommulusTheme]
 *    with `coordinator.settingsAdapter.currentTheme` (live), so this screen does not wrap.
 *
 * Session invalidation needs no handling here: the coordinator's `onSessionInvalidated`
 * hook already routes the app back to onboarding. The Session section's "Log Out" button
 * calls [DesktopAppCoordinator.logout] (clears the durable client token + session record and
 * re-onboards) — Android has no confirmation dialog for this action, so neither does desktop.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    coordinator: DesktopAppCoordinator,
    modifier: Modifier = Modifier,
) {
    val presenter = remember { coordinator.settingsPresenter() }
    val uiState by presenter.uiState.collectAsState()
    // The Session section only offers "Log Out" while a session is active (a username is
    // recorded); the focus chain below skips the absent button when not logged in.
    val loggedIn = uiState.currentUsername != null
    var showThemeDialog by remember { mutableStateOf(false) }
    var restoreThemeFocus by remember { mutableStateOf(false) }
    var privacyOpenError by remember { mutableStateOf(false) }

    // Focus requesters — the D-pad/arrow navigation chain (mirrors the Android screen).
    val originFocus = remember { FocusRequester() }
    val checkConnectionFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val restoreDefaultFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val loginFocus = remember { FocusRequester() }
    val logoutFocus = remember { FocusRequester() }
    val hideUnsupportedFocus = remember { FocusRequester() }
    val themeFocus = remember { FocusRequester() }
    val segaCdFocus = remember { FocusRequester() }
    val playStationFocus = remember { FocusRequester() }
    val playStation2Focus = remember { FocusRequester() }
    val controllerSettingsFocus = remember { FocusRequester() }
    val keyboardSettingsFocus = remember { FocusRequester() }
    val verifySha1Focus = remember { FocusRequester() }
    val autocleanFocus = remember { FocusRequester() }
    val licensesFocus = remember { FocusRequester() }
    val privacyFocus = remember { FocusRequester() }

    LaunchedEffect(showThemeDialog, restoreThemeFocus) {
        if (!showThemeDialog && restoreThemeFocus) {
            themeFocus.requestFocus()
            restoreThemeFocus = false
        }
    }

    // Theming is owned by the shell (RommulusDesktopApp wraps the whole app in RommulusTheme
    // with coordinator.settingsAdapter.currentTheme, which updates live).
    val colors = LocalRommulusColors.current
    val navigator = LocalFocusNavigator.current

    // Shared between the Privacy Notice button's onClick and its controller activation action.
    val openPrivacyNotice: () -> Unit = {
        privacyOpenError = false
        try {
            java.awt.Desktop.getDesktop().browse(URI(PRIVACY_NOTICE_URL))
        } catch (_: Exception) {
            privacyOpenError = true
        }
    }

        // Land focus on the first interactive control (mirrors the Android screen).
        LaunchedEffect(Unit) {
            originFocus.requestFocus()
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(colors.nightHi)
                .keyboardShortcuts(
                    onBack = {
                        if (showThemeDialog) showThemeDialog = false else coordinator.onBack()
                    },
                    onSearch = { coordinator.navigate(Screen.SEARCH) },
                    onQuit = { /* window close is owned by the desktop shell */ },
                )
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
        ) {
            // ---- Title ----
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = 24.dp),
            )

            // ---- Server section ----
            SettingsSectionHeader("Server", colors)

            DesktopTextField(
                value = uiState.originText,
                onValueChange = presenter::onOriginTextChanged,
                label = "Server address",
                placeholder = "https://romm.example.com",
                onDone = { checkConnectionFocus.requestFocus() },
                modifier = Modifier
                    .focusRequester(originFocus)
                    .focusProperties {
                        up = privacyFocus
                        down = checkConnectionFocus
                    },
            )

            // Validation error (Android parity: Filled.Error icon + red text)
            uiState.validationError?.let { error ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp, start = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(text = error, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvOutlinedButton(
                    onClick = presenter::onCheckConnection,
                    enabled = uiState.connectionCheck !is ConnectionCheckState.Loading,
                    modifier = Modifier
                        .focusRequester(checkConnectionFocus)
                        .focusProperties {
                            up = originFocus
                            down = saveFocus
                        }
                        .focusableItem("settings:check-connection", navigator) {
                            if (uiState.connectionCheck !is ConnectionCheckState.Loading) presenter.onCheckConnection()
                        },
                ) {
                    if (uiState.connectionCheck is ConnectionCheckState.Loading) {
                        CircularProgressIndicator(
                            color = colors.romm500,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Text("Check Connection")
                }
                TvButton(
                    onClick = presenter::onSave,
                    modifier = Modifier
                        .focusRequester(saveFocus)
                        .focusProperties {
                            up = checkConnectionFocus
                            down = restoreDefaultFocus
                        }
                        .focusableItem("settings:save", navigator, presenter::onSave),
                ) {
                    Text("Save")
                }
                TvOutlinedButton(
                    onClick = presenter::onRestoreDefault,
                    modifier = Modifier
                        .focusRequester(restoreDefaultFocus)
                        .focusProperties {
                            up = saveFocus
                            down = usernameFocus
                        }
                        .focusableItem("settings:restore-default", navigator, presenter::onRestoreDefault),
                ) {
                    Text("Restore Default")
                }
            }

            // Connection check result
            when (val check = uiState.connectionCheck) {
                ConnectionCheckState.Idle -> Unit

                ConnectionCheckState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp, start = 16.dp),
                ) {
                    CircularProgressIndicator(color = colors.romm500, modifier = Modifier.padding(end = 8.dp))
                    Text("Checking connection…", color = colors.textSecondary)
                }

                is ConnectionCheckState.Success ->
                    SettingsFeedbackText(
                        message = if (check.version != null) "Connected — server ${check.version}" else "Connected",
                        color = SuccessGreen,
                        icon = Icons.Filled.CheckCircle,
                    )

                is ConnectionCheckState.Error ->
                    SettingsFeedbackText(message = check.message, color = ErrorRed, icon = Icons.Filled.Error)
            }

            // Save feedback
            uiState.saveSuccessMessage?.let { msg ->
                SettingsFeedbackText(msg, SuccessGreen, Icons.Filled.CheckCircle)
            }
            uiState.saveErrorMessage?.let { msg ->
                SettingsFeedbackText(msg, ErrorRed, Icons.Filled.Error)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ---- Session section (username/password + login) ----
            SettingsSectionHeader("Session", colors)

            SettingsInfoRow("Username", uiState.currentUsername ?: "(not logged in)", colors)

            Spacer(modifier = Modifier.height(12.dp))

            DesktopTextField(
                value = uiState.usernameText,
                onValueChange = presenter::onUsernameTextChanged,
                label = "Username",
                onDone = { passwordFocus.requestFocus() },
                modifier = Modifier
                    .focusRequester(usernameFocus)
                    .focusProperties {
                        up = restoreDefaultFocus
                        down = passwordFocus
                    },
            )

            Spacer(modifier = Modifier.height(8.dp))

            DesktopTextField(
                value = uiState.passwordText,
                onValueChange = presenter::onPasswordTextChanged,
                label = "Password",
                isPassword = true,
                onDone = presenter::onLogin,
                modifier = Modifier
                    .focusRequester(passwordFocus)
                    .focusProperties {
                        up = usernameFocus
                        down = loginFocus
                    },
            )

            Spacer(modifier = Modifier.height(12.dp))

            TvButton(
                onClick = presenter::onLogin,
                enabled = uiState.loginState !is SettingsLoginState.Loading,
                modifier = Modifier
                    .focusRequester(loginFocus)
                    .focusProperties {
                        up = passwordFocus
                        down = if (loggedIn) logoutFocus else hideUnsupportedFocus
                    }
                    .focusableItem("settings:login", navigator) {
                        if (uiState.loginState !is SettingsLoginState.Loading) presenter.onLogin()
                    },
            ) {
                if (uiState.loginState is SettingsLoginState.Loading) {
                    CircularProgressIndicator(color = colors.romm500, modifier = Modifier.padding(end = 8.dp))
                }
                Text("Log In")
            }

            // Login feedback
            when (val loginState = uiState.loginState) {
                SettingsLoginState.Success ->
                    SettingsFeedbackText("Logged in", SuccessGreen, Icons.Filled.CheckCircle)
                is SettingsLoginState.Error ->
                    SettingsFeedbackText(loginState.message, ErrorRed, Icons.Filled.Error)
                else -> Unit
            }

            // Log Out (visible only while a session is active): clears the durable client token
            // + session record and re-onboards via the coordinator (mirrors Android's
            // clearSessionFn + onSessionInvalidated pair; no confirmation step, as on Android).
            if (loggedIn) {
                Spacer(modifier = Modifier.height(8.dp))
                TvOutlinedButton(
                    onClick = coordinator::logout,
                    modifier = Modifier
                        .focusRequester(logoutFocus)
                        .focusProperties {
                            up = loginFocus
                            down = hideUnsupportedFocus
                        }
                        .focusableItem("settings:logout", navigator) { coordinator.logout() },
                ) {
                    Text("Log Out")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---- Library section ----
            SettingsSectionHeader("Library", colors)
            SettingsSwitchRow(
                title = "Hide unsupported-system games",
                description = "Hide games on platforms with no native emulator core yet, instead of showing them as unsupported",
                checked = uiState.hideUnsupportedSystems,
                onCheckedChange = presenter::onHideUnsupportedSystemsChanged,
                focusRequester = hideUnsupportedFocus,
                upFocus = if (loggedIn) logoutFocus else loginFocus,
                downFocus = themeFocus,
                colors = colors,
                navigator = navigator,
                focusKey = "settings:hide-unsupported",
                onActivate = { presenter.onHideUnsupportedSystemsChanged(!uiState.hideUnsupportedSystems) },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ---- Theme section ----
            SettingsSectionHeader("Theme", colors)
            Text(
                text = "Choose the look and feel of the app.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            TvButton(
                onClick = { showThemeDialog = true },
                modifier = Modifier
                    .focusRequester(themeFocus)
                    .focusProperties {
                        up = hideUnsupportedFocus
                        down = segaCdFocus
                    }
                    .focusableItem("settings:theme", navigator) { showThemeDialog = true },
            ) {
                Text("Change Theme")
            }
            Text(
                text = "Current: ${uiState.activeTheme.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ---- BIOS Configuration section ----
            SettingsSectionHeader("BIOS Configuration", colors)
            Text(
                text = "Select BIOS files for systems that require them. Files are downloaded securely from your RomM server.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            TvOutlinedButton(
                onClick = { coordinator.openBiosConfiguration(DesktopAppCoordinator.BiosSystem.SEGA_CD) },
                modifier = Modifier
                    .focusRequester(segaCdFocus)
                    .focusProperties {
                        up = themeFocus
                        down = playStationFocus
                    }
                    .focusableItem("settings:sega-cd", navigator) {
                        coordinator.openBiosConfiguration(DesktopAppCoordinator.BiosSystem.SEGA_CD)
                    },
            ) {
                Text("Sega CD")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TvOutlinedButton(
                onClick = { coordinator.openBiosConfiguration(DesktopAppCoordinator.BiosSystem.PLAYSTATION) },
                modifier = Modifier
                    .focusRequester(playStationFocus)
                    .focusProperties {
                        up = segaCdFocus
                        down = playStation2Focus
                    }
                    .focusableItem("settings:playstation", navigator) {
                        coordinator.openBiosConfiguration(DesktopAppCoordinator.BiosSystem.PLAYSTATION)
                    },
            ) {
                Text("PlayStation")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TvOutlinedButton(
                onClick = { coordinator.openBiosConfiguration(DesktopAppCoordinator.BiosSystem.PLAYSTATION_2) },
                modifier = Modifier
                    .focusRequester(playStation2Focus)
                    .focusProperties {
                        up = playStationFocus
                        down = controllerSettingsFocus
                    }
                    .focusableItem("settings:playstation-2", navigator) {
                        coordinator.openBiosConfiguration(DesktopAppCoordinator.BiosSystem.PLAYSTATION_2)
                    },
            ) {
                Text("PlayStation 2")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---- Controllers section (E2: controller console list → per-core binding config) ----
            SettingsSectionHeader("Controllers", colors)
            Text(
                text = "Map physical controller buttons and axes to console controls for each supported system.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            TvButton(
                onClick = { coordinator.openControllerSettings() },
                modifier = Modifier
                    .focusRequester(controllerSettingsFocus)
                    .focusProperties {
                        up = playStation2Focus
                        down = keyboardSettingsFocus
                    }
                    .focusableItem("settings:controller-settings", navigator) {
                        coordinator.openControllerSettings()
                    },
            ) {
                Text("Controller Settings")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TvOutlinedButton(
                onClick = { coordinator.openKeyboardSettings() },
                modifier = Modifier
                    .focusRequester(keyboardSettingsFocus)
                    .focusProperties {
                        up = controllerSettingsFocus
                        down = verifySha1Focus
                    }
                    .focusableItem("settings:keyboard-settings", navigator) {
                        coordinator.openKeyboardSettings()
                    },
            ) {
                Text("Keyboard Control Settings")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---- Advanced section ----
            SettingsSectionHeader("Advanced", colors)
            SettingsSwitchRow(
                title = "Verify ROM integrity (SHA-1) on launch",
                description = "Re-check each ROM's SHA-1 hash before playing and refuse to launch on a mismatch. " +
                    "Off by default — most libraries never need this.",
                checked = uiState.verifySha1OnLaunch,
                onCheckedChange = presenter::onVerifySha1OnLaunchChanged,
                focusRequester = verifySha1Focus,
                upFocus = keyboardSettingsFocus,
                downFocus = autocleanFocus,
                colors = colors,
                navigator = navigator,
                focusKey = "settings:verify-sha1",
                onActivate = { presenter.onVerifySha1OnLaunchChanged(!uiState.verifySha1OnLaunch) },
            )

            Spacer(modifier = Modifier.height(24.dp))

            SettingsSwitchRow(
                title = "Auto-clean uploaded saves",
                description = "Ask the server to keep only the most recent 5 files in the autosave slot after " +
                    "an upload, instead of keeping every copy on exit.",
                checked = uiState.autocleanSavesOnUpload,
                onCheckedChange = presenter::onAutocleanSavesOnUploadChanged,
                focusRequester = autocleanFocus,
                upFocus = verifySha1Focus,
                downFocus = licensesFocus,
                colors = colors,
                navigator = navigator,
                focusKey = "settings:autoclean",
                onActivate = { presenter.onAutocleanSavesOnUploadChanged(!uiState.autocleanSavesOnUpload) },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ---- App section (read-only info + links) ----
            SettingsSectionHeader("App", colors)

            SettingsInfoRow("Version", uiState.appVersion, colors)

            Spacer(modifier = Modifier.height(24.dp))

            // Open-source licenses: navigate to the dedicated LICENSE screen (the Android
            // fused-licenses dialog has no desktop equivalent yet).
            TvButton(
                onClick = { coordinator.navigate(Screen.LICENSE) },
                modifier = Modifier
                    .focusRequester(licensesFocus)
                    .focusProperties {
                        up = autocleanFocus
                        down = privacyFocus
                    }
                    .focusableItem("settings:licenses", navigator) { coordinator.navigate(Screen.LICENSE) },
            ) {
                Text("View Licenses")
            }

            Spacer(modifier = Modifier.height(8.dp))

            TvOutlinedButton(
                onClick = openPrivacyNotice,
                modifier = Modifier
                    .focusRequester(privacyFocus)
                    .focusProperties {
                        up = licensesFocus
                        down = originFocus
                    }
                    .focusableItem("settings:privacy", navigator, openPrivacyNotice),
            ) {
                Text("Privacy Notice")
            }

            Text(
                text = PRIVACY_NOTICE_URL,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (privacyOpenError) {
                Text(
                    text = "No web browser is available. Open the address above on another device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

    if (showThemeDialog) {
        ThemePickerDialog(
            current = uiState.activeTheme,
            onSelect = { theme ->
                presenter.onThemeSelected(theme)
                showThemeDialog = false
                restoreThemeFocus = true
            },
            onDismiss = {
                showThemeDialog = false
                restoreThemeFocus = true
            },
        )
    }
}

// --------------------------------------------------------------------------- section pieces

/** Accent-colored section header (mirrors the Android section titles). */
@Composable
private fun SettingsSectionHeader(title: String, colors: RommDesktopPalette) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = colors.romm300,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * Inline success/error feedback line under an action (mirrors the Android feedback rows:
 * a [Icons.Filled.CheckCircle] for success and a [Icons.Filled.Error] for failure, tinted
 * with the row color).
 */
@Composable
private fun SettingsFeedbackText(message: String, color: Color, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 12.dp, start = 16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(text = message, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A read-only label/value row for informational settings. */
@Composable
private fun SettingsInfoRow(label: String, value: String, colors: RommDesktopPalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(text = value, color = colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** A tappable title/description row with a [TvSwitch] on the right (mirrors the Android rows). */
@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    upFocus: FocusRequester,
    downFocus: FocusRequester,
    colors: RommDesktopPalette,
    navigator: FocusNavigator,
    focusKey: String,
    onActivate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = colors.textPrimary)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        TvSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusProperties {
                    up = upFocus
                    down = downFocus
                }
                .focusableItem(focusKey, navigator, onActivate),
        )
    }
}

/**
 * Dialog listing the selectable [RommTheme]s. Selecting one applies it immediately
 * (theme updates in place) and dismisses the dialog. Mirrors the Android
 * ThemePickerDialog; Escape dismisses (the dialog is its own desktop window, so it
 * wires its own [keyboardShortcuts]).
 */
@Composable
private fun ThemePickerDialog(
    current: RommTheme,
    onSelect: (RommTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocusRequester = remember { FocusRequester() }

    // The dialog is a separate desktop window — provide the palette explicitly (nesting
    // RommulusTheme is harmless, same rationale as OnboardingScreen).
    RommulusTheme(theme = current) {
        val colors = LocalRommulusColors.current
        val navigator = LocalFocusNavigator.current
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            val dialogFocusManager = LocalFocusManager.current
            val focusOverrideOwner = remember { Any() }
            DisposableEffect(navigator, dialogFocusManager, focusOverrideOwner) {
                navigator.installSpatialFocusOverride(
                    focusOverrideOwner,
                    dialogFocusManager::moveFocus,
                    onDismiss,
                )
                onDispose { navigator.removeSpatialFocusOverride(focusOverrideOwner) }
            }

            Column(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxWidth()
                    .background(colors.nightHi)
                    .keyboardShortcuts(
                        onBack = onDismiss,
                        onSearch = { /* no search inside the dialog */ },
                        onQuit = { /* window close is owned by the desktop shell */ },
                    )
                    .padding(24.dp),
            ) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.romm300,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                RommTheme.entries.forEachIndexed { index, theme ->
                    val isSelected = theme == current
                    val buttonContent: @Composable RowScope.() -> Unit = {
                        Text(theme.displayName)
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "current",
                                color = colors.romm300,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    val buttonModifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .focusableItem("theme:${theme.name}", navigator) { onSelect(theme) }
                        .then(
                            if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier,
                        )
                    if (isSelected) {
                        TvButton(onClick = { onSelect(theme) }, modifier = buttonModifier, content = buttonContent)
                    } else {
                        TvOutlinedButton(onClick = { onSelect(theme) }, modifier = buttonModifier, content = buttonContent)
                    }
                }
                Text(
                    text = "The theme updates immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        firstFocusRequester.requestFocus()
    }
}
