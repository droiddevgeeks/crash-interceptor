package com.droiddevgeeks.sample

import android.app.Application
import android.util.Log
import com.droiddevgeeks.crashsink.CrashReporter

/**
 * crashsink is installed here — ONCE per process — not in an Activity.
 *
 * Thread.setDefaultUncaughtExceptionHandler is process-global static state. Installing it
 * from Activity.onCreate re-runs on every recreation (rotation, dark-mode, "don't keep
 * activities", …) and stacks a new CrashInterceptor on the previous one, so a single crash
 * gets written once per stacked interceptor. Application.onCreate runs exactly once per
 * process, which is where global handlers belong — and it also captures startup crashes that
 * happen before the first Activity.
 *
 * Firebase Crashlytics auto-installs its own handler during Firebase init (before this runs),
 * so the handler crashsink decorates — and always delegates to — is Crashlytics'.
 */
class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val downstream = Thread.getDefaultUncaughtExceptionHandler()
        Log.d(TAG, "downstream handler (crashsink will delegate to): ${downstream?.javaClass?.name}")

        val reporter = CrashReporter.create(
            this,
            /* fileCap = */ 20,
            /* flushTimeoutMillis = */ 1000L,
            { token, _, level, culprit, timestamp, context ->
                // Runs on the ingest thread. Log to logcat and buffer for the UI.
                Log.i(TAG, "sink received crash: culprit=$culprit level=$level ts=$timestamp context=$context")
                CrashLog.add("INGESTED  culprit=$culprit  token=$token  context=$context")
            },
            OWNED_PREFIX
        )
        // install() also flushes crashes captured on the PREVIOUS run — write-now, send-on-next-launch.
        reporter.install()
        reporter.startCapturing("sample-session")
        Log.d(TAG, "crashsink installed. ownedPrefix=$OWNED_PREFIX")
    }

    companion object {
        const val TAG = "crashsink-sample"
        const val OWNED_PREFIX = "com.droiddevgeeks.fakesdk."
    }
}
