package com.droiddevgeeks.crashsink

/**
 * Shared stack-frame classification used by attribution and payload culprit.
 *
 * `internal`: used only by [CrashAttributor] and [CrashProcessor] inside the library.
 */
internal object CrashFrames {

    const val MAX_CAUSE_DEPTH = 50

    private val FRAMEWORK_PREFIXES = arrayOf(
        "java.", "javax.", "kotlin.", "android.", "androidx.",
        "dalvik.", "com.google.android.", "sun."
    )

    fun isOurs(className: String?, ownedPrefix: String): Boolean {
        return className != null && className.startsWith(ownedPrefix)
    }

    fun isFramework(className: String): Boolean {
        for (prefix in FRAMEWORK_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true
            }
        }
        return false
    }

    /** First non-framework frame, or null. */
    fun firstApplicationFrame(frames: Array<StackTraceElement>?): StackTraceElement? {
        if (frames == null) {
            return null
        }
        for (frame in frames) {
            if (!isFramework(frame.className)) {
                return frame
            }
        }
        return null
    }

    /** Deepest cause, bounded to guard cyclic chains. */
    fun deepestCause(throwable: Throwable): Throwable {
        var current = throwable
        for (i in 0 until MAX_CAUSE_DEPTH) {
            val cause = current.cause
            if (cause == null || cause === current) {
                break
            }
            current = cause
        }
        return current
    }
}
