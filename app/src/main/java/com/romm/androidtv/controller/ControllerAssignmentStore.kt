package com.romm.androidtv.controller

import android.content.SharedPreferences
import com.romm.androidtv.controller.model.ControllerSlot

/** Persists stable controller reconnect keys by player port. */
class ControllerAssignmentStore(private val prefs: SharedPreferences) {
    fun loadPreferredReconnectKeys(): List<String?> =
        List(ControllerSlot.SLOT_COUNT) { slotIndex ->
            prefs.getString(keyForSlot(slotIndex), null)
        }

    fun savePreferredReconnectKeys(reconnectKeys: List<String?>): Boolean {
        val editor = prefs.edit()
        repeat(ControllerSlot.SLOT_COUNT) { slotIndex ->
            val key = keyForSlot(slotIndex)
            reconnectKeys.getOrNull(slotIndex)?.let { editor.putString(key, it) }
                ?: editor.remove(key)
        }
        return editor.commit()
    }

    private fun keyForSlot(slotIndex: Int): String = "player_${slotIndex + 1}_reconnect_key"

    companion object {
        const val PREFS_NAME = "controller_assignments"
    }
}
