package com.cashfree.pg.cf_analytics.crash;

import com.cashfree.pg.base.logger.CFLoggerService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Builds the redacted crash payload and writes it within a bounded timeout. Never throws. */
public final class CrashProcessor {

    private static final String TAG = "CrashProcessor";

    private final Redactor redactor;
    private final CrashFileStore store;
    private final ExecutorService writerExecutor;
    private final long flushTimeoutMillis;
    private final Set<Throwable> handled =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>()));

    private volatile boolean tracking = false;
    private volatile String token;
    private long sequence = 0L;

    public CrashProcessor(final Redactor redactor, final CrashFileStore store,
                          final ExecutorService writerExecutor, final long flushTimeoutMillis) {
        this.redactor = redactor;
        this.store = store;
        this.writerExecutor = writerExecutor;
        this.flushTimeoutMillis = flushTimeoutMillis;
    }

    public void setTracking(final boolean tracking) { this.tracking = tracking; }
    public boolean isTracking() { return tracking; }
    public void setToken(final String token) { this.token = token; }
    public String getToken() { return token; }

    public boolean persistBlocking(final Thread thread, final Throwable throwable) {
        if (!tracking || token == null || throwable == null) {
            return false;
        }
        if (!handled.add(throwable)) {
            return true; // already persisted by an earlier link in the chain
        }
        final String currentToken = token;
        final long crashTs = System.currentTimeMillis();
        final long threadId = thread != null ? thread.getId() : -1L;
        final String fileBase = "crash_" + crashTs + "_" + (sequence++);

        final CountDownLatch latch = new CountDownLatch(1);
        writerExecutor.submit(new Runnable() {
            @Override public void run() {
                try {
                    final String json =
                            buildPayloadJson(throwable, threadId, currentToken, redactor, crashTs);
                    store.writeAtomic(fileBase, json);
                } catch (Throwable t) {
                    CFLoggerService.getInstance().e(TAG, "crash write failed: " + t.getMessage());
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            final boolean flushed = latch.await(flushTimeoutMillis, TimeUnit.MILLISECONDS);
            if (!flushed) {
                CFLoggerService.getInstance().e(TAG, "crash flush timed out; delegating anyway");
            }
            return flushed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Pure payload builder: a CFLoggedException-shaped JSON object. */
    static String buildPayloadJson(final Throwable throwable, final long threadId,
                                   final String token, final Redactor redactor,
                                   final long crashTimestamp) {
        final JSONArray values = new JSONArray();
        Throwable present = throwable;
        int guard = 0;
        String culprit = "unknown";
        while (present != null && guard++ < 50) {
            final JSONArray frames = new JSONArray();
            final StackTraceElement[] trace = present.getStackTrace();
            for (StackTraceElement el : trace) {
                final JSONObject frame = new JSONObject();
                try {
                    frame.put("function", el.getMethodName());
                    frame.put("module", el.getClassName());
                    frame.put("filename", el.getFileName());
                    frame.put("lineno", el.getLineNumber());
                    frame.put("in_app", el.getClassName().startsWith("com.cashfree.pg."));
                } catch (Throwable ignored) { }
                frames.put(frame);
            }
            if (trace.length > 0) {
                culprit = trace[0].getClassName() + " in " + trace[0].getMethodName();
            }
            final JSONObject value = new JSONObject();
            try {
                value.put("type", present.getClass().getSimpleName());
                value.put("value", redactor.scrub(present.getMessage()));
                value.put("module", present.getClass().getPackage() != null
                        ? present.getClass().getPackage().getName() : "unknown");
                value.put("thread_id", threadId);
                value.put("stacktrace", new JSONObject().put("frames", frames));
            } catch (Throwable ignored) { }
            values.put(value);
            final Throwable cause = present.getCause();
            present = (cause == present) ? null : cause;
        }

        final JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("level", "fatal");
            payload.put("culprit", culprit);
            payload.put("timestamp", crashTimestamp);
            payload.put("exception_values", values.toString());
        } catch (Throwable ignored) { }
        return payload.toString();
    }
}
