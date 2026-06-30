package com.cashfree.pg.cf_analytics.crash;

/** Boundary over the analytics persistence/upload pipeline. */
public interface CrashSink {
    void submit(String token, String exceptionValues, String level, String culprit, long timestamp);
}
