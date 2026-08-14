package com.romm.androidtv.network

import android.util.Log

/**
 * Production Android [LogSink] for [RommLog]. Wired in `RommApplication.onCreate`.
 * Routes each [RommLog] level to the matching `android.util.Log` method so the
 * native app's logcat output is byte-identical to the pre-seam implementation.
 */
object AndroidLogSink : LogSink {
    override fun log(level: Int, tag: String, message: String) {
        when (level) {
            RommLog.VERBOSE -> Log.v(tag, message)
            RommLog.DEBUG -> Log.d(tag, message)
            RommLog.INFO -> Log.i(tag, message)
            RommLog.WARN -> Log.w(tag, message)
            RommLog.ERROR -> Log.e(tag, message)
            else -> Log.println(level, tag, message)
        }
    }
}
