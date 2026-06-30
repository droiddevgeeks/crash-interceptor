package com.droiddevgeeks.crashsink;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Public facade wiring the crash subsystem together. */
public final class CrashReporter {

    private final CrashHandlerManager manager;
    private final CrashProcessor processor;
    private final CrashIngestor ingestor;
    private final ExecutorService writerExecutor;
    private final ExecutorService ioExecutor;

    CrashReporter(final CrashHandlerManager manager, final CrashProcessor processor,
                  final CrashIngestor ingestor, final ExecutorService writerExecutor,
                  final ExecutorService ioExecutor) {
        this.manager = manager;
        this.processor = processor;
        this.ingestor = ingestor;
        this.writerExecutor = writerExecutor;
        this.ioExecutor = ioExecutor;
    }

    public static CrashReporter create(final File crashDir, final int fileCap,
                                       final long flushTimeoutMillis, final CrashSink sink,
                                       final String ownedPrefix) {
        final ExecutorService writerExecutor =
                Executors.newSingleThreadExecutor(namedDaemonFactory("crash-writer"));
        final ExecutorService ioExecutor =
                Executors.newSingleThreadExecutor(namedDaemonFactory("crash-ingest"));
        final CrashFileStore store = new CrashFileStore(crashDir, fileCap);
        final CrashProcessor processor =
                new CrashProcessor(new Redactor(), store, writerExecutor, flushTimeoutMillis, ownedPrefix);
        final CrashHandlerManager manager = new CrashHandlerManager(new CrashAttributor(ownedPrefix), processor);
        final CrashIngestor ingestor = new CrashIngestor(store, sink, ioExecutor);
        return new CrashReporter(manager, processor, ingestor, writerExecutor, ioExecutor);
    }

    /** Named daemon threads: easy to spot in a thread dump, never block host JVM exit. */
    private static ThreadFactory namedDaemonFactory(final String name) {
        return new ThreadFactory() {
            @Override public Thread newThread(final Runnable r) {
                final Thread thread = new Thread(r, name);
                thread.setDaemon(true);
                return thread;
            }
        };
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

    /**
     * Releases the background executor threads. Call when the reporter is no longer needed
     * (e.g. before re-creating one) to avoid thread accumulation. The reporter must not be
     * reused after this.
     */
    public void shutdown() {
        writerExecutor.shutdown();
        ioExecutor.shutdown();
    }
}
