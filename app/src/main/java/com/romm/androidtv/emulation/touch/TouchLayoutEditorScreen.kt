package com.romm.androidtv.emulation.touch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.romm.androidtv.controller.config.CoreControllerProfile
import com.romm.androidtv.library.ui.RommTvColors
import com.romm.androidtv.library.ui.TvButton
import com.romm.androidtv.library.ui.TvOutlinedButton

@Composable
fun TouchLayoutEditorScreen(
    profile: CoreControllerProfile,
    repository: TouchLayoutRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onLayoutChanged: (TouchLayoutOverrideDocument?) -> Unit = {},
) {
    val defaults = remember(profile.coreId) { DefaultTouchLayouts.forProfile(profile) }
    var layout by remember(profile.coreId) {
        mutableStateOf(DefaultTouchLayouts.forProfile(profile, repository.load(profile.coreId)))
    }
    var selectedId by remember(profile.coreId) {
        mutableStateOf(layout.controls.firstOrNull()?.visualId)
    }
    var editingId by remember(profile.coreId) { mutableStateOf<TouchVisualControlId?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val editing = layout.controls.firstOrNull { it.visualId == editingId }

    BackHandler(onBack = onBack)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .displayCutoutPadding(),
    ) {
        val editorViewportWidth = maxWidth
        val editorViewportHeight = maxHeight
        LayoutCanvas(
            profile = profile,
            layout = layout,
            selectedId = selectedId,
            onSelect = { selectedId = it },
            onEdit = {
                selectedId = it
                editingId = it
            },
            onMove = { visualId, dx, dy ->
                layout = layout.updateControl(visualId) { definition ->
                    definition.withGeometry(
                        center = NormalizedPoint(
                            (definition.center.x + dx).coerceIn(
                                definition.size.width / 2f,
                                1f - definition.size.width / 2f,
                            ),
                            (definition.center.y + dy).coerceIn(
                                definition.size.height / 2f,
                                1f - definition.size.height / 2f,
                            ),
                        ),
                    )
                }
                status = null
            },
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(RommTvColors.NightHi.copy(alpha = 0.94f))
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "${profile.consoleName} On-Screen Controller",
                        style = MaterialTheme.typography.titleLarge,
                        color = RommTvColors.TextPrimary,
                    )
                    Text(
                        text = "Drag to move. Double-tap a control for size, opacity, and visibility.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RommTvColors.TextSecondary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvOutlinedButton(onClick = onBack) { Text("Back") }
                    TvButton(
                        onClick = {
                            val document = layout.toOverrideDocument()
                            status = if (repository.save(document)) {
                                onLayoutChanged(document)
                                "Saved"
                            } else {
                                "Save failed"
                            }
                        },
                    ) { Text("Save") }
                }
            }
            if (editing != null) {
                EditorControls(
                    selected = editing,
                    status = status,
                    onDone = { editingId = null },
                    onResize = { factor ->
                        layout = layout.updateControl(editing.visualId) { definition ->
                            val size = proportionallyResizedSize(
                                definition = definition,
                                factor = factor,
                                viewportWidth = editorViewportWidth,
                                viewportHeight = editorViewportHeight,
                            )
                            definition.withGeometry(
                                size = size,
                                center = NormalizedPoint(
                                    definition.center.x.coerceIn(size.width / 2f, 1f - size.width / 2f),
                                    definition.center.y.coerceIn(size.height / 2f, 1f - size.height / 2f),
                                ),
                            )
                        }
                        status = null
                    },
                    onOpacity = { delta ->
                        layout = layout.updateControl(editing.visualId) { definition ->
                            definition.withAppearance(
                                opacity = (definition.opacity + delta).coerceIn(.20f, 1f),
                            )
                        }
                        status = null
                    },
                    onVisibility = {
                        layout = layout.updateControl(editing.visualId) { definition ->
                            definition.withAppearance(visible = !definition.visible)
                        }
                        status = null
                    },
                    onReset = {
                        layout = defaults
                        repository.reset(profile.coreId)
                        onLayoutChanged(null)
                        selectedId = defaults.controls.firstOrNull()?.visualId
                        editingId = null
                        status = "Reset to default"
                    },
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LayoutCanvas(
    profile: CoreControllerProfile,
    layout: ConsoleTouchLayout,
    selectedId: TouchVisualControlId?,
    onSelect: (TouchVisualControlId) -> Unit,
    onEdit: (TouchVisualControlId) -> Unit,
    onMove: (TouchVisualControlId, Float, Float) -> Unit,
) {
    val descriptors = remember(profile) { profile.controls.associateBy { it.id } }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090B10)),
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        layout.controls.forEach { definition ->
            val rendered = renderedSize(definition, maxWidth, maxHeight)
            val x = maxWidth * definition.center.x - rendered.width / 2
            val y = maxHeight * definition.center.y - rendered.height / 2
            val selected = definition.visualId == selectedId
            val shape = when (definition) {
                is TouchControlDefinition.Button ->
                    if (definition.shape == TouchControlShape.CIRCLE) CircleShape else RoundedCornerShape(18.dp)
                is TouchControlDefinition.Stick -> CircleShape
                is TouchControlDefinition.Dpad -> RoundedCornerShape(24.dp)
                is TouchControlDefinition.Menu -> RoundedCornerShape(18.dp)
            }
            val label = when (definition) {
                is TouchControlDefinition.Button ->
                    definition.displayLabel ?: descriptors[definition.controlId]?.label.orEmpty()
                is TouchControlDefinition.Dpad -> "D-Pad"
                is TouchControlDefinition.Stick -> "Stick"
                is TouchControlDefinition.Menu -> "Menu"
            }

            Box(
                modifier = Modifier
                    .offset(x, y)
                    .size(rendered.width, rendered.height)
                    .alpha(if (definition.visible) definition.opacity else .22f)
                    .background(RommTvColors.Romm500.copy(alpha = .32f), shape)
                    .border(
                        width = if (selected) 4.dp else 2.dp,
                        color = if (selected) RommTvColors.Romm300 else Color.White.copy(alpha = .55f),
                        shape = shape,
                    )
                    .combinedClickable(
                        onClick = { onSelect(definition.visualId) },
                        onDoubleClick = { onEdit(definition.visualId) },
                    )
                    .pointerInput(definition.visualId, widthPx, heightPx) {
                        detectDragGestures(
                            onDragStart = { onSelect(definition.visualId) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onMove(
                                    definition.visualId,
                                    dragAmount.x / widthPx,
                                    dragAmount.y / heightPx,
                                )
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (definition.visible) label else "$label (Hidden)",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                }
            }
        }
}

@Composable
private fun EditorControls(
    selected: TouchControlDefinition,
    status: String?,
    modifier: Modifier = Modifier,
    onResize: (Float) -> Unit,
    onOpacity: (Float) -> Unit,
    onVisibility: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected.visualId.value,
                color = RommTvColors.TextPrimary,
                modifier = Modifier.padding(end = 12.dp),
            )
            TvOutlinedButton(onClick = { onResize(.90f) }) { Text("Size -") }
            TvOutlinedButton(onClick = { onResize(1.10f) }) { Text("Size +") }
            TvOutlinedButton(onClick = { onOpacity(-.10f) }) { Text("Opacity -") }
            TvOutlinedButton(onClick = { onOpacity(.10f) }) { Text("Opacity +") }
            TvOutlinedButton(onClick = onVisibility) {
                Text(if (selected.visible) "Hide" else "Show")
            }
            TvOutlinedButton(onClick = onReset) { Text("Reset Default") }
            TvButton(onClick = onDone) { Text("Done") }
        }
        if (status != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(status, color = RommTvColors.Romm300, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun proportionallyResizedSize(
    definition: TouchControlDefinition,
    factor: Float,
    viewportWidth: androidx.compose.ui.unit.Dp,
    viewportHeight: androidx.compose.ui.unit.Dp,
): NormalizedSize {
    require(factor > 0f)
    val rendered = renderedSize(definition, viewportWidth, viewportHeight)
    val targetWidth: androidx.compose.ui.unit.Dp
    val targetHeight: androidx.compose.ui.unit.Dp
    if (definition is TouchControlDefinition.Dpad ||
        definition is TouchControlDefinition.Stick ||
        definition is TouchControlDefinition.Button &&
        definition.shape == TouchControlShape.CIRCLE
    ) {
        val side = ((rendered.width.value + rendered.height.value) / 2f * factor).dp
        targetWidth = side
        targetHeight = side
    } else {
        targetWidth = rendered.width * factor
        targetHeight = rendered.height * factor
    }
    return NormalizedSize(
        width = (targetWidth.value / viewportWidth.value).coerceIn(.02f, .50f),
        height = (targetHeight.value / viewportHeight.value).coerceIn(.02f, .50f),
    )
}

private fun ConsoleTouchLayout.updateControl(
    visualId: TouchVisualControlId,
    update: (TouchControlDefinition) -> TouchControlDefinition,
): ConsoleTouchLayout = copy(
    controls = controls.map { if (it.visualId == visualId) update(it) else it },
)

private fun TouchControlDefinition.withGeometry(
    center: NormalizedPoint = this.center,
    size: NormalizedSize = this.size,
): TouchControlDefinition = when (this) {
    is TouchControlDefinition.Button -> copy(center = center, size = size)
    is TouchControlDefinition.Dpad -> copy(center = center, size = size)
    is TouchControlDefinition.Stick -> copy(center = center, size = size)
    is TouchControlDefinition.Menu -> copy(center = center, size = size)
}

private fun TouchControlDefinition.withAppearance(
    opacity: Float = this.opacity,
    visible: Boolean = this.visible,
): TouchControlDefinition = when (this) {
    is TouchControlDefinition.Button -> copy(opacity = opacity, visible = visible)
    is TouchControlDefinition.Dpad -> copy(opacity = opacity, visible = visible)
    is TouchControlDefinition.Stick -> copy(opacity = opacity, visible = visible)
    is TouchControlDefinition.Menu -> copy(opacity = opacity, visible = visible)
}
