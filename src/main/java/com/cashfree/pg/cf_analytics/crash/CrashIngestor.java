package com.cashfree.pg.cf_analytics.crash;

import com.cashfree.pg.base.logger.CFLoggerService;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
                    final List<File> files = store.listCompleted();
                    for (File file : files) {
                        ingestOne(file);
                    }
                } catch (Throwable t) {
                    CFLoggerService.getInstance().e(TAG, "ingest sweep failed: " + t.getMessage());
                }
            }
        });
    }

    private void ingestOne(final File file) {
        try {
            final String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            final JSONObject json = new JSONObject(content);
            sink.submit(
                    json.optString("token", null),
                    json.optString("exception_values", "[]"),
                    json.optString("level", "fatal"),
                    json.optString("culprit", "unknown"),
                    json.optLong("timestamp", 0L));
            store.delete(file);
        } catch (Throwable t) {
            // Poison file: drop it so it cannot wedge the queue.
            CFLoggerService.getInstance().e(TAG, "dropping unparseable crash file: " + t.getMessage());
            store.delete(file);
        }
    }
}
