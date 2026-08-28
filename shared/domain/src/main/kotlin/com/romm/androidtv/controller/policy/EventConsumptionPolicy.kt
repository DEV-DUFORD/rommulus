package com.romm.androidtv.controller.policy

import com.romm.androidtv.controller.model.NeutralKey

/**
 * Pure, framework-free event consumption policy.
 *
 * Determines whether a physical input event should be consumed by the
 * controller router (preventing it from reaching the WebView or native UI).
 *
 * Rules:
 * - BACK is NEVER consumed (always reserved for native navigation).
 * - Known controller buttons ARE consumed, including buttons that only become
 *   meaningful through a custom mapping.
 * - Unknown keys are NOT consumed.
 * - This prevents the router from swallowing events it cannot translate.
 */
object EventConsumptionPolicy {

    /**
     * Check whether a key event (given as a platform key code) should be consumed
     * by the controller router. Only consumes keys that have a known mapping to a
     * controller button, and never consumes BACK.
     */
    fun shouldConsumeKeyEvent(keyCode: Int): Boolean {
        val neutral = NeutralKey.fromPlatform(keyCode) ?: return false
        return neutral != NeutralKey.BACK
    }

    /**
     * Check whether a key event (given as a platform key code) is the Back key.
     * Always reserved for native handling.
     */
    fun isBackKey(keyCode: Int): Boolean =
        NeutralKey.fromPlatform(keyCode) == NeutralKey.BACK
}
