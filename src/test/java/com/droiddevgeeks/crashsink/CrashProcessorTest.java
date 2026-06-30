package com.droiddevgeeks.crashsink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

public class CrashProcessorTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private CrashFileStore store;
    private ExecutorService writer;
    private CrashProcessor processor;

    @Before public void setUp() throws IOException {
        File dir = tmp.newFolder("crashes");
        store = new CrashFileStore(dir, 20);
        writer = Executors.newSingleThreadExecutor();
        processor = new CrashProcessor(new Redactor(), store, writer, 1000L, "com.example.", null);
        processor.setTracking(true);
        processor.setToken("token-123");
    }

    private static Throwable ourCrash() {
        Throwable t = new IllegalStateException("card 4111 1111 1111 1111 bad");
        t.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.example.Foo", "bar", "Foo.java", 7)});
        return t;
    }

    @Test public void persistsOneFileForOurCrash() {
        boolean flushed = processor.persistBlocking(Thread.currentThread(), ourCrash());
        assertTrue(flushed);
        assertEquals(1, store.listCompleted().size());
    }

    @Test public void payloadIsRedacted() throws IOException {
        processor.persistBlocking(Thread.currentThread(), ourCrash());
        File f = store.listCompleted().get(0);
        String content = new String(java.nio.file.Files.readAllBytes(f.toPath()));
        assertFalse(content.contains("4111"));
        assertTrue(content.contains("[REDACTED]"));
    }

    @Test public void sameThrowableDedupedToOneFile() {
        Throwable t = ourCrash();
        processor.persistBlocking(Thread.currentThread(), t);
        processor.persistBlocking(Thread.currentThread(), t); // second pass through chain
        assertEquals(1, store.listCompleted().size());
    }

    @Test public void notTrackingWritesNothing() {
        processor.setTracking(false);
        processor.persistBlocking(Thread.currentThread(), ourCrash());
        assertEquals(0, store.listCompleted().size());
    }

    @Test public void nullTokenWritesNothing() {
        processor.setToken(null);
        processor.persistBlocking(Thread.currentThread(), ourCrash());
        assertEquals(0, store.listCompleted().size());
    }

    @Test public void rejectedExecutorDoesNotThrowAndReturnsFalse() {
        ExecutorService dead = Executors.newSingleThreadExecutor();
        dead.shutdownNow();
        CrashProcessor p = new CrashProcessor(new Redactor(), store, dead, 1000L, "com.example.", null);
        p.setTracking(true);
        p.setToken("tok");
        // Must NOT throw, and must report not-flushed.
        assertFalse(p.persistBlocking(Thread.currentThread(), ourCrash()));
    }

    @Test public void culpritIsTheAttributedOwnedOriginNotFrameworkTopFrame() throws Exception {
        Throwable t = new IllegalStateException("x");
        t.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 1),
                new StackTraceElement("com.example.Worker", "run", "Worker.java", 2)});
        String json = CrashProcessor.buildPayloadJson(t, 1L, "tok", new Redactor(), 5L, "com.example.", null);
        org.json.JSONObject obj = new org.json.JSONObject(json);
        assertEquals("com.example.Worker in run", obj.getString("culprit"));
    }

    @Test public void contextsContainsDeviceMetadataAndMemory() throws Exception {
        DeviceMetadata md = new DeviceMetadata(
                "14", 34, "Google", "Pixel 8", "1.2.3", 42);
        String json = CrashProcessor.buildPayloadJson(
                ourCrash(), 1L, "tok", new Redactor(), 5L, "com.example.", md);
        org.json.JSONObject payload = new org.json.JSONObject(json);
        org.json.JSONObject contexts = new org.json.JSONObject(payload.getString("contexts"));

        org.json.JSONObject device = contexts.getJSONObject("device");
        assertEquals("Pixel 8", device.getString("model"));
        assertEquals("Google", device.getString("manufacturer"));
        assertEquals("14", device.getString("os_version"));
        assertEquals(34, device.getInt("sdk_int"));
        assertEquals("1.2.3", device.getString("app_version_name"));
        assertEquals(42, device.getInt("app_version_code"));

        org.json.JSONObject memory = contexts.getJSONObject("memory");
        assertTrue(memory.has("heap_max"));
        assertTrue(memory.has("heap_free"));
        assertTrue(memory.has("heap_total"));
    }

    @Test public void timeoutReturnsFalseWhenWriteNeverRuns() {
        ExecutorService neverRuns = new AbstractExecutorService() {
            public void execute(Runnable r) { /* drop the task on the floor */ }
            public void shutdown() {}
            public List<Runnable> shutdownNow() { return new ArrayList<>(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long t, TimeUnit u) { return true; }
        };
        CrashProcessor p = new CrashProcessor(new Redactor(), store, neverRuns, 50L, "com.example.", null);
        p.setTracking(true);
        p.setToken("tok");
        assertFalse(p.persistBlocking(Thread.currentThread(), ourCrash()));
    }
}
