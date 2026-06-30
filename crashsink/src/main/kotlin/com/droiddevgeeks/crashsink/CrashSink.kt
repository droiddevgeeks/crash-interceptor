package com.droiddevgeeks.crashsink

/**
 * Boundary over the analytics persistence/upload pipeline.
 *
 * A `fun interface` (SAM): callable from Kotlin as a trailing lambda and implemented
 * from Java either as a lambda or an anonymous `CrashSink` class.
 */
fun interface CrashSink {
    fun submit(
        token: String?,
        exceptionValues: String,
        level: String,
        culprit: String,
        timestamp: Long,
        contexts: String
    )
}
