package com.romm.androidtv.controller.policy

import com.romm.androidtv.controller.model.KEYCODE_TO_CONTROL
import com.romm.androidtv.controller.model.LogicalControl

/**
 * Pure, framework-free event consumption policy.
 *
 * Determines whether a physical input event should be consumed by the
 * controller router (preventing it from reaching the WebView or native UI).
 *
 * Rules:
 * - KEYCODE_BACK is NEVER consumed (always reserved for native navigation).
 * - KeyCodes that map to a [LogicalControl] ARE consumed.
 * - KeyCodes that do NOT map to any control are NOT consumed.
 * - This prevents the router from swallowing events it cannot translate.
 */
object EventConsumptionPolicy {

    /**
     * Check whether a key event should be consumed by the controller router.
     * Only consumes keyCodes that have a known mapping to a logical control,
     * and never consumes KEYCODE_BACK.
     */
    fun shouldConsumeKeyEvent(keyCode: Int): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) return false
        return KEYCODE_TO_CONTROL.containsKey(keyCode)
    }

    /**
     * Check whether a key event is the Android Back key.
     * Always reserved for native handling.
     */
    fun isBackKey(keyCode: Int): Boolean =
        keyCode == android.view.KeyEvent.KEYCODE_BACK
}
