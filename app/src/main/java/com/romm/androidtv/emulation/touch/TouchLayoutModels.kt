package com.romm.androidtv.emulation.touch

import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.CoreControllerProfile

const val TOUCH_LAYOUT_SCHEMA_VERSION = 1

@JvmInline
value class TouchVisualControlId(val value: String) {
    init {
        require(value.isNotBlank()) { "visual control ID must not be blank" }
    }
}

data class NormalizedPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "normalized point must be inside 0..1" }
    }
}

data class NormalizedSize(val width: Float, val height: Float) {
    init {
        require(width > 0f && width <= 1f && height > 0f && height <= 1f) {
            "normalized size must be inside 0..1"
        }
    }
}

enum class TouchControlShape { CIRCLE, ROUNDED_RECT }

sealed interface TouchControlDefinition {
    val visualId: TouchVisualControlId
    val center: NormalizedPoint
    val size: NormalizedSize
    val opacity: Float
    val visible: Boolean

    data class Button(
        override val visualId: TouchVisualControlId,
        val controlId: CoreControlId,
        val displayLabel: String? = null,
        override val center: NormalizedPoint,
        override val size: NormalizedSize,
        val shape: TouchControlShape = TouchControlShape.CIRCLE,
        override val opacity: Float = 0.72f,
        override val visible: Boolean = true,
    ) : TouchControlDefinition

    data class Dpad(
        override val visualId: TouchVisualControlId,
        val up: CoreControlId = CoreControlId.D_PAD_UP,
        val down: CoreControlId = CoreControlId.D_PAD_DOWN,
        val left: CoreControlId = CoreControlId.D_PAD_LEFT,
        val right: CoreControlId = CoreControlId.D_PAD_RIGHT,
        override val center: NormalizedPoint,
        override val size: NormalizedSize,
        override val opacity: Float = 0.72f,
        override val visible: Boolean = true,
    ) : TouchControlDefinition

    data class Stick(
        override val visualId: TouchVisualControlId,
        val xAxis: CoreControlId,
        val yAxis: CoreControlId,
        override val center: NormalizedPoint,
        override val size: NormalizedSize,
        override val opacity: Float = 0.72f,
        override val visible: Boolean = true,
    ) : TouchControlDefinition

    data class Menu(
        override val visualId: TouchVisualControlId = TouchVisualControlId("system.pause"),
        override val center: NormalizedPoint,
        override val size: NormalizedSize,
        override val opacity: Float = 0.72f,
        override val visible: Boolean = true,
    ) : TouchControlDefinition
}

data class ConsoleTouchLayout(
    val schemaVersion: Int = TOUCH_LAYOUT_SCHEMA_VERSION,
    val layoutId: String,
    val coreId: String,
    val controls: List<TouchControlDefinition>,
) {
    init {
        require(schemaVersion == TOUCH_LAYOUT_SCHEMA_VERSION)
        require(layoutId.isNotBlank() && coreId.isNotBlank())
        require(controls.map { it.visualId }.distinct().size == controls.size) {
            "visual control IDs must be unique in $layoutId"
        }
        require(controls.all { it.opacity in 0f..1f })
    }
}

/**
 * Persistence-ready visual overrides deliberately contain no CoreControlId or logical target.
 * Applying an override can move, resize, fade, or hide a visual control, but can never remap it.
 */
data class TouchLayoutOverride(
    val center: NormalizedPoint? = null,
    val size: NormalizedSize? = null,
    val opacity: Float? = null,
    val visible: Boolean? = null,
) {
    init {
        require(opacity == null || opacity in 0f..1f)
    }
}

data class TouchLayoutOverrideDocument(
    val schemaVersion: Int,
    val layoutId: String,
    val coreId: String,
    val controls: Map<TouchVisualControlId, TouchLayoutOverride>,
)

