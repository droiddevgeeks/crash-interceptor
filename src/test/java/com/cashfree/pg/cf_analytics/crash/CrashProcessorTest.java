package com.cashfree.pg.cf_analytics.crash;

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
        File dir = tmp.newFolder("cashfree_crashes");
        store = new CrashFileStore(dir, 20);
        writer = Executors.newSingleThreadExecutor();
        processor = new CrashProcessor(new Redactor(), store, writer, 1000L);
        processor.setTracking(true);
        processor.setToken("token-123");
    }

    private static Throwable ourCrash() {
        Throwable t = new IllegalStateException("card 4111 1111 1111 1111 bad");
        t.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.cashfree.pg.Foo", "bar", "Foo.java", 7)});
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
        CrashProcessor p = new CrashProcessor(new Redactor(), store, dead, 1000L);
        p.setTracking(true);
        p.setToken("tok");
        // Must NOT throw, and must report not-flushed.
        assertFalse(p.persistBlocking(Thread.currentThread(), ourCrash()));
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
        CrashProcessor p = new CrashProcessor(new Redactor(), store, neverRuns, 50L);
        p.setTracking(true);
        p.setToken("tok");
        assertFalse(p.persistBlocking(Thread.currentThread(), ourCrash()));
    }
}
