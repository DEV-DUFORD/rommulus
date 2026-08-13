package com.romm.androidtv.library.ui

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavePickerScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loadedPicker_focusesNewestEntry() {
        val entries = listOf(
            SavePickerEntryUiModel(
                saveId = 2L,
                fileName = "autosave [2026-08-13_14-59-29].srm",
                coreId = "mupen64plus_next",
                sizeText = "290 KB",
                updatedAtText = "2026-08-13 14:59:29 UTC",
                isDefaultSelection = true,
            ),
            SavePickerEntryUiModel(
                saveId = 1L,
                fileName = "autosave [2026-08-13_13-02-56].srm",
                coreId = "mupen64plus_next",
                sizeText = "290 KB",
                updatedAtText = "2026-08-13 13:02:56 UTC",
                isDefaultSelection = false,
            ),
        )

        composeTestRule.setContent {
            RommTvTheme {
                SavePickerScreen(
                    state = SavePickerState.Loaded(
                        SavePickerUiModel(romTitle = "Super Mario 64", entries = entries),
                    ),
                    onSelect = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("save_picker_entry_2").assertIsFocused()
    }
}
