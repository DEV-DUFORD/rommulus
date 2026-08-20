package com.romm.desktop.ui.screens.controller

import com.romm.androidtv.controller.config.BindingSlot
import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.CoreControllerProfiles
import com.romm.androidtv.controller.config.PhysicalBinding
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.desktop.controller.DesktopCaptureState
import com.romm.desktop.controller.config.DesktopControllerConfigRepository
import com.romm.desktop.storage.sqlite.SqliteControllerBindingStore
import com.romm.desktop.storage.sqlite.SqliteDatabase
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Pure-logic tests for the desktop controller config screen (E2): binding-row rendering from
 * the merged config and the capture dialog's state→content transitions. No Compose runtime.
 */
@DisplayName("ControllerConfigScreen — row rendering + capture dialog logic")
class ControllerConfigScreenLogicTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var repo: DesktopControllerConfigRepository

    /** fceumm (NES): D-Pad x4, A, B, Select, Start + the app-level pause-menu control. */
    private val profile = CoreControllerProfiles.byCoreId("fceumm")!!
    private val coreId = "fceumm"

    @BeforeEach
    fun setUp() {
        val db = SqliteDatabase.open(tempDir.resolve("rommulus.db")).getOrThrow()
        repo = DesktopControllerConfigRepository(SqliteControllerBindingStore(db))
    }

    // ---------------------------------------------------------------- rows from merged config

    @Nested
    @DisplayName("buildBindingRows — merged config (profile defaults + overrides)")
    inner class RowRendering {

        @Test
        fun `defaults render one row per profile control in catalog order`() = runTest {
            val config = repo.loadCore(coreId)
            val rows = buildBindingRows(profile, 0, config)

            assertThat(rows.map { it.controlId }).containsExactlyElementsOf(profile.controls.map { it.id })
            // NES A defaults to the gamepad A button; every default row is mapped.
            val aRow = rows.single { it.controlId == CoreControlId.BUTTON_A }
            assertThat(aRow.label).isEqualTo("A")
            assertThat(aRow.bindingLabel).isEqualTo("Button A")
            assertThat(rows).allSatisfy { row ->
                assertThat(row.bindingLabel).isNotEqualTo("Unmapped")
            }
        }

        @Test
        fun `a stored override replaces the default binding label`() = runTest {
            repo.setBinding(
                coreId = coreId,
                playerIndex = 0,
                controlId = CoreControlId.BUTTON_A,
                binding = PhysicalBinding.Key(NeutralKey.BUTTON_X.platformCode),
                bindingSlot = BindingSlot.PRIMARY,
            )
            val config = repo.loadCore(coreId)
            val rows = buildBindingRows(profile, 0, config)

            assertThat(rows.single { it.controlId == CoreControlId.BUTTON_A }.bindingLabel)
                .isEqualTo("Button X")
            // Untouched controls keep their defaults.
            assertThat(rows.single { it.controlId == CoreControlId.BUTTON_B }.bindingLabel)
                .isEqualTo("Button B")
        }

        @Test
        fun `clearBinding renders Unmapped and resetPlayer restores the default`() = runTest {
            repo.clearBinding(coreId, 0, CoreControlId.START, BindingSlot.PRIMARY)
            var rows = buildBindingRows(profile, 0, repo.loadCore(coreId))
            assertThat(rows.single { it.controlId == CoreControlId.START }.bindingLabel)
                .isEqualTo("Unmapped")

            repo.resetPlayer(coreId, 0)
            rows = buildBindingRows(profile, 0, repo.loadCore(coreId))
            assertThat(rows.single { it.controlId == CoreControlId.START }.bindingLabel)
                .isEqualTo("Start")
        }

        @Test
        fun `unknown player index renders no rows`() = runTest {
            val config = repo.loadCore(coreId)
            assertThat(buildBindingRows(profile, 3, config)).isEmpty()
        }

        @Test
        fun `axis and direction bindings format like the Android labels`() = runTest {
            repo.setBinding(
                coreId = coreId,
                playerIndex = 0,
                controlId = CoreControlId.D_PAD_UP,
                binding = PhysicalBinding.AxisDirection(NeutralAxis.Y.platformCode, -1),
                bindingSlot = BindingSlot.PRIMARY,
            )
            val rows = buildBindingRows(profile, 0, repo.loadCore(coreId))
            assertThat(rows.single { it.controlId == CoreControlId.D_PAD_UP }.bindingLabel)
                .isEqualTo("Left Stick Up")
        }
    }

    // ---------------------------------------------------------------- binding labels

    @Nested
    @DisplayName("desktopBindingLabel — neutral platform code formatting")
    inner class BindingLabels {

        @Test
        fun `null renders Unmapped`() {
            assertThat(desktopBindingLabel(null)).isEqualTo("Unmapped")
        }

        @Test
        fun `key codes map to Android-parity labels`() {
            assertThat(desktopBindingLabel(PhysicalBinding.Key(NeutralKey.BUTTON_A.platformCode)))
                .isEqualTo("Button A")
            assertThat(desktopBindingLabel(PhysicalBinding.Key(NeutralKey.BUTTON_START.platformCode)))
                .isEqualTo("Start")
            assertThat(desktopBindingLabel(PhysicalBinding.Key(NeutralKey.DPAD_LEFT.platformCode)))
                .isEqualTo("D-Pad Left")
        }

        @Test
        fun `axis codes map to stick and trigger labels`() {
            assertThat(desktopBindingLabel(PhysicalBinding.Axis(NeutralAxis.X.platformCode)))
                .isEqualTo("Left Stick X")
            assertThat(desktopBindingLabel(PhysicalBinding.Axis(NeutralAxis.RY.platformCode)))
                .isEqualTo("Right Stick Y")
            assertThat(desktopBindingLabel(PhysicalBinding.Axis(NeutralAxis.LTRIGGER.platformCode)))
                .isEqualTo("Left Trigger")
        }

        @Test
        fun `axis directions carry polarity`() {
            assertThat(desktopBindingLabel(PhysicalBinding.AxisDirection(NeutralAxis.X.platformCode, 1)))
                .isEqualTo("Left Stick Right")
            assertThat(desktopBindingLabel(PhysicalBinding.AxisDirection(NeutralAxis.Y.platformCode, -1)))
                .isEqualTo("Left Stick Up")
        }

        @Test
        fun `unknown codes fall back to generic labels`() {
            assertThat(desktopBindingLabel(PhysicalBinding.Key(12345))).isEqualTo("Key 12345")
            assertThat(desktopBindingLabel(PhysicalBinding.Axis(99))).isEqualTo("Axis 99")
        }
    }

    // ---------------------------------------------------------------- capture dialog content

    @Nested
    @DisplayName("captureDialogContent — state transitions")
    inner class CaptureDialogContent {

        private val control = "A Button"
        private val player = "Controller 1"

        @Test
        fun `awaiting neutral and capturing show the press-a-button prompt`() {
            for (state in listOf<DesktopCaptureState>(
                DesktopCaptureState.AwaitingNeutral,
                DesktopCaptureState.Capturing,
            )) {
                val content = captureDialogContent(state, control, player)
                assertThat(content.title).isEqualTo("Map $control")
                assertThat(content.body).contains("Press a button or move a stick").contains(player)
                assertThat(content.secondary).isEqualTo(CAPTURE_BACK_HINT)
                assertThat(content.isError).isFalse()
            }
        }

        @Test
        fun `result shows the captured binding label`() {
            val content = captureDialogContent(
                DesktopCaptureState.Result(PhysicalBinding.Key(NeutralKey.BUTTON_X.platformCode)),
                control,
                player,
            )
            assertThat(content.body).isEqualTo("Captured: Button X")
            assertThat(content.secondary).isEmpty()
            assertThat(content.isError).isFalse()
        }

        @Test
        fun `timeout is an error state with the no-input message`() {
            val content = captureDialogContent(DesktopCaptureState.TimedOut, control, player)
            assertThat(content.body).isEqualTo("No input detected")
            assertThat(content.secondary).isEqualTo(CAPTURE_BACK_HINT)
            assertThat(content.isError).isTrue()
        }

        @Test
        fun `no device assigned is an error state naming the player`() {
            val content = captureDialogContent(DesktopCaptureState.NoDeviceAssigned, control, player)
            assertThat(content.body).isEqualTo("Connect $player to remap inputs")
            assertThat(content.isError).isTrue()
        }

        @Test
        fun `idle and cancelled render empty placeholders`() {
            for (state in listOf<DesktopCaptureState>(
                DesktopCaptureState.Idle,
                DesktopCaptureState.Cancelled,
            )) {
                val content = captureDialogContent(state, control, player)
                assertThat(content.title).isEmpty()
                assertThat(content.body).isEmpty()
                assertThat(content.secondary).isEmpty()
                assertThat(content.isError).isFalse()
            }
        }
    }
}
