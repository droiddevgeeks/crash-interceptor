package com.droiddevgeeks.crashsink;

import android.util.Log;

/**
 * Thin error logger over {@link android.util.Log}. Centralizes the one Android platform
 * dependency so the rest of the library stays plain Java.
 */
public final class CrashLogger {

    private static final CrashLogger INSTANCE = new CrashLogger();

    private CrashLogger() {
    }

    public static CrashLogger getInstance() {
        return INSTANCE;
    }

    public void e(final String tag, final String message) {
        Log.e(tag, message);
    }
}
