package io.hazaar.lapdog.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test
    fun formats() {
        assertEquals("0:14.630", Format.lap(14630)) // same as the timer's OSD text
        assertEquals("2:05.004", Format.lap(125004))
        assertEquals("0:08.4", Format.running(8412))
        assertEquals("1:00.0", Format.running(60000))
        assertEquals("0:00.0", Format.running(-5))
        assertEquals("+0.300", Format.delta(300))
        assertEquals("-1.020", Format.delta(-1020))
        assertEquals("2:00", Format.minutes(120))
    }

    @Test
    fun parsesMinutes() {
        assertEquals(120, Format.parseMinutes("2:00"))
        assertEquals(90, Format.parseMinutes(" 1:30 "))
        assertEquals(45, Format.parseMinutes("45"))
        assertNull(Format.parseMinutes("1:75"))
        assertNull(Format.parseMinutes("x"))
    }
}
