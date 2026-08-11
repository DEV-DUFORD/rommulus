package com.romm.androidtv.platform

import androidx.window.layout.FoldingFeature

enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }
enum class WindowHeightClass { COMPACT, MEDIUM, EXPANDED }

data class DeviceProfile(
    val isTv: Boolean,
    val hasTouchscreen: Boolean,
    val windowWidthClass: WindowWidthClass,
    val windowHeightClass: WindowHeightClass,
    val foldingFeature: FoldingFeature?,
) {
    val isCompactWidth: Boolean get() = windowWidthClass == WindowWidthClass.COMPACT
    val isCompactHeight: Boolean get() = windowHeightClass == WindowHeightClass.COMPACT
}
