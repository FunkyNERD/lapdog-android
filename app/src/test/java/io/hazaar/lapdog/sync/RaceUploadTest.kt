package io.hazaar.lapdog.sync

import io.hazaar.lapdog.protocol.LapFrame.Mode
import io.hazaar.lapdog.state.HeatRecord
import io.hazaar.lapdog.state.LapEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceUploadTest {
    private val started = 1_760_241_290_123L

    @Test
    fun raceCarriesTheHoleshotIntoLapTimes() {
        val h = HeatRecord(
            id = started, heat = 3, event = "Thursday Backyard", mode = Mode.RACE,
            laps = listOf(LapEntry(1, 21_345), LapEntry(2, 20_871), LapEntry(3, 22_000)),
            holeshotMs = 1_500, raceMs = 65_716,
        )
        val r = RaceUpload.of(h)
        assertEquals(3, r.lapCount)
        assertEquals(65.716, r.totalTimeS!!, 1e-9)
        assertEquals(20.871, r.bestLapS!!, 1e-9)
        assertEquals("Heat 3 · Race", r.label)
        assertEquals("Thursday Backyard", r.event)
        assertEquals(1_760_241_290L, r.startedAt)
        assertEquals(listOf(22_845L, 43_716L, 65_716L), r.laps.map { it.tMs })
        assertEquals(listOf(false, true, false), r.laps.map { it.best })
        assertEquals(21.345, r.laps[0].timeS, 1e-9)
    }

    @Test
    fun practiceTimesFromTheStartPassAndGapsLoseTheRunningTime() {
        val h = HeatRecord(
            id = started, heat = 7, prefix = "",
            laps = listOf(LapEntry(1, 30_000), LapEntry(2, 31_000), LapEntry(4, 29_000), LapEntry(5, 30_500)),
        )
        val r = RaceUpload.of(h)
        assertEquals(5, r.lapCount) // lap 3 was missed, not skipped
        assertEquals("7 · Practice", r.label)
        assertNull(r.event)
        assertEquals(listOf(30_000L, 61_000L, null, null), r.laps.map { it.tMs })
        assertEquals(120.5, r.totalTimeS!!, 1e-9)
    }

    @Test
    fun aRaceWithoutItsHoleshotHasNoRunningTime() {
        val h = HeatRecord(id = started, heat = 1, mode = Mode.RACE, laps = listOf(LapEntry(1, 20_000)))
        assertNull(RaceUpload.of(h).laps.single().tMs)
    }

    @Test
    fun jsonMatchesTheDeviceApi() {
        val h = HeatRecord(id = started, heat = 1, laps = listOf(LapEntry(1, 20_000)))
        val obj = Json { explicitNulls = false }.encodeToJsonElement(RaceUpload.serializer(), RaceUpload.of(h)).jsonObject
        assertEquals(setOf("lap_count", "total_time_s", "best_lap_s", "label", "started_at", "laps"), obj.keys)
        assertEquals(setOf("lap", "t_ms", "time_s", "best"), obj["laps"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject.keys })
    }

    @Test
    fun onlyHeatsWithLapsGoUp() {
        assertFalse(HeatRecord(id = 1, heat = 1).uploadable())
        assertTrue(HeatRecord(id = 1, heat = 1, laps = listOf(LapEntry(1, 1000))).uploadable())
    }
}
