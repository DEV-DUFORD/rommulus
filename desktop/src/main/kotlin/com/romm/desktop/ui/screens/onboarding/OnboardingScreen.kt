package com.romm.desktop.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.romm.androidtv.network.RommServerAddress
import com.romm.androidtv.network.ServerAddressResult
import com.romm.androidtv.onboarding.AsyncActionState
import com.romm.androidtv.onboarding.OnboardingEffect
import com.romm.androidtv.onboarding.OnboardingLoginError
import com.romm.androidtv.onboarding.OnboardingPresenter
import com.romm.androidtv.onboarding.OnboardingServerError
import com.romm.androidtv.onboarding.OnboardingStep
import com.romm.androidtv.onboarding.OnboardingUiState
import com.romm.androidtv.onboarding.QrLoginError
import com.romm.androidtv.onboarding.QrLoginUiState
import com.romm.desktop.DesktopAppCoordinator
import com.romm.desktop.ui.components.DesktopTextField
import com.romm.desktop.ui.components.ErrorBanner
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.LoadingIndicator
import com.romm.desktop.ui.components.TvButton
import com.romm.desktop.ui.components.TvOutlinedButton
import com.romm.desktop.ui.image.loadBundledImage
import com.romm.desktop.ui.navigation.LocalFocusNavigator
import com.romm.desktop.ui.navigation.focusableItem
import com.romm.desktop.ui.navigation.keyboardShortcuts
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.ceil
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** Amber helper-text color with WCAG AA contrast against the dark background (mirrors Android). */
private val AmberWarningColor = Color(0xFFFCD34D)

/**
 * Phase 6 desktop onboarding screen — drives the shared [OnboardingPresenter] through its
 * three steps (WELCOME → SERVER → CREDENTIALS), mirroring the Android onboarding flow
 * (`app/.../onboarding/ui/OnboardingScreen.kt`) with desktop components.
 *
 * The presenter is obtained pre-wired from the coordinator via
 * [DesktopAppCoordinator.onboardingPresenter] (its 8 fun-interface seams are already bound to
 * the auth repositories, QR-login repository, and settings adapter). This screen only:
 *
 *  - renders each step per [OnboardingUiState.step];
 *  - forwards user events to the presenter;
 *  - collects the one-shot [OnboardingEffect.Completed] and calls
 *    [DesktopAppCoordinator.onOnboardingCompleted];
 *  - clears the in-memory password on teardown ([OnboardingPresenter.onCleared]);
 *  - handles Escape (step back; exit at WELCOME) via [keyboardShortcuts]. Enter activates the
 *    focused control implicitly through the standard focus/onClick flow and the text fields'
 *    `onDone` handlers.
 */
@Composable
fun OnboardingScreen(
    coordinator: DesktopAppCoordinator,
    modifier: Modifier = Modifier,
) {
    // The coordinator owns the presenter (lazy singleton with all 8 fun-interfaces pre-wired).
    val presenter: OnboardingPresenter = remember(coordinator) { coordinator.onboardingPresenter() }
    val state by presenter.uiState.collectAsState()

    // One-shot completion effect → switch the whole app to MAIN mode.
    LaunchedEffect(presenter) {
        presenter.effects.collect { effect ->
            if (effect == OnboardingEffect.Completed) coordinator.onOnboardingCompleted()
        }
    }

    // Clear the in-memory password when the screen leaves composition (mirrors Android's
    // ViewModel onCleared so the String is GC-eligible immediately).
    DisposableEffect(presenter) {
        onDispose { presenter.onCleared() }
    }

    // Theming is owned by the shell (RommulusDesktopApp wraps the whole app in RommulusTheme).
    Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            LocalRommulusColors.current.stageHi,
                            LocalRommulusColors.current.stageLo,
                        ),
                        start = Offset.Zero,
                        end = Offset(1400f, 1400f),
                    ),
                )
                .keyboardShortcuts(
                    onBack = {
                        if (state.step == OnboardingStep.WELCOME) {
                            // Root of the flow: exit, mirroring Android's "host finishes itself".
                            coordinator.exitRequested = true
                        } else {
                            presenter.onBack()
                        }
                    },
                    onSearch = { /* no search during onboarding */ },
                    onQuit = { coordinator.exitRequested = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(if (state.step == OnboardingStep.CREDENTIALS) 960.dp else 640.dp)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state.step) {
                    OnboardingStep.WELCOME -> WelcomeStep(onContinue = presenter::onContinue)

                    OnboardingStep.SERVER -> ServerStep(
                        state = state,
                        onServerChanged = presenter::onServerChanged,
                        onValidateServer = presenter::onValidateServer,
                    )

                    OnboardingStep.CREDENTIALS -> CredentialsStep(
                        state = state,
                        onUsernameChanged = presenter::onUsernameChanged,
                        onPasswordChanged = presenter::onPasswordChanged,
                        onLogin = presenter::onLogin,
                        onRemoveOldestDeviceAndRetry = presenter::onRemoveOldestDeviceAndRetry,
                        onRetryQrLogin = presenter::onRetryQrLogin,
                    )
                }
            }
        }
}

