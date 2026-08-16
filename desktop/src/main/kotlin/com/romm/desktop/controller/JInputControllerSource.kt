package com.romm.desktop.controller

import com.romm.androidtv.controller.model.DeviceSignature
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.androidtv.controller.util.AxisNormalizer
import com.romm.desktop.log.DesktopLogger
import net.java.games.input.Component
import net.java.games.input.Controller
import net.java.games.input.ControllerEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

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
 * button in JInput on this platform: the Linux plugin reports the hats (ABS_HAT0X/ABS_HAT0Y)
 * as two axis components that BOTH carry the [Component.Identifier.Axis.SLIDER] identifier
 * (LinuxNativeTypesMap.getAbsAxisID falls back to SLIDER for unmapped absolute axes). They
 * are translated directly into DPAD_* [NeutralKey]s in [LiveJInputController.poll] — not
 * folded into the X/Y axis map, which would clobber the left-stick values.
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
 * here. SLIDER is deliberately NOT in this table: on Linux both D-pad hats arrive as
 * SLIDER components, and mapping them into NeutralAxis.X/Y would clobber the left-stick
 * values (the axes map is keyed by NeutralAxis, last write wins). SLIDER components are
 * handled separately in [LiveJInputController.poll] and become DPAD_* buttons. POV and the
 * other slider-family axes are ignored. There are no trigger-like identifiers
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
 * The system property every JInput 2.0.10 platform plugin's `loadLibrary()` checks first
 * (verified in LinuxEnvironmentPlugin, OSXEnvironmentPlugin, DirectInputEnvironmentPlugin
 * and RawInputEnvironmentPlugin bytecode): when set, the plugin `System.load()`s
 * `<property>/<File.separator>/<System.mapLibraryName(lib)>` instead of falling back to
 * `System.loadLibrary()` (which only searches `java.library.path`).
 */
private const val JINPUT_LIBRARYPATH_PROPERTY = "net.java.games.input.librarypath"

/**
 * Native library files shipped at the ROOT of the `natives-all` classifier jar
 * (`jinput-2.0.10-natives-all.jar`; verified by jar inspection). Extracted to a temp dir
 * so the [JINPUT_LIBRARYPATH_PROPERTY] hook above can find them. The per-OS plugin loads
 * exactly one of these (Linux: `libjinput-linux64.so`, macOS: `libjinput-osx.jnilib`,
 * Windows: `jinput-raw_64.dll` + `jinput-dx8_64.dll` [+ `jinput-wintab.dll` for the
 * WinTab plugin]), so extracting all of them is cheap and makes the bootstrap
 * OS-agnostic.
 */
private val JINPUT_NATIVE_RESOURCES = listOf(
    "libjinput-linux64.so",
    "libjinput-osx.jnilib",
    "jinput-raw_64.dll",
    "jinput-dx8_64.dll",
    "jinput-wintab.dll",
)

/**
 * Production [JInputSource] backed by JInput's `ControllerEnvironment` singleton.
 *
 * The environment is obtained lazily on first [enumerate] so that merely
 * constructing this class (e.g. in a headless test JVM) does not load any
 * platform native.
 */
class JInputControllerSource : JInputSource {

    private val logger: Logger = DesktopLogger.get()

