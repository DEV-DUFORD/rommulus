package com.romm.androidtv.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.romm.androidtv.library.ui.ControllerFriendlyTextField
import com.romm.androidtv.library.ui.RommTvColors
import com.romm.androidtv.network.RommServerAddress
import com.romm.androidtv.network.ServerAddressResult
import com.romm.androidtv.onboarding.AsyncActionState
import com.romm.androidtv.onboarding.OnboardingLoginError
import com.romm.androidtv.onboarding.OnboardingServerError
import com.romm.androidtv.onboarding.OnboardingStep
import com.romm.androidtv.onboarding.OnboardingUiState
import com.romm.androidtv.R

/** Amber helper-text color with WCAG AA contrast against the dark background. */
private val AmberWarningColor = Color(0xFFFCD34D)

/**
 * Phase 4 root composable for the first-run onboarding flow.
 *
 * Renders one of the three steps based on [state.step], each inside an
 * [OnboardingScreenShell]. Back is handled here at the root: while a
 * [ControllerFriendlyTextField] is editing it consumes Back itself (no
 * step-back), otherwise the key bubbles up to this handler.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    modifier: Modifier = Modifier,
    onContinue: () -> Unit,
    onServerChanged: (String) -> Unit,
    onValidateServer: () -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit,
    onRemoveOldestDeviceAndRetry: () -> Unit,
    onRetryQrLogin: () -> Unit,
    onBack: () -> Unit,
) {
    val content: @Composable () -> Unit = when (state.step) {
        OnboardingStep.WELCOME -> { { WelcomeStep(onContinue = onContinue) } }
        OnboardingStep.SERVER -> {
            {
                ServerStep(
                    state = state,
                    onServerChanged = onServerChanged,
                    onValidateServer = onValidateServer,
                )
            }
        }
        OnboardingStep.CREDENTIALS -> {
            {
                CredentialsStep(
                    state = state,
                    onUsernameChanged = onUsernameChanged,
                    onPasswordChanged = onPasswordChanged,
                    onLogin = onLogin,
                    onRemoveOldestDeviceAndRetry = onRemoveOldestDeviceAndRetry,
                    onRetryQrLogin = onRetryQrLogin,
                )
            }
        }
    }

    OnboardingScreenShell(
        maxContentWidth = if (state.step == OnboardingStep.CREDENTIALS) 960.dp else 640.dp,
        modifier = modifier.onKeyEvent { event ->
            // Preview dispatch is handled by the editing field (Back while editing
            // is consumed there). This bubbling handler fires only when nothing
            // else consumed Back — i.e. not editing. Guard on KeyDown so an
            // ACTION_UP doesn't fire onBack a second time (a single Back press
            // produces both a KeyDown and a KeyUp key event).
            if (event.key == Key.Back && event.type == KeyEventType.KeyDown) {
                onBack()
                true
            } else {
                false
            }
        },
    ) {
        content()
    }
}

@Composable
private fun WelcomeStep(
    onContinue: () -> Unit,
) {
    val continueFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        continueFocus.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_welcome"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = R.raw.romm_logo,
            contentDescription = stringResource(R.string.onboarding_logo_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(160.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_headline),
            style = MaterialTheme.typography.headlineLarge,
            color = RommTvColors.TextPrimary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = RommTvColors.TextSecondary,
        )
        Spacer(modifier = Modifier.height(32.dp))
        OnboardingPrimaryButton(
            text = stringResource(R.string.onboarding_continue),
            loadingText = "",
            loading = false,
            enabled = true,
            onClick = onContinue,
            testTag = "onboarding_continue",
            focusRequester = continueFocus,
        )
    }
}

@Composable
private fun ServerStep(
    state: OnboardingUiState,
    onServerChanged: (String) -> Unit,
    onValidateServer: () -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Amber HTTP warning: show when the CURRENT input parses to an accepted
    // private-LAN HTTP origin. Pure + cheap; recomputed on every edit.
    val isPrivateLanHttp = remember(state.serverInput) {
        val parsed = RommServerAddress.parseAndNormalize(state.serverInput)
        parsed is ServerAddressResult.Valid && RommServerAddress.isPrivateLanHttp(parsed)
    }

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        fieldFocus.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_server"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_server_headline),
            style = MaterialTheme.typography.headlineMedium,
            color = RommTvColors.TextPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_server_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = RommTvColors.TextSecondary,
        )
        Spacer(modifier = Modifier.height(24.dp))

        ControllerFriendlyTextField(
            value = state.serverInput,
            onValueChange = onServerChanged,
            label = {
                Text(
                    text = stringResource(R.string.onboarding_server_label),
                    color = RommTvColors.TextSecondary,
                )
            },
            placeholder = {
                Text(
                    text = stringResource(R.string.onboarding_server_placeholder),
                    color = RommTvColors.TextSecondary,
                )
            },
            isError = state.serverError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onValidateServer() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_server_field")
                .focusRequester(fieldFocus),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = RommTvColors.NightLo,
                unfocusedContainerColor = RommTvColors.NightLo,
                focusedIndicatorColor = if (state.serverError != null) {
                    Color(0xFFF87171)
                } else {
                    RommTvColors.Romm500
                },
                unfocusedIndicatorColor = RommTvColors.TextSecondary.copy(alpha = 0.3f),
            ),
        )

        if (state.serverError == null) {
            Text(
                text = stringResource(R.string.onboarding_server_example),
                style = MaterialTheme.typography.bodySmall,
                color = RommTvColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (isPrivateLanHttp) {
            Text(
                text = stringResource(R.string.onboarding_server_http_warning),
                style = MaterialTheme.typography.bodySmall,
                color = AmberWarningColor,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        state.serverError?.let { error ->
            val message = when (error) {
                OnboardingServerError.InvalidAddress -> stringResource(R.string.onboarding_server_error_not_romm)
                OnboardingServerError.NotRomm -> stringResource(R.string.onboarding_server_error_not_romm)
                OnboardingServerError.SetupIncomplete -> stringResource(R.string.onboarding_server_error_setup)
                OnboardingServerError.UserpassDisabled -> stringResource(R.string.onboarding_server_error_userpass)
                OnboardingServerError.InsecurePublicHttp -> stringResource(R.string.onboarding_server_error_public_http)
                OnboardingServerError.PersistenceFailure -> stringResource(R.string.onboarding_server_error_persistence)
            }
            showError(
                error = message,
                modifier = Modifier.testTag("onboarding_server_error"),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        val loading = state.serverAction == AsyncActionState.Loading
        OnboardingPrimaryButton(
            text = stringResource(R.string.onboarding_next),
            loadingText = stringResource(R.string.onboarding_next_loading),
            loading = loading,
            enabled = true,
            onClick = onValidateServer,
            testTag = "onboarding_next",
        )
    }
}

@Composable
private fun CredentialsStep(
    state: OnboardingUiState,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit,
    onRemoveOldestDeviceAndRetry: () -> Unit,
    onRetryQrLogin: () -> Unit,
) {
    val usernameFocus = remember { FocusRequester() }
    val removeOldestFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        usernameFocus.requestFocus()
    }

    // When the token-limit error first appears, the remediation button becomes the
    // recommended next action — move focus to it so the user doesn't have to hunt for it.
    LaunchedEffect(state.loginError) {
        if (state.loginError is OnboardingLoginError.TokenLimitReached) {
            removeOldestFocus.requestFocus()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_credentials"),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Column(
        modifier = Modifier.weight(2f),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_credentials_headline),
            style = MaterialTheme.typography.headlineMedium,
            color = RommTvColors.TextPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_credentials_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = RommTvColors.TextSecondary,
        )
        // "Connecting to {HOST}" — only shown once a validated origin exists.
        state.normalizedOrigin?.let { host ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_connecting_to, host),
                style = MaterialTheme.typography.bodySmall,
                color = RommTvColors.TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))

        ControllerFriendlyTextField(
            value = state.username,
            onValueChange = onUsernameChanged,
            label = {
                Text(
                    text = stringResource(R.string.onboarding_username_label),
                    color = RommTvColors.TextSecondary,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_username_field")
                .focusRequester(usernameFocus),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = RommTvColors.NightLo,
                unfocusedContainerColor = RommTvColors.NightLo,
                focusedIndicatorColor = RommTvColors.Romm500,
                unfocusedIndicatorColor = RommTvColors.TextSecondary.copy(alpha = 0.3f),
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        ControllerFriendlyTextField(
            value = state.password,
            onValueChange = onPasswordChanged,
            label = {
                Text(
                    text = stringResource(R.string.onboarding_password_label),
                    color = RommTvColors.TextSecondary,
                )
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onLogin() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding_password_field"),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = RommTvColors.NightLo,
                unfocusedContainerColor = RommTvColors.NightLo,
                focusedIndicatorColor = RommTvColors.Romm500,
                unfocusedIndicatorColor = RommTvColors.TextSecondary.copy(alpha = 0.3f),
            ),
        )

        state.loginError?.let { error ->
            val message = when (error) {
                OnboardingLoginError.InvalidCredentials ->
                    stringResource(R.string.onboarding_login_error_invalid, state.normalizedOrigin.orEmpty())
                OnboardingLoginError.NetworkFailure ->
                    stringResource(R.string.onboarding_login_error_network, state.normalizedOrigin.orEmpty())
                OnboardingLoginError.TlsFailure ->
                    stringResource(R.string.onboarding_login_error_tls, state.normalizedOrigin.orEmpty())
                OnboardingLoginError.ServerFailure ->
                    stringResource(R.string.onboarding_login_error_server)
                OnboardingLoginError.VerificationFailure ->
                    stringResource(R.string.onboarding_login_error_verification)
                OnboardingLoginError.DeviceCredentialFailure ->
                    stringResource(R.string.onboarding_login_error_persist)
                OnboardingLoginError.TokenLimitReached ->
                    stringResource(R.string.onboarding_login_error_token_limit)
                OnboardingLoginError.RequiredFields ->
                    stringResource(R.string.onboarding_login_error_empty)
            }
            showError(
                error = message,
                modifier = Modifier.testTag("onboarding_login_error"),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        val loading = state.loginAction == AsyncActionState.Loading
        OnboardingPrimaryButton(
            text = stringResource(R.string.onboarding_login),
            loadingText = stringResource(R.string.onboarding_login_loading),
            loading = loading,
            enabled = true,
            onClick = onLogin,
            testTag = "onboarding_login",
        )

        if (state.loginError is OnboardingLoginError.TokenLimitReached) {
            Spacer(modifier = Modifier.height(12.dp))
            OnboardingPrimaryButton(
                text = stringResource(R.string.onboarding_remove_oldest_device),
                loadingText = stringResource(R.string.onboarding_remove_oldest_device_loading),
                loading = loading,
                enabled = true,
                onClick = onRemoveOldestDeviceAndRetry,
                testTag = "onboarding_remove_oldest_device",
                focusRequester = removeOldestFocus,
            )
        }
    }
        QrLoginPanel(
            state = state.qrLoginState,
            onRetry = onRetryQrLogin,
            modifier = Modifier.weight(1f),
        )
    }
}
