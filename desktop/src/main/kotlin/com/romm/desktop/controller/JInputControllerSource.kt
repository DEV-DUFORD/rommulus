package com.romm.desktop.controller

import com.romm.androidtv.controller.model.DeviceSignature
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.androidtv.controller.util.AxisNormalizer
import net.java.games.input.Component
import net.java.games.input.Controller
import net.java.games.input.ControllerEnvironment

/**
 * JInput -> neutral model translation tables (the desktop ingestion boundary,
 * mirroring the Android `AndroidInputMappingAdapter`).
 *
 * NOTE on the JInput 2.0.10 API: the Maven artifact `net.java.jinput:jinput` ships the
 * package `net.java.games.input`, and its [Component] is a flat interface — there are NO
 * `Component.Button` / `Component.Axis` subtypes, no `isPressed()`, and no per-axis
 * min/max accessors. Components report their type through [Component.getIdentifier] (an
 * [Component.Identifier.Button] or [Component.Identifier.Axis]) and their value through
 * [Component.getPollData] (0.0f/1.0f for buttons, [-1, +1] for analog axes).
 *
 * The 2.0.10 button identifier set is `_0.._31`, TRIGGER, THUMB..., A/B/X/Y/C/Z, SELECT,
 * START, MODE, LEFT_THUMB/RIGHT_THUMB(2/3), TOOL_* — there are no BUTTON_A-style names,
 * no L/R shoulder constants, and no DPAD_* button constants. We therefore map only the
 * identifiers that exist: A/B/X/Y, SELECT/START, and the thumb clicks. The D-pad is not a
 * button in JInput on this platform; it arrives as X/Y axis movement (hats) and is turned
 * into DPAD logical buttons by [com.romm.androidtv.controller.model.GamepadSnapshot.fromPhysicalInput]'s
 * standard d-pad axis-direction mapping, so no D-pad table entry is needed here.
 */
private val BUTTON_TO_NEUTRAL: Map<Component.Identifier.Button, NeutralKey> = mapOf(
    Component.Identifier.Button.A to NeutralKey.BUTTON_A,
    Component.Identifier.Button.B to NeutralKey.BUTTON_B,
    Component.Identifier.Button.X to NeutralKey.BUTTON_X,
    Component.Identifier.Button.Y to NeutralKey.BUTTON_Y,
    Component.Identifier.Button.SELECT to NeutralKey.BUTTON_SELECT,
    Component.Identifier.Button.START to NeutralKey.BUTTON_START,
    Component.Identifier.Button.LEFT_THUMB to NeutralKey.BUTTON_THUMBL,
    Component.Identifier.Button.RIGHT_THUMB to NeutralKey.BUTTON_THUMBR,
)

/**
 * JInput -> neutral axis translation. JInput 2.0.10 exposes the six standard stick axes
 * plus slider/acceleration variants and POV; only X/Y/Z/RX/RY/RZ have neutral equivalents
 * here (the SLIDER-family and POV axes are ignored). There are no trigger-like identifiers
 * (THROTTLE/GAS/...)
 * in this API version, so every mapped axis normalizes as a stick via
 * [AxisNormalizer.normalize] with the JInput convention that polled analog data is already
 * in [-1, +1] (min = -1, max = +1) and the device dead zone comes from
 * [Component.getDeadZone].
 */
private val AXIS_TO_NEUTRAL: Map<Component.Identifier.Axis, NeutralAxis> = mapOf(
    Component.Identifier.Axis.X to NeutralAxis.X,
    Component.Identifier.Axis.Y to NeutralAxis.Y,
    Component.Identifier.Axis.Z to NeutralAxis.Z,
    Component.Identifier.Axis.RX to NeutralAxis.RX,
    Component.Identifier.Axis.RY to NeutralAxis.RY,
    Component.Identifier.Axis.RZ to NeutralAxis.RZ,
)

/** JInput reports polled analog axis data in this range (see [Component.getPollData]). */
private const val JINPUT_AXIS_MIN = -1f
private const val JINPUT_AXIS_MAX = 1f

/**
 * Production [JInputSource] backed by JInput's `ControllerEnvironment` singleton.
 *
 * The environment is obtained lazily on first [enumerate] so that merely
 * constructing this class (e.g. in a headless test JVM) does not load any
 * platform native.
 */
class JInputControllerSource : JInputSource {

    private val environment: ControllerEnvironment by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ControllerEnvironment.getDefaultEnvironment()
    }

    /** Wrappers are cached per underlying JInput controller instance. */
    private val wrappers = HashMap<Controller, JInputController>()

    override fun enumerate(): List<JInputController> {
        // getControllers() returns a Controller[] (a snapshot array in 2.0.10).
        val controllers = environment.controllers
        val result = ArrayList<JInputController>(controllers.size)
        val seen = HashSet<Controller>()
        for (controller in controllers) {
            seen.add(controller)
            result.add(wrappers.getOrPut(controller) { LiveJInputController(controller) })
        }
        // Drop wrappers for controllers that were unplugged since the last tick.
        wrappers.keys.retainAll(seen)
        return result
    }
}

/**
 * Wraps one JInput [Controller], translating its components into the neutral
 * model on every [poll].
 *
 * JInput exposes no portable VID/PID, so the [DeviceSignature] identity is the
 * controller name (descriptor `jinput:<name>`), which is stable for the lifetime
 * of the OS session — the same session-stability guarantee the Android
 * signature adapter provides for transient device ids.
 */
private class LiveJInputController(private val controller: Controller) : JInputController {

    override val id: String = controller.name?.takeIf { it.isNotBlank() } ?: controller.javaClass.name

    override val signature: DeviceSignature = DeviceSignature(
        descriptor = "jinput:$id",
        vendorId = 0,
        productId = 0,
        name = id,
    )

    override fun poll(): JInputControllerState {
        val buttons = LinkedHashSet<NeutralKey>()
        val axes = HashMap<NeutralAxis, Float>()

        // getComponents() returns a Component[] in 2.0.10; the component's identifier
        // (not its runtime class) tells us whether it is a button or an axis.
        for (component in controller.components) {
            when (val identifier = component.identifier) {
                is Component.Identifier.Button -> {
                    val neutral = BUTTON_TO_NEUTRAL[identifier] ?: continue
                    // Button poll data is 0.0f (released) or 1.0f (pressed).
                    if (component.pollData > 0f) buttons.add(neutral)
                }

                is Component.Identifier.Axis -> {
                    val neutral = AXIS_TO_NEUTRAL[identifier] ?: continue
                    axes[neutral] = AxisNormalizer.normalize(
                        rawValue = component.pollData,
                        rangeMin = JINPUT_AXIS_MIN,
                        rangeMax = JINPUT_AXIS_MAX,
                        rangeFlat = component.deadZone,
                    )
                }
            }
        }

        return JInputControllerState(buttons = buttons, axes = axes)
    }
}