// --------------------------------------------------------------------------- WELCOME

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    val continueFocus = remember { FocusRequester() }
    val navigator = LocalFocusNavigator.current

    LaunchedEffect(Unit) {
        continueFocus.requestFocus()
    }

    // Bundled ROMM logo — the desktop copy of app/src/main/res/raw/romm_logo.svg, mirroring
    // Android's AsyncImage(R.raw.romm_logo) in the welcome step.
    val logoBitmap = remember { loadBundledImage("/icons/romm_logo.svg") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (logoBitmap != null) {
            Image(
                bitmap = logoBitmap,
                contentDescription = "RomMulus",
                modifier = Modifier.size(160.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Welcome to RomMulus",
                style = MaterialTheme.typography.headlineLarge,
                color = LocalRommulusColors.current.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            // Fallback if the bundled asset is missing — text branding stands in for the logo.
            Text(
                text = "RomMulus",
                style = MaterialTheme.typography.displayMedium,
                color = LocalRommulusColors.current.romm300,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        Text(
            text = "Connect to your ROMM server to browse and manage your collection.",
            style = MaterialTheme.typography.bodyLarge,
            color = LocalRommulusColors.current.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        TvButton(
            onClick = onContinue,
            modifier = Modifier
                .focusRequester(continueFocus)
                .focusableItem("onboarding:continue", navigator, onContinue),
        ) {
            Text("Continue")
        }
    }
}

// --------------------------------------------------------------------------- SERVER

@Composable
private fun ServerStep(
    state: OnboardingUiState,
    onServerChanged: (String) -> Unit,
    onValidateServer: () -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    val navigator = LocalFocusNavigator.current

    LaunchedEffect(Unit) {
        fieldFocus.requestFocus()
    }

    // Amber HTTP warning: show when the CURRENT input parses to an accepted private-LAN HTTP
    // origin. Pure + cheap; recomputed on every edit (same rule as the Android screen).
    val isPrivateLanHttp = remember(state.serverInput) { isPrivateLanHttpWarning(state.serverInput) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Add your ROMM server",
            style = MaterialTheme.typography.headlineMedium,
            color = LocalRommulusColors.current.textPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Enter the address of your ROMM instance.",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalRommulusColors.current.textSecondary,
        )
        Spacer(modifier = Modifier.height(24.dp))

        DesktopTextField(
            value = state.serverInput,
            onValueChange = onServerChanged,
            label = "Server address",
            placeholder = "https://romm.example.com",
            onDone = onValidateServer,
            modifier = Modifier.focusRequester(fieldFocus),
        )

        if (state.serverError == null) {
            Text(
                text = "Example: https://romm.example.com or http://192.168.1.10:1337",
                style = MaterialTheme.typography.bodySmall,
                color = LocalRommulusColors.current.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (isPrivateLanHttp) {
            Text(
                text = "Plain HTTP is only allowed on a private network. Anyone on this network can read your data.",
                style = MaterialTheme.typography.bodySmall,
                color = AmberWarningColor,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        state.serverError?.let { error ->
            ErrorBanner(
                message = serverErrorMessage(error),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        if (state.serverAction == AsyncActionState.Loading) {
            LoadingIndicator()
        } else {
            TvButton(
                onClick = onValidateServer,
                modifier = Modifier.focusableItem("onboarding:next", navigator, onValidateServer),
            ) {
                Text("Next")
            }
        }
    }
}

// --------------------------------------------------------------------------- CREDENTIALS

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
    val passwordFocus = remember { FocusRequester() }
    val removeOldestFocus = remember { FocusRequester() }
    val navigator = LocalFocusNavigator.current

    LaunchedEffect(Unit) {
        usernameFocus.requestFocus()
    }

    // When the token-limit error first appears, the remediation button becomes the recommended
    // next action — move focus to it so the user doesn't have to hunt for it (mirrors Android).
    LaunchedEffect(state.loginError) {
        if (showRemoveOldestDeviceButton(state)) {
            removeOldestFocus.requestFocus()
        }
    }

    @Composable
    fun LoginFormContent() {
        Text(
            text = "Sign in",
            style = MaterialTheme.typography.headlineMedium,
            color = LocalRommulusColors.current.textPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Use the account you created when setting up ROMM.",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalRommulusColors.current.textSecondary,
        )
        // "Connecting to {origin}" — only shown once a validated origin exists.
        state.normalizedOrigin?.let { origin ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Connecting to $origin",
                style = MaterialTheme.typography.bodySmall,
                color = LocalRommulusColors.current.textSecondary,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))

        DesktopTextField(
            value = state.username,
            onValueChange = onUsernameChanged,
            label = "Username",
            onDone = { passwordFocus.requestFocus() },
            modifier = Modifier.focusRequester(usernameFocus),
        )
        Spacer(modifier = Modifier.height(12.dp))

        DesktopTextField(
            value = state.password,
            onValueChange = onPasswordChanged,
            label = "Password",
            isPassword = true,
            onDone = onLogin,
            modifier = Modifier.focusRequester(passwordFocus),
        )

        state.loginError?.let { error ->
            ErrorBanner(
                message = loginErrorMessage(error, state.normalizedOrigin),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        val loading = state.loginAction == AsyncActionState.Loading
        if (loading) {
            LoadingIndicator()
        } else {
            TvButton(
                onClick = onLogin,
                modifier = Modifier.focusableItem("onboarding:login", navigator, onLogin),
            ) {
                Text("Log in")
            }
        }

        if (showRemoveOldestDeviceButton(state)) {
            Spacer(modifier = Modifier.height(12.dp))
            TvOutlinedButton(
                onClick = onRemoveOldestDeviceAndRetry,
                modifier = Modifier
                    .focusRequester(removeOldestFocus)
                    .focusableItem("onboarding:remove-oldest", navigator, onRemoveOldestDeviceAndRetry),
            ) {
                Text("Remove oldest device and retry")
            }
        }
    }

    // Desktop is always wide enough for the two-pane layout (form + QR panel side by side).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(2f),
            verticalArrangement = Arrangement.Center,
        ) {
            LoginFormContent()
        }
        QrLoginPanel(
            state = state.qrLoginState,
            onRetry = onRetryQrLogin,
            modifier = Modifier.weight(1f),
        )
    }
}

// --------------------------------------------------------------------------- QR panel

/**
 * Desktop QR-login panel (mirrors Android's `QrLoginPanel`).
 */
@Composable
private fun QrLoginPanel(
    state: QrLoginUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalRommulusColors.current
    Column(
        modifier = modifier
            .background(palette.nightLo.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .border(1.dp, palette.textSecondary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Log in with your phone",
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.size(16.dp))

        when (state) {
            QrLoginUiState.Idle,
            QrLoginUiState.Loading -> {
                CircularProgressIndicator(
                    color = palette.romm500,
                    modifier = Modifier.size(42.dp),
                )
                Spacer(modifier = Modifier.size(12.dp))
                PanelMessage("Preparing QR login…")
            }

            is QrLoginUiState.Ready -> {
                val qrBitmap = remember(state.session.verificationUrl) {
                    createQrImageBitmap(state.session.verificationUrl)
                }
                Image(
                    bitmap = qrBitmap,
                    contentDescription = "QR code for phone sign-in",
                    modifier = Modifier
                        .size(210.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, palette.textSecondary.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                )
                Spacer(modifier = Modifier.size(12.dp))
                PanelMessage("Open ROMM on your phone and scan this code.")
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = formatUserCode(state.session.userCode),
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Expires in ${ceil(state.session.expiresInSeconds / 60.0).toInt()} min",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }

            QrLoginUiState.Unsupported ->
                PanelMessage("This server does not support QR login.")

            QrLoginUiState.Denied ->
                RetryState(message = "QR login was denied.", onRetry = onRetry)

            QrLoginUiState.Expired ->
                RetryState(message = "The QR code expired.", onRetry = onRetry)

            is QrLoginUiState.Error ->
                RetryState(message = qrErrorMessage(state.reason), onRetry = onRetry)
        }
    }
}

@Composable
private fun RetryState(message: String, onRetry: () -> Unit) {
    val navigator = LocalFocusNavigator.current
    PanelMessage(message)
    Spacer(modifier = Modifier.size(16.dp))
    TvOutlinedButton(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth().focusableItem("onboarding:qr-retry", navigator, onRetry),
    ) {
        Text("Retry")
    }
}

@Composable
private fun PanelMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = LocalRommulusColors.current.textSecondary,
        textAlign = TextAlign.Center,
    )
}

// --------------------------------------------------------------------------- Pure helpers (unit-testable)

/**
 * True when [serverInput] parses to a valid private-LAN HTTP origin — the condition for the
 * amber "plain HTTP on a private network" warning banner. Mirrors the Android screen's check:
 * parse with [RommServerAddress.parseAndNormalize], then classify with
 * [RommServerAddress.isPrivateLanHttp].
 */
internal fun isPrivateLanHttpWarning(serverInput: String): Boolean {
    val parsed = RommServerAddress.parseAndNormalize(serverInput)
    return parsed is ServerAddressResult.Valid && RommServerAddress.isPrivateLanHttp(parsed)
}

/** Maps a typed server-validation error to its user-facing message (no resources on desktop). */
internal fun serverErrorMessage(error: OnboardingServerError): String = when (error) {
    OnboardingServerError.InvalidAddress -> "That doesn't look like a valid ROMM server address."
    OnboardingServerError.NotRomm -> "That server is not running ROMM."
    OnboardingServerError.SetupIncomplete -> "ROMM setup on that server is incomplete."
    OnboardingServerError.UserpassDisabled -> "Username/password login is disabled on that server."
    OnboardingServerError.InsecurePublicHttp ->
        "Plain HTTP is not allowed for public servers. Use HTTPS or a private-LAN address."
    OnboardingServerError.PersistenceFailure -> "Could not save the server address on this device."
}

/** Maps a typed login error to its user-facing message; [origin] is interpolated where relevant. */
internal fun loginErrorMessage(error: OnboardingLoginError, origin: String?): String = when (error) {
    OnboardingLoginError.InvalidCredentials -> "Login failed for ${origin.orEmpty()}: check your username and password."
    OnboardingLoginError.NetworkFailure -> "Could not reach ${origin.orEmpty()}."
    OnboardingLoginError.TlsFailure -> "TLS handshake with ${origin.orEmpty()} failed."
    OnboardingLoginError.ServerFailure -> "The ROMM server returned an error."
    OnboardingLoginError.VerificationFailure -> "The server rejected the new session."
    OnboardingLoginError.DeviceCredentialFailure -> "Could not store device credentials on this machine."
    OnboardingLoginError.TokenLimitReached ->
        "This account has reached its device limit. Remove a device to continue."
    OnboardingLoginError.RequiredFields -> "Enter your username and password."
}

/** Maps a QR-login failure reason to its user-facing message. */
internal fun qrErrorMessage(reason: QrLoginError): String = when (reason) {
    QrLoginError.NETWORK -> "Network error while polling the QR login."
    QrLoginError.INSUFFICIENT_SCOPES -> "Your ROMM account does not have the scopes required for QR login."
    QrLoginError.VERIFICATION -> "The server rejected the QR session verification."
    QrLoginError.TOKEN_PERSISTENCE -> "Could not save the QR token to this device's keyring."
    QrLoginError.TOKEN_VERIFICATION -> "Could not read the QR token back from this device's keyring."
    QrLoginError.DEVICE_IDENTITY_PERSISTENCE -> "Could not save this device's QR pairing."
    QrLoginError.SESSION_PERSISTENCE -> "Could not save the QR login session on this device."
}

/**
 * Formats an 8-character user code as `XXXX-XXXX` (case-insensitive, dashes stripped first).
 * Codes of any other length are returned upper-cased unchanged. Ported from Android's
 * `QrLoginPanel.formatUserCode`.
 */
internal fun formatUserCode(code: String): String {
    val normalized = code.replace("-", "").uppercase()
    return if (normalized.length == 8) {
        "${normalized.take(4)}-${normalized.drop(4)}"
    } else {
        normalized
    }
}

internal fun createQrImageBitmap(content: String, size: Int = 512): ImageBitmap {
    require(content.isNotBlank()) { "QR content must not be blank" }
    require(size > 0) { "QR size must be positive" }
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 2),
    )
    return Surface.makeRasterN32Premul(size, size).use { surface ->
        surface.canvas.clear(0xffffffff.toInt())
        Paint().use { paint ->
            paint.color = 0xff000000.toInt()
            for (y in 0 until size) {
                var x = 0
                while (x < size) {
                    if (!matrix[x, y]) {
                        x++
                        continue
                    }
                    val start = x
                    while (x < size && matrix[x, y]) x++
                    surface.canvas.drawRect(
                        Rect.makeLTRB(start.toFloat(), y.toFloat(), x.toFloat(), (y + 1).toFloat()),
                        paint,
                    )
                }
            }
        }
        surface.makeImageSnapshot().toComposeImageBitmap()
    }
}

/** The remediation button is offered only on CREDENTIALS while the token-limit error shows. */
internal fun showRemoveOldestDeviceButton(state: OnboardingUiState): Boolean =
    state.step == OnboardingStep.CREDENTIALS &&
        state.loginError is OnboardingLoginError.TokenLimitReached
