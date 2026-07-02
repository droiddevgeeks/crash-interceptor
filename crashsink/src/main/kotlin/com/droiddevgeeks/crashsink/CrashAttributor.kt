package com.droiddevgeeks.crashsink

/** Decides whether a crash originated in owned code (top application frame). */
class CrashAttributor(val ownedPrefix: String) {

    fun isOurs(throwable: Throwable?): Boolean {
        if (throwable == null) {
            return false
        }
        val origin = CrashFrames.firstApplicationFrame(
            CrashFrames.deepestCause(throwable).stackTrace
        )
        return origin != null && CrashFrames.isOurs(origin.className, ownedPrefix)
    }
}
