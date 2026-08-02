package com.romm.androidtv.controller.config

import android.view.KeyEvent
import android.view.MotionEvent
import com.romm.androidtv.controller.model.AXIS_TO_CONTROL
import com.romm.androidtv.controller.model.KEYCODE_TO_CONTROL
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.emulation.model.CoreManifest

/**
 * Static catalog of per-core controller profiles for every approved emulator core.
 *
 * Each profile declares the console-facing metadata, the complete list of console
 * controls with their RetroPad / [LogicalControl] targets (sourced from each core's
 * input descriptors), per-player default physical bindings that preserve today's
 * runtime behavior, and artwork/highlight metadata.
 *
 * Default console-control -> RetroPad/LogicalControl mapping (documentation-as-code):
 * - **genesis_plus_gx** — D-Pad -> DPAD; A->Y, B->B, C->A, X->L, Y->X, Z->R; Mode->Select; Start->Start.
 * - **snes9x** — D-Pad -> DPAD; A->A, B->B, X->X, Y->Y; L->L, R->R; Select->Select; Start->Start.
 * - **fceumm** — D-Pad -> DPAD; A->A, B->B; Select->Select; Start->Start.
 * - **mgba** — D-Pad -> DPAD; A->A, B->B; L->L, R->R; Select->Select; Start->Start.
 * - **stella** — D-Pad -> DPAD; Trigger->A, Fire->B, Booster->Y; Select->Select; Start->Reset(Start).
 * - **gambatte** — D-Pad -> DPAD; A->A, B->B; Select->Select; Start->Start.
 * - **beetle_pce_fast** — D-Pad -> DPAD; I->A, II->B, III->Y, IV->X, V->L, VI->R; Select->Select; Run->Start.
 * - **mednafen_ngp** — D-Pad -> DPAD; A->B, B->A (A/B swap); Option->Start.
 * - **mednafen_wswan** — D-Pad -> DPAD; A->A, B->B; Start->Start.
 * - **handy** — D-Pad -> DPAD; A->A, B->B; Option 1->L, Option 2->R; Pause->Start.
 * - **prosystem** — D-Pad -> DPAD; Button 1->B, Button 2->A; Pause->Start; Select->Select.
 * - **pcsx_rearmed** — D-Pad -> DPAD; Cross->B, Circle->A, Triangle->X, Square->Y; L1->L, R1->R,
 *   L2->L2(trigger), R2->R2(trigger), L3->L3, R3->R3; Select->Select; Start->Start;
 *   Left/Right Stick -> AXIS_LX/LY/RX/RY.
 * - **mupen64plus_next** — D-Pad -> DPAD; A Button->B, B Button->Y, C-Up->X, C-Down->A, C-Left->L,
 *   C-Right->R; Z Trigger->L2(trigger), L Shoulder->Select, R Shoulder->R2; Start->Start;
 *   Control Stick -> AXIS_LX/LY.
 *
 * **Artwork attribution** — The seven handheld profiles (mgba, gambatte, beetle_pce_fast,
 * mednafen_ngp, mednafen_wswan, handy, prosystem) use silhouette illustrations from the
 * "1 Color Controllers and Handhelds" collection by Pineapple Graphics on Etsy
 * (https://www.etsy.com/shop/PineappleGraphicsShp). The author provides no license;
 * attribution is required. The actual vector SVG assets are imported in a later phase;
 * `resourceName` values remain as placeholders until that migration completes.
 */
object CoreControllerProfiles {

    /** One profile per approved core, in stable [CoreManifest] order. */
    val all: List<CoreControllerProfile> = listOf(
        genesisPlusGx(),
        snes9x(),
        fceumm(),
        mgba(),
        stella(),
        gambatte(),
        beetlePceFast(),
        mednafenNgp(),
        mednafenWswan(),
        handy(),
        prosystem(),
        pcsxRearmed(),
        mupen64PlusNext(),
    )

