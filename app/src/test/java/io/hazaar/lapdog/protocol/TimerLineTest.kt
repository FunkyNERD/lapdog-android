package io.hazaar.lapdog.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerLineTest {
    @Test
    fun parsesEachKind() {
        assertEquals(
            TimerLine.Frame(LapFrame.Lap(42, 3, 2, 1, 12345)),
            TimerLine.parse("F:212A03020139300000\r"),
        )
        assertEquals(TimerLine.Setting("event", "Thursday Backyard"), TimerLine.parse("S:event=Thursday Backyard"))
        assertEquals(TimerLine.Setting("phrase", "a=b"), TimerLine.parse("S:phrase=a=b"))
        assertEquals(TimerLine.Setting("event", ""), TimerLine.parse("S:event="))
        assertEquals(TimerLine.Rssi(12.5f), TimerLine.parse("R:12.5"))
        assertEquals(TimerLine.Reply(true, ""), TimerLine.parse("OK"))
        assertEquals(TimerLine.Reply(false, "disarm first"), TimerLine.parse("ERR disarm first"))
        assertEquals(TimerLine.Reply(false, ""), TimerLine.parse("ERR"))
    }

    @Test
    fun ignoresEverythingElse() {
        assertNull(TimerLine.parse("I (123) lapdog: armed"))
        assertNull(TimerLine.parse("F:112A0339300000")) // old v1 frame
        assertNull(TimerLine.parse("R:abc"))
        assertNull(TimerLine.parse("S:=x"))
        assertNull(TimerLine.parse("OKAY"))
    }
}