object TouchLayoutMigration {
    fun applyOrReset(
        defaults: ConsoleTouchLayout,
        saved: TouchLayoutOverrideDocument?,
    ): ConsoleTouchLayout {
        if (saved == null ||
            saved.schemaVersion != TOUCH_LAYOUT_SCHEMA_VERSION ||
            saved.layoutId != defaults.layoutId ||
            saved.coreId != defaults.coreId
        ) {
            return defaults
        }
        return defaults.copy(
            controls = defaults.controls.map { definition ->
                val override = saved.controls[definition.visualId] ?: return@map definition
                definition.withVisualOverride(override)
            },
        )
    }
}

fun TouchControlDefinition.referencedControlIds(): Set<CoreControlId> = when (this) {
    is TouchControlDefinition.Button -> setOf(controlId)
    is TouchControlDefinition.Dpad -> setOf(up, down, left, right)
    is TouchControlDefinition.Stick -> setOf(xAxis, yAxis)
    is TouchControlDefinition.Menu -> emptySet()
}

fun TouchControlDefinition.resolve(profile: CoreControllerProfile): ResolvedTouchControl {
    val descriptors = profile.controls.associateBy { it.id }
    return when (this) {
        is TouchControlDefinition.Button -> ResolvedTouchControl.Button(
            definition = this,
            descriptor = requireNotNull(descriptors[controlId]) {
                "${profile.coreId} layout references missing control $controlId"
            },
        )
        is TouchControlDefinition.Dpad -> ResolvedTouchControl.Dpad(
            definition = this,
            up = requireNotNull(descriptors[up]),
            down = requireNotNull(descriptors[down]),
            left = requireNotNull(descriptors[left]),
            right = requireNotNull(descriptors[right]),
        )
        is TouchControlDefinition.Stick -> ResolvedTouchControl.Stick(
            definition = this,
            xAxis = requireNotNull(descriptors[xAxis]),
            yAxis = requireNotNull(descriptors[yAxis]),
        )
        is TouchControlDefinition.Menu -> ResolvedTouchControl.Menu(this)
    }
}

sealed interface ResolvedTouchControl {
    val definition: TouchControlDefinition

    data class Button(
        override val definition: TouchControlDefinition.Button,
        val descriptor: com.romm.androidtv.controller.config.CoreControlDescriptor,
    ) : ResolvedTouchControl

    data class Dpad(
        override val definition: TouchControlDefinition.Dpad,
        val up: com.romm.androidtv.controller.config.CoreControlDescriptor,
        val down: com.romm.androidtv.controller.config.CoreControlDescriptor,
        val left: com.romm.androidtv.controller.config.CoreControlDescriptor,
        val right: com.romm.androidtv.controller.config.CoreControlDescriptor,
    ) : ResolvedTouchControl

    data class Stick(
        override val definition: TouchControlDefinition.Stick,
        val xAxis: com.romm.androidtv.controller.config.CoreControlDescriptor,
        val yAxis: com.romm.androidtv.controller.config.CoreControlDescriptor,
    ) : ResolvedTouchControl

    data class Menu(
        override val definition: TouchControlDefinition.Menu,
    ) : ResolvedTouchControl
}

private fun TouchControlDefinition.withVisualOverride(
    override: TouchLayoutOverride,
): TouchControlDefinition = when (this) {
    is TouchControlDefinition.Button -> copy(
        center = override.center ?: center,
        size = override.size ?: size,
        opacity = override.opacity ?: opacity,
        visible = override.visible ?: visible,
    )
    is TouchControlDefinition.Dpad -> copy(
        center = override.center ?: center,
        size = override.size ?: size,
        opacity = override.opacity ?: opacity,
        visible = override.visible ?: visible,
    )
    is TouchControlDefinition.Stick -> copy(
        center = override.center ?: center,
        size = override.size ?: size,
        opacity = override.opacity ?: opacity,
        visible = override.visible ?: visible,
    )
    is TouchControlDefinition.Menu -> copy(
        center = override.center ?: center,
        size = override.size ?: size,
        opacity = override.opacity ?: opacity,
        visible = override.visible ?: visible,
    )
}
