package com.romm.desktop.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.romm.androidtv.library.LicenseNotice
import com.romm.desktop.library.DesktopLicensesLoader
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.components.LocalRommulusTheme
import com.romm.desktop.ui.components.LoadingIndicator
import com.romm.desktop.ui.components.RommulusTheme
import com.romm.desktop.ui.navigation.keyboardShortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop "View Licenses" dialog (standalone, opened by the Settings wave):
 * a Compose Desktop [Dialog] listing every open-source notice — the
 * Gradle/transitive dependencies loaded via [DesktopLicensesLoader] plus the
 * vendored libretro cores — mirroring Android's `LicensesDialog`.
 *
 * Arrow keys move focus between notices (each row is focusable); focusing or
 * clicking a row expands it to reveal its license text. Escape or the Close
 * button dismisses via [onDismiss].
 */
@Composable
fun LicensesDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalRommulusColors.current
    // Captured in the OUTER composition (the shell provides RommulusTheme) because Compose
    // Desktop dialogs are separate compositions: locals do not propagate into the dialog
    // window, so the theme value is re-applied explicitly below.
    val theme = LocalRommulusTheme.current
    val notices by produceState<List<LicenseNotice>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { DesktopLicensesLoader.load() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        RommulusTheme(theme = theme) {
        Box(
            // Explicit size: Compose Desktop 1.6 has no `defaultSize` modifier, and a
            // `fillMaxSize` root gives the dialog window no intrinsic size to size itself to.
            modifier = modifier
                .size(760.dp, 540.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.nightHi)
                .keyboardShortcuts(
                    onBack = onDismiss,
                    onSearch = { /* dialog: no search shortcut */ },
                    onQuit = { /* window close is owned by the desktop shell */ },
                ),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                DialogHeader(count = notices?.size, onDismiss = onDismiss)

                Spacer(modifier = Modifier.height(16.dp))

                when (val loaded = notices) {
                    null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }

                    else -> if (loaded.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No license notices found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                            )
                        }
                    } else {
                        var expandedIndex by remember { mutableStateOf<Int?>(null) }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // `itemsIndexed` (not `items`) because we need the row index to
                            // track which notice is expanded.
                            itemsIndexed(loaded) { index, notice ->
                                LicenseNoticeItem(
                                    notice = notice,
                                    expanded = expandedIndex == index,
                                    onSelect = { expandedIndex = index },
                                    onToggle = {
                                        expandedIndex = if (expandedIndex == index) null else index
                                    },
                                )
                            }
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
    val colors = LocalRommulusColors.current
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
                color = colors.textPrimary,
            )
            count?.let {
                Text(
                    text = "$it libraries",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.romm300,
                )
            }
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .focusRequester(closeFocusRequester)
                .onGloballyPositioned { closeReady = true },
        ) {
            Text("Close", color = colors.romm300)
        }
    }
    LaunchedEffect(closeReady) {
        if (closeReady) closeFocusRequester.requestFocus()
    }
}

/**
 * One notice row: the component name, expanding to the full license text when
 * focused (arrow-key navigation) or clicked. The row is focusable purely so the
 * keyboard can reach it and auto-expand it; the content itself is read-only.
 */
@Composable
private fun LicenseNoticeItem(
    notice: LicenseNotice,
    expanded: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors = LocalRommulusColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (expanded) colors.romm600.copy(alpha = 0.3f) else colors.nightLo)
            .onFocusChanged { state ->
                if (state.isFocused) onSelect()
            }
            .clickable(onClick = onToggle)
            .padding(16.dp),
    ) {
        Text(
            text = notice.name,
            style = MaterialTheme.typography.titleMedium,
            color = colors.romm300,
        )
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = notice.text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textPrimary,
            )
        }
    }
}
