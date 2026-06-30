package com.droiddevgeeks.crashsink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class CrashIngestorTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: CrashFileStore
    private lateinit var sink: RecordingSink
    private lateinit var ingestor: CrashIngestor

    /** Runs submitted work inline so the test is deterministic. */
    private fun directExecutor(): ExecutorService = object : AbstractExecutorService() {
        override fun execute(command: Runnable) { command.run() }
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = ArrayList()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private class RecordingSink : CrashSink {
        val tokens = ArrayList<String?>()
        override fun submit(
            token: String?, exceptionValues: String, level: String,
            culprit: String, timestamp: Long, contexts: String
        ) {
            tokens.add(token)
        }
    }

    @Before fun setUp() {
        val dir = tmp.newFolder("crashes")
        store = CrashFileStore(dir, 20)
        sink = RecordingSink()
        ingestor = CrashIngestor(store, sink, directExecutor())
    }

    @Test fun ingestsCompletedFileThenDeletesIt() {
        val json = CrashProcessor.buildPayloadJson(ourCrash(), 1L, "tok-A", Redactor(), 123L, "com.example.", null)
        store.writeAtomic("c1", json)

        ingestor.flushAsync()

        assertEquals(1, sink.tokens.size)
        assertEquals("tok-A", sink.tokens[0])
        assertEquals(0, store.listCompleted().size)
    }

    @Test fun poisonFileIsDeletedNotRetriedForever() {
        store.writeAtomic("bad", "{ this is not valid json ")
        ingestor.flushAsync()
        assertEquals(0, sink.tokens.size)
        assertEquals(0, store.listCompleted().size)
    }

    @Test fun sweepsLeftoverTemps() {
        val orphan = File(tmp.root, "crashes/dead.tmp")
        orphan.createNewFile()
        ingestor.flushAsync()
        assertFalse(orphan.exists())
    }

    @Test fun sinkFailureKeepsFileForRetry() {
        val throwingSink = CrashSink { _, _, _, _, _, _ -> throw RuntimeException("downstream down") }
        val ing = CrashIngestor(store, throwingSink, directExecutor())
        store.writeAtomic(
            "keep",
            CrashProcessor.buildPayloadJson(ourCrash(), 1L, "tok-K", Redactor(), 1L, "com.example.", null)
        )

        ing.flushAsync()

        // Submission failed → file must remain for a future retry, not be deleted.
        assertEquals(1, store.listCompleted().size)
    }

    private fun ourCrash(): Throwable {
        val t = RuntimeException("x")
        t.stackTrace = arrayOf(StackTraceElement("com.example.Foo", "bar", "Foo.java", 1))
        return t
    }
}
