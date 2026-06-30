package com.droiddevgeeks.crashsink

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor

/** On a healthy process, reconstructs persisted crashes and feeds them to the pipeline. */
class CrashIngestor(
    private val store: CrashFileStore,
    private val sink: CrashSink,
    private val ioExecutor: Executor
) {
    fun flushAsync() {
        ioExecutor.execute {
            try {
                store.sweepTemps()
            } catch (t: Throwable) {
                CrashLogger.e(TAG, "temp sweep failed: " + t.message)
            }
            try {
                val files = store.listCompleted()
                for (file in files) {
                    ingestOne(file)
                }
            } catch (t: Throwable) {
                CrashLogger.e(TAG, "ingest failed: " + t.message)
            }
        }
    }

    private fun ingestOne(file: File) {
        val token: String?
        val exceptionValues: String
        val level: String
        val culprit: String
        val timestamp: Long
        val contexts: String
        try {
            val content = readUtf8(file)
            val json = JSONObject(content)
            // isNull() is true when the key is absent or JSON null; otherwise the value is a
            // present, non-null string, so the single-arg optString returns it directly.
            token = if (json.isNull("token")) null else json.optString("token")
            exceptionValues = json.optString("exception_values", "[]")
            level = json.optString("level", "fatal")
            culprit = json.optString("culprit", "unknown")
            timestamp = json.optLong("timestamp", 0L)
            contexts = json.optString("contexts", "{}")
        } catch (t: Throwable) {
            // Unparseable poison file: drop it so it cannot wedge the queue.
            CrashLogger.e(TAG, "dropping unparseable crash file: " + t.message)
            store.delete(file)
            return
        }
        try {
            sink.submit(token, exceptionValues, level, culprit, timestamp, contexts)
            store.delete(file) // delete only after a successful hand-off
        } catch (t: Throwable) {
            // Downstream submission failed; keep the file for retry on a future flush.
            CrashLogger.e(TAG, "sink submit failed; keeping crash file for retry: " + t.message)
        }
    }

    companion object {
        private const val TAG = "CrashIngestor"

        /**
         * Reads a (small) crash file as UTF-8 using only java.io, so it works on Android API 21+
         * with no java.nio.file dependency (java.nio.file is API 26+ and not desugared).
         */
        @Throws(IOException::class)
        private fun readUtf8(file: File): String {
            val size = file.length().toInt()
            val buffer = ByteArray(size)
            val input = FileInputStream(file)
            try {
                var offset = 0
                var read = 0
                while (offset < size &&
                    input.read(buffer, offset, size - offset).also { read = it } != -1
                ) {
                    offset += read
                }
            } finally {
                input.close()
            }
            return String(buffer, StandardCharsets.UTF_8)
        }
    }
}
