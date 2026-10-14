package io.hazaar.lapdog.state

/** One completed lap. Lap numbers can have gaps if BLE missed a burst. */
data class LapEntry(val lap: Int, val ms: Long)

/** Numbers the lap screens show, computed from a heat's laps. */
data class LapStats(val laps: List<LapEntry>) {
    val count get() = laps.size
    val best: LapEntry? get() = laps.minByOrNull { it.ms }
    val last: LapEntry? get() = laps.lastOrNull()
    val averageMs: Long? get() = if (laps.isEmpty()) null else laps.sumOf { it.ms } / laps.size

    /** Fastest three laps in a row (consecutive lap numbers), RotorHazard style. */
    val bestThreeMs: Long? get() = laps.windowed(3)
        .filter { it[2].lap - it[0].lap == 2 }
        .minOfOrNull { w -> w.sumOf { it.ms } }

    /** Last lap minus the best lap; 0 when the last lap is the best. */
    val lastDeltaMs: Long? get() = last?.let { l -> best?.let { l.ms - it.ms } }

    fun isBest(e: LapEntry) = best?.let { it.lap == e.lap } ?: false
}
