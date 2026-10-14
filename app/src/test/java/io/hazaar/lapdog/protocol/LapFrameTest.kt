package io.hazaar.lapdog.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LapFrameTest {
    // Same vector as lapdog-firmware's test/test_frame - keeps the two sides in sync.
    private val vector = "F:112A0339300000"

    @Test
    fun parsesFirmwareVector() {
        assertEquals(LapFrame(LapFrame.Type.LAP, 42, 3, 12345), LapFrame.fromLine(vector))
    }

    @Test
    fun parsesWithCrLf() {
        assertEquals(12345L, LapFrame.fromLine("$vector\r")?.value)
    }

    @Test
    fun ignoresNonFrameLines() {
        assertNull(LapFrame.fromLine("OK"))
        assertNull(LapFrame.fromLine("R:-88.5"))
        assertNull(LapFrame.fromLine("I (123) lapdog: armed"))
        assertNull(LapFrame.fromLine("F:112A03393000")) // short
        assertNull(LapFrame.fromLine("F:212A0339300000")) // wrong version
    }

    @Test
    fun decodesFullU32() {
        val b = byteArrayOf(0x11, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(0xFFFFFFFFL, LapFrame.decode(b)?.value)
    }
}
