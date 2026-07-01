package com.droiddevgeeks.crashsink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Compile-time and runtime proof that the (Kotlin) crashsink API is fully usable from Java.
 *
 * The rest of the unit suite is Kotlin; this single Java class is the library's permanent,
 * CI-enforced interop guard. It exercises every Java-facing seam: the SAM {@link CrashSink},
 * the static {@code create}/{@code buildPayloadJson} factories, {@link DeviceMetadata} field
 * access, the public wiring constructor, and a full Java-driven capture + ingest round-trip.
 */
public class JavaInteropTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Thread.UncaughtExceptionHandler original;

    /** Runs submitted work inline so the round-trip test is deterministic. */
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

    private static Throwable ourCrash() {
        Throwable t = new IllegalStateException("boom from java");
        t.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.example.Foo", "bar", "Foo.java", 1)});
        return t;
    }

    @Before public void setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler();
    }

    @After public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original);
    }

    @Test public void crashSinkImplementableAsJavaLambda() {
        List<String> got = new ArrayList<>();
        CrashSink sink = (token, exceptionValues, level, culprit, timestamp, contexts) -> got.add(culprit);
        sink.submit("tok", "[]", "fatal", "com.example.Foo in bar", 1L, "{}");
        assertEquals(1, got.size());
        assertEquals("com.example.Foo in bar", got.get(0));
    }

    @Test public void deviceMetadataExposesPublicFieldsToJava() {
        DeviceMetadata md = new DeviceMetadata("13", 33, "Google", "Pixel 7", "1.4.0", 140);
        assertEquals("13", md.osVersion);
        assertEquals(33, md.sdkInt);
        assertEquals("Google", md.manufacturer);
        assertEquals("Pixel 7", md.model);
        assertEquals("1.4.0", md.appVersionName);
        assertEquals(140, md.appVersionCode);
    }

    @Test public void buildPayloadJsonCallableStaticallyFromJava() throws Exception {
        String json = CrashProcessor.buildPayloadJson(
                ourCrash(), 7L, "tok-j", new Redactor(), 99L, "com.example.", null);
        JSONObject obj = new JSONObject(json);
        assertEquals("tok-j", obj.getString("token"));
        assertEquals("com.example.Foo in bar", obj.getString("culprit"));
    }

    @Test public void fileFactoryCreateAndLifecycleCallableFromJava() throws Exception {
        File dir = tmp.newFolder("crashes");
        CrashSink sink = (token, ev, level, culprit, ts, ctx) -> { /* no-op */ };

        // The create(File, int, long, CrashSink, String) factory and the instance lifecycle
        // methods are all plain Java calls against the Kotlin class.
        CrashReporter reporter = CrashReporter.create(dir, 20, 1000L, sink, "com.example.");
        reporter.startCapturing("session-j");
        reporter.stopCapturing();
        reporter.shutdown();
    }

    @Test public void convenienceOverloadAndDefaultConstantsCallableFromJava() throws Exception {
        // The default cap constant is a plain static field from Java.
        assertEquals(20, CrashReporter.DEFAULT_FILE_CAP);
        assertEquals(1000L, CrashReporter.DEFAULT_FLUSH_TIMEOUT_MS);

        File dir = tmp.newFolder("crashes");
        CrashSink sink = (token, ev, level, culprit, ts, ctx) -> { /* no-op */ };

        // Three-arg convenience overload (defaults for fileCap + flushTimeout), from Java.
        CrashReporter reporter = CrashReporter.create(dir, sink, "com.example.");
        reporter.startCapturing("session-j-default");
        reporter.stopCapturing();
        reporter.shutdown();
    }

    @Test public void wiringConstructorCaptureAndIngestRoundTripFromJava() throws Exception {
        File dir = tmp.newFolder("crashes");
        CrashFileStore store = new CrashFileStore(dir, 20);

        final boolean[] hostCalled = {false};
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> hostCalled[0] = true);

        List<String> tokens = new ArrayList<>();
        CrashSink sink = (token, ev, level, culprit, ts, ctx) -> tokens.add(token);

        ExecutorService writer = directExecutor();
        ExecutorService io = directExecutor();
        CrashProcessor processor =
                new CrashProcessor(new Redactor(), store, writer, 1000L, "com.example.", null);
        CrashHandlerManager manager =
                new CrashHandlerManager(new CrashAttributor("com.example."), processor);
        CrashIngestor ingestor = new CrashIngestor(store, sink, io);
        // The public wiring constructor, instantiated from Java.
        CrashReporter reporter = new CrashReporter(manager, processor, ingestor, writer, io);

        reporter.install();
        reporter.startCapturing("tok-e2e");

        Thread.getDefaultUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), ourCrash());

        assertEquals(1, store.listCompleted().size()); // captured by the Kotlin pipeline
        assertTrue(hostCalled[0]);                      // delegated to the Java host handler

        // Simulate a fresh launch ingesting the persisted crash → handed to the Java sink.
        new CrashIngestor(store, sink, directExecutor()).flushAsync();

        assertEquals(1, tokens.size());
        assertEquals("tok-e2e", tokens.get(0));
        assertEquals(0, store.listCompleted().size()); // delivered, then deleted
    }
}
