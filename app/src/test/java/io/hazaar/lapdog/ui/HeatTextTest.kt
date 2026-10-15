package io.hazaar.lapdog.ui

import io.hazaar.lapdog.protocol.LapFrame.Mode
import io.hazaar.lapdog.protocol.LapFrame.Reason
import io.hazaar.lapdog.state.HeatRecord
import io.hazaar.lapdog.state.Ignored
import io.hazaar.lapdog.state.IgnoredPass
import io.hazaar.lapdog.state.LapEntry
import io.hazaar.lapdog.state.PersonalBests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

class HeatTextTest {
    private val t = HeatText(ZoneOffset.UTC, Locale.UK)
    private val at = LocalDateTime.of(2026, 10, 15, 19, 42).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun laps(vararg ms: Long) = ms.mapIndexed { i, v -> LapEntry(i + 1, v) }

    private val practice = HeatRecord(
        at, 3, event = "Thursday Backyard", startedAt = at, channel = "R4", freqMhz = 5769,
        laps = laps(15812, 15204, 14980), ignored = Ignored(tooSoon = 1), passes = listOf(IgnoredPass(Reason.TOO_SOON, 1200, 2)),
    )

    @Test
    fun rowsAndSubtitle() {
        assertEquals("Thursday Backyard · Thu 15 Oct, 7:42 pm · R4 5769 MHz", t.subtitle(practice))
        assertEquals("7:42 pm · 3 laps · 0:45.996 · 1 ignored", t.meta(practice))
        val race = HeatRecord(at, 2, mode = Mode.RACE, lapLimit = 3, holeshotMs = 4671, laps = laps(14630, 15102, 15188))
        assertEquals("7:42 pm · 3/3 laps · 0:49.591", t.meta(race))
        val timed = race.copy(lapLimit = 0, timeLimitS = 120)
        assertEquals("7:42 pm · 2:00 limit · 3 laps", t.meta(timed))
    }

    @Test
    fun days() {
        val today = LocalDate.of(2026, 10, 15)
        assertEquals("Today", t.dayLabel(today, today))
        assertEquals("Yesterday", t.dayLabel(today.minusDays(1), today))
        assertEquals("Tue 13 Oct", t.dayLabel(today.minusDays(2), today))
        assertEquals("today", t.relativeDay(practice, today))
    }

    @Test
    fun ignored() {
        assertEquals("Pass ignored · 1.2 s after lap 2 · too soon", t.pass(practice.passes[0]))
        assertEquals("Pass ignored · 8.0 s at the gate after the start · parked", t.pass(IgnoredPass(Reason.TOO_LONG, 8000, 0)))
        assertEquals("1 pass ignored this heat — too soon after a lap (a crash into the gate?)", t.ignoredNote(practice.ignored))
        assertEquals("±0.48s", t.consistency(477))
    }

    @Test
    fun shareSummary() {
        val s = t.share(practice, PersonalBests.of(listOf(practice)))
        assertTrue(s, s.startsWith("LapDog · Heat 3 (Practice)\nThursday Backyard"))
        assertTrue(s, "Best lap 0:14.980 (lap 3) · personal best" in s)
        assertTrue(s, "Lap 3  0:14.980  best" in s)
    }
}
