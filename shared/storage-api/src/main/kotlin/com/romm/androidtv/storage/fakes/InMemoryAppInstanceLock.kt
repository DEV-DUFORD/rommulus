package com.romm.androidtv.storage.fakes

import com.romm.androidtv.storage.ports.AppInstanceLock

/** In-memory single-instance lock for tests and desktop dev-loop use. */
class InMemoryAppInstanceLock : AppInstanceLock {

    @Volatile private var held = false

    override fun acquire(): Boolean {
        return synchronized(this) {
            if (held) return false
            held = true
            true
        }
    }

    override fun release() {
        synchronized(this) { held = false }
    }

    override fun isHeld(): Boolean = held
}