    /** Look up a profile by its core id. */
    fun byCoreId(coreId: String): CoreControllerProfile? =
        all.find { it.coreId == coreId }

    /** Profiles whose core id is in [CoreManifest.approvedEntries]. */
    fun forApprovedCores(): List<CoreControllerProfile> {
        val approvedIds = CoreManifest.approvedEntries().map { it.coreId }.toSet()
        return all.filter { it.coreId in approvedIds }
    }

    // -------------------------------------------------------------------------
    // Profiles
    // -------------------------------------------------------------------------

    private fun genesisPlusGx() = profile(
        coreId = "genesis_plus_gx",
        consoleName = "Sega Systems",
        consoleSubtitle = "Genesis, Master System, Game Gear, and Sega CD",
        playerCount = 2,
        artwork = controllerconsArt("controller_outline_genesis"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_Y, InputKind.BUTTON, circle("button_a", 0.62f, 0.55f, 0.05f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.72f, 0.55f, 0.05f)),
            desc(CoreControlId.BUTTON_C, "C", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_c", 0.82f, 0.55f, 0.05f)),
            desc(CoreControlId.BUTTON_X, "X", LogicalControl.BUTTON_LB, InputKind.BUTTON, circle("button_x", 0.62f, 0.42f, 0.05f)),
            desc(CoreControlId.BUTTON_Y, "Y", LogicalControl.BUTTON_X, InputKind.BUTTON, circle("button_y", 0.72f, 0.42f, 0.05f)),
            desc(CoreControlId.BUTTON_Z, "Z", LogicalControl.BUTTON_RB, InputKind.BUTTON, circle("button_z", 0.82f, 0.42f, 0.05f)),
            desc(CoreControlId.MODE, "Mode", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("mode", 0.52f, 0.55f, 0.07f, 0.05f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.52f, 0.42f, 0.07f, 0.05f)),
        ),
    )

