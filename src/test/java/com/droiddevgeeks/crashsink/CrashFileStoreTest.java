package com.droiddevgeeks.crashsink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class CrashFileStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File dir;

    @Before public void setUp() throws IOException {
        dir = tmp.newFolder("crashes");
    }

    @Test public void writeThenListReturnsCrashFile() throws IOException {
        new CrashFileStore(dir, 20).writeAtomic("c1", "{\"k\":1}");
        List<File> files = new CrashFileStore(dir, 20).listCompleted();
        assertEquals(1, files.size());
        assertTrue(files.get(0).getName().endsWith(".crash"));
        assertEquals("{\"k\":1}", new String(Files.readAllBytes(files.get(0).toPath()), StandardCharsets.UTF_8));
    }

    @Test public void noTempLeftAfterSuccessfulWrite() throws IOException {
        new CrashFileStore(dir, 20).writeAtomic("c1", "x");
        for (File f : dir.listFiles()) {
            assertFalse(f.getName().endsWith(".tmp"));
        }
    }

    @Test public void capEvictsOldest() throws IOException {
        CrashFileStore store = new CrashFileStore(dir, 2);
        store.writeAtomic("a", "1");
        store.writeAtomic("b", "2");
        store.writeAtomic("c", "3"); // exceeds cap of 2
        List<File> files = store.listCompleted();
        assertEquals(2, files.size());
    }

    @Test public void sweepTempsRemovesOrphans() throws IOException {
        File orphan = new File(dir, "dead.tmp");
        assertTrue(orphan.createNewFile());
        new CrashFileStore(dir, 20).sweepTemps();
        assertFalse(orphan.exists());
    }

    @Test public void deleteRemovesFile() throws IOException {
        CrashFileStore store = new CrashFileStore(dir, 20);
        store.writeAtomic("a", "1");
        File f = store.listCompleted().get(0);
        store.delete(f);
        assertEquals(0, store.listCompleted().size());
    }

    @Test public void sameBaseNameOverwritesAtomicallyToOneFile() throws IOException {
        CrashFileStore store = new CrashFileStore(dir, 20);
        store.writeAtomic("dup", "first");
        store.writeAtomic("dup", "second");
        List<File> files = store.listCompleted();
        assertEquals(1, files.size());
        assertEquals("second", new String(Files.readAllBytes(files.get(0).toPath()), StandardCharsets.UTF_8));
    }

    @Test public void nonPositiveCapKeepsAllFiles() throws IOException {
        CrashFileStore store = new CrashFileStore(dir, 0);
        store.writeAtomic("a", "1");
        store.writeAtomic("b", "2");
        store.writeAtomic("c", "3");
        assertEquals(3, store.listCompleted().size());
    }
}
