package io.hazaar.lapdog.state

import io.hazaar.lapdog.protocol.LapFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LapEditsTest {
    private fun laps(vararg ms: Long) = ms.mapIndexed { i, t -> LapEntry(i + 1, t) }

    // Heat 4: lap 3 is two laps (a missed pass); a pass ignored after lap 4.
    private val heat = HeatRecord(
        id = 1, heat = 4,
        laps = laps(29_000, 30_500, 59_400, 28_900),
        passes = listOf(IgnoredPass(LapFrame.Reason.TOO_SOON, 1_200, afterLap = 4)),
    )

    @Test
    fun splitInHalf() {
        val h = LapEdits.split(heat, 2)
        assertEquals(listOf(1, 2, 3, 4, 5), h.laps.map { it.lap })
        assertEquals(listOf(29_000L, 30_500L, 29_700L, 29_700L, 28_900L), h.laps.map { it.ms })
        assertEquals(listOf(false, false, true, true, false), h.laps.map { it.split })
        assertEquals(heat.stats.totalMs, h.stats.totalMs)
        assertEquals(5, h.passes.single().afterLap) // still after the lap that was lap 4
        assertTrue(h.edited)
        assertEquals(heat.laps, h.originalLaps)
    }

    @Test
    fun splitInThreeKeepsEveryMillisecond() {
        val h = LapEdits.split(HeatRecord(id = 1, heat = 1, laps = laps(10_000)), 0, 3)
        assertEquals(listOf(3_333L, 3_333L, 3_334L), h.laps.map { it.ms })
    }

    @Test
    fun splitLapsAreNeverBest() {
        val h = LapEdits.split(HeatRecord(id = 1, heat = 1, laps = laps(30_000, 50_000, 31_000)), 1)
        assertEquals(30_000L, h.stats.best!!.ms) // not the 25 s halves
        assertNull(h.stats.bestThree?.takeIf { w -> w.any { it.split } })
    }

    @Test
    fun falseReadMergesIntoPrevious() {
        // A false read split lap 2 into 12.1 + 18.4.
        val h0 = HeatRecord(id = 1, heat = 1, laps = laps(29_000, 12_100, 18_400, 29_500),
            passes = listOf(IgnoredPass(LapFrame.Reason.TOO_LONG, 2_000, afterLap = 3)))
        val h = LapEdits.mergeWithPrevious(h0, 2)
        assertEquals(listOf(LapEntry(1, 29_000), LapEntry(2, 30_500), LapEntry(3, 29_500)), h.laps)
        assertEquals(2, h.passes.single().afterLap)
        assertEquals(h0.stats.totalMs, h.stats.totalMs)
        assertEquals(h, LapEdits.mergeWithNext(h0, 1)) // the same pass, from the other side
    }

    @Test
    fun mergeEdges() {
        assertFalse(LapEdits.canMergePrevious(heat, 0))
        assertTrue(LapEdits.canMergeNext(heat, 0))
        assertTrue(LapEdits.canMergePrevious(heat, 3))
        assertFalse(LapEdits.canMergeNext(heat, 3))
    }

    @Test
    fun gapsKeepTheirSpacing() {
        // BLE missed lap 3's frame: laps 1, 2, 4.
        val h0 = HeatRecord(id = 1, heat = 1, laps = listOf(LapEntry(1, 30_000), LapEntry(2, 60_000), LapEntry(4, 31_000)))
        val h = LapEdits.split(h0, 1)
        assertEquals(listOf(1, 2, 3, 5), h.laps.map { it.lap })
    }

    @Test
    fun restoreUndoesEverything() {
        val h = LapEdits.mergeWithNext(LapEdits.split(heat, 2), 0)
        val r = LapEdits.restore(h)
        assertEquals(heat.laps, r.laps)
        assertEquals(heat.passes, r.passes)
        assertFalse(r.edited)
        assertEquals(heat.laps, h.originalLaps) // the first edit's snapshot, not the second's
    }

    @Test
    fun oldHeatFilesStillLoad() {
        val json = """{"id":1,"heat":2,"laps":[{"lap":1,"ms":30000}]}"""
        val h = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(HeatRecord.serializer(), json)
        assertFalse(h.laps.single().split)
        assertFalse(h.edited)
    }
}
