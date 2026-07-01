package com.droiddevgeeks.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.droiddevgeeks.fakesdk.FakeSdk
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * UI only. crashsink is installed once per process in [SampleApp]; this Activity never
 * installs anything — it just triggers demo crashes. Ingested previous-run crashes are
 * logged to logcat by the sink in SampleApp (tag "crashsink-sample").
 *
 *  - SDK crash  → owned prefix matches → crashsink CAPTURES it (logged next launch) AND
 *                 delegates to Crashlytics → lands in BOTH.
 *  - Host crash → prefix does not match → crashsink skips capture, only delegates → Crashlytics only.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.log).text =
            "crashsink installed in SampleApp. ownedPrefix=${SampleApp.OWNED_PREFIX}\n" +
            "Trigger a crash, relaunch, and watch logcat (tag '${SampleApp.TAG}') for the\n" +
            "INGESTED line from the previous run."

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
        }
    }

    private fun crashInHostCode() {
        throw RuntimeException("Host activity crashed on purpose")
    }
}
