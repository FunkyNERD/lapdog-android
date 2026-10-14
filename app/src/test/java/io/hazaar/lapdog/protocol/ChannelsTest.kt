package io.hazaar.lapdog.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelsTest {
    @Test
    fun matchesFirmwareTable() {
        assertEquals(5769, Channels.freqOf("R4"))
        assertEquals(5769, Channels.freqOf("r4"))
        assertEquals(5362, Channels.freqOf("L1"))
        assertEquals(5945, Channels.freqOf("E8"))
        assertNull(Channels.freqOf("R9"))
        assertNull(Channels.freqOf("X1"))
        assertEquals("R4", Channels.labelOf(5769))
        assertEquals("R7", Channels.labelOf(5880)) // also F8: table order wins, as on the timer
        assertNull(Channels.labelOf(5801))
    }
}
