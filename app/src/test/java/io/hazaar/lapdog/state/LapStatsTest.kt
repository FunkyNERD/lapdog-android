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
    }
}
