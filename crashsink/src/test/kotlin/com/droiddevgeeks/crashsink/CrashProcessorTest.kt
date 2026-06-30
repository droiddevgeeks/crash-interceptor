package com.droiddevgeeks.crashsink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CrashProcessorTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: CrashFileStore
    private lateinit var writer: ExecutorService
    private lateinit var processor: CrashProcessor

    @Before fun setUp() {
        val dir = tmp.newFolder("crashes")
        store = CrashFileStore(dir, 20)
        writer = Executors.newSingleThreadExecutor()
        processor = CrashProcessor(Redactor(), store, writer, 1000L, "com.example.", null)
        processor.isTracking = true
        processor.token = "token-123"
    }

    private fun ourCrash(): Throwable {
        val t = IllegalStateException("card 4111 1111 1111 1111 bad")
        t.stackTrace = arrayOf(StackTraceElement("com.example.Foo", "bar", "Foo.java", 7))
        return t
    }

    @Test fun persistsOneFileForOurCrash() {
        val flushed = processor.persistBlocking(Thread.currentThread(), ourCrash())
        assertTrue(flushed)
        assertEquals(1, store.listCompleted().size)
    }

    @Test fun payloadIsRedacted() {
        processor.persistBlocking(Thread.currentThread(), ourCrash())
        val f = store.listCompleted()[0]
        val content = String(Files.readAllBytes(f.toPath()))
        assertFalse(content.contains("4111"))
        assertTrue(content.contains("[REDACTED]"))
    }

    @Test fun sameThrowableDedupedToOneFile() {
        val t = ourCrash()
        processor.persistBlocking(Thread.currentThread(), t)
        processor.persistBlocking(Thread.currentThread(), t) // second pass through chain
        assertEquals(1, store.listCompleted().size)
    }

    @Test fun notTrackingWritesNothing() {
        processor.isTracking = false
        processor.persistBlocking(Thread.currentThread(), ourCrash())
        assertEquals(0, store.listCompleted().size)
    }

    @Test fun nullTokenWritesNothing() {
        processor.token = null
        processor.persistBlocking(Thread.currentThread(), ourCrash())
        assertEquals(0, store.listCompleted().size)
    }

    @Test fun rejectedExecutorDoesNotThrowAndReturnsFalse() {
        val dead = Executors.newSingleThreadExecutor()
        dead.shutdownNow()
        val p = CrashProcessor(Redactor(), store, dead, 1000L, "com.example.", null)
        p.isTracking = true
        p.token = "tok"
        // Must NOT throw, and must report not-flushed.
        assertFalse(p.persistBlocking(Thread.currentThread(), ourCrash()))
    }

    @Test fun culpritIsTheAttributedOwnedOriginNotFrameworkTopFrame() {
        val t = IllegalStateException("x")
        t.stackTrace = arrayOf(
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 1),
            StackTraceElement("com.example.Worker", "run", "Worker.java", 2)
        )
        val json = CrashProcessor.buildPayloadJson(t, 1L, "tok", Redactor(), 5L, "com.example.", null)
        val obj = JSONObject(json)
        assertEquals("com.example.Worker in run", obj.getString("culprit"))
    }

    @Test fun contextsContainsDeviceMetadataAndMemory() {
        val md = DeviceMetadata("14", 34, "Google", "Pixel 8", "1.2.3", 42)
        val json = CrashProcessor.buildPayloadJson(ourCrash(), 1L, "tok", Redactor(), 5L, "com.example.", md)
        val payload = JSONObject(json)
        val contexts = JSONObject(payload.getString("contexts"))

        val device = contexts.getJSONObject("device")
        assertEquals("Pixel 8", device.getString("model"))
        assertEquals("Google", device.getString("manufacturer"))
        assertEquals("14", device.getString("os_version"))
        assertEquals(34, device.getInt("sdk_int"))
        assertEquals("1.2.3", device.getString("app_version_name"))
        assertEquals(42, device.getInt("app_version_code"))

        val memory = contexts.getJSONObject("memory")
        assertTrue(memory.has("heap_max"))
        assertTrue(memory.has("heap_free"))
        assertTrue(memory.has("heap_total"))
    }

    @Test fun timeoutReturnsFalseWhenWriteNeverRuns() {
        val neverRuns = object : AbstractExecutorService() {
            override fun execute(command: Runnable) { /* drop the task on the floor */ }
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = ArrayList()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
        }
        val p = CrashProcessor(Redactor(), store, neverRuns, 50L, "com.example.", null)
        p.isTracking = true
        p.token = "tok"
        assertFalse(p.persistBlocking(Thread.currentThread(), ourCrash()))
    }
}
