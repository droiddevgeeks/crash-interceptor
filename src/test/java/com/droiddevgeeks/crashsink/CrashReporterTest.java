package com.droiddevgeeks.crashsink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CrashReporterTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Thread.UncaughtExceptionHandler original;
    private RecordingHandler previous;

    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            public void execute(Runnable r) { r.run(); }
            public void shutdown() {}
            public List<Runnable> shutdownNow() { return new ArrayList<>(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long t, TimeUnit u) { return true; }
        };
    }

    static final class RecordingHandler implements Thread.UncaughtExceptionHandler {
        volatile boolean called = false;
        public void uncaughtException(Thread t, Throwable e) { called = true; }
    }

    static final class RecordingSink implements CrashSink {
        final List<String> tokens = new ArrayList<>();
        public void submit(String token, String ev, String level, String culprit, long ts, String contexts) {
            tokens.add(token);
        }
    }

    private static Throwable ourCrash() {
        Throwable t = new IllegalStateException("boom");
        t.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.example.Foo", "bar", "Foo.java", 1)});
        return t;
    }

    private CrashReporter newReporter(CrashFileStore store, CrashSink sink) {
        ExecutorService writer = directExecutor();
        ExecutorService io = directExecutor();
        CrashProcessor processor = new CrashProcessor(new Redactor(), store, writer, 1000L, "com.example.", null);
        CrashHandlerManager manager = new CrashHandlerManager(new CrashAttributor("com.example."), processor);
        CrashIngestor ingestor = new CrashIngestor(store, sink, io);
        return new CrashReporter(manager, processor, ingestor, writer, io);
    }

    @Before public void setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler();
        previous = new RecordingHandler();
        Thread.setDefaultUncaughtExceptionHandler(previous); // becomes our 'previous' on install
    }

    @After public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original);
    }

    @Test public void capturesOurCrashAndDelegatesToHost() throws IOException {
        File dir = tmp.newFolder("crashes");
        CrashFileStore store = new CrashFileStore(dir, 20);
        CrashReporter reporter = newReporter(store, new RecordingSink());

        reporter.install();
        reporter.startCapturing("tok-int");

        Thread.getDefaultUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), ourCrash());

        assertEquals(1, store.listCompleted().size());   // captured
        assertTrue(previous.called);                      // delegated to host
    }

    @Test public void doesNotCaptureWhenStopped() throws IOException {
        File dir = tmp.newFolder("crashes");
        CrashFileStore store = new CrashFileStore(dir, 20);
        CrashReporter reporter = newReporter(store, new RecordingSink());

        reporter.install();
        reporter.startCapturing("tok-int");
        reporter.stopCapturing();

        Thread.getDefaultUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), ourCrash());

        assertEquals(0, store.listCompleted().size());   // not captured
        assertTrue(previous.called);                      // still delegated
    }

    @Test public void installIngestsCrashesFromPreviousRun() throws IOException {
        File dir = tmp.newFolder("crashes");
        CrashFileStore store = new CrashFileStore(dir, 20);
        store.writeAtomic("old",
                CrashProcessor.buildPayloadJson(ourCrash(), 1L, "tok-prev", new Redactor(), 9L, "com.example.", null));

        RecordingSink sink = new RecordingSink();
        CrashReporter reporter = newReporter(store, sink);
        reporter.install(); // flushAsync runs on the direct executor -> synchronous

        assertEquals(1, sink.tokens.size());
        assertEquals("tok-prev", sink.tokens.get(0));
        assertEquals(0, store.listCompleted().size());
    }

    @Test public void contextOverloadCapturesCrashUnderFilesDirAndDelegates() throws Exception {
        File filesDir = tmp.newFolder("files");

        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        PackageInfo pi = new PackageInfo();
        pi.versionName = "1.2.3";
        pi.versionCode = 42;
        when(context.getFilesDir()).thenReturn(filesDir);
        when(context.getPackageManager()).thenReturn(pm);
        when(context.getPackageName()).thenReturn("com.example");
        when(pm.getPackageInfo("com.example", 0)).thenReturn(pi);

        CrashReporter reporter =
                CrashReporter.create(context, 20, 1000L, new RecordingSink(), "com.example.");
        reporter.install();
        reporter.startCapturing("tok-ctx");

        Thread.getDefaultUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), ourCrash());

        // persistBlocking waits up to the flush timeout for the daemon writer.
        File crashDir = new File(filesDir, "crashes");
        CrashFileStore store = new CrashFileStore(crashDir, 20);
        assertEquals(1, store.listCompleted().size());  // captured under filesDir/crashes
        assertTrue(previous.called);                     // delegated to host

        reporter.shutdown();
    }

    @Test public void shutdownStopsBothExecutors() throws IOException {
        File dir = tmp.newFolder("crashes");
        CrashFileStore store = new CrashFileStore(dir, 20);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        ExecutorService io = Executors.newSingleThreadExecutor();
        CrashProcessor processor = new CrashProcessor(new Redactor(), store, writer, 1000L, "com.example.", null);
        CrashHandlerManager manager = new CrashHandlerManager(new CrashAttributor("com.example."), processor);
        CrashIngestor ingestor = new CrashIngestor(store, new RecordingSink(), io);
        CrashReporter reporter = new CrashReporter(manager, processor, ingestor, writer, io);

        reporter.shutdown();

        assertTrue(writer.isShutdown());
        assertTrue(io.isShutdown());
    }
}
