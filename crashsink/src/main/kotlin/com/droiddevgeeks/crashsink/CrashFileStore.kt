package com.droiddevgeeks.crashsink

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections

/** Lock-free, one-file-per-crash storage. Atomic temp+rename, no fsync. */
class CrashFileStore(
    private val dir: File,
    private val maxFiles: Int
) {
    @Throws(IOException::class)
    fun writeAtomic(fileBaseName: String, content: String) {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw IOException("Unable to create crash dir: $dir")
        }
        val temp = File(dir, fileBaseName + TEMP_SUFFIX)
        val target = File(dir, fileBaseName + CRASH_SUFFIX)
        val out = FileOutputStream(temp)
        try {
            try {
                out.write(content.toByteArray(StandardCharsets.UTF_8))
            } catch (e: IOException) {
                temp.delete()
                throw e
            }
            // Intentionally NO fsync: atomicity comes from rename, not durability.
        } finally {
            out.close()
        }
        // File.renameTo maps to rename(2) on Android/Linux (and APFS/HFS+ on the dev host):
        // an atomic replace within the same directory. Uses only java.io, so it works on
        // Android API 21+ with no java.nio.file dependency. Crash file names are unique, so
        // the target normally does not pre-exist; on the rare failure we leave the temp for
        // sweepTemps() and surface it rather than destroying any existing target.
        if (!temp.renameTo(target)) {
            throw IOException("Atomic rename failed: $temp -> $target")
        }
        enforceCap()
    }

    fun listCompleted(): List<File> {
        val all = dir.listFiles()
        val crashes = ArrayList<File>()
        if (all != null) {
            for (f in all) {
                if (f.name.endsWith(CRASH_SUFFIX)) {
                    crashes.add(f)
                }
            }
        }
        // Collections.sort + a primitive long comparison avoids List.sort /
        // Comparator.comparingLong, which need Android API 24 or desugaring.
        Collections.sort(crashes) { a, b -> a.lastModified().compareTo(b.lastModified()) }
        return crashes
    }

    fun delete(file: File?) {
        file?.delete()
    }

    fun sweepTemps() {
        val all = dir.listFiles()
        if (all != null) {
            for (f in all) {
                if (f.name.endsWith(TEMP_SUFFIX)) {
                    f.delete()
                }
            }
        }
    }

    private fun enforceCap() {
        if (maxFiles <= 0) {
            return
        }
        val crashes = listCompleted() // oldest first
        for (i in 0 until crashes.size - maxFiles) {
            crashes[i].delete()
        }
    }

    companion object {
        private const val CRASH_SUFFIX = ".crash"
        private const val TEMP_SUFFIX = ".tmp"
    }
}
