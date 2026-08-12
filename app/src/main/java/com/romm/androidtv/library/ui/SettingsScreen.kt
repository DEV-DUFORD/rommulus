package com.romm.androidtv.library.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.romm.androidtv.library.ConnectionCheckState
import com.romm.androidtv.library.SettingsLoginState
import com.romm.androidtv.library.SettingsViewModel
import com.romm.androidtv.library.ui.TvSwitch
import com.romm.androidtv.platform.currentDeviceProfile

/**
 * Native Settings screen for the LibraryScaffold sidebar (SETTINGS destination).
 *
 * Features:
 * - Editable RomM server origin with real-time validation
 * - Save, Restore Default, Check Connection actions
 * - Inline success/error feedback
 * - Read-only session info (username) and app version
 * - D-pad navigable via focusRequester chain + semantic onClick
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModelFactory: SettingsViewModel.Factory,
    onOpenSegaCdBios: () -> Unit = {},
    onOpenPlayStationBios: () -> Unit = {},
    onOpenControllerSettings: () -> Unit = {},
    onOpenOnScreenControllerSettings: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)

    val uiState by viewModel.uiState.collectAsState()

    val focusManager = LocalFocusManager.current
    val originFieldFocusRequester = remember { FocusRequester() }
    val checkConnectionFocusRequester = remember { FocusRequester() }
    val saveFocusRequester = remember { FocusRequester() }
    val restoreDefaultFocusRequester = remember { FocusRequester() }
    val usernameFieldFocusRequester = remember { FocusRequester() }
    val passwordFieldFocusRequester = remember { FocusRequester() }
    val loginFocusRequester = remember { FocusRequester() }
    val filterToggleFocusRequester = remember { FocusRequester() }
    val controllerSettingsFocusRequester = remember { FocusRequester() }
    val onScreenControllerSettingsFocusRequester = remember { FocusRequester() }
    val segaCdFocusRequester = remember { FocusRequester() }
    val playStationFocusRequester = remember { FocusRequester() }
    val verifySha1FocusRequester = remember { FocusRequester() }
    val autocleanFocusRequester = remember { FocusRequester() }
    val onScreenControlsFocusRequester = remember { FocusRequester() }
    val themeFocusRequester = remember { FocusRequester() }
    val licensesFocusRequester = remember { FocusRequester() }
    val privacyFocusRequester = remember { FocusRequester() }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var privacyOpenError by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val profile = currentDeviceProfile()

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        originFieldFocusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RommTvColors.NightHi)
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
    ) {
        // ---- Title ----
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = RommTvColors.TextPrimary,
            modifier = Modifier.padding(bottom = 24.dp),
        )

        // ---- Server section ----
        Text(
            text = "Server",
            style = MaterialTheme.typography.titleLarge,
            color = RommTvColors.Romm300,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        ControllerFriendlyTextField(
            value = uiState.originText,
            onValueChange = viewModel::onOriginTextChanged,
            label = { Text("Server address", color = RommTvColors.TextSecondary) },
            placeholder = { Text("https://romm.example.com", color = RommTvColors.TextSecondary) },
            isError = uiState.validationError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.moveFocus(FocusDirection.Down) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(originFieldFocusRequester),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = RommTvColors.NightLo,
                unfocusedContainerColor = RommTvColors.NightLo,
                focusedIndicatorColor = if (uiState.validationError != null)
                    Color(0xFFf44336) else RommTvColors.Romm500,
                unfocusedIndicatorColor = RommTvColors.TextSecondary.copy(alpha = 0.3f),
            ),
            touchEditEnabled = profile.hasTouchscreen,
        )

        // Validation error
        uiState.validationError?.let { error ->
            Row(
                modifier = Modifier.padding(top = 4.dp, start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null,
                    tint = Color(0xFFf44336),
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(text = error, color = Color(0xFFf44336), style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- Action buttons row ----
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SettingsActionButton(
                label = "Check Connection",
                focusRequester = checkConnectionFocusRequester,
                isLoading = uiState.connectionCheck is ConnectionCheckState.Loading,
                onClick = viewModel::onCheckConnection,
            )

            TvButton(
                onClick = viewModel::onSave,
                modifier = Modifier.focusRequester(saveFocusRequester),
            ) {
                Text("Save")
            }

            TvOutlinedButton(
                onClick = viewModel::onRestoreDefault,
                modifier = Modifier.focusRequester(restoreDefaultFocusRequester),
            ) {
                Text("Restore Default")
            }
        }

        // Connection check result
        when (uiState.connectionCheck) {
            is ConnectionCheckState.Idle -> {}
            is ConnectionCheckState.Loading -> {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                    CircularProgressIndicator(
                        color = RommTvColors.Romm500,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Checking connection…", color = RommTvColors.TextSecondary)
                }
            }
            is ConnectionCheckState.Success -> {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4caf50),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    val version = (uiState.connectionCheck as ConnectionCheckState.Success).version
                    Text(
                        text = if (version != null) "Connected — server $version" else "Connected",
                        color = Color(0xFF4caf50),
                    )
                }
            }
            is ConnectionCheckState.Error -> {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = Color(0xFFf44336),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(
                        text = (uiState.connectionCheck as ConnectionCheckState.Error).message,
                        color = Color(0xFFf44336),
                    )
                }
            }
        }

        // Save feedback
        uiState.saveSuccessMessage?.let { msg ->
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4caf50),
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(text = msg, color = Color(0xFF4caf50))
            }
        }

        uiState.saveErrorMessage?.let { msg ->
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null,
                    tint = Color(0xFFf44336),
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(text = msg, color = Color(0xFFf44336))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ---- Session section (username/password + login) ----
        Text(
            text = "Session",
            style = MaterialTheme.typography.titleLarge,
            color = RommTvColors.Romm300,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        SettingsInfoRow(
            label = "Username",
            value = uiState.currentUsername ?: "(not logged in)",
        )

        Spacer(modifier = Modifier.height(12.dp))

        ControllerFriendlyTextField(
            value = uiState.usernameText,
            onValueChange = viewModel::onUsernameTextChanged,
            label = { Text("Username", color = RommTvColors.TextSecondary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(usernameFieldFocusRequester),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = RommTvColors.NightLo,
                unfocusedContainerColor = RommTvColors.NightLo,
                focusedIndicatorColor = RommTvColors.Romm500,
                unfocusedIndicatorColor = RommTvColors.TextSecondary.copy(alpha = 0.3f),
            ),
            touchEditEnabled = profile.hasTouchscreen,
        )

        Spacer(modifier = Modifier.height(8.dp))

        ControllerFriendlyTextField(
            value = uiState.passwordText,
            onValueChange = viewModel::onPasswordTextChanged,
            label = { Text("Password", color = RommTvColors.TextSecondary) },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { viewModel.onLogin() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passwordFieldFocusRequester),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = RommTvColors.NightLo,
                unfocusedContainerColor = RommTvColors.NightLo,
                focusedIndicatorColor = RommTvColors.Romm500,
                unfocusedIndicatorColor = RommTvColors.TextSecondary.copy(alpha = 0.3f),
            ),
            touchEditEnabled = profile.hasTouchscreen,
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingsActionButton(
            label = "Log In",
            focusRequester = loginFocusRequester,
            isLoading = uiState.loginState is SettingsLoginState.Loading,
            onClick = viewModel::onLogin,
            modifier = Modifier.focusProperties { down = filterToggleFocusRequester },
        )

        // Login feedback
        when (val loginState = uiState.loginState) {
            is SettingsLoginState.Success -> {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4caf50),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text("Logged in", color = Color(0xFF4caf50))
                }
            }
            is SettingsLoginState.Error -> {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = Color(0xFFf44336),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(text = loginState.message, color = Color(0xFFf44336))
                }
            }
            else -> {}
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ---- Library section ----
        Text(
            text = "Library",
            style = MaterialTheme.typography.titleLarge,
            color = RommTvColors.Romm300,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Hide unsupported-system games", color = RommTvColors.TextPrimary)
                Text(
                    text = "Hide games on platforms with no native emulator core yet, instead of showing them as unsupported",
                    style = MaterialTheme.typography.bodySmall,
                    color = RommTvColors.TextSecondary,
                )
            }
            TvSwitch(
                checked = uiState.hideUnsupportedSystems,
                onCheckedChange = viewModel::onHideUnsupportedSystemsChanged,
                modifier = Modifier
                    .focusRequester(filterToggleFocusRequester)
                    .focusProperties {
                        up = loginFocusRequester
                        down = themeFocusRequester
                    },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ---- Theme section ----
        Text(
            text = "Theme",
            style = MaterialTheme.typography.titleLarge,
            color = RommTvColors.Romm300,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = "Choose the look and feel of the app.",
            style = MaterialTheme.typography.bodySmall,
            color = RommTvColors.TextSecondary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        TvButton(
            onClick = { showThemeDialog = true },
            modifier = Modifier
                .focusRequester(themeFocusRequester)
                .focusProperties {
                    up = filterToggleFocusRequester
                    down = controllerSettingsFocusRequester
                },
        ) {
            Text("Change Theme")
        }

        Text(
            text = "Current: ${uiState.activeTheme.displayName}",
            style = MaterialTheme.typography.bodySmall,
            color = RommTvColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (showThemeDialog) {
            ThemePickerDialog(
                current = uiState.activeTheme,
                themes = RommTheme.entries,
                onSelect = { theme ->
                    viewModel.onThemeSelected(theme)
                    showThemeDialog = false
                },
                onDismiss = { showThemeDialog = false },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Controllers",
            style = MaterialTheme.typography.titleLarge,
            color = RommTvColors.Romm300,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = "Customize controller layouts for each console.",
            style = MaterialTheme.typography.bodySmall,
            color = RommTvColors.TextSecondary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        TvButton(
            onClick = onOpenControllerSettings,
            modifier = Modifier
                .focusRequester(controllerSettingsFocusRequester)
                .focusProperties {
                    up = themeFocusRequester
                    down = if (profile.hasTouchscreen) {
                        onScreenControllerSettingsFocusRequester
                    } else {
                        segaCdFocusRequester
                    }
                },
        ) {
            Text("Controller Settings")
        }

        if (profile.hasTouchscreen) {
            Spacer(modifier = Modifier.height(8.dp))
            TvButton(
                onClick = onOpenOnScreenControllerSettings,
                modifier = Modifier
                    .focusRequester(onScreenControllerSettingsFocusRequester)
                    .focusProperties {
                        up = controllerSettingsFocusRequester
                        down = segaCdFocusRequester
                    },
            ) {
                Text("On-Screen Controller Settings")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "BIOS Configuration",
            style = MaterialTheme.typography.titleLarge,
            color = RommTvColors.Romm300,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = "Select BIOS files for systems that require them. Files are downloaded securely from your RomM server.",
            style = MaterialTheme.typography.bodySmall,
            color = RommTvColors.TextSecondary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        TvOutlinedButton(
            onClick = onOpenSegaCdBios,
            modifier = Modifier
                .focusRequester(segaCdFocusRequester)
                .focusProperties {
                    up = if (profile.hasTouchscreen) {
                        onScreenControllerSettingsFocusRequester
                    } else {
                        controllerSettingsFocusRequester
                    }
                    down = playStationFocusRequester
                },
        ) {
            Text("Sega CD")
        }
        Spacer(modifier = Modifier.height(8.dp))
        TvOutlinedButton(
            onClick = onOpenPlayStationBios,
            modifier = Modifier
                .focusRequester(playStationFocusRequester)
                .focusProperties {
                    up = segaCdFocusRequester
                    down = verifySha1FocusRequester
                },
        ) {
            Text("PlayStation")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ---- Advanced section ----
        Text(
            text = "Advanced",
            style = MaterialTheme.typography.titleLarge,
            color = RommTvColors.Romm300,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Verify ROM integrity (SHA-1) on launch", color = RommTvColors.TextPrimary)
                Text(
                    text = "Re-check each ROM's SHA-1 hash before playing and refuse to launch on a mismatch. " +
                        "Off by default — most libraries never need this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RommTvColors.TextSecondary,
                )
            }
            TvSwitch(
                checked = uiState.verifySha1OnLaunch,
                onCheckedChange = viewModel::onVerifySha1OnLaunchChanged,
                modifier = Modifier
                    .focusRequester(verifySha1FocusRequester)
                    .focusProperties {
                        up = playStationFocusRequester
                        down = autocleanFocusRequester
                    },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Auto-clean uploaded saves", color = RommTvColors.TextPrimary)
                Text(
                    text = "Ask the server to keep only the most recent 5 files in the autosave slot after " +
                        "an upload, instead of keeping every copy on exit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RommTvColors.TextSecondary,
                )
            }
            TvSwitch(
                checked = uiState.autocleanSavesOnUpload,
                onCheckedChange = viewModel::onAutocleanSavesOnUploadChanged,
                modifier = Modifier
                    .focusRequester(autocleanFocusRequester)
                    .focusProperties {
                        up = verifySha1FocusRequester
                        down = if (profile.hasTouchscreen) {
                            onScreenControlsFocusRequester
                        } else {
                            licensesFocusRequester
                        }
                    },
            )
        }

        if (profile.hasTouchscreen) {
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "On-screen game controls", color = RommTvColors.TextPrimary)
                    Text(
                        text = "Show touch controls during gameplay. Disable if you use a Bluetooth or USB controller.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RommTvColors.TextSecondary,
                    )
                }
                TvSwitch(
                    checked = uiState.onScreenGameControlsEnabled,
                    onCheckedChange = viewModel::onOnScreenGameControlsChanged,
                    modifier = Modifier
                        .focusRequester(onScreenControlsFocusRequester)
                        .focusProperties {
                            up = autocleanFocusRequester
                            down = licensesFocusRequester
                        },
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ---- App info section (read-only) ----
        Text(
            text = "App",
            style = MaterialTheme.typography.titleLarge,
            color = RommTvColors.Romm300,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        SettingsInfoRow(
            label = "Version",
            value = uiState.appVersion,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Open-source licenses: a fused list of Google's auto-generated notices for every
        // Gradle/transitive dependency plus the vendored libretro core notices (assets),
        // rendered in a single "Open Source Licenses" dialog (Play Store attribution).
            TvButton(
                onClick = { showLicensesDialog = true },
                modifier = Modifier
                    .focusRequester(licensesFocusRequester)
                    .focusProperties {
                        up = if (profile.hasTouchscreen) {
                            onScreenControlsFocusRequester
                        } else {
                            autocleanFocusRequester
                        }
                        down = privacyFocusRequester
                    },
            ) {
            Text("View Licenses")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TvOutlinedButton(
            onClick = {
                privacyOpenError = false
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_NOTICE_URL))
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    privacyOpenError = true
                }
            },
            modifier = Modifier
                .focusRequester(privacyFocusRequester)
                .focusProperties { up = licensesFocusRequester },
        ) {
            Text("Privacy Notice")
        }

        Text(
            text = PRIVACY_NOTICE_URL,
            style = MaterialTheme.typography.bodySmall,
            color = RommTvColors.TextSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (privacyOpenError) {
            Text(
                text = "No web browser is installed. Open the address above on another device.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFf44336),
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (showLicensesDialog) {
            LicensesDialog(onDismiss = { showLicensesDialog = false })
        }
    }
}

private const val PRIVACY_NOTICE_URL = "https://dev-duford.github.io/rommulus/privacy/"

/** A button that shows a loading spinner when [isLoading] is true. */
@Composable
private fun SettingsActionButton(
    label: String,
    focusRequester: FocusRequester,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvOutlinedButton(
        onClick = onClick,
        enabled = !isLoading,
        modifier = modifier.focusRequester(focusRequester),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = RommTvColors.Romm500,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(label)
    }
}

