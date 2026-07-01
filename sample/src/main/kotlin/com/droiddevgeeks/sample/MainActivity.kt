package com.droiddevgeeks.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import com.droiddevgeeks.fakesdk.FakeSdk
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * UI only. crashsink is installed once per process in [SampleApp]; this Activity never
 * installs anything — it just renders crashes ingested from the previous run and triggers
 * demo crashes.
 *
 *  - SDK crash  → owned prefix matches → crashsink CAPTURES it (INGESTED next launch) AND
 *                 delegates to Crashlytics → lands in BOTH.
 *  - Host crash → prefix does not match → crashsink skips capture, only delegates → Crashlytics only.
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log)

        // Ingest runs on a background thread in SampleApp.onCreate; re-render whenever a crash
        // lands, and once now for anything already buffered before this Activity existed.
        CrashLog.observe { renderLog() }
        renderLog()

        findViewById<Button>(R.id.btnCrashSdk).setOnClickListener {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("crash_source", "fakesdk")
                log("about to crash inside FakeSdk (expect crashsink capture + Crashlytics)")
            }
            // Top app frame is com.droiddevgeeks.fakesdk.* → captured + delegated → BOTH.
            FakeSdk.boom()
        }
        findViewById<Button>(R.id.btnCrashHost).setOnClickListener {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("crash_source", "host")
                log("about to crash in host code (expect Crashlytics only, no crashsink capture)")
            }
            // Top app frame is com.droiddevgeeks.sample.* → not captured → delegated → Crashlytics only.
            crashInHostCode()
        }
        findViewById<Button>(R.id.btnJavaInterop).setOnClickListener {
            JavaInteropDemo.run(this)
            logView.append("\nJava interop demo ran (see logcat tag '${SampleApp.TAG}').")
        }
    }

    override fun onDestroy() {
        CrashLog.observe(null) // detach the render callback
        super.onDestroy()
    }

    /** Rebuilds the whole log from the header + buffered INGESTED lines. Idempotent. */
    private fun renderLog() {
        val sb = StringBuilder()
            .append("crashsink installed in SampleApp. ownedPrefix=${SampleApp.OWNED_PREFIX}\n")
            .append("Crashes from a previous run appear below as INGESTED lines.\n\n")
        if (CrashLog.ingested.isEmpty()) {
            sb.append("(no crashes ingested from a previous run)\n")
        } else {
            CrashLog.ingested.forEach { sb.append(it).append("\n") }
        }
        logView.text = sb
        Log.d(SampleApp.TAG, "rendered ${CrashLog.ingested.size} ingested crash(es)")
    }

    private fun crashInHostCode() {
        throw RuntimeException("Host activity crashed on purpose")
    }
}
