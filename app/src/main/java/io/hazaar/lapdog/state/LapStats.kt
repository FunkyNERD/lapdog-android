package io.hazaar.lapdog.state

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** One completed lap. Lap numbers can have gaps if BLE missed a burst. */
@Serializable
data class LapEntry(val lap: Int, val ms: Long)

/** Numbers the lap screens show, computed from a heat's laps. */
data class LapStats(val laps: List<LapEntry>) {
    val count get() = laps.size
    val best: LapEntry? get() = laps.minByOrNull { it.ms }
    val last: LapEntry? get() = laps.lastOrNull()
    val totalMs: Long get() = laps.sumOf { it.ms }
    val averageMs: Long? get() = if (laps.isEmpty()) null else laps.sumOf { it.ms } / laps.size

    /** The slowest lap; the first one on a tie. */
    val slowest: LapEntry? get() = laps.maxByOrNull { it.ms }

    /** Fastest three laps in a row (consecutive lap numbers), RotorHazard style. */
    val bestThree: List<LapEntry>? get() = laps.windowed(3)
        .filter { it[2].lap - it[0].lap == 2 }
        .minByOrNull { w -> w.sumOf { it.ms } }

    val bestThreeMs: Long? get() = bestThree?.sumOf { it.ms }

    /** Population standard deviation of the lap times, ms: the "consistency" stat. */
    val stdDevMs: Long? get() {
        if (laps.isEmpty()) return null
        val mean = laps.sumOf { it.ms }.toDouble() / laps.size
        return sqrt(laps.sumOf { (it.ms - mean) * (it.ms - mean) } / laps.size).roundToLong()
    }

    /** Last lap minus the best lap; 0 when the last lap is the best. */
    val lastDeltaMs: Long? get() = last?.let { l -> best?.let { l.ms - it.ms } }

    fun isBest(e: LapEntry) = best?.let { it.lap == e.lap } ?: false
}