/** A read-only label/value row for informational settings. */
@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = RommTvColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(text = value, color = RommTvColors.TextPrimary, modifier = Modifier.weight(1f))
    }
}

/**
 * Dialog listing the selectable [themes]. Selecting one applies it immediately
 * (theme updates in place) and dismisses the dialog. Mirrors the focus/back
 * handling of GameDetailErrorAlert in CollectionPickerDialog.kt.
 */
@Composable
private fun ThemePickerDialog(
    current: RommTheme,
    themes: List<RommTheme>,
    onSelect: (RommTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocusRequester = remember { FocusRequester() }
    var ready by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        BackHandler { onDismiss() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(RommTvColors.NightHi)
                .padding(24.dp),
        ) {
            Text(
                text = "Theme",
                style = MaterialTheme.typography.titleLarge,
                color = RommTvColors.Romm300,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            themes.forEachIndexed { index, theme ->
                val isSelected = theme == current
                val button: @Composable RowScope.() -> Unit = {
                    Text(theme.displayName)
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Current theme",
                            tint = RommTvColors.Romm300,
                        )
                    }
                }
                if (isSelected) {
                    TvButton(
                        onClick = { onSelect(theme) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .then(
                                if (index == 0) Modifier.focusRequester(firstFocusRequester)
                                    .onGloballyPositioned { ready = true }
                                else Modifier,
                            ),
                        content = button,
                    )
                } else {
                    TvOutlinedButton(
                        onClick = { onSelect(theme) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .then(
                                if (index == 0) Modifier.focusRequester(firstFocusRequester)
                                    .onGloballyPositioned { ready = true }
                                else Modifier,
                            ),
                        content = button,
                    )
                }
            }
            Text(
                text = "The theme updates immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = RommTvColors.TextSecondary,
            )
        }
    }
    LaunchedEffect(ready) {
        if (ready) firstFocusRequester.requestFocus()
    }
}
