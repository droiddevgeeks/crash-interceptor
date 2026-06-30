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

/**
 * Test harness for crashsink. Demonstrates the core contract: capture only the SDK's own
 * crashes, delegate everything else, and surface previously-captured crashes on next launch.
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log)

        // 1. Install a demo HOST handler FIRST, then crashsink. This proves crashsink
        //    *decorates* the chain (delegates to whatever was there) rather than replacing it.
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "HOST HANDLER saw it: ${throwable.javaClass.simpleName}")
            systemHandler?.uncaughtException(thread, throwable)
        }

        // 2. Install crashsink. The CrashSink lambda (Kotlin SAM) appends each ingested crash
        //    to the on-screen log and to logcat. install() also flushes crashes captured on
        //    the PREVIOUS run — write-now, send-on-next-launch.
        val reporter = CrashReporter.create(
            this,
            /* fileCap = */ 20,
            /* flushTimeoutMillis = */ 1000L,
            { token, _, level, culprit, timestamp, _ ->
                // The sink runs on the ingest thread; marshal UI updates to the main thread.
                mainHandler.post { appendLog("INGESTED  culprit=$culprit  token=$token") }
                Log.i(TAG, "sink received crash: culprit=$culprit level=$level ts=$timestamp")
            },
            OWNED_PREFIX
        )
        reporter.install()
        reporter.startCapturing("sample-session")

        appendLog("crashsink installed. ownedPrefix=$OWNED_PREFIX")
        appendLog("Any crash from a previous run shows above as an INGESTED line.")

        findViewById<Button>(R.id.btnCrashSdk).setOnClickListener {
            // Top app frame is com.droiddevgeeks.fakesdk.* → CAPTURED, surfaced on next launch.
            FakeSdk.boom()
        }
        findViewById<Button>(R.id.btnCrashHost).setOnClickListener {
            // Top app frame is com.droiddevgeeks.sample.* → NOT captured → delegated to host.
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
