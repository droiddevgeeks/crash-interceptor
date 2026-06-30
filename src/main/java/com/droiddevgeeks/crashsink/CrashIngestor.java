package com.droiddevgeeks.crashsink;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;

/** On a healthy process, reconstructs persisted crashes and feeds them to the pipeline. */
public final class CrashIngestor {

    private static final String TAG = "CrashIngestor";

    private final CrashFileStore store;
    private final CrashSink sink;
    private final Executor ioExecutor;

    public CrashIngestor(final CrashFileStore store, final CrashSink sink, final Executor ioExecutor) {
        this.store = store;
        this.sink = sink;
        this.ioExecutor = ioExecutor;
    }

    public void flushAsync() {
        ioExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    store.sweepTemps();
                } catch (Throwable t) {
                    CrashLogger.getInstance().e(TAG, "temp sweep failed: " + t.getMessage());
                }
                try {
                    final List<File> files = store.listCompleted();
                    for (File file : files) {
                        ingestOne(file);
                    }
                } catch (Throwable t) {
                    CrashLogger.getInstance().e(TAG, "ingest failed: " + t.getMessage());
                }
            }
        });
    }

    private void ingestOne(final File file) {
        final String token;
        final String exceptionValues;
        final String level;
        final String culprit;
        final long timestamp;
        try {
            final String content = readUtf8(file);
            final JSONObject json = new JSONObject(content);
            token = json.isNull("token") ? null : json.optString("token", null);
            exceptionValues = json.optString("exception_values", "[]");
            level = json.optString("level", "fatal");
            culprit = json.optString("culprit", "unknown");
            timestamp = json.optLong("timestamp", 0L);
        } catch (Throwable t) {
            // Unparseable poison file: drop it so it cannot wedge the queue.
            CrashLogger.getInstance().e(TAG, "dropping unparseable crash file: " + t.getMessage());
            store.delete(file);
            return;
        }
        try {
            sink.submit(token, exceptionValues, level, culprit, timestamp);
            store.delete(file); // delete only after a successful hand-off
        } catch (Throwable t) {
            // Downstream submission failed; keep the file for retry on a future flush.
            CrashLogger.getInstance().e(TAG, "sink submit failed; keeping crash file for retry: " + t.getMessage());
        }
    }

    /**
     * Reads a (small) crash file as UTF-8 using only java.io, so it works on Android API 21+
     * with no java.nio.file dependency (java.nio.file is API 26+ and not desugared).
     */
    private static String readUtf8(final File file) throws IOException {
        final int size = (int) file.length();
        final byte[] buffer = new byte[size];
        final FileInputStream in = new FileInputStream(file);
        try {
            int offset = 0;
            int read;
            while (offset < size && (read = in.read(buffer, offset, size - offset)) != -1) {
                offset += read;
            }
        } finally {
            in.close();
        }
        return new String(buffer, StandardCharsets.UTF_8);
    }
}
