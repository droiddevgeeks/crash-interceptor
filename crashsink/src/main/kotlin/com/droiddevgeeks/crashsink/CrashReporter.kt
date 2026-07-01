package com.droiddevgeeks.crashsink

import android.content.Context
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Public facade wiring the crash subsystem together.
 *
 * The primary constructor is public for testing: it lets unit tests inject executors and
 * collaborators directly. Consumers should use the [create] factories instead.
 */
class CrashReporter(
    private val manager: CrashHandlerManager,
    private val processor: CrashProcessor,
    private val ingestor: CrashIngestor,
    private val writerExecutor: ExecutorService,
    private val ioExecutor: ExecutorService
) {
    /** Install the interceptor into the chain and flush any crashes left from previous runs. */
    fun install() {
        manager.install()
        ingestor.flushAsync()
    }

    /** Begin capturing crashes for the given session token; re-assert our chain position. */
    fun startCapturing(token: String?) {
        processor.token = token
        processor.isTracking = true
        manager.reassert()
    }

    /** Stop capturing (token-scoped session ended). */
    fun stopCapturing() {
        processor.isTracking = false
    }

    /**
     * Releases the background executor threads. Call when the reporter is no longer needed
     * (e.g. before re-creating one) to avoid thread accumulation. The reporter must not be
     * reused after this.
     */
    fun shutdown() {
        writerExecutor.shutdown()
        ioExecutor.shutdown()
    }

    companion object {
        /**
         * Default max number of crash files retained on disk (oldest evicted beyond this).
         *
         * The cap bounds the *failure* path: a down/flaky [CrashSink] keeps files for retry, and
         * a crash-looping or multi-process app can write faster than they are ingested. 20 keeps a
         * burst of crashes / a temporary delivery outage without letting the crash dir grow
         * unbounded. Set a larger value to tolerate longer outages; `0` disables eviction entirely
         * (unbounded — not recommended on a device). Setting it to `1` defeats the retry guarantee.
         */
        const val DEFAULT_FILE_CAP: Int = 20

        /** Default ceiling (ms) the crashing thread waits for the write before delegating anyway. */
        const val DEFAULT_FLUSH_TIMEOUT_MS: Long = 1000L

        @JvmStatic
        fun create(
            crashDir: File,
            fileCap: Int,
            flushTimeoutMillis: Long,
            sink: CrashSink,
            ownedPrefix: String
        ): CrashReporter = create(crashDir, fileCap, flushTimeoutMillis, sink, ownedPrefix, null)

        /**
         * Context-based overload: derives the crash dir from [Context.getFilesDir] and attaches
         * device/app metadata (collected once, off the crash path) to every captured crash.
         */
        @JvmStatic
        fun create(
            context: Context,
            fileCap: Int,
            flushTimeoutMillis: Long,
            sink: CrashSink,
            ownedPrefix: String
        ): CrashReporter {
            val crashDir = File(context.filesDir, "crashes")
            val metadata = AndroidDeviceMetadata.collect(context)
            return create(crashDir, fileCap, flushTimeoutMillis, sink, ownedPrefix, metadata)
        }

        /**
         * Convenience overload using [DEFAULT_FILE_CAP] and [DEFAULT_FLUSH_TIMEOUT_MS]. Derives the
         * crash dir from [Context.getFilesDir] and attaches device/app metadata. Callable from
         * Kotlin and Java; use the full overload to tune `fileCap` / `flushTimeoutMillis`.
         */
        @JvmStatic
        fun create(context: Context, sink: CrashSink, ownedPrefix: String): CrashReporter =
            create(context, DEFAULT_FILE_CAP, DEFAULT_FLUSH_TIMEOUT_MS, sink, ownedPrefix)

        /** Convenience overload for a caller-supplied dir, using the default cap and flush timeout. */
        @JvmStatic
        fun create(crashDir: File, sink: CrashSink, ownedPrefix: String): CrashReporter =
            create(crashDir, DEFAULT_FILE_CAP, DEFAULT_FLUSH_TIMEOUT_MS, sink, ownedPrefix, null)

        private fun create(
            crashDir: File,
            fileCap: Int,
            flushTimeoutMillis: Long,
            sink: CrashSink,
            ownedPrefix: String,
            metadata: DeviceMetadata?
        ): CrashReporter {
            val writerExecutor = Executors.newSingleThreadExecutor(namedDaemonFactory("crash-writer"))
            val ioExecutor = Executors.newSingleThreadExecutor(namedDaemonFactory("crash-ingest"))
            val store = CrashFileStore(crashDir, fileCap)
            val processor = CrashProcessor(
                Redactor(), store, writerExecutor, flushTimeoutMillis, ownedPrefix, metadata
            )
            val manager = CrashHandlerManager(CrashAttributor(ownedPrefix), processor)
            val ingestor = CrashIngestor(store, sink, ioExecutor)
            return CrashReporter(manager, processor, ingestor, writerExecutor, ioExecutor)
        }

        /** Named daemon threads: easy to spot in a thread dump, never block host JVM exit. */
        private fun namedDaemonFactory(name: String): ThreadFactory =
            ThreadFactory { r ->
                Thread(r, name).apply { isDaemon = true }
            }
    }
}
