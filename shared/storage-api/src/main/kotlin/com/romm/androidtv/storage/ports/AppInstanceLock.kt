package com.romm.androidtv.storage.ports

/** Single-instance lock to prevent concurrent app instances from corrupting shared storage. */
interface AppInstanceLock {
    /** Attempt to acquire the lock. Returns false when another instance holds it. */
    fun acquire(): Boolean

    /** Release the lock held by this instance. */
    fun release()

    /** Whether this instance currently holds the lock. */
    fun isHeld(): Boolean
}
