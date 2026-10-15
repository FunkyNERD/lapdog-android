package io.hazaar.lapdog.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LapStatsTest {
    private val laps = listOf(15012L, 14880, 14630, 14212, 15420, 14995, 14512).mapIndexed { i, ms -> LapEntry(i + 1, ms) }

    @Test
    fun designNumbers() {
        val s = LapStats(laps)
        assertEquals(7, s.count)
        assertEquals(LapEntry(4, 14212), s.best)
        assertEquals(14808L, s.averageMs) // 103661 / 7
        assertEquals(300L, s.lastDeltaMs) // the design's "+0.300"
        assertEquals(14880L + 14630 + 14212, s.bestThreeMs)
    }

    @Test
    fun heatDetailNumbers() {
        // The Heat detail design's practice heat.
        val heat = listOf(15812L, 15204, 14980, 14630, 14212, 15420, 14995, 14512, 15890, 14760, 15103, 14890)
            .mapIndexed { i, ms -> LapEntry(i + 1, ms) }
        val s = LapStats(heat)
        assertEquals(180408L, s.totalMs) // "3:00.408"
        assertEquals(15034L, s.averageMs)
        assertEquals(LapEntry(9, 15890), s.slowest)
        assertEquals(listOf(3, 4, 5), s.bestThree!!.map { it.lap })
        assertEquals(43822L, s.bestThreeMs)
        assertEquals(477L, s.stdDevMs) // population std dev, "±0.48s"
    }

    @Test
    fun stdDevOfOneLapIsZero() {
        assertEquals(0L, LapStats(listOf(LapEntry(1, 15000))).stdDevMs)
        assertNull(LapStats(listOf(LapEntry(1, 15000))).bestThreeMs)
    }

    @Test
    fun bestThreeNeedsConsecutiveLaps() {
        val gappy = listOf(LapEntry(1, 10000), LapEntry(2, 10000), LapEntry(4, 10000))
        assertNull(LapStats(gappy).bestThreeMs)
    }

    @Test
    fun empty() {
        val s = LapStats(emptyList())
        assertNull(s.best)
        assertNull(s.averageMs)
        assertNull(s.lastDeltaMs)
        assertNull(s.slowest)
        assertNull(s.stdDevMs)
        assertEquals(0L, s.totalMs)
    }
}
