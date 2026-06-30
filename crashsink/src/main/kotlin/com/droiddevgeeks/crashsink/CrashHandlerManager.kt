package com.droiddevgeeks.crashsink

/** Owns installation and survival of the crash interceptor in the handler chain. */
class CrashHandlerManager(
    private val attributor: CrashAttributor,
    private val processor: CrashProcessor
) {
    // `private set` keeps the public `getInstalled()` accessor the Java callers use,
    // while only this class can replace the installed interceptor.
    @Volatile
    var installed: CrashInterceptor? = null
        private set

    @Synchronized
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        installed = CrashInterceptor(previous, attributor, processor).also {
            Thread.setDefaultUncaughtExceptionHandler(it)
        }
    }

    @Synchronized
    fun reassert() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === installed) {
            return // still in the slot; nothing to do
        }
        CrashLogger.e(TAG, "crash handler displaced; re-asserting over $current")
        installed = CrashInterceptor(current, attributor, processor).also {
            Thread.setDefaultUncaughtExceptionHandler(it)
        }
    }

    companion object {
        private const val TAG = "CrashHandlerManager"
    }
}
