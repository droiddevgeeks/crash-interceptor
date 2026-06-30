package com.droiddevgeeks.sample;

import android.content.Context;
import android.util.Log;

import com.droiddevgeeks.crashsink.CrashReporter;
import com.droiddevgeeks.crashsink.CrashSink;

/**
 * Proves the Kotlin crashsink API is callable from Java — at compile time (this file compiles
 * against the Kotlin library) and at runtime (the button invokes {@link #run}).
 *
 * Exercises: the static {@code create(Context, int, long, CrashSink, String)} factory,
 * {@link CrashSink} implemented as a Java lambda (a Kotlin {@code fun interface} SAM), and the
 * {@code startCapturing}/{@code stopCapturing}/{@code shutdown} instance methods.
 */
public final class JavaInteropDemo {

    private static final String TAG = "crashsink-sample";

    private JavaInteropDemo() {
    }

    public static void run(Context context) {
        // CrashSink as a Java lambda — the Kotlin `fun interface` is a SAM from Java too.
        CrashSink sink = (token, exceptionValues, level, culprit, timestamp, contexts) ->
                Log.i(TAG, "[java] sink got crash culprit=" + culprit + " token=" + token);

        // Kotlin companion @JvmStatic factory, called as a plain static method from Java.
        CrashReporter reporter =
                CrashReporter.create(context, 20, 1000L, sink, "com.droiddevgeeks.fakesdk.");

        // Instance methods. We intentionally do NOT install() here — this demo only proves the
        // API is Java-callable without disturbing the Activity's own reporter chain.
        reporter.startCapturing("java-interop-session");
        Log.i(TAG, "[java] started capturing via Java-called Kotlin API");
        reporter.stopCapturing();
        reporter.shutdown();
        Log.i(TAG, "[java] stopped + shut down cleanly");
    }
}
