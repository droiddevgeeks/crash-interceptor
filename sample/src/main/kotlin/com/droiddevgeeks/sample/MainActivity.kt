package com.droiddevgeeks.sample

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import com.droiddevgeeks.crashsink.CrashReporter
import com.droiddevgeeks.fakesdk.FakeSdk
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Test harness for crashsink. Demonstrates the core contract: capture only the SDK's own
 * crashes, delegate everything else, and surface previously-captured crashes on next launch.
 *
 * Firebase Crashlytics is the *real* downstream reporter here. It auto-installs its own
 * Thread.UncaughtExceptionHandler during Firebase init (before this Activity runs), so when
 * crashsink installs on top, crashsink's `previous` handler IS Crashlytics'. Every crash is
 * always delegated down to it; crashsink additionally *captures* the ones attributable to the
 * owned prefix. Net effect: an SDK crash lands in BOTH; a host crash lands in Crashlytics only.
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log)

        // 1. Whatever is in the default-handler slot right now is the DOWNSTREAM reporter that
        //    crashsink will decorate. Firebase auto-installed Crashlytics' handler before us —
        //    log its class so the chain is visible. crashsink installs on top and always
        //    delegates here, so this handler sees every crash regardless of attribution.
        val downstream = Thread.getDefaultUncaughtExceptionHandler()
        appendLog("downstream handler (crashsink will delegate to): ${downstream?.javaClass?.name}")

        // 2. Install crashsink. The CrashSink lambda (Kotlin SAM) appends each ingested crash
        //    to the on-screen log and to logcat. install() also flushes crashes captured on
        //    the PREVIOUS run — write-now, send-on-next-launch.
        val reporter = CrashReporter.create(
            this,
            /* fileCap = */ 20,
            /* flushTimeoutMillis = */ 1000L,
            { token, _, level, culprit, timestamp, context ->
                // The sink runs on the ingest thread; marshal UI updates to the main thread.
                mainHandler.post { appendLog("INGESTED  culprit=$culprit  token=$token, context==> $context") }
                Log.i(
                    TAG,
                    "sink received crash: culprit=$culprit level=$level ts=$timestamp, context==> $context"
                )
            },
            OWNED_PREFIX
        )
        reporter.install()
        reporter.startCapturing("sample-session")

        appendLog("crashsink installed. ownedPrefix=$OWNED_PREFIX")
        appendLog("Any crash from a previous run shows above as an INGESTED line.")

        findViewById<Button>(R.id.btnCrashSdk).setOnClickListener {
            // Top app frame is com.droiddevgeeks.fakesdk.* → crashsink CAPTURES it (surfaced on
            // next launch) AND delegates down to Crashlytics → lands in BOTH. Breadcrumbs below
            // tag the Crashlytics-side report so you can tell the two paths apart in the console.
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("crash_source", "fakesdk")
                log("about to crash inside FakeSdk (expect crashsink capture + Crashlytics)")
            }
            FakeSdk.boom()
        }
        findViewById<Button>(R.id.btnCrashHost).setOnClickListener {
            // Top app frame is com.droiddevgeeks.sample.* → crashsink does NOT capture; it only
            // delegates down to Crashlytics → lands in Crashlytics ONLY.
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("crash_source", "host")
                log("about to crash in host code (expect Crashlytics only, no crashsink capture)")
            }
            crashInHostCode()
        }
        findViewById<Button>(R.id.btnJavaInterop).setOnClickListener {
            JavaInteropDemo.run(this)
            appendLog("Java interop demo ran (see logcat tag '$TAG').")
        }
    }

    private fun crashInHostCode() {
        throw RuntimeException("Host activity crashed on purpose")
    }

    private fun appendLog(line: String) {
        logView.append(line + "\n")
        Log.d(TAG, line)
    }

    companion object {
        private const val TAG = "crashsink-sample"
        private const val OWNED_PREFIX = "com.droiddevgeeks.fakesdk."
    }
}
