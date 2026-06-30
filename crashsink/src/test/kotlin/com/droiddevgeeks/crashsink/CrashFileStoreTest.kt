package com.droiddevgeeks.crashsink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class CrashFileStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File

    @Before fun setUp() {
        dir = tmp.newFolder("crashes")
    }

    @Test fun writeThenListReturnsCrashFile() {
        CrashFileStore(dir, 20).writeAtomic("c1", "{\"k\":1}")
        val files = CrashFileStore(dir, 20).listCompleted()
        assertEquals(1, files.size)
        assertTrue(files[0].name.endsWith(".crash"))
        assertEquals("{\"k\":1}", String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8))
    }

    @Test fun noTempLeftAfterSuccessfulWrite() {
        CrashFileStore(dir, 20).writeAtomic("c1", "x")
        for (f in dir.listFiles()!!) {
            assertFalse(f.name.endsWith(".tmp"))
        }
    }

    @Test fun capEvictsOldest() {
        val store = CrashFileStore(dir, 2)
        store.writeAtomic("a", "1")
        store.writeAtomic("b", "2")
        store.writeAtomic("c", "3") // exceeds cap of 2
        assertEquals(2, store.listCompleted().size)
    }

    @Test fun sweepTempsRemovesOrphans() {
        val orphan = File(dir, "dead.tmp")
        assertTrue(orphan.createNewFile())
        CrashFileStore(dir, 20).sweepTemps()
        assertFalse(orphan.exists())
    }

    @Test fun deleteRemovesFile() {
        val store = CrashFileStore(dir, 20)
        store.writeAtomic("a", "1")
        val f = store.listCompleted()[0]
        store.delete(f)
        assertEquals(0, store.listCompleted().size)
    }

    @Test fun sameBaseNameOverwritesAtomicallyToOneFile() {
        val store = CrashFileStore(dir, 20)
        store.writeAtomic("dup", "first")
        store.writeAtomic("dup", "second")
        val files = store.listCompleted()
        assertEquals(1, files.size)
        assertEquals("second", String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8))
    }

    @Test fun nonPositiveCapKeepsAllFiles() {
        val store = CrashFileStore(dir, 0)
        store.writeAtomic("a", "1")
        store.writeAtomic("b", "2")
        store.writeAtomic("c", "3")
        assertEquals(3, store.listCompleted().size)
    }
}
