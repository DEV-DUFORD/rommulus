package com.romm.androidtv.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.romm.androidtv.library.LicenseNotice
import com.romm.androidtv.library.LicensesRepository

/**
 * Full-screen dialog listing every open-source notice (Gradle dependencies from the
 * oss-licenses-plugin plus the vendored libretro cores). D-pad moves focus between
 * notices; Back (registered in the Dialog window) or the Close button dismisses it.
 */
@Composable
fun LicensesDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val notices by produceState<List<LicenseNotice>?>(initialValue = null, context) {
        value = LicensesRepository.load(context)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // Registered inside the Dialog's content so it subscribes to the Dialog
        // window's own OnBackPressedDispatcher (see GameDetailErrorAlert).
        BackHandler { onDismiss() }
        Box(
            modifier = modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(RommTvColors.NightHi),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                DialogHeader(count = notices?.size, onDismiss = onDismiss)

                Spacer(modifier = Modifier.height(16.dp))

                when (val loaded = notices) {
                    null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RommTvColors.Romm500)
                    }
                    else -> if (loaded.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No license notices found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = RommTvColors.TextSecondary,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(loaded) { notice ->
                                LicenseNoticeItem(notice)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogHeader(count: Int?, onDismiss: () -> Unit) {
    val closeFocusRequester = remember { FocusRequester() }
    var closeReady by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Open Source Licenses",
                style = MaterialTheme.typography.headlineMedium,
                color = RommTvColors.TextPrimary,
            )
            count?.let {
                Text(
                    text = "$it libraries",
                    style = MaterialTheme.typography.titleSmall,
                    color = RommTvColors.Romm300,
                )
            }
        }
        TvButton(
            onClick = onDismiss,
            modifier = Modifier
                .focusRequester(closeFocusRequester)
                .onGloballyPositioned { closeReady = true },
        ) {
            Text("Close")
        }
    }
    LaunchedEffect(closeReady) {
        if (closeReady) closeFocusRequester.requestFocus()
    }
}

@Composable
private fun LicenseNoticeItem(notice: LicenseNotice) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    // Focusable (via clickable) purely to make each notice reachable by the D-pad and
    // to auto-scroll the LazyColumn to it; the item itself is read-only.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) RommTvColors.Romm600.copy(alpha = 0.3f) else RommTvColors.NightLo)
            .clickable(interactionSource = interactionSource, indication = null, onClick = {})
            .padding(16.dp),
    ) {
        Text(
            text = notice.name,
            style = MaterialTheme.typography.titleMedium,
            color = RommTvColors.Romm300,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = notice.text,
            style = MaterialTheme.typography.bodySmall,
            color = RommTvColors.TextPrimary,
        )
    }
}