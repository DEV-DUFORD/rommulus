package com.romm.androidtv.emulation.touch

import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.CoreControllerProfile

object DefaultTouchLayouts {
    val all: List<ConsoleTouchLayout> = listOf(
        genesis(),
        snes(),
        nes(),
        gba(),
        atari2600(),
        gameBoy(),
        turboGrafx(),
        neoGeoPocket(),
        wonderSwan(),
        lynx(),
        atari7800(),
        playStation(),
        nintendo64(),
    )

    private val byCoreId = all.associateBy { it.coreId }

    fun forCore(coreId: String): ConsoleTouchLayout? = byCoreId[coreId]

    fun forProfile(
        profile: CoreControllerProfile,
        saved: TouchLayoutOverrideDocument? = null,
    ): ConsoleTouchLayout = TouchLayoutMigration.applyOrReset(
        defaults = requireNotNull(forCore(profile.coreId)) {
            "No default touch layout for ${profile.coreId}"
        },
        saved = saved,
    )

    private fun genesis() = layout(
        "genesis_plus_gx",
        dpad(.15f, .73f),
        button("a", CoreControlId.BUTTON_A, .72f, .78f),
        button("b", CoreControlId.BUTTON_B, .80f, .72f),
        button("c", CoreControlId.BUTTON_C, .88f, .66f),
        button("x", CoreControlId.BUTTON_X, .68f, .62f, small = true),
        button("y", CoreControlId.BUTTON_Y, .76f, .56f, small = true),
        button("z", CoreControlId.BUTTON_Z, .84f, .50f, small = true),
        pill("mode", CoreControlId.MODE, .44f, .84f),
        pill("start", CoreControlId.START, .56f, .84f),
        menu(),
    )

    private fun snes() = layout(
        "snes9x",
        dpad(.16f, .72f),
        button("y", CoreControlId.BUTTON_Y, .76f, .72f),
        button("b", CoreControlId.BUTTON_B, .84f, .80f),
        button("x", CoreControlId.BUTTON_X, .84f, .64f),
        button("a", CoreControlId.BUTTON_A, .92f, .72f),
        shoulder("l", CoreControlId.L1, .16f),
        shoulder("r", CoreControlId.R1, .84f),
        pill("select", CoreControlId.SELECT, .44f, .84f),
        pill("start", CoreControlId.START, .56f, .84f),
        menu(),
    )

    private fun nes() = layout(
        "fceumm",
        dpad(.16f, .74f),
        button("b", CoreControlId.BUTTON_B, .78f, .76f),
        button("a", CoreControlId.BUTTON_A, .89f, .70f),
        pill("select", CoreControlId.SELECT, .44f, .84f),
        pill("start", CoreControlId.START, .56f, .84f),
        menu(),
    )

    private fun gba() = layout(
        "mgba",
        dpad(.16f, .73f),
        button("b", CoreControlId.BUTTON_B, .80f, .76f),
        button("a", CoreControlId.BUTTON_A, .90f, .68f),
        shoulder("l", CoreControlId.L1, .14f),
        shoulder("r", CoreControlId.R1, .86f),
        pill("select", CoreControlId.SELECT, .45f, .85f),
        pill("start", CoreControlId.START, .57f, .85f),
        menu(),
    )

    private fun atari2600() = layout(
        "stella",
        dpad(.17f, .72f, .26f),
        button("trigger", CoreControlId.BUTTON_A, .76f, .64f, label = "Trigger"),
        button("fire", CoreControlId.BUTTON_B, .88f, .72f, label = "Fire"),
        button("booster", CoreControlId.BUTTON_Y, .76f, .80f, label = "Boost"),
        pill("select", CoreControlId.SELECT, .44f, .86f),
        pill("start", CoreControlId.START, .56f, .86f),
        menu(),
    )

    private fun gameBoy() = layout(
        "gambatte",
        dpad(.16f, .74f),
        button("b", CoreControlId.BUTTON_B, .80f, .77f),
        button("a", CoreControlId.BUTTON_A, .90f, .68f),
        pill("select", CoreControlId.SELECT, .44f, .86f),
        pill("start", CoreControlId.START, .56f, .86f),
        menu(),
    )

    private fun turboGrafx() = layout(
        "beetle_pce_fast",
        dpad(.14f, .73f, .23f),
        button("iii", CoreControlId.BUTTON_III, .68f, .78f, small = true),
        button("ii", CoreControlId.BUTTON_II, .78f, .74f),
        button("i", CoreControlId.BUTTON_I, .89f, .70f),
        button("iv", CoreControlId.BUTTON_IV, .68f, .62f, small = true),
        button("v", CoreControlId.BUTTON_V, .79f, .58f, small = true),
        button("vi", CoreControlId.BUTTON_VI, .90f, .54f, small = true),
        pill("select", CoreControlId.SELECT, .44f, .86f),
        pill("run", CoreControlId.START, .56f, .86f),
        menu(),
    )

    private fun neoGeoPocket() = layout(
        "mednafen_ngp",
        dpad(.17f, .74f),
        button("a", CoreControlId.BUTTON_A, .81f, .76f),
        button("b", CoreControlId.BUTTON_B, .91f, .68f),
        pill("option", CoreControlId.OPTION, .50f, .86f, label = "Option"),
        menu(),
    )

    private fun wonderSwan() = layout(
        "mednafen_wswan",
        dpad(.16f, .72f),
        button("b", CoreControlId.BUTTON_B, .82f, .75f),
        button("a", CoreControlId.BUTTON_A, .91f, .67f),
        pill("start", CoreControlId.START, .50f, .86f),
        menu(),
    )

