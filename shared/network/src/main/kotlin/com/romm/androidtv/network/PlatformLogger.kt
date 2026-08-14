package com.romm.androidtv.network

/**
 * Platform-neutral logging seam (Phase 2 work item 4).
 *
 * Production Android wires an [AndroidLogSink] in `RommApplication.onCreate`,
 * preserving the exact `android.util.Log` level/message behavior. On a plain JVM
 * (unit tests, the Linux desktop port) [sink] stays null and every call no-ops.
 *
 * Level constants mirror `android.util.Log` exactly so existing call sites can
 * keep passing `RommLog.DEBUG` / `RommLog.WARN` unchanged.
 */
object RommLog {

    /** Mirrors `android.util.Log.VERBOSE`. */
    const val VERBOSE = 2
    /** Mirrors `android.util.Log.DEBUG`. */
    const val DEBUG = 3
    /** Mirrors `android.util.Log.INFO`. */
    const val INFO = 4
    /** Mirrors `android.util.Log.WARN`. */
    const val WARN = 5
    /** Mirrors `android.util.Log.ERROR`. */
    const val ERROR = 6

    /** Pluggable sink; null on a plain JVM so logging no-ops. */
    @Volatile
    var sink: LogSink? = null

    fun debug(tag: String, message: String) { sink?.log(DEBUG, tag, message) }
    fun info(tag: String, message: String) { sink?.log(INFO, tag, message) }
    fun warn(tag: String, message: String) { sink?.log(WARN, tag, message) }
    fun error(tag: String, message: String) { sink?.log(ERROR, tag, message) }

    /** Maps a raw [RommLog] level constant to the typed convenience call. */
    fun log(level: Int, tag: String, message: String) {
        when (level) {
            DEBUG -> debug(tag, message)
            INFO -> info(tag, message)
            WARN -> warn(tag, message)
            ERROR -> error(tag, message)
            else -> sink?.log(level, tag, message)
        }
    }
}

/** Receives [RommLog] output; platform implementations route it to their logger. */
fun interface LogSink {
    fun log(level: Int, tag: String, message: String)
}
