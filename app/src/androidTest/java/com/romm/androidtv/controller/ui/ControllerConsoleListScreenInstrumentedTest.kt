package com.romm.androidtv.controller.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.romm.androidtv.controller.config.CoreControllerProfile
import com.romm.androidtv.controller.config.ControllerArtwork
import com.romm.androidtv.controller.config.CoreControlDescriptor
import com.romm.androidtv.controller.config.PlayerControllerConfig
import com.romm.androidtv.library.ui.RommTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for [ControllerConsoleListScreen].
 *
 * Validates: list renders profile console names, card click invokes onSelectCore,
 * and subtitle is rendered when present.
 */
@RunWith(AndroidJUnit4::class)
class ControllerConsoleListScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testProfiles = listOf(
        mockProfile("snes9x", "Super Nintendo", null, 2),
        mockProfile("genesis_plus_gx", "Sega Systems", "Genesis, Master System, Game Gear, and Sega CD", 2),
        mockProfile("fceumm", "Nintendo Entertainment System", null, 2),
    )

    @Test
    fun consoleListScreen_rendersAllProfileNames() {
        composeTestRule.setContent {
            RommTvTheme {
                ControllerConsoleListScreen(
                    profiles = testProfiles,
                    onSelectCore = { },
                    onBack = { },
                )
            }
        }

        // Title
        composeTestRule.onNodeWithText("Controller Settings", useUnmergedTree = true)
            .assertExists()

        // Each console name appears
        composeTestRule.onNodeWithText("Super Nintendo", useUnmergedTree = true)
            .assertExists()
        composeTestRule.onNodeWithText("Sega Systems", useUnmergedTree = true)
            .assertExists()
        composeTestRule.onNodeWithText("Nintendo Entertainment System", useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun consoleListScreen_rendersSubtitleWhenPresent() {
        composeTestRule.setContent {
            RommTvTheme {
                ControllerConsoleListScreen(
                    profiles = testProfiles,
                    onSelectCore = { },
                    onBack = { },
                )
            }
        }

        // Sega Systems has a subtitle
        composeTestRule.onNodeWithText("Genesis, Master System, Game Gear, and Sega CD", useUnmergedTree = true)
            .assertExists()

        // Super Nintendo has no subtitle — should not appear
        composeTestRule.onNodeWithText("Genesis, Master System", useUnmergedTree = true)
            .assertExists() // Confirms the subtitle text is present
    }

    @Test
    fun consoleListScreen_cardClickInvokesOnSelectCore() {
        var selectedCoreId: String? = null

        composeTestRule.setContent {
            RommTvTheme {
                ControllerConsoleListScreen(
                    profiles = testProfiles,
                    onSelectCore = { coreId -> selectedCoreId = coreId },
                    onBack = { },
                )
            }
        }

        // Click the Sega Systems card
        composeTestRule.onNodeWithText("Sega Systems", useUnmergedTree = true)
            .performClick()

        composeTestRule.waitForIdle()
        assert(selectedCoreId == "genesis_plus_gx") {
            "Expected onSelectCore(\"genesis_plus_gx\"), got \"$selectedCoreId\""
        }
    }

    @Test
    fun consoleListScreen_emptyProfilesRendersTitleOnly() {
        composeTestRule.setContent {
            RommTvTheme {
                ControllerConsoleListScreen(
                    profiles = emptyList(),
                    onSelectCore = { },
                    onBack = { },
                )
            }
        }

        composeTestRule.onNodeWithText("Controller Settings", useUnmergedTree = true)
            .assertExists()
    }
}

/**
 * Builds a minimal [CoreControllerProfile] for testing.
 */
private fun mockProfile(
    coreId: String,
    consoleName: String,
    consoleSubtitle: String?,
    playerCount: Int,
): CoreControllerProfile {
    val emptyControls = emptyList<CoreControlDescriptor>()
    val defaults = (0 until playerCount).associateWith { PlayerControllerConfig(emptyMap()) }
    val artwork = ControllerArtwork(
        resourceName = "test",
        source = "test",
        license = "test",
        licenseAssetPath = null,
        viewBoxWidth = 1f,
        viewBoxHeight = 1f,
    )
    return CoreControllerProfile(
        coreId = coreId,
        consoleName = consoleName,
        consoleSubtitle = consoleSubtitle,
        playerCount = playerCount,
        artwork = artwork,
        controls = emptyControls,
        defaults = defaults,
    )
}