    private fun lynx() = layout(
        "handy",
        dpad(.15f, .72f),
        button("b", CoreControlId.BUTTON_B, .81f, .76f),
        button("a", CoreControlId.BUTTON_A, .91f, .68f),
        pill("option1", CoreControlId.OPTION_1, .39f, .86f, label = "Option 1"),
        pill("option2", CoreControlId.OPTION_2, .52f, .86f, label = "Option 2"),
        pill("pause", CoreControlId.PAUSE, .65f, .86f, label = "Pause"),
        menu(),
    )

    private fun atari7800() = layout(
        "prosystem",
        dpad(.16f, .73f),
        button("one", CoreControlId.BUTTON_1, .80f, .76f, label = "1"),
        button("two", CoreControlId.BUTTON_2, .91f, .68f, label = "2"),
        pill("select", CoreControlId.SELECT, .44f, .86f),
        pill("pause", CoreControlId.PAUSE, .56f, .86f, label = "Pause"),
        menu(),
    )

    private fun playStation() = layout(
        "pcsx_rearmed",
        dpad(.13f, .70f, .22f),
        stick("left-stick", CoreControlId.LEFT_STICK_X, CoreControlId.LEFT_STICK_Y, .30f, .83f),
        stick("right-stick", CoreControlId.RIGHT_STICK_X, CoreControlId.RIGHT_STICK_Y, .68f, .83f),
        button("square", CoreControlId.BUTTON_Y, .79f, .70f, label = "□"),
        button("cross", CoreControlId.BUTTON_B, .86f, .78f, label = "✕"),
        button("triangle", CoreControlId.BUTTON_X, .86f, .62f, label = "△"),
        button("circle", CoreControlId.BUTTON_A, .93f, .70f, label = "◯"),
        shoulder("l1", CoreControlId.L1, .13f, .16f),
        shoulder("l2", CoreControlId.L2, .13f, .07f),
        shoulder("r1", CoreControlId.R1, .87f, .16f),
        shoulder("r2", CoreControlId.R2, .87f, .07f),
        button("l3", CoreControlId.L3, .40f, .71f, small = true),
        button("r3", CoreControlId.R3, .60f, .71f, small = true),
        pill("select", CoreControlId.SELECT, .44f, .62f),
        pill("start", CoreControlId.START, .56f, .62f),
        menu(),
    )

    private fun nintendo64() = layout(
        "mupen64plus_next",
        dpad(.11f, .78f, .19f),
        stick("control-stick", CoreControlId.LEFT_STICK_X, CoreControlId.LEFT_STICK_Y, .31f, .76f),
        button("b", CoreControlId.BUTTON_B, .68f, .76f, label = "B"),
        button("a", CoreControlId.BUTTON_A, .77f, .82f, label = "A"),
        button("c-left", CoreControlId.N64_C_LEFT, .83f, .67f, label = "C◀", small = true),
        button("c-down", CoreControlId.N64_C_DOWN, .89f, .73f, label = "C▼", small = true),
        button("c-up", CoreControlId.N64_C_UP, .89f, .61f, label = "C▲", small = true),
        button("c-right", CoreControlId.N64_C_RIGHT, .95f, .67f, label = "C▶", small = true),
        shoulder("l", CoreControlId.L1, .14f),
        shoulder("r", CoreControlId.R1, .86f),
        pill("z", CoreControlId.Z, .31f, .55f, label = "Z"),
        button("start", CoreControlId.START, .51f, .76f, label = "Start", small = true),
        menu(),
    )

    private fun layout(coreId: String, vararg controls: TouchControlDefinition) =
        ConsoleTouchLayout(
            layoutId = "$coreId.default.v1",
            coreId = coreId,
            controls = controls.toList(),
        )

    private fun dpad(x: Float, y: Float, size: Float = .24f) = TouchControlDefinition.Dpad(
        visualId = TouchVisualControlId("dpad"),
        center = NormalizedPoint(x, y),
        size = NormalizedSize(size, size * 1.65f),
    )

    private fun button(
        id: String,
        controlId: CoreControlId,
        x: Float,
        y: Float,
        label: String? = null,
        small: Boolean = false,
    ) = TouchControlDefinition.Button(
        visualId = TouchVisualControlId("button.$id"),
        controlId = controlId,
        displayLabel = label,
        center = NormalizedPoint(x, y),
        size = if (small) NormalizedSize(.065f, .105f) else NormalizedSize(.078f, .13f),
    )

    private fun pill(
        id: String,
        controlId: CoreControlId,
        x: Float,
        y: Float,
        label: String? = null,
    ) = TouchControlDefinition.Button(
        visualId = TouchVisualControlId("menu.$id"),
        controlId = controlId,
        displayLabel = label,
        center = NormalizedPoint(x, y),
        size = NormalizedSize(.105f, .075f),
        shape = TouchControlShape.ROUNDED_RECT,
    )

    private fun shoulder(
        id: String,
        controlId: CoreControlId,
        x: Float,
        y: Float = .10f,
    ) = TouchControlDefinition.Button(
        visualId = TouchVisualControlId("shoulder.$id"),
        controlId = controlId,
        center = NormalizedPoint(x, y),
        size = NormalizedSize(.15f, .08f),
        shape = TouchControlShape.ROUNDED_RECT,
    )

    private fun stick(
        id: String,
        xAxis: CoreControlId,
        yAxis: CoreControlId,
        x: Float,
        y: Float,
    ) = TouchControlDefinition.Stick(
        visualId = TouchVisualControlId("stick.$id"),
        xAxis = xAxis,
        yAxis = yAxis,
        center = NormalizedPoint(x, y),
        size = NormalizedSize(.13f, .22f),
    )

    private fun menu() = TouchControlDefinition.Menu(
        center = NormalizedPoint(.50f, .08f),
        size = NormalizedSize(.09f, .075f),
    )
}
