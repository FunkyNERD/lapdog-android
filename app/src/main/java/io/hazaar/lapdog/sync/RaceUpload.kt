package io.hazaar.lapdog.sync

import io.hazaar.lapdog.state.HeatRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One lap as the website's race API takes it. */
@Serializable
data class UploadLap(
    val lap: Int,
    /** Race time at the end of this lap; null after a gap, where it isn't known. */
    @SerialName("t_ms") val tMs: Long?,
    @SerialName("time_s") val timeS: Double,
    val best: Boolean,
)

/**
 * The body of `PUT /api/v1/races/{localId}` for one heat (lapdog-web
 * docs/dev/device-api.md, "Uploading a race"). Practice heats go up too: the website
 * keeps them as races without a position, filed under the heat's event or its day.
 */
@Serializable
data class RaceUpload(
    @SerialName("lap_count") val lapCount: Int,
    @SerialName("total_time_s") val totalTimeS: Double?,
    @SerialName("best_lap_s") val bestLapS: Double?,
    val label: String,
    val event: String? = null,
    @SerialName("started_at") val startedAt: Long,
    val laps: List<UploadLap>,
) {
    companion object {
        fun of(h: HeatRecord): RaceUpload {
            val stats = h.stats
            // Laps BLE missed still happened, so the count is the last lap number.
            val count = h.laps.maxOfOrNull { it.lap } ?: 0
            var t: Long? = h.holeshotMs ?: if (h.isRace) null else 0L
            var expected = 1
            val laps = h.laps.map { l ->
                if (l.lap != expected) t = null
                expected = l.lap + 1
                t = t?.plus(l.ms)
                UploadLap(l.lap, t, seconds(l.ms), stats.isBest(l))
            }
            return RaceUpload(
                lapCount = count,
                totalTimeS = (h.raceTimeMs ?: stats.totalMs.takeIf { it > 0 })?.let(::seconds),
                bestLapS = stats.best?.let { seconds(it.ms) },
                label = (if (h.isRace) "${h.name} · Race" else "${h.name} · Practice").take(64),
                event = h.event.trim().takeIf { it.isNotEmpty() }?.take(100),
                startedAt = h.startedAt / 1000,
                laps = laps,
            )
        }

        private fun seconds(ms: Long) = ms / 1000.0
    }
}

/** Which heats go up: everything finished with at least one lap. */
fun HeatRecord.uploadable() = laps.isNotEmpty()
