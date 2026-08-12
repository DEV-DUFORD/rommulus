package com.romm.androidtv.platform

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator

/** CompositionLocal that provides the current [DeviceProfile] downstream. */
val LocalDeviceProfile = staticCompositionLocalOf<DeviceProfile> {
    DeviceProfile(
        isTv = false,
        hasTouchscreen = true,
        isPortrait = false,
        windowWidthClass = WindowWidthClass.MEDIUM,
        windowHeightClass = WindowHeightClass.MEDIUM,
        foldingFeature = null,
    )
}

/** Returns the current [DeviceProfile] from the nearest [LocalDeviceProvider]. */
@Composable
fun currentDeviceProfile(): DeviceProfile = LocalDeviceProfile.current

/** Provides a computed [DeviceProfile] to all descendants. */
@Composable
fun ProvideDeviceProfile(content: @Composable () -> Unit) {
    val profile = rememberDeviceProfile()
    CompositionLocalProvider(LocalDeviceProfile provides profile, content = content)
}

/** Pure classification of width breakpoints (Material 3 extended). */
fun classifyWidth(widthDp: Dp): WindowWidthClass {
    return when {
        widthDp < 600.dp -> WindowWidthClass.COMPACT
        widthDp < 840.dp -> WindowWidthClass.MEDIUM
        else -> WindowWidthClass.EXPANDED
    }
}

/** Pure classification of height breakpoints. */
fun classifyHeight(heightDp: Dp): WindowHeightClass {
    return when {
        heightDp < 480.dp -> WindowHeightClass.COMPACT
        heightDp < 900.dp -> WindowHeightClass.MEDIUM
        else -> WindowHeightClass.EXPANDED
    }
}

@Composable
fun rememberDeviceProfile(): DeviceProfile {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    // Derive Activity from the context hierarchy.
    val activity = findActivity(context)

    // TV detection: UiModeManager + PackageManager check.
    val isTv = remember(context) {
        val uiModeMgr = try {
            context.getSystemService(android.app.UiModeManager::class.java)
        } catch (_: Exception) {
            null
        }
        val isTvUiMode = uiModeMgr?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        isTvUiMode || hasLeanback
    }

    // Touchscreen detection.
    val hasTouchscreen = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    // Window bounds → width/height classes.
    val (widthDp, heightDp) = remember(
        activity,
        context,
        density,
        configuration.orientation,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    ) {
        val wmCalc = WindowMetricsCalculator.getOrCreate()
        val metrics = if (activity != null) {
            wmCalc.computeCurrentWindowMetrics(activity)
        } else {
            wmCalc.computeMaximumWindowMetrics(context)
        }
        val bounds = metrics.bounds
        with(density) {
            Pair(bounds.width().toDp(), bounds.height().toDp())
        }
    }

    val windowWidthClass = remember(widthDp) { classifyWidth(widthDp) }
    val windowHeightClass = remember(heightDp) { classifyHeight(heightDp) }

    // Folding feature observation via WindowInfoTracker.
    val foldingFeatureState = produceState<FoldingFeature?>(initialValue = null, activity, context) {
        if (activity != null) {
            val tracker = WindowInfoTracker.getOrCreate(context)
            tracker.windowLayoutInfo(activity).collect { layoutInfo ->
                value = layoutInfo.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            }
        } else {
            value = null
        }
    }

    val isPortrait = heightDp > widthDp

    return remember(isTv, hasTouchscreen, isPortrait, windowWidthClass, windowHeightClass, foldingFeatureState.value) {
        DeviceProfile(
            isTv = isTv,
            hasTouchscreen = hasTouchscreen,
            isPortrait = isPortrait,
            windowWidthClass = windowWidthClass,
            windowHeightClass = windowHeightClass,
            foldingFeature = foldingFeatureState.value,
        )
    }
}

/** Walks the context wrapper chain to find an Activity. */
private fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current != null) {
        if (current is Activity) return current
        if (current is android.view.ContextThemeWrapper) {
            current = current.baseContext
        } else {
            break
        }
    }
    return null
}
