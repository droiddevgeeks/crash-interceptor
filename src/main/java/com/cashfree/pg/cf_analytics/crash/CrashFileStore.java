package com.cashfree.pg.cf_analytics.crash;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
            out.write(content.getBytes(StandardCharsets.UTF_8));
            // Intentionally NO fsync: atomicity comes from rename, not durability.
        } finally {
            if (out != null) {
                out.close();
            }
        }
        if (!temp.renameTo(target)) {
            // Fallback: rename can fail if target exists on some filesystems.
            if (target.exists() && target.delete() && temp.renameTo(target)) {
                // recovered
            } else {
                temp.delete();
                throw new IOException("Atomic rename failed for " + temp);
            }
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
        crashes.sort(Comparator.comparingLong(File::lastModified));
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
        final List<File> crashes = listCompleted(); // oldest first
        for (int i = 0; i < crashes.size() - maxFiles; i++) {
            crashes.get(i).delete();
        }
    }
}
