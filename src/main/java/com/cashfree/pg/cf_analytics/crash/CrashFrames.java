package com.cashfree.pg.cf_analytics.crash;

/** Shared stack-frame classification used by attribution and payload culprit. */
final class CrashFrames {

    static final String SDK_PREFIX = "com.cashfree.pg.";
    static final int MAX_CAUSE_DEPTH = 50;

    private static final String[] FRAMEWORK_PREFIXES = {
            "java.", "javax.", "kotlin.", "android.", "androidx.",
            "dalvik.", "com.google.android.", "sun."
    };

    private CrashFrames() {
    }

    static boolean isOurs(final String className) {
        return className != null && className.startsWith(SDK_PREFIX);
    }

    static boolean isFramework(final String className) {
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** First non-framework frame, or null. */
    static StackTraceElement firstApplicationFrame(final StackTraceElement[] frames) {
        if (frames == null) {
            return null;
        }
        for (StackTraceElement frame : frames) {
            if (!isFramework(frame.getClassName())) {
                return frame;
            }
        }
        return null;
    }

    /** Deepest cause, bounded to guard cyclic chains. */
    static Throwable deepestCause(final Throwable throwable) {
        Throwable current = throwable;
        for (int i = 0; i < MAX_CAUSE_DEPTH; i++) {
            final Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        return current;
    }
}
