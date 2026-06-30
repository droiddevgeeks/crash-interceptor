package com.droiddevgeeks.crashsink;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Lock-free, one-file-per-crash storage. Atomic temp+rename, no fsync. */
public final class CrashFileStore {

    private static final String CRASH_SUFFIX = ".crash";
    private static final String TEMP_SUFFIX = ".tmp";

    private final File dir;
    private final int maxFiles;

    public CrashFileStore(final File dir, final int maxFiles) {
        this.dir = dir;
        this.maxFiles = maxFiles;
    }

    public void writeAtomic(final String fileBaseName, final String content) throws IOException {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("Unable to create crash dir: " + dir);
        }
        final File temp = new File(dir, fileBaseName + TEMP_SUFFIX);
        final File target = new File(dir, fileBaseName + CRASH_SUFFIX);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(temp);
            try {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                temp.delete();
                throw e;
            }
            // Intentionally NO fsync: atomicity comes from rename, not durability.
        } finally {
            if (out != null) {
                out.close();
            }
        }
        // File.renameTo maps to rename(2) on Android/Linux (and APFS/HFS+ on the dev host):
        // an atomic replace within the same directory. Uses only java.io, so it works on
        // Android API 21+ with no java.nio.file dependency. Crash file names are unique, so
        // the target normally does not pre-exist; on the rare failure we leave the temp for
        // sweepTemps() and surface it rather than destroying any existing target.
        if (!temp.renameTo(target)) {
            throw new IOException("Atomic rename failed: " + temp + " -> " + target);
        }
        enforceCap();
    }

    public List<File> listCompleted() {
        final File[] all = dir.listFiles();
        final List<File> crashes = new ArrayList<>();
        if (all != null) {
            for (File f : all) {
                if (f.getName().endsWith(CRASH_SUFFIX)) {
                    crashes.add(f);
                }
            }
        }
        // Collections.sort + explicit Comparator (Long.compare is API 19+) avoids
        // List.sort / Comparator.comparingLong, which need Android API 24 or desugaring.
        Collections.sort(crashes, new Comparator<File>() {
            @Override public int compare(final File a, final File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        return crashes;
    }

    public void delete(final File file) {
        if (file != null) {
            file.delete();
        }
    }

    public void sweepTemps() {
        final File[] all = dir.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.getName().endsWith(TEMP_SUFFIX)) {
                    f.delete();
                }
            }
        }
    }

    private void enforceCap() {
        if (maxFiles <= 0) {
            return;
        }
        final List<File> crashes = listCompleted(); // oldest first
        for (int i = 0; i < crashes.size() - maxFiles; i++) {
            crashes.get(i).delete();
        }
    }
}
