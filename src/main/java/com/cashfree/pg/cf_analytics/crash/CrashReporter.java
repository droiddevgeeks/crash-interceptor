package com.cashfree.pg.cf_analytics.crash;

import java.io.File;
import java.util.concurrent.Executors;

/** Public facade wiring the crash subsystem together. */
public final class CrashReporter {

    private final CrashHandlerManager manager;
    private final CrashProcessor processor;
    private final CrashIngestor ingestor;

    CrashReporter(final CrashHandlerManager manager, final CrashProcessor processor,
                  final CrashIngestor ingestor) {
        this.manager = manager;
        this.processor = processor;
        this.ingestor = ingestor;
    }

    public static CrashReporter create(final File crashDir, final int fileCap,
                                       final long flushTimeoutMillis, final CrashSink sink) {
        final CrashFileStore store = new CrashFileStore(crashDir, fileCap);
        final CrashProcessor processor = new CrashProcessor(
                new Redactor(), store, Executors.newSingleThreadExecutor(), flushTimeoutMillis);
        final CrashHandlerManager manager = new CrashHandlerManager(new CrashAttributor(), processor);
        final CrashIngestor ingestor = new CrashIngestor(store, sink, Executors.newSingleThreadExecutor());
        return new CrashReporter(manager, processor, ingestor);
    }

    /** Install the interceptor into the chain and flush any crashes left from previous runs. */
    public void install() {
        manager.install();
        ingestor.flushAsync();
    }

    /** Begin capturing crashes for the given session token; re-assert our chain position. */
    public void startCapturing(final String token) {
        processor.setToken(token);
        processor.setTracking(true);
        manager.reassert();
    }

    /** Stop capturing (token-scoped session ended). */
    public void stopCapturing() {
        processor.setTracking(false);
    }
}
