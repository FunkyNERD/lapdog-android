package io.hazaar.lapdog.state

import io.hazaar.lapdog.protocol.LapFrame
import io.hazaar.lapdog.protocol.LapFrame.Mode
import io.hazaar.lapdog.protocol.LapFrame.Reason
import io.hazaar.lapdog.protocol.LapFrame.Stage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class HeatRecordTest {
    private var wall = 1_760_000_000_000L
    private val m = TimerModel { wall }
    private var seq = 0
    private var now = 1_000L

    private fun feed(f: (Int) -> LapFrame) = m.onFrame(f(seq++), Via.BLE, now)
    private fun text(slot: Int, t: String) = feed({ LapFrame.Text(it, 0, slot, 0, 1, t) })

    @Test
    fun recordKeepsWhatTheFramesCarried() {
        m.onUsb(true)
        m.onSetting("chan", "R4")
        m.onSetting("freq", "5769")
        m.onSetting("event", "Thursday Backyard")
        feed({ LapFrame.Armed(it, 0, Mode.PRACTICE, 3, 0, 0) })
        feed({ LapFrame.Lap(it, 0, 0, 0, 0) })
        feed({ LapFrame.Lap(it, 0, 1, 0, 15812) })
        feed({ LapFrame.Lap(it, 0, 2, 0, 15204) })
        feed({ LapFrame.Ignored(it, 0, Reason.TOO_SOON, 1200, 1) })
        // Lap 3 and an IGNORED burst were missed over BLE.
        feed({ LapFrame.Lap(it, 0, 4, 0, 14630) })
        assertTrue(m.takeFinished().isEmpty())
        feed({ LapFrame.Disarmed(it, 0, 3, 4, 2) })

        val h = m.takeFinished().single()
        assertTrue(m.takeFinished().isEmpty())
        assertEquals(wall, h.id)
        assertEquals(wall, h.startedAt)
        assertEquals("Heat 3", h.name)
        assertEquals("Thursday Backyard", h.event)
        assertEquals(Mode.PRACTICE, h.mode)
        assertEquals("R4", h.channel)
        assertEquals(5769, h.freqMhz)
        assertEquals(listOf(LapEntry(1, 15812), LapEntry(2, 15204), LapEntry(4, 14630)), h.laps)
        assertEquals(listOf(IgnoredPass(Reason.TOO_SOON, 1200, 2)), h.passes)
        assertEquals(2, h.ignored.total) // the timer's count, though one frame was missed
        assertNull(h.holeshotMs)
        assertEquals(h, m.state.lastSession)
    }

    @Test
    fun raceHeatFromFrames() {
        feed({ LapFrame.Armed(it, 0, Mode.RACE, 2, 3, 0) })
        text(LapFrame.SLOT_HEAT_PREFIX, "Race")
        feed({ LapFrame.Race(it, 0, Stage.STAGING, 0) })
        feed({ LapFrame.Race(it, 0, Stage.RACING, 0) })
        feed({ LapFrame.Lap(it, 0, 0, 0, 4671) })
        feed({ LapFrame.Lap(it, 0, 1, 0, 14630) })
        feed({ LapFrame.Lap(it, 0, 2, 0, 15102) })
        feed({ LapFrame.Lap(it, 0, 3, LapFrame.FLAG_FINAL, 15188) })
        feed({ LapFrame.Race(it, 0, Stage.FINISHED, 49591) })
        feed({ LapFrame.Disarmed(it, 0, 2, 3, 0) })

        val h = m.takeFinished().single()
        assertEquals("Race 2", h.name)
        assertEquals(Mode.RACE, h.mode)
        assertNull(h.channel) // BLE only: settings never read
        assertEquals(4671L, h.holeshotMs)
        assertEquals(3, h.lapLimit)
        assertEquals(49591L, h.raceMs)
        assertEquals(49591L, h.raceTimeMs)
        assertTrue(h.finished)
        assertEquals(3, h.laps.size)
    }

    @Test
    fun raceTimeFallsBackToHoleshotPlusLaps() {
        val h = HeatRecord(1, 1, mode = Mode.RACE, holeshotMs = 4000, laps = listOf(LapEntry(1, 15000), LapEntry(2, 16000)))
        assertEquals(35000L, h.raceTimeMs)
    }

    @Test
    fun newHeatWhileArmedEndsTheLastOne() {
        feed({ LapFrame.Armed(it, 0, Mode.PRACTICE, 3, 0, 0) })
        feed({ LapFrame.Lap(it, 0, 0, 0, 0) })
        feed({ LapFrame.Lap(it, 0, 1, 0, 15000) })
        wall += 60_000
        feed({ LapFrame.Armed(it, 0, Mode.PRACTICE, 4, 0, 0) }) // "heat next"
        assertEquals(listOf(3), m.takeFinished().map { it.heat })
        assertEquals(4, m.state.session!!.heat)
        assertEquals(wall, m.state.session!!.startedAt)
    }

    @Test
    fun restartDropsTheAbortedRace() {
        feed({ LapFrame.Armed(it, 0, Mode.RACE, 5, 3, 0) })
        feed({ LapFrame.Lap(it, 0, 0, 0, 4000) })
        feed({ LapFrame.Armed(it, 0, Mode.RACE, 5, 3, 0) }) // restart: same heat
        assertTrue(m.takeFinished().isEmpty())
        assertFalse(m.state.session!!.started)
    }

    @Test
    fun armWithoutAPassIsNotSaved() {
        feed({ LapFrame.Armed(it, 0, Mode.PRACTICE, 3, 0, 0) })
        feed({ LapFrame.Disarmed(it, 0, 3, 0, 0) })
        assertTrue(m.takeFinished().isEmpty())
    }

    @Test
    fun personalBestsAcrossHeats() {
        val a = HeatRecord(1, 1, startedAt = 100, laps = laps(15000, 14500, 14600, 14700))
        val b = HeatRecord(2, 2, startedAt = 200, laps = laps(14500, 15500, 15500))
        val c = HeatRecord(3, 3, startedAt = 300, laps = laps(16000))
        val pb = PersonalBests.of(listOf(c, b, a))
        assertEquals(a.id, pb.lap!!.first.id) // a tie goes to the earlier heat
        assertEquals(14500L, pb.lap!!.second.ms)
        assertTrue(pb.isBestLap(a))
        assertFalse(pb.isBestLap(b))
        assertEquals(a.id, pb.three!!.first.id)
        assertEquals(listOf(2, 3, 4), pb.three!!.second.map { it.lap })
        assertEquals(PersonalBests(), PersonalBests.of(emptyList()))
    }

    @Test
    fun groupsByDayNewestFirst() {
        val day = 86_400_000L
        val a = HeatRecord(1, 1, event = "Backyard", startedAt = day * 20_000 + 1000)
        val b = HeatRecord(2, 2, event = "Thursday Backyard", startedAt = day * 20_001 + 1000)
        val c = HeatRecord(3, 3, event = "Thursday Backyard", startedAt = day * 20_001 + 2000)
        val days = groupByDay(listOf(a, b, c), ZoneOffset.UTC)
        assertEquals(listOf(LocalDate.ofEpochDay(20_001), LocalDate.ofEpochDay(20_000)), days.map { it.date })
        assertEquals(listOf(3, 2), days[0].heats.map { it.heat })
        assertEquals("Thursday Backyard", days[0].event)
    }

    @Test
    fun jsonRoundTrip() {
        val h = HeatRecord(
            7, 7, "Heat", "Backyard", Mode.RACE, 7, "R4", 5769, laps(15000, 15100), 4000, 0, 120, 120500, true,
            Ignored(tooSoon = 1), listOf(IgnoredPass(Reason.TOO_SOON, 900, 1)),
        )
        val json = Json { ignoreUnknownKeys = true }
        val text = json.encodeToString(HeatRecord.serializer(), h)
        assertEquals(h, json.decodeFromString(HeatRecord.serializer(), text))
        // Older or newer files: missing fields default, unknown ones are skipped.
        assertEquals(HeatRecord(9, 2), json.decodeFromString(HeatRecord.serializer(), """{"id":9,"heat":2,"startedAt":9,"later":1}"""))
    }

    private fun laps(vararg ms: Long) = ms.mapIndexed { i, t -> LapEntry(i + 1, t) }
}
