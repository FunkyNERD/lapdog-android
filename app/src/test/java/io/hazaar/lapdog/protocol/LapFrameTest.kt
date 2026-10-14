package io.hazaar.lapdog.protocol

import io.hazaar.lapdog.protocol.LapFrame.Mode
import io.hazaar.lapdog.protocol.LapFrame.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LapFrameTest {
    // The same hex vectors as lapdog-firmware's test/test_frame - keeps the two sides in sync.
    private val vectors = mapOf(
        "212A03020139300000" to LapFrame.Lap(42, 3, lap = 2, flags = LapFrame.FLAG_BEST, ms = 12345),
        "220100010400030000" to LapFrame.Armed(1, 0, Mode.RACE, heat = 4, lapLimit = 3, timeLimitS = 0),
        "230A0004000C02" to LapFrame.Disarmed(10, 0, heat = 4, laps = 12, ignored = 2),
        "270B0001E803000003" to LapFrame.Ignored(11, 0, LapFrame.Reason.TOO_SOON, ms = 1000, count = 3),
        "24090003010400A0860100" to LapFrame.Heartbeat(9, 0, Stage.RACING, Mode.RACE, heat = 4, uptimeMs = 100000),
        "25050004C8AF0000" to LapFrame.Race(5, 0, Stage.FINISHED, raceMs = 45000),
        "260700810148656174" to LapFrame.Text(7, 0, LapFrame.SLOT_HEAT_PREFIX, 0, 1, "Heat"),
        "2600000F01" to LapFrame.Text(0, 0, 15, 0, 1, ""),
    )

    @Test
    fun decodesFirmwareVectors() {
        vectors.forEach { (hex, frame) -> assertEquals(hex, frame, LapFrame.fromHex(hex)) }
    }

    @Test
    fun encodesFirmwareVectors() {
        vectors.forEach { (hex, frame) -> assertEquals(hex, LapFrame.toHex(frame)) }
    }

    @Test
    fun lapFlags() {
        val lap = LapFrame.fromHex("212A03020339300000") as LapFrame.Lap
        assertTrue(lap.best)
        assertTrue(lap.final)
    }

    @Test
    fun decodesFullU32() {
        val f = LapFrame.fromHex("21000001 00FFFFFFFF".replace(" ", "")) as LapFrame.Lap
        assertEquals(0xFFFFFFFFL, f.ms)
    }

    @Test
    fun everyFrameFitsALegacyAdvert() {
        val longest = LapFrame.Text(0, 0, LapFrame.SLOT_EVENT, 0, 3, "x".repeat(LapFrame.MAX_TEXT_CHUNK))
        assertEquals(LapFrame.MAX_SIZE, LapFrame.encode(longest).size)
        assertTrue(3 + 4 + LapFrame.MAX_SIZE <= 31)
    }

    @Test
    fun rejectsBadFrames() {
        assertNull(LapFrame.fromHex("112A0339300000")) // version 1
        assertNull(LapFrame.fromHex("212A0302013930")) // short LAP
        assertNull(LapFrame.fromHex("2F0000000000000000")) // unknown type
        assertNull(LapFrame.fromHex("260000802278")) // part 2 of 2 (0-based)
        assertNull(LapFrame.fromHex("21ZZ03020139300000")) // not hex
        assertNull(LapFrame.fromHex("21" + "00".repeat(24))) // over MAX_SIZE
    }
}
