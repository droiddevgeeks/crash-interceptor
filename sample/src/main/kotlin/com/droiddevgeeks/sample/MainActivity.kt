package com.droiddevgeeks.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.droiddevgeeks.fakesdk.FakeSdk
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * UI + a second init call. The guest SDK was already initialised from [SampleApp]; calling
 * [FakeSdk.init] again here (a host that inits from both Application and an Activity) is safe —
 * crashsink adopts the interceptor already in the chain instead of stacking a duplicate, so a
 * single crash is never delivered twice. The buttons just trigger demo crashes; ingested
 * previous-run crashes are logged to logcat by FakeSdk's sink (tag "crashsink-sample").
 *
 *  - SDK crash  → owned prefix matches → crashsink CAPTURES it (logged next launch) AND
 *                 delegates to Crashlytics → lands in BOTH.
 *  - Host crash → prefix does not match → crashsink skips capture, only delegates → Crashlytics only.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Idempotent: SampleApp already called this. A real host might only init the SDK from
        // an Activity (no Application), so the SDK must tolerate being init'd from either.
        FakeSdk.init(this)

        findViewById<TextView>(R.id.log).text =
            "crashsink installed from FakeSdk.init. ownedPrefix=${FakeSdk.OWNED_PREFIX}\n" +
            "Trigger a crash, relaunch, and watch logcat (tag '${FakeSdk.TAG}') for the\n" +
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
