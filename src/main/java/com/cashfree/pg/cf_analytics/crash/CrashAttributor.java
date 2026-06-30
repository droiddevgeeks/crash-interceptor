package com.cashfree.pg.cf_analytics.crash;

/** Decides whether a crash originated in Cashfree SDK code (top application frame). */
public final class CrashAttributor {

    public boolean isOurs(final Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        final StackTraceElement origin = CrashFrames.firstApplicationFrame(
                CrashFrames.deepestCause(throwable).getStackTrace());
        return origin != null && CrashFrames.isOurs(origin.getClassName());
    }
}
