package com.romm.androidtv.controller.util

/**
 * Pure axis normalization utility.
 *
 * Normalizes a raw axis value using device-reported MotionRange bounds.
 * Correctly handles the hardware flat (deadzone) region reported by
 * [android.view.MotionEvent.MotionRange].
 *
 * The `flat` field on MotionRange is a single non-negative distance from
 * the axis center. This is not a min/max pair and the center is not always zero.
 */
object AxisNormalizer {

    /**
     * Normalize a raw axis value to [-1, +1], applying the device-reported
     * flat (deadzone) region.
     *
     * @param rawValue the raw axis reading from MotionEvent.getAxisValue()
     * @param rangeMin MotionRange.min
     * @param rangeMax MotionRange.max
     * @param rangeFlat MotionRange.flat (non-negative deadzone threshold in
     *   the device's native coordinate space)
     * @return normalized value in [-1, +1], or 0f if within the centered flat region
     */
    fun normalize(
        rawValue: Float,
        rangeMin: Float,
        rangeMax: Float,
        rangeFlat: Float
    ): Float {
        if (!rawValue.isFinite() || !rangeMin.isFinite() || !rangeMax.isFinite()) return 0f
        if (rangeMax <= rangeMin) return 0f

        val center = rangeMin + (rangeMax - rangeMin) / 2f
        if (rangeFlat > 0f && kotlin.math.abs(rawValue - center) <= rangeFlat) return 0f

        val extent = if (rawValue >= center) rangeMax - center else center - rangeMin
        if (extent <= 0f) return 0f
        return ((rawValue - center) / extent).coerceIn(-1f, 1f)
    }

    /** Normalize a trigger to [0, 1], including devices whose rest value is -1. */
    fun normalizeTrigger(
        rawValue: Float,
        rangeMin: Float,
        rangeMax: Float,
        rangeFlat: Float
    ): Float {
        if (!rawValue.isFinite() || !rangeMin.isFinite() || !rangeMax.isFinite()) return 0f
        if (rangeMax <= rangeMin) return 0f

        val normalized = ((rawValue - rangeMin) / (rangeMax - rangeMin)).coerceIn(0f, 1f)
        val normalizedFlat = (rangeFlat.coerceAtLeast(0f) / (rangeMax - rangeMin))
            .coerceIn(0f, 1f)
        return if (normalized <= normalizedFlat) 0f else normalized
    }

    /**
     * Normalize when no MotionRange is available (fallback).
     * Assumes raw value is already in [-1, +1].
     */
    fun normalizeFallback(rawValue: Float): Float =
        rawValue.coerceIn(-1f, 1f)
}
