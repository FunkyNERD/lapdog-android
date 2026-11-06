package io.hazaar.lapdog.service

import io.hazaar.lapdog.state.HeatRecord
import io.hazaar.lapdog.state.LapEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Deleting heats with an undo window, on real files (HeatStore is a singleton: one test). */
class HeatStoreTest {
    private fun heat(id: Long) = HeatRecord(id = id, heat = id.toInt(), laps = listOf(LapEntry(1, 30_000)))

    private fun waitFor(what: String, ms: Long = 3_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    @Test
    fun deleteWaitsForTheUndoWindowAndUndoKeepsTheFile() = runBlocking {
        val dir = Files.createTempDirectory("heats").toFile()
        HeatStore.init(dir)
        HeatStore.all()
        listOf(heat(1), heat(2), heat(3)).forEach(HeatStore::save)
        fun file(id: Long) = File(dir, "$id.json")
        waitFor("saved files") { (1L..3L).all { file(it).isFile } }

        // Delete two; undo one of them.
        HeatStore.delete(listOf(1L, 2L))
        assertEquals(listOf(3L), HeatStore.heats.value.map { it.id })
        assertTrue("files stay during the undo window", file(1).isFile && file(2).isFile)
        HeatStore.restore(listOf(heat(2)))
        assertEquals(setOf(2L, 3L), HeatStore.heats.value.map { it.id }.toSet())

        // After the window: the deleted one's file is gone, the undone one's is still there.
        Thread.sleep(HeatStore.UNDO_MS + 500)
        assertFalse("deleted heat's file removed after the window", file(1).exists())
        assertTrue("undone heat's file kept", file(2).isFile)
        assertTrue(file(3).isFile)

        // Deleted again later: that delete goes ahead.
        HeatStore.delete(2L)
        Thread.sleep(HeatStore.UNDO_MS + 500)
        assertFalse(file(2).exists())
        assertEquals(listOf(3L), HeatStore.heats.value.map { it.id })
        dir.deleteRecursively()
        Unit
    }
}
