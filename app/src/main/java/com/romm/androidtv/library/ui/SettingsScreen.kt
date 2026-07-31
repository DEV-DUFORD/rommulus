package com.romm.androidtv.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.romm.androidtv.library.ConnectionCheckState
import com.romm.androidtv.library.SettingsLoginState
import com.romm.androidtv.library.SettingsViewModel

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

        TextField(
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

            Button(
                onClick = viewModel::onSave,
                modifier = Modifier.focusRequester(saveFocusRequester),
            ) {
                Text("Save")
            }

            OutlinedButton(
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

        TextField(
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
        )

        Spacer(modifier = Modifier.height(8.dp))

        TextField(
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
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingsActionButton(
            label = "Log In",
            focusRequester = loginFocusRequester,
            isLoading = uiState.loginState is SettingsLoginState.Loading,
            onClick = viewModel::onLogin,
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
    }
}

/** A button that shows a loading spinner when [isLoading] is true. */
@Composable
private fun SettingsActionButton(
    label: String,
    focusRequester: FocusRequester,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = !isLoading,
        modifier = Modifier.focusRequester(focusRequester),
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
