package com.droiddevgeeks.crashsink

import android.util.Log

/**
 * Thin error logger over [android.util.Log]. Centralizes the one Android platform
 * dependency so the rest of the library stays plain.
 *
 * `internal`: used only inside the library; never part of the public surface.
 */
internal object CrashLogger {
    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }
}
