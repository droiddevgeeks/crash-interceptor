package com.droiddevgeeks.crashsink;

/** Decides whether a crash originated in owned code (top application frame). */
public final class CrashAttributor {

    private final String ownedPrefix;

    public CrashAttributor(final String ownedPrefix) {
        this.ownedPrefix = ownedPrefix;
    }

    public boolean isOurs(final Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        final StackTraceElement origin = CrashFrames.firstApplicationFrame(
                CrashFrames.deepestCause(throwable).getStackTrace());
        return origin != null && CrashFrames.isOurs(origin.getClassName(), ownedPrefix);
    }
}
