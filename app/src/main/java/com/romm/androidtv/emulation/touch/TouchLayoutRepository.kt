package com.romm.androidtv.emulation.touch

import android.content.SharedPreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class TouchLayoutRepository(
    private val prefs: SharedPreferences,
    moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
) {
    private val adapter = moshi.adapter<List<PersistedTouchLayout>>(
        Types.newParameterizedType(List::class.java, PersistedTouchLayout::class.java),
    )

    fun load(coreId: String): TouchLayoutOverrideDocument? {
        val stored = readAll().firstOrNull { it.coreId == coreId } ?: return null
        return stored.toDocument()
    }

    fun save(document: TouchLayoutOverrideDocument): Boolean {
        val layouts = readAll().filterNot { it.coreId == document.coreId } + document.toPersisted()
        return prefs.edit().putString(KEY_LAYOUTS, adapter.toJson(layouts)).commit()
    }

    fun reset(coreId: String): Boolean {
        val layouts = readAll().filterNot { it.coreId == coreId }
        return prefs.edit().putString(KEY_LAYOUTS, adapter.toJson(layouts)).commit()
    }

    private fun readAll(): List<PersistedTouchLayout> {
        val json = prefs.getString(KEY_LAYOUTS, null) ?: return emptyList()
        return runCatching { adapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY_LAYOUTS = "touch_layout_overrides"
    }
}

internal data class PersistedTouchLayout(
    val schemaVersion: Int,
    val layoutId: String,
    val coreId: String,
    val controls: List<PersistedTouchControlOverride>,
)

internal data class PersistedTouchControlOverride(
    val visualId: String,
    val centerX: Float?,
    val centerY: Float?,
    val width: Float?,
    val height: Float?,
    val opacity: Float?,
    val visible: Boolean?,
)

private fun PersistedTouchLayout.toDocument() = TouchLayoutOverrideDocument(
    schemaVersion = schemaVersion,
    layoutId = layoutId,
    coreId = coreId,
    controls = controls.associate { stored ->
        TouchVisualControlId(stored.visualId) to TouchLayoutOverride(
            center = if (stored.centerX != null && stored.centerY != null) {
                NormalizedPoint(stored.centerX, stored.centerY)
            } else {
                null
            },
            size = if (stored.width != null && stored.height != null) {
                NormalizedSize(stored.width, stored.height)
            } else {
                null
            },
            opacity = stored.opacity,
            visible = stored.visible,
        )
    },
)

private fun TouchLayoutOverrideDocument.toPersisted() = PersistedTouchLayout(
    schemaVersion = schemaVersion,
    layoutId = layoutId,
    coreId = coreId,
    controls = controls.map { (visualId, override) ->
        PersistedTouchControlOverride(
            visualId = visualId.value,
            centerX = override.center?.x,
            centerY = override.center?.y,
            width = override.size?.width,
            height = override.size?.height,
            opacity = override.opacity,
            visible = override.visible,
        )
    },
)

fun ConsoleTouchLayout.toOverrideDocument(): TouchLayoutOverrideDocument =
    TouchLayoutOverrideDocument(
        schemaVersion = schemaVersion,
        layoutId = layoutId,
        coreId = coreId,
        controls = controls.associate { definition ->
            definition.visualId to TouchLayoutOverride(
                center = definition.center,
                size = definition.size,
                opacity = definition.opacity,
                visible = definition.visible,
            )
        },
    )
