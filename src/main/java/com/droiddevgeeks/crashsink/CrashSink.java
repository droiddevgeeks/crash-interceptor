package com.droiddevgeeks.crashsink;

/** Boundary over the analytics persistence/upload pipeline. */
public interface CrashSink {
    void submit(String token, String exceptionValues, String level, String culprit,
                long timestamp, String contexts);
}
