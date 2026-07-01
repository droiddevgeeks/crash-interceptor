package com.droiddevgeeks.sample

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide sink buffer. crashsink ingests previous-run crashes during
 * [SampleApp.onCreate] — before any Activity exists — so the CrashSink can't touch a
 * TextView directly. It appends here instead; MainActivity observes and re-renders.
 *
 * The change callback is dispatched on the main thread (the sink runs on the ingest
 * thread), so observers can update UI without marshalling themselves.
 */
object CrashLog {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Append-only; iteration is snapshot-safe for the re-render. */
    val ingested = CopyOnWriteArrayList<String>()

    @Volatile
    private var onChange: (() -> Unit)? = null

    /** Called by the CrashSink on the ingest thread for each delivered crash. */
    fun add(line: String) {
        ingested.add(line)
        onChange?.let { cb -> mainHandler.post(cb) }
    }

    /** MainActivity registers a full re-render here; pass null to detach (avoids leaks). */
    fun observe(cb: (() -> Unit)?) {
        onChange = cb
    }
}
