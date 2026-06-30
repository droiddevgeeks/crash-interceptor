package com.droiddevgeeks.crashsink;

/**
 * Lightweight logger used by the crash subsystem. Mirrors the minimal API surface the
 * crash pipeline depends on. Logs to stderr in this project.
 */
public final class CrashLogger {

    private static final CrashLogger INSTANCE = new CrashLogger();

    private CrashLogger() {
    }

    public static CrashLogger getInstance() {
        return INSTANCE;
    }

    public void e(final String tag, final String message) {
        System.err.println("E/" + tag + ": " + message);
    }
}
