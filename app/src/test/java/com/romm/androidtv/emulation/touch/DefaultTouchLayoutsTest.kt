package com.romm.androidtv.emulation.touch

import androidx.compose.ui.unit.dp
import com.romm.androidtv.controller.config.CoreControllerProfiles
import com.romm.androidtv.emulation.model.CoreManifest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DefaultTouchLayoutsTest {

    @Test
    fun `every approved profile selects one bespoke default layout`() {
        val approvedCoreIds = CoreManifest.approvedEntries().map { it.coreId }

        assertThat(DefaultTouchLayouts.all.map { it.coreId })
            .containsExactlyInAnyOrderElementsOf(approvedCoreIds)
        CoreControllerProfiles.forApprovedCores().forEach { profile ->
            assertThat(DefaultTouchLayouts.forProfile(profile).coreId).isEqualTo(profile.coreId)
        }
    }

    @Test
    fun `every profile control is represented exactly once in its layout`() {
        CoreControllerProfiles.forApprovedCores().forEach { profile ->
            val layout = DefaultTouchLayouts.forProfile(profile)
            val represented = layout.controls.flatMap { it.referencedControlIds() }

            assertThat(represented)
                .`as`("layout controls for ${profile.coreId}")
                .containsExactlyInAnyOrderElementsOf(profile.controls.map { it.id })
            assertThat(represented.distinct())
                .`as`("duplicate layout mappings for ${profile.coreId}")
                .hasSameSizeAs(represented)
        }
    }

    @Test
    fun `resolved controls retain profile logical mappings`() {
        CoreControllerProfiles.forApprovedCores().forEach { profile ->
            val descriptors = profile.controls.associateBy { it.id }
            DefaultTouchLayouts.forProfile(profile).controls.forEach { definition ->
                when (val resolved = definition.resolve(profile)) {
                    is ResolvedTouchControl.Button ->
                        assertThat(resolved.descriptor.target)
                            .isEqualTo(descriptors.getValue(resolved.definition.controlId).target)
                    is ResolvedTouchControl.Dpad -> {
                        assertThat(resolved.up.target).isEqualTo(descriptors.getValue(resolved.definition.up).target)
                        assertThat(resolved.down.target).isEqualTo(descriptors.getValue(resolved.definition.down).target)
                        assertThat(resolved.left.target).isEqualTo(descriptors.getValue(resolved.definition.left).target)
                        assertThat(resolved.right.target).isEqualTo(descriptors.getValue(resolved.definition.right).target)
                    }
                    is ResolvedTouchControl.Stick -> {
                        assertThat(resolved.xAxis.target).isEqualTo(descriptors.getValue(resolved.definition.xAxis).target)
                        assertThat(resolved.yAxis.target).isEqualTo(descriptors.getValue(resolved.definition.yAxis).target)
                    }
                    is ResolvedTouchControl.Menu -> Unit
                }
            }
        }
    }

    @Test
    fun `normalized control bounds remain within the usable canvas`() {
        DefaultTouchLayouts.all.flatMap { it.controls }.forEach { control ->
            val left = control.center.x - control.size.width / 2f
            val right = control.center.x + control.size.width / 2f
            val top = control.center.y - control.size.height / 2f
            val bottom = control.center.y + control.size.height / 2f

            assertThat(left).isGreaterThanOrEqualTo(0f)
            assertThat(right).isLessThanOrEqualTo(1f)
            assertThat(top).isGreaterThanOrEqualTo(0f)
            assertThat(bottom).isLessThanOrEqualTo(1f)
        }
    }

    @Test
    fun `renderer enforces 48dp minimum touch targets`() {
        DefaultTouchLayouts.all.flatMap { it.controls }.forEach { control ->
            val size = renderedSize(control, 320.dp, 240.dp)
            assertThat(size.width).isGreaterThanOrEqualTo(48.dp)
            assertThat(size.height).isGreaterThanOrEqualTo(48.dp)
        }
    }

    @Test
    fun `playstation face buttons use full-size symbols`() {
        val labels = DefaultTouchLayouts.forCore("pcsx_rearmed")!!.controls
            .filterIsInstance<TouchControlDefinition.Button>()
            .filter { it.visualId.value in setOf("button.square", "button.cross", "button.triangle", "button.circle") }
            .associate { it.visualId.value to it.displayLabel }

        assertThat(labels).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "button.square" to "□",
                "button.cross" to "✕",
                "button.triangle" to "△",
                "button.circle" to "◯",
            ),
        )
    }

    @Test
    fun `visual override cannot alter logical mapping`() {
        val profile = CoreControllerProfiles.byCoreId("snes9x")!!
        val defaults = DefaultTouchLayouts.forProfile(profile)
        val button = defaults.controls.filterIsInstance<TouchControlDefinition.Button>().first()
        val override = TouchLayoutOverrideDocument(
            schemaVersion = TOUCH_LAYOUT_SCHEMA_VERSION,
            layoutId = defaults.layoutId,
            coreId = defaults.coreId,
            controls = mapOf(
                button.visualId to TouchLayoutOverride(
                    center = NormalizedPoint(.5f, .5f),
                    size = NormalizedSize(.2f, .2f),
                    opacity = .25f,
                    visible = false,
                ),
            ),
        )

        val customized = DefaultTouchLayouts.forProfile(profile, override)
        val customizedButton = customized.controls
            .filterIsInstance<TouchControlDefinition.Button>()
            .first { it.visualId == button.visualId }

        assertThat(customizedButton.controlId).isEqualTo(button.controlId)
        assertThat(customizedButton.center).isEqualTo(NormalizedPoint(.5f, .5f))
        assertThat(customizedButton.visible).isFalse()
    }

    @Test
    fun `unknown schema resets to current defaults`() {
        val profile = CoreControllerProfiles.byCoreId("fceumm")!!
        val defaults = DefaultTouchLayouts.forProfile(profile)
        val incompatible = TouchLayoutOverrideDocument(
            schemaVersion = TOUCH_LAYOUT_SCHEMA_VERSION + 1,
            layoutId = defaults.layoutId,
            coreId = defaults.coreId,
            controls = mapOf(
                TouchVisualControlId("button.a") to TouchLayoutOverride(visible = false),
            ),
        )

        assertThat(DefaultTouchLayouts.forProfile(profile, incompatible)).isEqualTo(defaults)
    }
}
