package com.droiddevgeeks.crashsink

import android.content.Context
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Public facade wiring the crash subsystem together.
 *
 * The primary constructor is `internal`: it lets in-module unit tests inject executors and
 * collaborators directly, and it necessarily takes internal collaborator types. Consumers use the
 * public [create] factories instead.
 */
class CrashReporter internal constructor(
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

        /**
         * The one factory consumers call. Builds from an Android [Context]: derives the crash dir
         * from [Context.getFilesDir], attaches device/app metadata (collected once, off the crash
         * path) to every captured crash, and stores crashes in a per-SDK private subdirectory
         * derived from [ownedPrefix]. Because the directory is namespaced by your package, two
         * crashsink-using SDKs in the same app — or a guest SDK alongside a crashsink-using host —
         * never share a crash directory and cannot cross-deliver or cross-delete each other's
         * crashes.
         *
         * [fileCap] and [flushTimeoutMillis] are optional: omit them in Kotlin (they default to
         * [DEFAULT_FILE_CAP] / [DEFAULT_FLUSH_TIMEOUT_MS]); `@JvmOverloads` generates the matching
         * shorter Java overloads so Java callers can omit them too.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            sink: CrashSink,
            ownedPrefix: String,
            fileCap: Int = DEFAULT_FILE_CAP,
            flushTimeoutMillis: Long = DEFAULT_FLUSH_TIMEOUT_MS
        ): CrashReporter {
            val crashDir = crashDirFor(context.filesDir, ownedPrefix)
            val metadata = AndroidDeviceMetadata.collect(context)
            return create(crashDir, fileCap, flushTimeoutMillis, sink, ownedPrefix, metadata)
        }

        /**
         * The per-SDK crash directory [create] uses: `<filesDir>/crashsink/<name>`, where `<name>`
         * is [ownedPrefix] with any character outside `[A-Za-z0-9._-]` replaced by `_` and
         * surrounding `.`/`_` trimmed (e.g. `"com.example.sdk."` -> `"com.example.sdk"`). Distinct
         * prefixes yield distinct directories. Internal — an implementation detail of [create].
         */
        internal fun crashDirFor(filesDir: File, ownedPrefix: String): File =
            File(File(filesDir, SDK_CRASH_DIR_PARENT), sdkDirName(ownedPrefix))

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

        /** Parent directory that holds each SDK's namespaced crash subdirectory. */
        private const val SDK_CRASH_DIR_PARENT = "crashsink"

        private val UNSAFE_DIR_CHARS = Regex("[^A-Za-z0-9._-]")

        /** Filesystem-safe, collision-free directory name derived from an owned prefix. */
        private fun sdkDirName(ownedPrefix: String): String {
            val cleaned = ownedPrefix.replace(UNSAFE_DIR_CHARS, "_").trim('.', '_')
            return cleaned.ifEmpty { "default" }
        }
    }
}
