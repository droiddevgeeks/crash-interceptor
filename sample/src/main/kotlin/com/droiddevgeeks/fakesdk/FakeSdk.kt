package com.droiddevgeeks.fakesdk

import android.content.Context
import android.util.Log
import com.droiddevgeeks.crashsink.CrashReporter

/**
 * Stand-in for a third-party SDK that a host app integrates — and the realistic place crashsink
 * gets installed.
 *
 * A guest SDK does NOT own the host's `Application` class and can't assume one exists. What it
 * always has is the [Context] the host hands it when calling the SDK's own init entry point.
 * So crashsink is installed from [init] — not from an Application — wherever the host chooses to
 * call it.
 *
 * A crash thrown from [boom] has `com.droiddevgeeks.fakesdk.FakeSdk` as its top application
 * frame, so crashsink — configured with `ownedPrefix = OWNED_PREFIX` — attributes it to this
 * SDK and captures it, while still delegating downstream (e.g. Crashlytics).
 */
object FakeSdk {
    const val TAG = "crashsink-sample"
    const val OWNED_PREFIX = "com.droiddevgeeks.fakesdk."

    @Volatile
    private var reporter: CrashReporter? = null

    /**
     * The SDK's init entry point — the method a host app calls to set the SDK up. crashsink is
     * installed here. Safe to call more than once: a host that calls it from several places
     * (Application, then an Activity, …) still ends up with a single installed interceptor, and
     * we never build a second [CrashReporter] whose background threads would leak.
     */
    @Synchronized
    fun init(context: Context) {
        if (reporter != null) {
            return // already initialised; don't re-create or re-install
        }
        val downstream = Thread.getDefaultUncaughtExceptionHandler()
        Log.d(TAG, "downstream handler (crashsink will delegate to): ${downstream?.javaClass?.name}")

        reporter = CrashReporter.create(
            // applicationContext: never hold an Activity, even if init is called from one.
            context.applicationContext,
            /* fileCap = */ 20,
            /* flushTimeoutMillis = */ 1000L,
            { token, _, level, culprit, timestamp, ctx ->
                // Runs on the ingest thread. A real SDK would POST to its backend here;
                // the sample just logs each previous-run crash it delivers.
                Log.i(TAG, "INGESTED crash: culprit=$culprit token=$token level=$level ts=$timestamp context=$ctx")
            },
            OWNED_PREFIX
        ).apply {
            // install() also flushes crashes captured on the PREVIOUS run — write-now, send-on-next-launch.
            install()
            startCapturing("sample-session")
        }
        Log.d(TAG, "crashsink installed from FakeSdk.init. ownedPrefix=$OWNED_PREFIX")
    }

    fun boom(): Nothing {
        throw IllegalStateException("FakeSdk exploded while processing a payment")
    }
}
