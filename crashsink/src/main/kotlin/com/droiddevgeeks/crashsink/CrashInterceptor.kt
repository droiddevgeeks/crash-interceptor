package com.droiddevgeeks.crashsink

/** Decorator in the uncaught-exception chain. Captures our crashes, always delegates. */
class CrashInterceptor(
    val previous: Thread.UncaughtExceptionHandler?,
    val attributor: CrashAttributor,
    private val processor: CrashProcessor
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            if (attributor.isOurs(throwable)) {
                processor.persistBlocking(thread, throwable)
            }
        } catch (suppressed: Throwable) {
            // Our reporting must never mask the original crash.
        } finally {
            val prev = previous
            if (prev != null) {
                prev.uncaughtException(thread, throwable)
            } else {
                System.exit(10)
            }
        }
    }
}