    private fun snes9x() = profile(
        coreId = "snes9x",
        consoleName = "Super Nintendo",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = controllerconsArt("controller_outline_snes"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.72f, 0.58f, 0.05f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.80f, 0.50f, 0.05f)),
            desc(CoreControlId.BUTTON_X, "X", LogicalControl.BUTTON_X, InputKind.BUTTON, circle("button_x", 0.64f, 0.50f, 0.05f)),
            desc(CoreControlId.BUTTON_Y, "Y", LogicalControl.BUTTON_Y, InputKind.BUTTON, circle("button_y", 0.72f, 0.42f, 0.05f)),
            desc(CoreControlId.L1, "L", LogicalControl.BUTTON_LB, InputKind.BUTTON, rect("l1", 0.16f, 0.05f, 0.18f, 0.07f)),
            desc(CoreControlId.R1, "R", LogicalControl.BUTTON_RB, InputKind.BUTTON, rect("r1", 0.66f, 0.05f, 0.18f, 0.07f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("select", 0.46f, 0.55f, 0.07f, 0.05f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.56f, 0.55f, 0.07f, 0.05f)),
        ),
    )

    private fun fceumm() = profile(
        coreId = "fceumm",
        consoleName = "Nintendo Entertainment System",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = controllerconsArt("controller_outline_nes"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.70f, 0.49f, 0.05f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.80f, 0.49f, 0.05f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("select", 0.46f, 0.55f, 0.07f, 0.05f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.56f, 0.55f, 0.07f, 0.05f)),
        ),
    )

    private fun mgba() = profile(
        coreId = "mgba",
        consoleName = "Game Boy Advance",
        consoleSubtitle = null,
        playerCount = 1,
        artwork = pineappleGraphicsArt("controller_outline_gba"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.70f, 0.49f, 0.05f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.80f, 0.49f, 0.05f)),
            desc(CoreControlId.L1, "L", LogicalControl.BUTTON_LB, InputKind.BUTTON, rect("l1", 0.16f, 0.05f, 0.18f, 0.07f)),
            desc(CoreControlId.R1, "R", LogicalControl.BUTTON_RB, InputKind.BUTTON, rect("r1", 0.66f, 0.05f, 0.18f, 0.07f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("select", 0.46f, 0.55f, 0.07f, 0.05f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.56f, 0.55f, 0.07f, 0.05f)),
        ),
    )

    private fun stella() = profile(
        coreId = "stella",
        consoleName = "Atari 2600",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = controllerconsArt("controller_outline_atari2600"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_A, "Trigger", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.66f, 0.49f, 0.05f)),
            desc(CoreControlId.BUTTON_B, "Fire", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.76f, 0.49f, 0.05f)),
            desc(CoreControlId.BUTTON_Y, "Booster", LogicalControl.BUTTON_Y, InputKind.BUTTON, circle("button_y", 0.86f, 0.49f, 0.05f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("select", 0.46f, 0.55f, 0.07f, 0.05f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.56f, 0.55f, 0.07f, 0.05f)),
        ),
    )

    private fun gambatte() = profile(
        coreId = "gambatte",
        consoleName = "Game Boy / Game Boy Color",
        consoleSubtitle = null,
        playerCount = 1,
        artwork = pineappleGraphicsArt("controller_outline_gb"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.70f, 0.49f, 0.05f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.80f, 0.49f, 0.05f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("select", 0.46f, 0.55f, 0.07f, 0.05f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.56f, 0.55f, 0.07f, 0.05f)),
        ),
    )

    private fun beetlePceFast() = profile(
        coreId = "beetle_pce_fast",
        consoleName = "TurboGrafx-16",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = pineappleGraphicsArt("controller_outline_tg16"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_I, "I", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_i", 0.62f, 0.55f, 0.05f)),
            desc(CoreControlId.BUTTON_II, "II", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_ii", 0.72f, 0.55f, 0.05f)),
            desc(CoreControlId.BUTTON_III, "III", LogicalControl.BUTTON_Y, InputKind.BUTTON, circle("button_iii", 0.82f, 0.55f, 0.05f)),
            desc(CoreControlId.BUTTON_IV, "IV", LogicalControl.BUTTON_X, InputKind.BUTTON, circle("button_iv", 0.62f, 0.42f, 0.05f)),
            desc(CoreControlId.BUTTON_V, "V", LogicalControl.BUTTON_LB, InputKind.BUTTON, circle("button_v", 0.72f, 0.42f, 0.05f)),
            desc(CoreControlId.BUTTON_VI, "VI", LogicalControl.BUTTON_RB, InputKind.BUTTON, circle("button_vi", 0.82f, 0.42f, 0.05f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("select", 0.46f, 0.55f, 0.07f, 0.05f)),
            desc(CoreControlId.START, "Run", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.56f, 0.55f, 0.07f, 0.05f)),
        ),
    )

    private fun mednafenNgp() = profile(
        coreId = "mednafen_ngp",
        consoleName = "Neo Geo Pocket",
        consoleSubtitle = null,
        playerCount = 1,
        artwork = pineappleGraphicsArt("controller_outline_ngp"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_a", 0.70f, 0.49f, 0.05f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_b", 0.80f, 0.49f, 0.05f)),
            desc(CoreControlId.OPTION, "Option", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("option", 0.52f, 0.55f, 0.07f, 0.05f)),
        ),
    )

    private fun mednafenWswan() = profile(
        coreId = "mednafen_wswan",
        consoleName = "WonderSwan",
        consoleSubtitle = null,
        playerCount = 1,
        artwork = pineappleGraphicsArt("controller_outline_wswan"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.70f, 0.49f, 0.05f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.80f, 0.49f, 0.05f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.52f, 0.55f, 0.07f, 0.05f)),
        ),
    )

    private fun handy() = profile(
        coreId = "handy",
        consoleName = "Atari Lynx",
        consoleSubtitle = null,
        playerCount = 1,
        artwork = pineappleGraphicsArt("controller_outline_lynx"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.62f, 0.56f, 0.05f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.78f, 0.56f, 0.05f)),
            desc(CoreControlId.OPTION_1, "Option 1", LogicalControl.BUTTON_LB, InputKind.BUTTON, circle("option_1", 0.62f, 0.44f, 0.05f)),
            desc(CoreControlId.OPTION_2, "Option 2", LogicalControl.BUTTON_RB, InputKind.BUTTON, circle("option_2", 0.78f, 0.44f, 0.05f)),
            desc(CoreControlId.PAUSE, "Pause", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("pause", 0.70f, 0.63f, 0.07f, 0.05f)),
        ),
    )

    private fun prosystem() = profile(
        coreId = "prosystem",
        consoleName = "Atari 7800",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = pineappleGraphicsArt("controller_outline_atari7800"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_1, "Button 1", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_1", 0.70f, 0.49f, 0.05f)),
            desc(CoreControlId.BUTTON_2, "Button 2", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_2", 0.80f, 0.49f, 0.05f)),
            desc(CoreControlId.PAUSE, "Pause", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("pause", 0.56f, 0.55f, 0.07f, 0.05f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("select", 0.46f, 0.55f, 0.07f, 0.05f)),
        ),
    )

    private fun pcsxRearmed() = profile(
        coreId = "pcsx_rearmed",
        consoleName = "PlayStation",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = controllerconsArt("controller_outline_ps1"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_B, "Cross", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.80f, 0.50f, 0.05f)),
            desc(CoreControlId.BUTTON_A, "Circle", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.72f, 0.58f, 0.05f)),
            desc(CoreControlId.BUTTON_X, "Triangle", LogicalControl.BUTTON_X, InputKind.BUTTON, circle("button_x", 0.72f, 0.42f, 0.05f)),
            desc(CoreControlId.BUTTON_Y, "Square", LogicalControl.BUTTON_Y, InputKind.BUTTON, circle("button_y", 0.64f, 0.50f, 0.05f)),
            desc(CoreControlId.L1, "L1", LogicalControl.BUTTON_LB, InputKind.BUTTON, rect("l1", 0.16f, 0.05f, 0.18f, 0.07f)),
            desc(CoreControlId.R1, "R1", LogicalControl.BUTTON_RB, InputKind.BUTTON, rect("r1", 0.66f, 0.05f, 0.18f, 0.07f)),
            desc(CoreControlId.L2, "L2", LogicalControl.BUTTON_LT, InputKind.TRIGGER, rect("l2", 0.16f, 0.02f, 0.18f, 0.05f)),
            desc(CoreControlId.R2, "R2", LogicalControl.BUTTON_RT, InputKind.TRIGGER, rect("r2", 0.66f, 0.02f, 0.18f, 0.05f)),
            desc(CoreControlId.L3, "L3", LogicalControl.BUTTON_L3, InputKind.BUTTON, circle("l3", 0.33f, 0.55f, 0.06f)),
            desc(CoreControlId.R3, "R3", LogicalControl.BUTTON_R3, InputKind.BUTTON, circle("r3", 0.63f, 0.55f, 0.06f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("select", 0.46f, 0.55f, 0.07f, 0.05f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.56f, 0.55f, 0.07f, 0.05f)),
            desc(CoreControlId.LEFT_STICK_X, "Left Stick X", LogicalControl.AXIS_LX, InputKind.ANALOG_STICK, oval("left_stick_x", 0.30f, 0.53f, 0.09f, 0.06f)),
            desc(CoreControlId.LEFT_STICK_Y, "Left Stick Y", LogicalControl.AXIS_LY, InputKind.ANALOG_STICK, oval("left_stick_y", 0.30f, 0.53f, 0.09f, 0.06f)),
            desc(CoreControlId.RIGHT_STICK_X, "Right Stick X", LogicalControl.AXIS_RX, InputKind.ANALOG_STICK, oval("right_stick_x", 0.60f, 0.53f, 0.09f, 0.06f)),
            desc(CoreControlId.RIGHT_STICK_Y, "Right Stick Y", LogicalControl.AXIS_RY, InputKind.ANALOG_STICK, oval("right_stick_y", 0.60f, 0.53f, 0.09f, 0.06f)),
        ),
    )

    private fun mupen64PlusNext() = profile(
        coreId = "mupen64plus_next",
        consoleName = "Nintendo 64",
        consoleSubtitle = null,
        playerCount = 4,
        artwork = controllerconsArt("controller_outline_n64"),
        controls = dpad() + listOf(
            desc(CoreControlId.BUTTON_A, "A Button", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_a", 0.72f, 0.58f, 0.05f)),
            desc(CoreControlId.BUTTON_B, "B Button", LogicalControl.BUTTON_Y, InputKind.BUTTON, circle("button_b", 0.80f, 0.50f, 0.05f)),
            desc(CoreControlId.N64_C_UP, "C-Up", LogicalControl.BUTTON_X, InputKind.BUTTON, circle("n64_c_up", 0.72f, 0.42f, 0.04f)),
            desc(CoreControlId.N64_C_DOWN, "C-Down", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("n64_c_down", 0.64f, 0.50f, 0.04f)),
            desc(CoreControlId.N64_C_LEFT, "C-Left", LogicalControl.BUTTON_LB, InputKind.BUTTON, circle("n64_c_left", 0.56f, 0.50f, 0.04f)),
            desc(CoreControlId.N64_C_RIGHT, "C-Right", LogicalControl.BUTTON_RB, InputKind.BUTTON, circle("n64_c_right", 0.88f, 0.50f, 0.04f)),
            desc(CoreControlId.Z, "Z Trigger", LogicalControl.BUTTON_LT, InputKind.TRIGGER, rect("z", 0.68f, 0.66f, 0.10f, 0.05f)),
            desc(CoreControlId.L1, "L Shoulder", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("l1", 0.16f, 0.05f, 0.18f, 0.07f)),
            desc(CoreControlId.R1, "R Shoulder", LogicalControl.BUTTON_RT, InputKind.BUTTON, rect("r1", 0.66f, 0.05f, 0.18f, 0.07f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.52f, 0.42f, 0.07f, 0.05f)),
            desc(CoreControlId.LEFT_STICK_X, "Control Stick X", LogicalControl.AXIS_LX, InputKind.ANALOG_STICK, oval("left_stick_x", 0.36f, 0.53f, 0.10f, 0.06f)),
            desc(CoreControlId.LEFT_STICK_Y, "Control Stick Y", LogicalControl.AXIS_LY, InputKind.ANALOG_STICK, oval("left_stick_y", 0.36f, 0.53f, 0.10f, 0.06f)),
        ),
    )

    // -------------------------------------------------------------------------
    // Builders
    // -------------------------------------------------------------------------

    private fun profile(
        coreId: String,
        consoleName: String,
        consoleSubtitle: String?,
        playerCount: Int,
        artwork: ControllerArtwork,
        controls: List<CoreControlDescriptor>,
    ): CoreControllerProfile {
        val bindings = controls.associate { it.id to defaultBinding(it.target) }
        val defaults = (0 until playerCount).associateWith { PlayerControllerConfig(bindings) }
        return CoreControllerProfile(
            coreId = coreId,
            consoleName = consoleName,
            consoleSubtitle = consoleSubtitle,
            playerCount = playerCount,
            artwork = artwork,
            controls = controls,
            defaults = defaults,
        )
    }

    /**
     * Shared D-pad controls with identical geometry across every profile.
     * Region ids derive from the control ids, which are unique per profile.
     */
    private fun dpad(): List<CoreControlDescriptor> = listOf(
        desc(CoreControlId.D_PAD_UP, "D-Pad Up", LogicalControl.DPAD_UP, InputKind.DPAD, circle("d_pad_up", 0.075f, 0.42f, 0.06f)),
        desc(CoreControlId.D_PAD_DOWN, "D-Pad Down", LogicalControl.DPAD_DOWN, InputKind.DPAD, circle("d_pad_down", 0.075f, 0.56f, 0.06f)),
        desc(CoreControlId.D_PAD_LEFT, "D-Pad Left", LogicalControl.DPAD_LEFT, InputKind.DPAD, circle("d_pad_left", 0.015f, 0.49f, 0.06f)),
        desc(CoreControlId.D_PAD_RIGHT, "D-Pad Right", LogicalControl.DPAD_RIGHT, InputKind.DPAD, circle("d_pad_right", 0.135f, 0.49f, 0.06f)),
    )

    private fun desc(
        id: CoreControlId,
        label: String,
        target: LogicalControl,
        inputKind: InputKind,
        highlightRegion: ControllerHighlightRegion,
    ) = CoreControlDescriptor(id, label, target, inputKind, highlightRegion)

    /**
     * Derive the default physical binding for a logical target, mirroring
     * [KEYCODE_TO_CONTROL] (buttons/d-pad) and [AXIS_TO_CONTROL] (analog axes)
     * exactly so unconfigured cores keep today's default controller behavior.
     */
    private fun defaultBinding(target: LogicalControl): PhysicalBinding = when (target.type) {
        LogicalControl.Type.BUTTON -> {
            // KEYCODE_TO_CONTROL omits L2/R2 (only L1/R1 are standard gamepad buttons
            // there); map them to their Android key codes explicitly.
            val keyCode = when (target) {
                LogicalControl.BUTTON_LT -> KeyEvent.KEYCODE_BUTTON_L2
                LogicalControl.BUTTON_RT -> KeyEvent.KEYCODE_BUTTON_R2
                else -> KEYCODE_TO_CONTROL.entries.first { it.value == target }.key
            }
            PhysicalBinding.Key(keyCode)
        }
        LogicalControl.Type.AXIS -> {
            // Right-stick axes appear twice in AXIS_TO_CONTROL (AXIS_RX and AXIS_Z both
            // map to AXIS_RX). AxisMappingPolicy prefers AXIS_RX/AXIS_RY over Z/RZ.
            val axis = when (target) {
                LogicalControl.AXIS_RX -> MotionEvent.AXIS_RX
                LogicalControl.AXIS_RY -> MotionEvent.AXIS_RY
                else -> AXIS_TO_CONTROL.entries.first { it.value == target }.key
            }
            PhysicalBinding.Axis(axis)
        }
    }

    private fun controllerconsArt(resourceName: String) = ControllerArtwork(
        resourceName = resourceName,
        source = "Controllercons 2.1 outline",
        license = "SIL Open Font License 1.1",
        licenseAssetPath = "licenses/controllercons-OFL-1.1.txt",
        viewBoxWidth = 64f,
        viewBoxHeight = 64f,
    )

    /**
     * Artwork metadata for the seven handheld-profile silhouettes sourced from the
     * Pineapple Graphics Etsy collection ("1 Color Controllers and Handhelds").
     * No license is provided by the author; attribution is required.
     */
    private fun pineappleGraphicsArt(resourceName: String) = ControllerArtwork(
        resourceName = resourceName,
        source = "Pineapple Graphics (Etsy) — 1 Color Controllers and Handhelds",
        license = "No license — attribution required",
        licenseAssetPath = null,
        viewBoxWidth = 360f,
        viewBoxHeight = 360f,
    )

    private fun region(id: String, shape: HighlightShape, x: Float, y: Float, width: Float, height: Float) =
        ControllerHighlightRegion(id, shape, x, y, width, height)

    private fun circle(id: String, x: Float, y: Float, size: Float) =
        region(id, HighlightShape.CIRCLE, x, y, size, size)

    private fun oval(id: String, x: Float, y: Float, width: Float, height: Float) =
        region(id, HighlightShape.OVAL, x, y, width, height)

    private fun rect(id: String, x: Float, y: Float, width: Float, height: Float) =
        region(id, HighlightShape.RECT, x, y, width, height)
}
