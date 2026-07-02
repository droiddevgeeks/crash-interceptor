package com.droiddevgeeks.crashsink

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CrashReporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private var original: Thread.UncaughtExceptionHandler? = null
    private lateinit var previous: RecordingHandler

    private fun directExecutor(): ExecutorService = object : AbstractExecutorService() {
        override fun execute(command: Runnable) { command.run() }
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = ArrayList()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        @Volatile var called = false
        override fun uncaughtException(t: Thread, e: Throwable) { called = true }
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

    private fun ourCrash(): Throwable {
        val t = IllegalStateException("boom")
        t.stackTrace = arrayOf(StackTraceElement("com.example.Foo", "bar", "Foo.java", 1))
        return t
    }

    private fun newReporter(store: CrashFileStore, sink: CrashSink): CrashReporter {
        val writer = directExecutor()
        val io = directExecutor()
        val processor = CrashProcessor(Redactor(), store, writer, 1000L, "com.example.", null)
        val manager = CrashHandlerManager(CrashAttributor("com.example."), processor)
        val ingestor = CrashIngestor(store, sink, io)
        return CrashReporter(manager, processor, ingestor, writer, io)
    }

    @Before fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        previous = RecordingHandler()
        Thread.setDefaultUncaughtExceptionHandler(previous) // becomes our 'previous' on install
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    @Test fun capturesOurCrashAndDelegatesToHost() {
        val dir = tmp.newFolder("crashes")
        val store = CrashFileStore(dir, 20)
        val reporter = newReporter(store, RecordingSink())

        reporter.install()
        reporter.startCapturing("tok-int")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), ourCrash())

        assertEquals(1, store.listCompleted().size) // captured
        assertTrue(previous.called) // delegated to host
    }

    @Test fun doesNotCaptureWhenStopped() {
        val dir = tmp.newFolder("crashes")
        val store = CrashFileStore(dir, 20)
        val reporter = newReporter(store, RecordingSink())

        reporter.install()
        reporter.startCapturing("tok-int")
        reporter.stopCapturing()

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), ourCrash())

        assertEquals(0, store.listCompleted().size) // not captured
        assertTrue(previous.called) // still delegated
    }

    @Test fun installIngestsCrashesFromPreviousRun() {
        val dir = tmp.newFolder("crashes")
        val store = CrashFileStore(dir, 20)
        store.writeAtomic(
            "old",
            CrashProcessor.buildPayloadJson(ourCrash(), 1L, "tok-prev", Redactor(), 9L, "com.example.", null)
        )

        val sink = RecordingSink()
        val reporter = newReporter(store, sink)
        reporter.install() // flushAsync runs on the direct executor -> synchronous

        assertEquals(1, sink.tokens.size)
        assertEquals("tok-prev", sink.tokens[0])
        assertEquals(0, store.listCompleted().size)
    }

    @Test @Suppress("DEPRECATION") fun contextCreateCapturesCrashUnderNamespacedDirAndDelegates() {
        val filesDir = tmp.newFolder("files")

        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val pi = PackageInfo()
        pi.versionName = "1.2.3"
        pi.versionCode = 42
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.packageName).thenReturn("com.example")
        `when`(pm.getPackageInfo("com.example", 0)).thenReturn(pi)

        val reporter = CrashReporter.create(context, RecordingSink(), "com.example.", 20, 1000L)
        reporter.install()
        reporter.startCapturing("tok-ctx")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), ourCrash())

        // persistBlocking waits up to the flush timeout for the daemon writer.
        val store = CrashFileStore(CrashReporter.crashDirFor(filesDir, "com.example."), 20)
        assertEquals(1, store.listCompleted().size) // captured under filesDir/crashsink/<prefix>
        assertTrue(previous.called) // delegated to host

        reporter.shutdown()
    }

    @Test fun exposesSaneDefaultConstants() {
        assertEquals(20, CrashReporter.DEFAULT_FILE_CAP)
        assertEquals(1000L, CrashReporter.DEFAULT_FLUSH_TIMEOUT_MS)
    }

    @Test @Suppress("DEPRECATION") fun convenienceContextCreateOverloadUsesDefaultsAndCaptures() {
        val filesDir = tmp.newFolder("files")

        val context = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val pi = PackageInfo()
        pi.versionName = "1.2.3"
        pi.versionCode = 42
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.packageManager).thenReturn(pm)
        `when`(context.packageName).thenReturn("com.example")
        `when`(pm.getPackageInfo("com.example", 0)).thenReturn(pi)

        // Three-arg convenience overload: no fileCap / flushTimeout passed.
        val reporter = CrashReporter.create(context, RecordingSink(), "com.example.")
        reporter.install()
        reporter.startCapturing("tok-default")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), ourCrash())

        val store = CrashFileStore(CrashReporter.crashDirFor(filesDir, "com.example."), CrashReporter.DEFAULT_FILE_CAP)
        assertEquals(1, store.listCompleted().size) // captured with default cap
        assertTrue(previous.called)                  // delegated to host

        reporter.shutdown()
    }

    @Test fun shutdownStopsBothExecutors() {
        val dir = tmp.newFolder("crashes")
        val store = CrashFileStore(dir, 20)
        val writer = Executors.newSingleThreadExecutor()
        val io = Executors.newSingleThreadExecutor()
        val processor = CrashProcessor(Redactor(), store, writer, 1000L, "com.example.", null)
        val manager = CrashHandlerManager(CrashAttributor("com.example."), processor)
        val ingestor = CrashIngestor(store, RecordingSink(), io)
        val reporter = CrashReporter(manager, processor, ingestor, writer, io)

        reporter.shutdown()

        assertTrue(writer.isShutdown)
        assertTrue(io.isShutdown)
    }

    @Test fun crashDirForNamespacesUnderCrashsinkParent() {
        val dir = CrashReporter.crashDirFor(tmp.root, "com.example.sdk.")
        assertEquals(File(File(tmp.root, "crashsink"), "com.example.sdk"), dir)
    }

    @Test fun crashDirForGivesDistinctDirsPerPrefix() {
        // Two guest SDKs (or SDK + host) with different prefixes must not share a directory.
        val a = CrashReporter.crashDirFor(tmp.root, "com.a.")
        val b = CrashReporter.crashDirFor(tmp.root, "com.b.")
        assertNotEquals(a, b)
        assertEquals(File(tmp.root, "crashsink"), a.parentFile)
        assertEquals(File(tmp.root, "crashsink"), b.parentFile)
    }

    @Test fun crashDirForSanitizesUnsafeChars() {
        // Path separators / spaces can never leak into the child directory name.
        val dir = CrashReporter.crashDirFor(tmp.root, "com/evil..name ")
        assertEquals(File(tmp.root, "crashsink"), dir.parentFile)
        assertTrue(!dir.name.contains("/") && !dir.name.contains(" "))
    }

    @Test fun crashDirForFallsBackWhenPrefixHasNoSafeChars() {
        val dir = CrashReporter.crashDirFor(tmp.root, "...")
        assertEquals(File(File(tmp.root, "crashsink"), "default"), dir)
    }

    @Test fun createFromContextBuildsInstallableReporter() {
        val ctx = mock(Context::class.java)
        `when`(ctx.filesDir).thenReturn(tmp.root)
        // packageManager left unstubbed (null) → AndroidDeviceMetadata.collect catches → defaults.

        val reporter = CrashReporter.create(ctx, RecordingSink(), "com.example.")
        reporter.install()
        reporter.startCapturing("s")
        reporter.stopCapturing()
        reporter.shutdown()

        // Wiring proof: our interceptor is in the slot, delegating to the pre-existing handler.
        // (No crash triggered here; crashDirFor tests cover the collision-avoidance directly.)
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() is CrashInterceptor)
    }
}
