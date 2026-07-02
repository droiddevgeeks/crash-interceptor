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
        val current = Thread.getDefaultUncaughtExceptionHandler()
        // A crashsink interceptor for this prefix already in the chain means the host
        // double-initialised us (or another copy of this SDK ran first). Adopt it rather than
        // stack a second interceptor that would write every crash twice.
        findOurs(current)?.let { installed = it; return }
        installed = CrashInterceptor(current, attributor, processor).also {
            Thread.setDefaultUncaughtExceptionHandler(it)
        }
    }

    @Synchronized
    fun reassert() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === installed) {
            return // still in the slot; nothing to do
        }
        // Displaced but still somewhere in the chain (a library wrapped us and delegates
        // through us) → re-adopt instead of stacking a duplicate.
        findOurs(current)?.let { installed = it; return }
        CrashLogger.e(TAG, "crash handler displaced; re-asserting over $current")
        installed = CrashInterceptor(current, attributor, processor).also {
            Thread.setDefaultUncaughtExceptionHandler(it)
        }
    }

    /**
     * Walk the handler chain from [head] and return an existing crashsink interceptor that
     * captures the same prefix as us, if one is present. A different prefix is a genuinely
     * different guest SDK also using crashsink — that chains normally and is left alone. The
     * walk is bounded so a malformed chain can never hang installation.
     */
    private fun findOurs(head: Thread.UncaughtExceptionHandler?): CrashInterceptor? {
        var h = head
        var hops = 0
        while (h is CrashInterceptor && hops < MAX_CHAIN_HOPS) {
            if (h.attributor.ownedPrefix == attributor.ownedPrefix) {
                return h
            }
            h = h.previous
            hops++
        }
        return null
    }

    companion object {
        private const val TAG = "CrashHandlerManager"

        /** Upper bound on the delegate-chain walk in [findOurs]; guards a malformed/cyclic chain. */
        private const val MAX_CHAIN_HOPS = 50
    }
}
