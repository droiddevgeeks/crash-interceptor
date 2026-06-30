package com.droiddevgeeks.crashsink;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Builds the redacted crash payload and writes it within a bounded timeout. Never throws. */
public final class CrashProcessor {

    private static final String TAG = "CrashProcessor";

    private final Redactor redactor;
    private final CrashFileStore store;
    private final ExecutorService writerExecutor;
    private final long flushTimeoutMillis;
    private final String ownedPrefix;
    private final DeviceMetadata metadata;
    private final Set<Throwable> handled =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>()));

    private volatile boolean tracking = false;
    private volatile String token;
    private final AtomicLong sequence = new AtomicLong(0L);
    // Per-instance salt (computed once, off the crash path) so crash files stay unique across
    // multiple processes writing to a shared dir — nanoTime's origin differs per process.
    private final String instanceTag = Long.toHexString(System.nanoTime());

    public CrashProcessor(final Redactor redactor, final CrashFileStore store,
                          final ExecutorService writerExecutor, final long flushTimeoutMillis,
                          final String ownedPrefix, final DeviceMetadata metadata) {
        this.redactor = redactor;
        this.store = store;
        this.writerExecutor = writerExecutor;
        this.flushTimeoutMillis = flushTimeoutMillis;
        this.ownedPrefix = ownedPrefix;
        this.metadata = metadata;
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
        final String fileBase = "crash_" + crashTs + "_" + instanceTag + "_" + sequence.getAndIncrement();

        final CountDownLatch latch = new CountDownLatch(1);
        try {
            writerExecutor.submit(new Runnable() {
                @Override public void run() {
                    try {
                        final String json =
                                buildPayloadJson(throwable, threadId, currentToken, redactor, crashTs, ownedPrefix, metadata);
                        store.writeAtomic(fileBase, json);
                    } catch (Throwable t) {
                        CrashLogger.getInstance().e(TAG, "crash write failed: " + t.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });
        } catch (Throwable t) {
            // Executor rejected the task (e.g. shut down). Never propagate from the crash path.
            CrashLogger.getInstance().e(TAG, "crash write could not be scheduled: " + t.getMessage());
            return false;
        }

        try {
            final boolean flushed = latch.await(flushTimeoutMillis, TimeUnit.MILLISECONDS);
            if (!flushed) {
                CrashLogger.getInstance().e(TAG, "crash flush timed out");
            }
            return flushed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Pure payload builder: a JSON object describing the crash and its cause chain. */
    static String buildPayloadJson(final Throwable throwable, final long threadId,
                                   final String token, final Redactor redactor,
                                   final long crashTimestamp, final String ownedPrefix,
                                   final DeviceMetadata metadata) {
        final JSONArray values = new JSONArray();
        Throwable present = throwable;
        int guard = 0;
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
                    frame.put("in_app", CrashFrames.isOurs(el.getClassName(), ownedPrefix));
                } catch (Throwable ignored) { }
                frames.put(frame);
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

        final StackTraceElement originFrame = CrashFrames.firstApplicationFrame(
                CrashFrames.deepestCause(throwable).getStackTrace());
        final String culprit = originFrame != null
                ? originFrame.getClassName() + " in " + originFrame.getMethodName()
                : "unknown";

        final org.json.JSONObject contexts = new org.json.JSONObject();
        try {
            if (metadata != null) {
                final org.json.JSONObject device = new org.json.JSONObject();
                device.put("os_version", metadata.osVersion);
                device.put("sdk_int", metadata.sdkInt);
                device.put("manufacturer", metadata.manufacturer);
                device.put("model", metadata.model);
                device.put("app_version_name", metadata.appVersionName);
                device.put("app_version_code", metadata.appVersionCode);
                contexts.put("device", device);
            }
            final Runtime rt = Runtime.getRuntime();
            final org.json.JSONObject memory = new org.json.JSONObject();
            memory.put("heap_free", rt.freeMemory());
            memory.put("heap_total", rt.totalMemory());
            memory.put("heap_max", rt.maxMemory());
            contexts.put("memory", memory);
        } catch (Throwable ignored) {
        }

        final JSONObject payload = new JSONObject();
        try {
            payload.put("token", token);
            payload.put("level", "fatal");
            payload.put("culprit", culprit);
            payload.put("timestamp", crashTimestamp);
            payload.put("exception_values", values.toString());
            payload.put("contexts", contexts.toString());
        } catch (Throwable ignored) { }
        return payload.toString();
    }
}
