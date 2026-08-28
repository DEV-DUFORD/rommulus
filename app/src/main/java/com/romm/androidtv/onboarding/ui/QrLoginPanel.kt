package com.romm.androidtv.onboarding.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.romm.androidtv.R
import com.romm.androidtv.library.ui.RommTvColors
import com.romm.androidtv.onboarding.QrLoginError
import com.romm.androidtv.onboarding.QrLoginUiState
import kotlin.math.ceil

@Composable
fun QrLoginPanel(
    state: QrLoginUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .testTag("onboarding_qr_panel")
            .background(RommTvColors.NightLo.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .border(1.dp, RommTvColors.TextSecondary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_qr_heading),
            style = MaterialTheme.typography.titleMedium,
            color = RommTvColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.size(16.dp))

        when (state) {
            QrLoginUiState.Idle,
            QrLoginUiState.Loading -> {
                CircularProgressIndicator(
                    color = RommTvColors.Romm500,
                    modifier = Modifier.size(42.dp),
                )
                Spacer(modifier = Modifier.size(12.dp))
                PanelMessage(stringResource(R.string.onboarding_qr_loading))
            }

            is QrLoginUiState.Ready -> {
                val bitmap = remember(state.session.verificationUrl) {
                    createQrBitmap(state.session.verificationUrl)
                }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.onboarding_qr_description),
                    modifier = Modifier
                        .size(210.dp)
                        .testTag("onboarding_qr_code"),
                )
                Spacer(modifier = Modifier.size(12.dp))
                PanelMessage(stringResource(R.string.onboarding_qr_instruction))
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(
                        R.string.onboarding_qr_code_label,
                        formatUserCode(state.session.userCode),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = RommTvColors.TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        R.string.onboarding_qr_expires,
                        ceil(state.session.expiresInSeconds / 60.0).toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = RommTvColors.TextSecondary,
                )
            }

            QrLoginUiState.Unsupported ->
                PanelMessage(stringResource(R.string.onboarding_qr_unsupported))

            QrLoginUiState.Denied -> RetryState(
                message = stringResource(R.string.onboarding_qr_denied),
                onRetry = onRetry,
            )

            QrLoginUiState.Expired -> RetryState(
                message = stringResource(R.string.onboarding_qr_expired),
                onRetry = onRetry,
            )

            is QrLoginUiState.Error -> RetryState(
                message = when (state.reason) {
                    QrLoginError.NETWORK -> stringResource(R.string.onboarding_qr_network_error)
                    QrLoginError.INSUFFICIENT_SCOPES -> stringResource(R.string.onboarding_qr_scope_error)
                    QrLoginError.VERIFICATION -> stringResource(R.string.onboarding_qr_verification_error)
                    QrLoginError.TOKEN_PERSISTENCE,
                    QrLoginError.TOKEN_VERIFICATION,
                    QrLoginError.DEVICE_IDENTITY_PERSISTENCE,
                    QrLoginError.SESSION_PERSISTENCE ->
                        stringResource(R.string.onboarding_qr_persistence_error)
                },
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun RetryState(message: String, onRetry: () -> Unit) {
    PanelMessage(message)
    Spacer(modifier = Modifier.size(16.dp))
    OnboardingPrimaryButton(
        text = stringResource(R.string.onboarding_qr_retry),
        loadingText = "",
        loading = false,
        enabled = true,
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth(),
        testTag = "onboarding_qr_retry",
    )
}

@Composable
private fun PanelMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = RommTvColors.TextSecondary,
        textAlign = TextAlign.Center,
    )
}

internal fun formatUserCode(code: String): String {
    val normalized = code.replace("-", "").uppercase()
    return if (normalized.length == 8) {
        "${normalized.take(4)}-${normalized.drop(4)}"
    } else {
        normalized
    }
}

internal fun createQrBitmap(content: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to 2),
    )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val offset = y * size
        for (x in 0 until size) {
            pixels[offset + x] = if (matrix[x, y]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
