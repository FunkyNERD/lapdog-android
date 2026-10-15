package io.hazaar.lapdog.ui

import io.hazaar.lapdog.protocol.Format
import io.hazaar.lapdog.protocol.LapFrame.Reason
import io.hazaar.lapdog.state.HeatRecord
import io.hazaar.lapdog.state.Ignored
import io.hazaar.lapdog.state.IgnoredPass
import io.hazaar.lapdog.state.PersonalBests
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words the history and heat screens (and the share text) use for a heat. Pure, so testable. */
class HeatText(private val zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) {
    private val clock = DateTimeFormatter.ofPattern("h:mm a", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    private val weekdayDayMonth = DateTimeFormatter.ofPattern("EEE d MMM", locale)

    private fun at(h: HeatRecord) = Instant.ofEpochMilli(h.startedAt).atZone(zone)

    /** "7:42 pm" */
    fun time(h: HeatRecord): String = clock.format(at(h)).lowercase()

    /** History section titles: "Today", "Yesterday", "Wed 14 Oct". */
    fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now(zone)) = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> weekdayDayMonth.format(date)
    }

    /** In a sentence: "today", "yesterday", "14 Oct". */
    fun relativeDay(h: HeatRecord, today: LocalDate = LocalDate.now(zone)) = when (val d = h.date(zone)) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> dayMonth.format(d)
    }

    fun mode(h: HeatRecord) = if (h.isRace) "Race" else "Practice"

    /** "R4 5769 MHz", "5769 MHz", or null when the channel was never read. */
    fun channel(h: HeatRecord): String? {
        val f = h.freqMhz?.let { "$it MHz" }
        return listOfNotNull(h.channel, f).joinToString(" ").ifEmpty { null }
    }

    /** "Thursday Backyard · Thu 15 Oct, 7:42 pm · R4 5769 MHz" */
    fun subtitle(h: HeatRecord) =
        listOfNotNull(h.event.ifEmpty { null }, "${weekdayDayMonth.format(at(h))}, ${time(h)}", channel(h)).joinToString(" · ")

    /** History row: "7:42 pm · 12 laps · 3:00.408 · 1 ignored", "7:31 pm · 3/3 laps · 0:49.591", "8:05 pm · 2:00 limit · 7 laps". */
    fun meta(h: HeatRecord): String {
        val st = h.stats
        val parts = mutableListOf(time(h))
        when {
            h.isRace && h.lapLimit > 0 -> {
                parts += "${st.count}/${h.lapLimit} laps"
                h.raceTimeMs?.let { parts += Format.lap(it) }
            }
            h.isRace && h.timeLimitS > 0 -> {
                parts += "${Format.minutes(h.timeLimitS)} limit"
                parts += laps(st.count)
            }
            else -> {
                parts += laps(st.count)
                if (st.count > 0) parts += Format.lap(st.totalMs)
            }
        }
        if (h.ignored.total > 0) parts += "${h.ignored.total} ignored"
        return parts.joinToString(" · ")
    }

    fun laps(n: Int) = if (n == 1) "1 lap" else "$n laps"

    /** "±0.48s" */
    fun consistency(ms: Long) = "±%.2fs".format(Locale.ROOT, ms / 1000.0)

    /** "15.03" for the chart's average label. */
    fun seconds(ms: Long, decimals: Int = 2) = "%.${decimals}f".format(Locale.ROOT, ms / 1000.0)

    /** Inline lap-list row: "Pass ignored · 1.2 s after lap 6 · too soon". */
    fun pass(p: IgnoredPass): String {
        val after = if (p.afterLap == 0) "the start" else "lap ${p.afterLap}"
        val s = seconds(p.ms, 1)
        return when (p.reason) {
            Reason.TOO_SOON -> "Pass ignored · $s s after $after · too soon"
            Reason.TOO_LONG -> "Pass ignored · $s s at the gate after $after · parked"
        }
    }

    /** "1 pass ignored this heat — too soon after a lap (a crash into the gate?)" */
    fun ignoredNote(ig: Ignored): String? {
        if (ig.total == 0) return null
        val what = if (ig.total == 1) "1 pass ignored this heat" else "${ig.total} passes ignored this heat"
        val why = when {
            ig.tooLong == 0 -> "too soon after a lap (a crash into the gate?)"
            ig.tooSoon == 0 -> "quad parked at the gate"
            else -> "${ig.tooSoon} too soon after a lap, ${ig.tooLong} parked at the gate"
        }
        return "$what — $why"
    }

    /** Plain-text summary for the share sheet. */
    fun share(h: HeatRecord, pb: PersonalBests): String {
        val st = h.stats
        return buildString {
            appendLine("LapDog · ${h.name} (${mode(h)})")
            appendLine(subtitle(h))
            st.best?.let { appendLine("Best lap ${Format.lap(it.ms)} (lap ${it.lap})" + if (pb.isBestLap(h)) " · personal best" else "") }
            if (h.isRace) {
                val laps = if (h.lapLimit > 0) "${st.count}/${h.lapLimit} laps" else laps(st.count) + " in ${Format.minutes(h.timeLimitS)}"
                appendLine(listOfNotNull(laps, h.raceTimeMs?.let { "race time ${Format.lap(it)}" }, h.holeshotMs?.let { "holeshot ${Format.lap(it)}" }).joinToString(" · "))
            } else if (st.count > 0) {
                appendLine("${laps(st.count)} · total ${Format.lap(st.totalMs)}")
            }
            st.averageMs?.let { avg ->
                appendLine(listOfNotNull("average ${Format.lap(avg)}", st.bestThreeMs?.let { "best 3 ${Format.lap(it)}" }, st.stdDevMs?.let { "consistency ${consistency(it)}" }).joinToString(" · "))
            }
            ignoredNote(h.ignored)?.let { appendLine(it) }
            if (st.count > 0) {
                appendLine()
                h.laps.forEach { appendLine("Lap ${it.lap}  ${Format.lap(it.ms)}" + if (st.isBest(it)) "  best" else "") }
            }
        }.trimEnd()
    }
}
