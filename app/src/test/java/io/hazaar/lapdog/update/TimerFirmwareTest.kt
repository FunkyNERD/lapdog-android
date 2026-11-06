package io.hazaar.lapdog.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerFirmwareTest {
    private fun r(v: String) = FirmwareRelease(v, url = "/flash/x/$v/firmware.bin")

    private val idx = FirmwareIndex(
        mapOf(
            "c5-mini" to listOf(r("0.6.0"), r("0.5.0"), r("0.4.2")),
            "c5-lcd147" to listOf(r("0.6.0"), r("0.5.0")),
            "c3-oled" to listOf(r("0.3.1")),
        ),
    )

    @Test
    fun offersTheNewestForTheBoard() {
        assertEquals("0.6.0", idx.offer("c5-mini", "0.5.0", null)?.version)
        assertNull(idx.offer("c5-mini", "0.6.0", null)) // up to date
        assertNull(idx.offer("c5-mini", "0.7.0", null)) // ahead (a dev build)
        assertNull(idx.offer("c3-oled", "0.3.1", null))
        assertNull(idx.offer("c5-unknown", "0.1.0", null)) // no releases for it
    }

    @Test
    fun aSkippedVersionIsntOfferedButANewerOneIs() {
        assertNull(idx.offer("c5-mini", "0.5.0", "0.6.0"))
        assertEquals("0.6.0", idx.offer("c5-mini", "0.5.0", "0.5.1")?.version)
    }

    @Test
    fun firmwareWithoutABoardGetsTheNewestOfAny() {
        assertEquals("0.6.0", idx.offer(null, "0.5.0", null)?.version)
        assertNull(idx.offer(null, "", null)) // version not read yet
    }

    @Test
    fun versionOrder() {
        assertEquals(1, compareVersions("0.10.0", "0.9.9"))
        assertEquals(-1, compareVersions("0.4.2", "0.5.0"))
        assertEquals(0, compareVersions("v0.5.0", "0.5.0"))
    }

    @Test
    fun parsesTheWebsitesList() {
        val s = """{"devices":{"c5-mini":[{"version":"0.5.0","date":"2026-10-31","notes":"Calibration","size":1294384,
            "sha256":"4693fa24ab89d987bf7fdd73dbdd57716769cd7ed2b10ac67d47ffbc1d1f0812","url":"/flash/c5-mini/0.5.0/firmware.bin","extra":1}]}}"""
        val i = Json { ignoreUnknownKeys = true }.decodeFromString<FirmwareIndex>(s)
        assertEquals(1294384L, i.latest("c5-mini")!!.size)
        assertEquals(listOf("c5-mini"), i.boards)
    }
}
