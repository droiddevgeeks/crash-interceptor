package com.cashfree.pg.cf_analytics.crash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

public class CrashIngestorTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private CrashFileStore store;
    private RecordingSink sink;
    private CrashIngestor ingestor;

    /** Runs submitted work inline so the test is deterministic. */
    private static ExecutorService directExecutor() {
        return new java.util.concurrent.AbstractExecutorService() {
            public void execute(Runnable r) { r.run(); }
            public void shutdown() {}
            public List<Runnable> shutdownNow() { return new ArrayList<>(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long t, java.util.concurrent.TimeUnit u) { return true; }
        };
    }

    static final class RecordingSink implements CrashSink {
        final List<String> tokens = new ArrayList<>();
        @Override public void submit(String token, String exceptionValues, String level,
                                     String culprit, long timestamp) {
            tokens.add(token);
        }
    }

    @Before public void setUp() throws IOException {
        File dir = tmp.newFolder("cashfree_crashes");
        store = new CrashFileStore(dir, 20);
        sink = new RecordingSink();
        ingestor = new CrashIngestor(store, sink, directExecutor());
    }

    @Test public void ingestsCompletedFileThenDeletesIt() throws IOException {
        String json = CrashProcessor.buildPayloadJson(
                ourCrash(), 1L, "tok-A", new Redactor(), 123L);
        store.writeAtomic("c1", json);

        ingestor.flushAsync();

        assertEquals(1, sink.tokens.size());
        assertEquals("tok-A", sink.tokens.get(0));
        assertEquals(0, store.listCompleted().size());
    }

    @Test public void poisonFileIsDeletedNotRetriedForever() throws IOException {
        store.writeAtomic("bad", "{ this is not valid json ");
        ingestor.flushAsync();
        assertEquals(0, sink.tokens.size());
        assertEquals(0, store.listCompleted().size());
    }

    @Test public void sweepsLeftoverTemps() throws IOException {
        File orphan = new File(tmp.getRoot(), "cashfree_crashes/dead.tmp");
        orphan.createNewFile();
        ingestor.flushAsync();
        assertFalse(orphan.exists());
    }

    private static Throwable ourCrash() {
        Throwable t = new RuntimeException("x");
        t.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.cashfree.pg.Foo", "bar", "Foo.java", 1)});
        return t;
    }
}
