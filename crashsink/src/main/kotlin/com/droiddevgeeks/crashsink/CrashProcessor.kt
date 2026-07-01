package com.droiddevgeeks.crashsink

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Builds the redacted crash payload and writes it within a bounded timeout. Never throws. */
class CrashProcessor(
    private val redactor: Redactor,
    private val store: CrashFileStore,
    private val writerExecutor: ExecutorService,
    private val flushTimeoutMillis: Long,
    private val ownedPrefix: String,
    private val metadata: DeviceMetadata?
) {
    private val handled: MutableSet<Throwable> =
        Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>()))

    // Boolean property named `isTracking` generates exactly `isTracking()` / `setTracking(boolean)`.
    @Volatile
    var isTracking: Boolean = false

    // String? property `token` generates exactly `getToken()` / `setToken(String)`.
    @Volatile
    var token: String? = null

    private val sequence = AtomicLong(0L)

    // Per-instance salt (computed once, off the crash path) so crash files stay unique across
    // multiple processes writing to a shared dir — nanoTime's origin differs per process.
    private val instanceTag = java.lang.Long.toHexString(System.nanoTime())

    fun persistBlocking(thread: Thread?, throwable: Throwable?): Boolean {
        if (!isTracking || token == null || throwable == null) {
            return false
        }
        if (!handled.add(throwable)) {
            return true // already persisted by an earlier link in the chain
        }
        val currentToken = token
        val crashTs = System.currentTimeMillis()
        // Thread.getId() is deprecated on SDK 36 in favor of threadId(), but threadId() is
        // API 36+ and we support minSdk 21 — keep getId() so old devices don't crash.
        @Suppress("DEPRECATION")
        val threadId = thread?.id ?: -1L
        val fileBase = "crash_" + crashTs + "_" + instanceTag + "_" + sequence.getAndIncrement()

        val latch = CountDownLatch(1)
        try {
            writerExecutor.submit(Runnable {
                try {
                    val json = buildPayloadJson(
                        throwable, threadId, currentToken, redactor, crashTs, ownedPrefix, metadata
                    )
                    store.writeAtomic(fileBase, json)
                } catch (t: Throwable) {
                    CrashLogger.e(TAG, "crash write failed: " + t.message)
                } finally {
                    latch.countDown()
                }
            })
        } catch (t: Throwable) {
            // Executor rejected the task (e.g. shut down). Never propagate from the crash path.
            CrashLogger.e(TAG, "crash write could not be scheduled: " + t.message)
            return false
        }

        return try {
            val flushed = latch.await(flushTimeoutMillis, TimeUnit.MILLISECONDS)
            if (!flushed) {
                CrashLogger.e(TAG, "crash flush timed out")
            }
            flushed
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    companion object {
        private const val TAG = "CrashProcessor"

        /**
         * Pure payload builder: a JSON object describing the crash and its cause chain.
         *
         * Public + `@JvmStatic` so the JVM unit tests can exercise it directly as
         * `CrashProcessor.buildPayloadJson(...)`. It is not part of the consumer-facing API.
         */
        @JvmStatic
        fun buildPayloadJson(
            throwable: Throwable,
            threadId: Long,
            token: String?,
            redactor: Redactor,
            crashTimestamp: Long,
            ownedPrefix: String,
            metadata: DeviceMetadata?
        ): String {
            val values = JSONArray()
            var present: Throwable? = throwable
            var guard = 0
            while (present != null && guard++ < 50) {
                val frames = JSONArray()
                val trace = present.stackTrace
                for (el in trace) {
                    val frame = JSONObject()
                    try {
                        frame.put("function", el.methodName)
                        frame.put("module", el.className)
                        frame.put("filename", el.fileName)
                        frame.put("lineno", el.lineNumber)
                        frame.put("in_app", CrashFrames.isOurs(el.className, ownedPrefix))
                    } catch (ignored: Throwable) {
                    }
                    frames.put(frame)
                }
                val value = JSONObject()
                try {
                    value.put("type", present.javaClass.simpleName)
                    value.put("value", redactor.scrub(present.message))
                    val pkg = present.javaClass.`package`
                    value.put("module", if (pkg != null) pkg.name else "unknown")
                    value.put("thread_id", threadId)
                    value.put("stacktrace", JSONObject().put("frames", frames))
                } catch (ignored: Throwable) {
                }
                values.put(value)
                val cause = present.cause
                present = if (cause === present) null else cause
            }

            val originFrame = CrashFrames.firstApplicationFrame(
                CrashFrames.deepestCause(throwable).stackTrace
            )
            val culprit = if (originFrame != null) {
                originFrame.className + " in " + originFrame.methodName
            } else {
                "unknown"
            }

            val contexts = JSONObject()
            try {
                if (metadata != null) {
                    val device = JSONObject()
                    device.put("os_version", metadata.osVersion)
                    device.put("sdk_int", metadata.sdkInt)
                    device.put("manufacturer", metadata.manufacturer)
                    device.put("model", metadata.model)
                    device.put("app_version_name", metadata.appVersionName)
                    device.put("app_version_code", metadata.appVersionCode)
                    contexts.put("device", device)
                }
                val rt = Runtime.getRuntime()
                val memory = JSONObject()
                memory.put("heap_free", rt.freeMemory())
                memory.put("heap_total", rt.totalMemory())
                memory.put("heap_max", rt.maxMemory())
                contexts.put("memory", memory)
            } catch (ignored: Throwable) {
            }

            val payload = JSONObject()
            try {
                payload.put("token", token)
                payload.put("level", "fatal")
                payload.put("culprit", culprit)
                payload.put("timestamp", crashTimestamp)
                payload.put("exception_values", values.toString())
                payload.put("contexts", contexts.toString())
            } catch (ignored: Throwable) {
            }
            return payload.toString()
        }
    }
}
