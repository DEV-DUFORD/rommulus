package com.romm.androidtv.controller.config

import com.romm.androidtv.controller.model.NEUTRAL_AXIS_TO_CONTROL
import com.romm.androidtv.controller.model.NEUTRAL_KEY_TO_CONTROL
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.androidtv.emulation.model.CoreManifest
import com.romm.androidtv.emulation.model.ANDROID_CORE_ABIS

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
 * - **dolphin** — D-Pad -> DPAD; A/B/X/Y -> A/B/X/Y; L/R -> L2/R2 triggers; Z -> R;
 *   Start->Start; Control Stick -> AXIS_LX/LY; C-Stick -> AXIS_RX/RY.
 * - **lrps2** — D-Pad -> DPAD; Cross->B, Circle->A, Triangle->X, Square->Y; L1->L, R1->R,
 *   L2->L2(trigger), R2->R2(trigger), L3->L3, R3->R3; Select->Select; Start->Start;
 *   Left/Right Stick -> AXIS_LX/LY/RX/RY.
 *
 * One profile uses the authoritative Controllercons 2.1 vector. The other fourteen use
 * artist-provided "1 Color Controllers and Handhelds" silhouettes.
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
        dolphin(),
        lrps2(),
    )

    /** Look up a profile by its core id. */
    fun byCoreId(coreId: String): CoreControllerProfile? =
        all.find { it.coreId == coreId }

    /** Profiles whose core id is in [CoreManifest.approvedEntries]. */
    fun forApprovedCores(supportedAbis: Set<String> = ANDROID_CORE_ABIS): List<CoreControllerProfile> {
        val approvedIds = CoreManifest.approvedEntries()
            .filter { it.supportedAbis.any(supportedAbis::contains) }
            .map { it.coreId }
            .toSet()
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
        artwork = artistProvidedArt("controller_outline_genesis"),
        controls = dpad(0.314f, 0.478f, 0.085f) + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_Y, InputKind.BUTTON, circle("button_a", 0.596f, 0.507f, 0.061f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.664f, 0.468f, 0.061f)),
            desc(CoreControlId.BUTTON_C, "C", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_c", 0.737f, 0.447f, 0.061f)),
            desc(CoreControlId.BUTTON_X, "X", LogicalControl.BUTTON_LB, InputKind.BUTTON, circle("button_x", 0.581f, 0.439f, 0.042f)),
            desc(CoreControlId.BUTTON_Y, "Y", LogicalControl.BUTTON_X, InputKind.BUTTON, circle("button_y", 0.637f, 0.410f, 0.042f)),
            desc(CoreControlId.BUTTON_Z, "Z", LogicalControl.BUTTON_RB, InputKind.BUTTON, circle("button_z", 0.700f, 0.395f, 0.042f)),
            desc(CoreControlId.MODE, "Mode", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, oval("mode", 0.468f, 0.438f, 0.068f, 0.035f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, oval("start", 0.468f, 0.438f, 0.068f, 0.035f)),
        ),
    )

    private fun snes9x() = profile(
        coreId = "snes9x",
        consoleName = "Super Nintendo",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = artistProvidedArt("controller_outline_snes"),
        controls = dpad(0.277f, 0.505f, 0.075f) + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.750f, 0.463f, 0.095f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.677f, 0.524f, 0.095f)),
            desc(CoreControlId.BUTTON_X, "X", LogicalControl.BUTTON_X, InputKind.BUTTON, circle("button_x", 0.674f, 0.403f, 0.095f)),
            desc(CoreControlId.BUTTON_Y, "Y", LogicalControl.BUTTON_Y, InputKind.BUTTON, circle("button_y", 0.598f, 0.455f, 0.095f)),
            desc(CoreControlId.L1, "L", LogicalControl.BUTTON_LB, InputKind.BUTTON, rect("l1", 0.20f, 0.30f, 0.17f, 0.06f)),
            desc(CoreControlId.R1, "R", LogicalControl.BUTTON_RB, InputKind.BUTTON, rect("r1", 0.63f, 0.30f, 0.17f, 0.06f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, oval("select", 0.391f, 0.493f, 0.075f, 0.075f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, oval("start", 0.474f, 0.497f, 0.075f, 0.075f)),
        ),
    )

    private fun fceumm() = profile(
        coreId = "fceumm",
        consoleName = "Nintendo Entertainment System",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = controllerconsArt("controller_outline_nes"),
        controls = dpad(0.195f, 0.538f, 0.075f) + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.750f, 0.494f, 0.148f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.594f, 0.494f, 0.148f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, oval("select", 0.350f, 0.545f, 0.120f, 0.075f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, oval("start", 0.470f, 0.545f, 0.120f, 0.075f)),
        ),
    )

    private fun mgba() = profile(
        coreId = "mgba",
        consoleName = "Game Boy Advance",
        consoleSubtitle = null,
        playerCount = 1,
        artwork = artistProvidedArt("controller_outline_gba"),
        controls = dpad(0.231f, 0.464f, 0.075f) + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.770f, 0.423f, 0.065f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.704f, 0.447f, 0.065f)),
            desc(CoreControlId.L1, "L", LogicalControl.BUTTON_LB, InputKind.BUTTON, rect("l1", 0.155f, 0.295f, 0.135f, 0.055f)),
            desc(CoreControlId.R1, "R", LogicalControl.BUTTON_RB, InputKind.BUTTON, rect("r1", 0.715f, 0.295f, 0.135f, 0.055f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, circle("select", 0.265f, 0.552f, 0.032f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, circle("start", 0.265f, 0.592f, 0.032f)),
        ),
    )

    private fun stella() = profile(
        coreId = "stella",
        consoleName = "Atari 2600",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = artistProvidedArt("controller_outline_atari2600"),
        controls = dpad(0.50f, 0.392f, 0.09f) + listOf(
            desc(CoreControlId.BUTTON_A, "Trigger", LogicalControl.BUTTON_A, InputKind.BUTTON, rect("button_a", 0.340f, 0.155f, 0.07f, 0.13f)),
            desc(CoreControlId.BUTTON_B, "Fire", LogicalControl.BUTTON_B, InputKind.BUTTON, rect("button_b", 0.590f, 0.155f, 0.07f, 0.13f)),
            desc(CoreControlId.BUTTON_Y, "Booster", LogicalControl.BUTTON_Y, InputKind.BUTTON, rect("button_y", 0.590f, 0.155f, 0.07f, 0.13f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("select", 0.410f, 0.720f, 0.075f, 0.045f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.515f, 0.720f, 0.075f, 0.045f)),
        ),
    )

    private fun gambatte() = profile(
        coreId = "gambatte",
        consoleName = "Game Boy / Game Boy Color",
        consoleSubtitle = null,
        playerCount = 1,
        artwork = artistProvidedArt("controller_outline_gb"),
        controls = dpad(0.373f, 0.615f, 0.075f) + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.623f, 0.573f, 0.06f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.547f, 0.597f, 0.06f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, oval("select", 0.440f, 0.720f, 0.052f, 0.028f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, oval("start", 0.508f, 0.720f, 0.052f, 0.028f)),
        ),
    )

    private fun beetlePceFast() = profile(
        coreId = "beetle_pce_fast",
        consoleName = "TurboGrafx-16",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = artistProvidedArt("controller_outline_tg16"),
        controls = dpad(0.281f, 0.563f, 0.075f) + listOf(
            desc(CoreControlId.BUTTON_I, "I", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_i", 0.756f, 0.524f, 0.06f)),
            desc(CoreControlId.BUTTON_II, "II", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_ii", 0.676f, 0.529f, 0.06f)),
            desc(CoreControlId.BUTTON_III, "III", LogicalControl.BUTTON_Y, InputKind.BUTTON, circle("button_iii", 0.613f, 0.569f, 0.06f)),
            desc(CoreControlId.BUTTON_IV, "IV", LogicalControl.BUTTON_X, InputKind.BUTTON, circle("button_iv", 0.584f, 0.494f, 0.06f)),
            desc(CoreControlId.BUTTON_V, "V", LogicalControl.BUTTON_LB, InputKind.BUTTON, circle("button_v", 0.648f, 0.455f, 0.06f)),
            desc(CoreControlId.BUTTON_VI, "VI", LogicalControl.BUTTON_RB, InputKind.BUTTON, circle("button_vi", 0.727f, 0.449f, 0.06f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, oval("select", 0.430f, 0.572f, 0.052f, 0.03f)),
            desc(CoreControlId.START, "Run", LogicalControl.BUTTON_START, InputKind.BUTTON, oval("start", 0.505f, 0.572f, 0.052f, 0.03f)),
        ),
    )

    private fun mednafenNgp() = profile(
        coreId = "mednafen_ngp",
        consoleName = "Neo Geo Pocket",
        consoleSubtitle = null,
        playerCount = 1,
        artwork = artistProvidedArt("controller_outline_ngp"),
        controls = dpad(0.242f, 0.465f, 0.075f) + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_a", 0.692f, 0.435f, 0.06f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_b", 0.760f, 0.389f, 0.06f)),
            desc(CoreControlId.OPTION, "Option", LogicalControl.BUTTON_START, InputKind.BUTTON, oval("option", 0.178f, 0.348f, 0.052f, 0.027f)),
        ),
    )

    private fun mednafenWswan() = profile(
        coreId = "mednafen_wswan",
        consoleName = "WonderSwan",
        consoleSubtitle = null,
        playerCount = 1,
        artwork = artistProvidedArt("controller_outline_wswan"),
        controls = dpad(0.233f, 0.400f, 0.075f) + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.748f, 0.600f, 0.06f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.788f, 0.558f, 0.06f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, oval("start", 0.500f, 0.650f, 0.075f, 0.04f)),
        ),
    )

    private fun handy() = profile(
        coreId = "handy",
        consoleName = "Atari Lynx",
        consoleSubtitle = null,
        playerCount = 1,
        artwork = artistProvidedArt("controller_outline_lynx"),
        controls = dpad(0.247f, 0.50f, 0.075f) + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.805f, 0.370f, 0.06f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.750f, 0.370f, 0.06f)),
            desc(CoreControlId.OPTION_1, "Option 1", LogicalControl.BUTTON_LB, InputKind.BUTTON, oval("option_1", 0.365f, 0.463f, 0.05f, 0.027f)),
            desc(CoreControlId.OPTION_2, "Option 2", LogicalControl.BUTTON_RB, InputKind.BUTTON, oval("option_2", 0.365f, 0.513f, 0.05f, 0.027f)),
            desc(CoreControlId.PAUSE, "Pause", LogicalControl.BUTTON_START, InputKind.BUTTON, oval("pause", 0.680f, 0.487f, 0.05f, 0.027f)),
        ),
    )

    private fun prosystem() = profile(
        coreId = "prosystem",
        consoleName = "Atari 7800",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = artistProvidedArt("controller_outline_atari7800"),
        controls = dpad(0.250f, 0.414f, 0.075f) + listOf(
            desc(CoreControlId.BUTTON_1, "Button 1", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_1", 0.470f, 0.535f, 0.10f)),
            desc(CoreControlId.BUTTON_2, "Button 2", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_2", 0.647f, 0.535f, 0.10f)),
            desc(CoreControlId.PAUSE, "Pause", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("pause", 0.515f, 0.635f, 0.10f, 0.045f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("select", 0.410f, 0.635f, 0.10f, 0.045f)),
        ),
    )

    private fun pcsxRearmed() = profile(
        coreId = "pcsx_rearmed",
        consoleName = "PlayStation",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = artistProvidedArt("controller_outline_ps1"),
        defaultPrimaryBindings = playStationFaceDefaults(),
        controls = dpad(0.288f, 0.454f, 0.075f) + listOf(
            desc(CoreControlId.BUTTON_B, "Cross", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.675f, 0.475f, 0.07f)),
            desc(CoreControlId.BUTTON_A, "Circle", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.731f, 0.419f, 0.07f)),
            desc(CoreControlId.BUTTON_X, "Triangle", LogicalControl.BUTTON_X, InputKind.BUTTON, circle("button_x", 0.675f, 0.362f, 0.07f)),
            desc(CoreControlId.BUTTON_Y, "Square", LogicalControl.BUTTON_Y, InputKind.BUTTON, circle("button_y", 0.620f, 0.419f, 0.07f)),
            desc(CoreControlId.L1, "L1", LogicalControl.BUTTON_LB, InputKind.BUTTON, rect("l1", 0.235f, 0.305f, 0.155f, 0.05f)),
            desc(CoreControlId.R1, "R1", LogicalControl.BUTTON_RB, InputKind.BUTTON, rect("r1", 0.610f, 0.305f, 0.155f, 0.05f)),
            desc(CoreControlId.L2, "L2", LogicalControl.BUTTON_LT, InputKind.TRIGGER, rect("l2", 0.260f, 0.278f, 0.115f, 0.04f)),
            desc(CoreControlId.R2, "R2", LogicalControl.BUTTON_RT, InputKind.TRIGGER, rect("r2", 0.625f, 0.278f, 0.115f, 0.04f)),
            desc(CoreControlId.L3, "L3", LogicalControl.BUTTON_L3, InputKind.BUTTON, circle("l3", 0.322f, 0.487f, 0.156f)),
            desc(CoreControlId.R3, "R3", LogicalControl.BUTTON_R3, InputKind.BUTTON, circle("r3", 0.522f, 0.487f, 0.156f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, oval("select", 0.410f, 0.430f, 0.055f, 0.045f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.535f, 0.425f, 0.060f, 0.055f)),
            desc(CoreControlId.LEFT_STICK_X, "Left Stick X", LogicalControl.AXIS_LX, InputKind.ANALOG_STICK, circle("left_stick_x", 0.322f, 0.487f, 0.156f)),
            desc(CoreControlId.LEFT_STICK_Y, "Left Stick Y", LogicalControl.AXIS_LY, InputKind.ANALOG_STICK, circle("left_stick_y", 0.322f, 0.487f, 0.156f)),
            desc(CoreControlId.RIGHT_STICK_X, "Right Stick X", LogicalControl.AXIS_RX, InputKind.ANALOG_STICK, circle("right_stick_x", 0.522f, 0.487f, 0.156f)),
            desc(CoreControlId.RIGHT_STICK_Y, "Right Stick Y", LogicalControl.AXIS_RY, InputKind.ANALOG_STICK, circle("right_stick_y", 0.522f, 0.487f, 0.156f)),
        ),
    )

    private fun mupen64PlusNext() = profile(
        coreId = "mupen64plus_next",
        consoleName = "Nintendo 64",
        consoleSubtitle = null,
        playerCount = 4,
        artwork = artistProvidedArt("controller_outline_n64"),
        controls = dpad(0.308f, 0.387f, 0.037f) + listOf(
            desc(CoreControlId.BUTTON_A, "A Button", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_a", 0.641f, 0.436f, 0.037f)),
            desc(CoreControlId.BUTTON_B, "B Button", LogicalControl.BUTTON_Y, InputKind.BUTTON, circle("button_b", 0.592f, 0.387f, 0.053f)),
            desc(CoreControlId.N64_C_UP, "C-Up", LogicalControl.BUTTON_X, InputKind.BUTTON, circle("n64_c_up", 0.682f, 0.320f, 0.040f)),
            desc(CoreControlId.N64_C_DOWN, "C-Down", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("n64_c_down", 0.682f, 0.386f, 0.040f)),
            desc(CoreControlId.N64_C_LEFT, "C-Left", LogicalControl.BUTTON_LB, InputKind.BUTTON, circle("n64_c_left", 0.649f, 0.353f, 0.040f)),
            desc(CoreControlId.N64_C_RIGHT, "C-Right", LogicalControl.BUTTON_RB, InputKind.BUTTON, circle("n64_c_right", 0.716f, 0.353f, 0.040f)),
            desc(CoreControlId.Z, "Z Trigger", LogicalControl.BUTTON_LT, InputKind.TRIGGER, rect("z", 0.417f, 0.667f, 0.167f, 0.11f)),
            desc(CoreControlId.L1, "L Shoulder", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, rect("l1", 0.236f, 0.214f, 0.153f, 0.05f)),
            desc(CoreControlId.R1, "R Shoulder", LogicalControl.BUTTON_RT, InputKind.BUTTON, rect("r1", 0.639f, 0.214f, 0.153f, 0.05f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, circle("start", 0.472f, 0.386f, 0.054f)),
            desc(CoreControlId.LEFT_STICK_X, "Control Stick X", LogicalControl.AXIS_LX, InputKind.ANALOG_STICK, oval("left_stick_x", 0.440f, 0.474f, 0.119f, 0.119f)),
            desc(CoreControlId.LEFT_STICK_Y, "Control Stick Y", LogicalControl.AXIS_LY, InputKind.ANALOG_STICK, oval("left_stick_y", 0.440f, 0.474f, 0.119f, 0.119f)),
        ),
    )

    private fun dolphin() = profile(
        coreId = "dolphin",
        consoleName = "Nintendo GameCube",
        consoleSubtitle = null,
        playerCount = 4,
        artwork = artistProvidedArt("controller_outline_gamecube"),
        controls = dpad(0.386f, 0.572f, 0.075f) + listOf(
            desc(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.714f, 0.417f, 0.086f)),
            desc(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.616f, 0.458f, 0.043f)),
            desc(CoreControlId.BUTTON_X, "X", LogicalControl.BUTTON_X, InputKind.BUTTON, oval("button_x", 0.755f, 0.357f, 0.060f, 0.034f)),
            desc(CoreControlId.BUTTON_Y, "Y", LogicalControl.BUTTON_Y, InputKind.BUTTON, oval("button_y", 0.638f, 0.357f, 0.060f, 0.034f)),
            desc(CoreControlId.L2, "L", LogicalControl.BUTTON_LT, InputKind.TRIGGER, rect("l2", 0.235f, 0.270f, 0.145f, 0.050f)),
            desc(CoreControlId.R2, "R", LogicalControl.BUTTON_RT, InputKind.TRIGGER, rect("r2", 0.620f, 0.270f, 0.145f, 0.050f)),
            desc(CoreControlId.Z, "Z", LogicalControl.BUTTON_RB, InputKind.BUTTON, rect("z", 0.690f, 0.300f, 0.100f, 0.040f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, circle("start", 0.500f, 0.434f, 0.035f)),
            desc(CoreControlId.LEFT_STICK_X, "Control Stick X", LogicalControl.AXIS_LX, InputKind.ANALOG_STICK, circle("left_stick_x", 0.283f, 0.413f, 0.086f)),
            desc(CoreControlId.LEFT_STICK_Y, "Control Stick Y", LogicalControl.AXIS_LY, InputKind.ANALOG_STICK, circle("left_stick_y", 0.283f, 0.413f, 0.086f)),
            desc(CoreControlId.RIGHT_STICK_X, "C-Stick X", LogicalControl.AXIS_RX, InputKind.ANALOG_STICK, circle("right_stick_x", 0.616f, 0.573f, 0.058f)),
            desc(CoreControlId.RIGHT_STICK_Y, "C-Stick Y", LogicalControl.AXIS_RY, InputKind.ANALOG_STICK, circle("right_stick_y", 0.616f, 0.573f, 0.058f)),
        ),
    )

    private fun lrps2() = profile(
        coreId = "lrps2",
        consoleName = "PlayStation 2",
        consoleSubtitle = null,
        playerCount = 2,
        artwork = artistProvidedArt("controller_outline_playstation2"),
        defaultPrimaryBindings = playStationFaceDefaults(),
        // DualShock 2 keeps the DualShock layout (the core exposes no named input
        // descriptors), so this mirrors the pcsx_rearmed geometry and RetroPad targets.
        controls = dpad(0.288f, 0.454f, 0.075f) + listOf(
            desc(CoreControlId.BUTTON_B, "Cross", LogicalControl.BUTTON_B, InputKind.BUTTON, circle("button_b", 0.675f, 0.475f, 0.07f)),
            desc(CoreControlId.BUTTON_A, "Circle", LogicalControl.BUTTON_A, InputKind.BUTTON, circle("button_a", 0.731f, 0.419f, 0.07f)),
            desc(CoreControlId.BUTTON_X, "Triangle", LogicalControl.BUTTON_X, InputKind.BUTTON, circle("button_x", 0.675f, 0.362f, 0.07f)),
            desc(CoreControlId.BUTTON_Y, "Square", LogicalControl.BUTTON_Y, InputKind.BUTTON, circle("button_y", 0.620f, 0.419f, 0.07f)),
            desc(CoreControlId.L1, "L1", LogicalControl.BUTTON_LB, InputKind.BUTTON, rect("l1", 0.235f, 0.305f, 0.155f, 0.05f)),
            desc(CoreControlId.R1, "R1", LogicalControl.BUTTON_RB, InputKind.BUTTON, rect("r1", 0.610f, 0.305f, 0.155f, 0.05f)),
            desc(CoreControlId.L2, "L2", LogicalControl.BUTTON_LT, InputKind.TRIGGER, rect("l2", 0.260f, 0.278f, 0.115f, 0.04f)),
            desc(CoreControlId.R2, "R2", LogicalControl.BUTTON_RT, InputKind.TRIGGER, rect("r2", 0.625f, 0.278f, 0.115f, 0.04f)),
            desc(CoreControlId.L3, "L3", LogicalControl.BUTTON_L3, InputKind.BUTTON, circle("l3", 0.322f, 0.487f, 0.156f)),
            desc(CoreControlId.R3, "R3", LogicalControl.BUTTON_R3, InputKind.BUTTON, circle("r3", 0.522f, 0.487f, 0.156f)),
            desc(CoreControlId.SELECT, "Select", LogicalControl.BUTTON_SELECT, InputKind.BUTTON, oval("select", 0.410f, 0.430f, 0.055f, 0.045f)),
            desc(CoreControlId.START, "Start", LogicalControl.BUTTON_START, InputKind.BUTTON, rect("start", 0.535f, 0.425f, 0.060f, 0.055f)),
            desc(CoreControlId.LEFT_STICK_X, "Left Stick X", LogicalControl.AXIS_LX, InputKind.ANALOG_STICK, circle("left_stick_x", 0.322f, 0.487f, 0.156f)),
            desc(CoreControlId.LEFT_STICK_Y, "Left Stick Y", LogicalControl.AXIS_LY, InputKind.ANALOG_STICK, circle("left_stick_y", 0.322f, 0.487f, 0.156f)),
            desc(CoreControlId.RIGHT_STICK_X, "Right Stick X", LogicalControl.AXIS_RX, InputKind.ANALOG_STICK, circle("right_stick_x", 0.522f, 0.487f, 0.156f)),
            desc(CoreControlId.RIGHT_STICK_Y, "Right Stick Y", LogicalControl.AXIS_RY, InputKind.ANALOG_STICK, circle("right_stick_y", 0.522f, 0.487f, 0.156f)),
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
        defaultPrimaryBindings: Map<CoreControlId, PhysicalBinding> = emptyMap(),
        controls: List<CoreControlDescriptor>,
    ): CoreControllerProfile {
        val allControls = controls + desc(
            CoreControlId.PAUSE_MENU,
            "Pause Menu (hold both)",
            LogicalControl.BUTTON_SELECT,
            InputKind.BUTTON,
            rect("pause_menu", 0.001f, 0.001f, 0.001f, 0.001f),
        )
        val hasAnalogControls = controls.any { it.inputKind == InputKind.ANALOG_STICK }
        val bindings = allControls.associate { descriptor ->
            descriptor.id to ControlBindings(
                primary = if (descriptor.id.isPauseMenuControl) {
                    PhysicalBinding.Key(NeutralKey.BUTTON_THUMBL.platformCode)
                } else {
                    defaultPrimaryBindings[descriptor.id] ?: defaultBinding(descriptor.target)
                },
                secondary = if (descriptor.id.isPauseMenuControl) {
                    PhysicalBinding.Key(NeutralKey.BUTTON_THUMBR.platformCode)
                } else {
                    defaultTriggerAxisAlias(descriptor)
                        ?: defaultRightStickAxisAlias(descriptor.target)
                        ?: if (!hasAnalogControls) defaultDigitalDpadAlias(descriptor.id) else null
                },
            )
        }

        val defaults = (0 until playerCount).associateWith { PlayerControllerConfig(bindings) }
        return CoreControllerProfile(
            coreId = coreId,
            consoleName = consoleName,
            consoleSubtitle = consoleSubtitle,
            playerCount = playerCount,
            artwork = artwork,
            controls = allControls,
            defaults = defaults,
        )
    }

    private fun playStationFaceDefaults(): Map<CoreControlId, PhysicalBinding> = mapOf(
        CoreControlId.BUTTON_B to PhysicalBinding.Key(NeutralKey.BUTTON_A.platformCode),
        CoreControlId.BUTTON_A to PhysicalBinding.Key(NeutralKey.BUTTON_B.platformCode),
        CoreControlId.BUTTON_X to PhysicalBinding.Key(NeutralKey.BUTTON_Y.platformCode),
        CoreControlId.BUTTON_Y to PhysicalBinding.Key(NeutralKey.BUTTON_X.platformCode),
    )

    /**
     * Shared D-pad controls with identical geometry across every profile.
     * Region ids derive from the control ids, which are unique per profile.
     */
    private fun dpad(centerX: Float, centerY: Float, size: Float): List<CoreControlDescriptor> = listOf(
        desc(CoreControlId.D_PAD_UP, "D-Pad Up", LogicalControl.DPAD_UP, InputKind.DPAD, circle("d_pad_up", centerX - size / 2f, centerY - size * 1.35f, size)),
        desc(CoreControlId.D_PAD_DOWN, "D-Pad Down", LogicalControl.DPAD_DOWN, InputKind.DPAD, circle("d_pad_down", centerX - size / 2f, centerY + size * 0.35f, size)),
        desc(CoreControlId.D_PAD_LEFT, "D-Pad Left", LogicalControl.DPAD_LEFT, InputKind.DPAD, circle("d_pad_left", centerX - size * 1.35f, centerY - size / 2f, size)),
        desc(CoreControlId.D_PAD_RIGHT, "D-Pad Right", LogicalControl.DPAD_RIGHT, InputKind.DPAD, circle("d_pad_right", centerX + size * 0.35f, centerY - size / 2f, size)),
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
     * [NEUTRAL_KEY_TO_CONTROL] (buttons/d-pad) and [NEUTRAL_AXIS_TO_CONTROL] (analog axes)
     * exactly so unconfigured cores keep today's default controller behavior.
     */
    private fun defaultBinding(target: LogicalControl): PhysicalBinding = when (target.type) {
        LogicalControl.Type.BUTTON -> {
            // NEUTRAL_KEY_TO_CONTROL omits L2/R2 (only L1/R1 are standard gamepad buttons
            // there); map them to their neutral key platform codes explicitly.
            val keyCode = when (target) {
                LogicalControl.BUTTON_LT -> NeutralKey.BUTTON_L2.platformCode
                LogicalControl.BUTTON_RT -> NeutralKey.BUTTON_R2.platformCode
                else -> NEUTRAL_KEY_TO_CONTROL.entries.first { it.value == target }.key.platformCode
            }
            PhysicalBinding.Key(keyCode)
        }
        LogicalControl.Type.AXIS -> {
            // Right-stick axes appear twice in NEUTRAL_AXIS_TO_CONTROL (NeutralAxis.RX and
            // NeutralAxis.Z both map to AXIS_RX). AxisMappingPolicy prefers RX/RY over Z/RZ.
            val axis = when (target) {
                LogicalControl.AXIS_RX -> NeutralAxis.RX.platformCode
                LogicalControl.AXIS_RY -> NeutralAxis.RY.platformCode
                else -> NEUTRAL_AXIS_TO_CONTROL.entries.first { it.value == target }.key.platformCode
            }
            PhysicalBinding.Axis(axis)
        }
    }

    private fun defaultDigitalDpadAlias(controlId: CoreControlId): PhysicalBinding? = when (controlId) {
        CoreControlId.D_PAD_UP -> PhysicalBinding.AxisDirection(NeutralAxis.Y.platformCode, -1)
        CoreControlId.D_PAD_DOWN -> PhysicalBinding.AxisDirection(NeutralAxis.Y.platformCode, 1)
        CoreControlId.D_PAD_LEFT -> PhysicalBinding.AxisDirection(NeutralAxis.X.platformCode, -1)
        CoreControlId.D_PAD_RIGHT -> PhysicalBinding.AxisDirection(NeutralAxis.X.platformCode, 1)
        else -> null
    }

    private fun defaultTriggerAxisAlias(descriptor: CoreControlDescriptor): PhysicalBinding? {
        if (descriptor.inputKind != InputKind.TRIGGER) return null
        return when (descriptor.target) {
            LogicalControl.BUTTON_LT -> PhysicalBinding.Axis(NeutralAxis.LTRIGGER.platformCode)
            LogicalControl.BUTTON_RT -> PhysicalBinding.Axis(NeutralAxis.RTRIGGER.platformCode)
            else -> null
        }
    }

    private fun defaultRightStickAxisAlias(target: LogicalControl): PhysicalBinding? = when (target) {
        LogicalControl.AXIS_RX -> PhysicalBinding.Axis(NeutralAxis.Z.platformCode)
        LogicalControl.AXIS_RY -> PhysicalBinding.Axis(NeutralAxis.RZ.platformCode)
        else -> null
    }

    private fun controllerconsArt(resourceName: String) = ControllerArtwork(
        resourceName = resourceName,
        source = "Controllercons 2.1 solid",
        license = "SIL Open Font License 1.1",
        licenseAssetPath = "licenses/controllercons-OFL-1.1.txt",
        viewBoxWidth = 64f,
        viewBoxHeight = 64f,
    )

    private fun artistProvidedArt(resourceName: String) = ControllerArtwork(
        resourceName = resourceName,
        source = "1 Color Controllers and Handhelds (artist-provided)",
        license = "Used with artist permission",
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