    /**
     * The environment is obtained lazily on first [enumerate] so that merely
     * constructing this class (e.g. in a headless test JVM) does not load any
     * platform native. [ensureJinputNatives] must run BEFORE the first environment
     * access: the platform plugin classes (e.g. `LinuxEnvironmentPlugin`) load their
     * native in a static initializer, and `DefaultControllerEnvironment.getControllers()`
     * wraps plugin loading in `catch (Throwable)` — a swallowed `UnsatisfiedLinkError`
     * leaves the environment reporting zero controllers forever.
     */
    private val environment: ControllerEnvironment by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ensureJinputNatives()
        ControllerEnvironment.getDefaultEnvironment()
    }

    /** Wrappers are cached per underlying JInput controller instance. */
    private val wrappers = HashMap<Controller, JInputController>()

    /** Set when the native bootstrap could not make the native libraries available. */
    @Volatile
    private var nativeBootstrapFailed = false

    /** Last logged controller-name list; bounds the enumeration diagnostic to once (and on change). */
    @Volatile
    private var lastLoggedControllers: List<String>? = null

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

        // Diagnostic: log the detected set once (and whenever it changes) so "no controllers
        // detected" is distinguishable from "detected but unmapped" in a single run.
        val names = controllers.map { it.name ?: "<unnamed>" }
        if (names != lastLoggedControllers) {
            lastLoggedControllers = names
            if (names.isEmpty() && nativeBootstrapFailed) {
                logger.log(
                    Level.WARNING,
                    "JInput detected 0 controllers and the native library bootstrap failed; " +
                        "see the earlier WARNING for the cause. Controllers will not appear " +
                        "until the JInput native library is loadable."
                )
            } else {
                logger.log(Level.INFO, "JInput detected ${names.size} controller(s): $names")
            }
            // "Detected but unreadable" diagnostic: JInput enumerates controllers from
            // /dev/input/event* but cannot open them when this process lacks read
            // permission (open() fails with EACCES) — typically because the user is not
            // in the `input` group. JInput logs the per-device failures only to
            // java.util.logging internally, so without this probe the app would just
            // report "detected N controller(s)" while nothing works.
            if (names.isNotEmpty() && inputEventDevicesReadable() == false) {
                logger.log(
                    Level.WARNING,
                    "Controllers were detected but no /dev/input/event* device is readable " +
                        "by this process (opening them fails with permission denied). " +
                        "Add your user to the 'input' group: sudo usermod -aG input \$USER, " +
                        "then log out and back in."
                )
            }
        }
        return result
    }

    /**
     * Make the JInput native libraries loadable BEFORE the first [ControllerEnvironment]
     * access (see [environment]).
     *
     * JInput 2.0.10 ships its natives in the `natives-all` classifier jar (a plain
     * `implementation` dependency in `desktop/build.gradle.kts`) but does NOT extract
     * them from the classpath — there is no `NativeLibLoader` or equivalent in the jar.
     * Every platform plugin's `loadLibrary()` first checks the
     * [JINPUT_LIBRARYPATH_PROPERTY] system property and, when set, `System.load()`s
     * `<property>/<mapLibraryName(lib)>` (verified in the plugin bytecode). So: extract
     * the natives from the classpath to a temp dir and point the property at it.
     *
     * Best-effort: any failure is logged at WARNING and the environment is still created
     * afterwards — it may still find the native via `java.library.path` (e.g. when a
     * distribution installs libjinput into the system loader path), and the enumeration
     * diagnostic in [enumerate] will surface the resulting empty controller list.
     */
    private fun ensureJinputNatives() {
        if (!System.getProperty(JINPUT_LIBRARYPATH_PROPERTY).isNullOrBlank()) {
            // Already configured (e.g. by the launcher or a test) — trust it.
            return
        }
        try {
            val loader = JInputControllerSource::class.java.classLoader
            val dir: Path = Files.createTempDirectory("jinput-natives")
            var extracted = 0
            for (resource in JINPUT_NATIVE_RESOURCES) {
                loader.getResourceAsStream(resource)?.use { input ->
                    Files.copy(input, dir.resolve(resource), StandardCopyOption.REPLACE_EXISTING)
                    extracted++
                }
            }
            if (extracted == 0) {
                nativeBootstrapFailed = true
                logger.log(
                    Level.WARNING,
                    "JInput natives-all jar not found on the classpath (expected resources: " +
                        "$JINPUT_NATIVE_RESOURCES). Controllers will not be available unless " +
                        "the JInput native libraries are on java.library.path."
                )
            } else {
                System.setProperty(JINPUT_LIBRARYPATH_PROPERTY, dir.toString())
                logger.log(
                    Level.INFO,
                    "Extracted $extracted JInput native(s) to $dir; set $JINPUT_LIBRARYPATH_PROPERTY"
                )
            }
        } catch (t: Throwable) {
            nativeBootstrapFailed = true
            logger.log(Level.WARNING, "JInput native extraction failed: $t", t)
        }
    }

    /**
     * Best-effort readability probe for the "detected but unreadable" condition:
     * returns true if at least one `/dev/input/event*` device node is readable by this
     * process, false if event devices exist but NONE are readable, and null if the probe
     * cannot run (non-Linux platform, `/dev/input` missing, no event nodes, or any I/O
     * failure). Callers treat null as "unknown" and do not warn. [Files.isReadable]
     * evaluates the permission bits against the current uid/gid — the same check that
     * makes open() fail with EACCES — so it faithfully predicts JInput's open failures.
     */
    private fun inputEventDevicesReadable(): Boolean? {
        return try {
            val inputDir = Path.of("/dev/input")
            if (!Files.isDirectory(inputDir)) return null
            var found = false
            var anyReadable = false
            Files.newDirectoryStream(inputDir, "event*").use { devices ->
                for (device in devices) {
                    found = true
                    if (Files.isReadable(device)) {
                        anyReadable = true
                        break
                    }
                }
            }
            if (!found) null else anyReadable
        } catch (t: Throwable) {
            null
        }
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

        // JInput's Linux plugin reports the D-pad hats (ABS_HAT0X/ABS_HAT0Y) as two
        // separate components that BOTH carry the Axis.SLIDER identifier. They cannot be
        // folded into the X/Y axis map (that would clobber the left-stick values), so
        // they are translated directly into DPAD_* buttons here. Orientation is assigned
        // by component order: the first SLIDER is the horizontal hat (HAT0X), the second
        // the vertical (HAT0Y) — matching the kernel's axis enumeration order. Hat poll
        // data is digital (-1/0/+1); the component dead zone (usually 0) is the threshold.
        var sliderIndex = 0

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
                    if (identifier == Component.Identifier.Axis.SLIDER) {
                        val value = component.pollData
                        val deadZone = component.deadZone
                        when (sliderIndex) {
                            0 -> when {
                                value < -deadZone -> buttons.add(NeutralKey.DPAD_LEFT)
                                value > deadZone -> buttons.add(NeutralKey.DPAD_RIGHT)
                                else -> {}
                            }
                            1 -> when {
                                value < -deadZone -> buttons.add(NeutralKey.DPAD_UP)
                                value > deadZone -> buttons.add(NeutralKey.DPAD_DOWN)
                                else -> {}
                            }
                        }
                        sliderIndex++
                    } else {
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
        }

        return JInputControllerState(buttons = buttons, axes = axes)
    }
}
