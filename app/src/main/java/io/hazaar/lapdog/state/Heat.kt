package io.hazaar.lapdog.state

import io.hazaar.lapdog.protocol.LapFrame
import io.hazaar.lapdog.protocol.LapFrame.Mode
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A gate pass the timer ignored, where it fell in the heat. */
@Serializable
data class IgnoredPass(
    val reason: LapFrame.Reason,
    /** Too soon: since the last lap. Too long: the peak's length. */
    val ms: Long,
    /** The last lap completed before it; 0 = after the start pass (or before it). */
    val afterLap: Int,
)

/**
 * One finished heat as saved on the phone: everything its frames carried, ARMED to
 * DISARMED. Laps can have gaps and [passes] can be fewer than [ignored] when BLE
 * missed bursts; the timer's running counts fill in what they can.
 */
@Serializable
data class HeatRecord(
    /** Wall clock (ms) when it was armed; unique per heat and the file name. */
    val id: Long,
    val heat: Int,
    val prefix: String = "Heat",
    val event: String = "",
    val mode: Mode = Mode.PRACTICE,
    val startedAt: Long = id,
    /** "R4", or null if the app never read the timer's settings (BLE only). */
    val channel: String? = null,
    val freqMhz: Int? = null,
    val laps: List<LapEntry> = emptyList(),
    /** Race: GO to the first gate pass. */
    val holeshotMs: Long? = null,
    val lapLimit: Int = 0,
    val timeLimitS: Int = 0,
    /** Race time at the finish, from the timer. */
    val raceMs: Long? = null,
    /** Race reached its limit (vs stopped early). */
    val finished: Boolean = false,
    val ignored: Ignored = Ignored(),
    val passes: List<IgnoredPass> = emptyList(),
) {
    val name get() = if (prefix.isEmpty()) "$heat" else "$prefix $heat"
    val stats get() = LapStats(laps)
    val isRace get() = mode == Mode.RACE

    /** GO to finish: the timer's own figure, else holeshot plus laps. */
    val raceTimeMs: Long? get() = raceMs ?: holeshotMs?.let { it + stats.totalMs }

    fun date(zone: ZoneId): LocalDate = Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
}

/** The fastest lap and three-in-a-row across all heats; the earlier heat wins a tie. */
data class PersonalBests(
    val lap: Pair<HeatRecord, LapEntry>? = null,
    val three: Pair<HeatRecord, List<LapEntry>>? = null,
) {
    fun isBestLap(h: HeatRecord) = lap?.first?.id == h.id

    companion object {
        fun of(heats: List<HeatRecord>): PersonalBests {
            val ordered = heats.sortedBy { it.startedAt }
            val lap = ordered.mapNotNull { h -> h.stats.best?.let { h to it } }.minByOrNull { it.second.ms }
            val three = ordered.mapNotNull { h -> h.stats.bestThree?.let { h to it } }.minByOrNull { p -> p.second.sumOf { it.ms } }
            return PersonalBests(lap, three)
        }
    }
}

/** History's sections: newest day first, newest heat first, with the day's event name. */
data class HeatDay(val date: LocalDate, val event: String, val heats: List<HeatRecord>)

fun groupByDay(heats: List<HeatRecord>, zone: ZoneId): List<HeatDay> =
    heats.sortedByDescending { it.startedAt }
        .groupBy { it.date(zone) }
        .map { (date, hs) -> HeatDay(date, hs.first().event, hs) }
