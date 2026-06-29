package com.cashfree.pg.cf_analytics.crash;

/** Decides whether a crash originated in Cashfree SDK code (top application frame). */
public final class CrashAttributor {

    private static final String SDK_PREFIX = "com.cashfree.pg.";
    private static final String[] FRAMEWORK_PREFIXES = {
            "java.", "javax.", "kotlin.", "android.", "androidx.",
            "dalvik.", "com.google.android.", "sun."
    };

    public boolean isOurs(final Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        final Throwable root = deepestCause(throwable);
        final StackTraceElement origin = firstApplicationFrame(root.getStackTrace());
        return origin != null && origin.getClassName().startsWith(SDK_PREFIX);
    }

    private Throwable deepestCause(final Throwable throwable) {
        Throwable current = throwable;
        // Bounded walk guards against self-causing / cyclic chains.
        for (int i = 0; i < 50; i++) {
            final Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        return current;
    }

    private StackTraceElement firstApplicationFrame(final StackTraceElement[] frames) {
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

    private boolean isFramework(final String className) {
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
