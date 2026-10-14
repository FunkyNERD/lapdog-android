package io.hazaar.lapdog.state

import io.hazaar.lapdog.protocol.LapFrame
import io.hazaar.lapdog.protocol.LapFrame.Mode
import io.hazaar.lapdog.protocol.LapFrame.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerModelTest {
    private val m = TimerModel()
    private var seq = 0
    private var now = 1_000L

    private fun feed(f: (Int) -> LapFrame, via: Via = Via.BLE) = m.onFrame(f(seq++), via, now)
    private fun text(slot: Int, t: String) = feed({ LapFrame.Text(it, 0, slot, 0, 1, t) })

    @Test
    fun practiceHeat() {
        feed({ LapFrame.Armed(it, 0, Mode.PRACTICE, 4, 0, 0) })
        text(LapFrame.SLOT_EVENT, "Thursday Backyard")
        text(0, "THURSDAY BACKYARD")
        assertTrue(m.state.armed)
        assertEquals("Thursday Backyard", m.state.event)
        assertEquals("THURSDAY BACKYARD", m.state.osdRows[0])
        assertEquals(Stage.PRACTICE, m.state.session!!.stage)

        now = 2_000
        feed({ LapFrame.Lap(it, 0, 0, 0, 0) }) // first pass starts lap 1
        assertEquals(2_000L, m.state.session!!.lastPassAt)
        assertTrue(m.state.session!!.laps.isEmpty())

        now = 16_630
        feed({ LapFrame.Lap(it, 0, 1, LapFrame.FLAG_BEST, 14630) })
        text(15, "L1:0:14.630 | BEST")
        assertEquals(listOf(LapEntry(1, 14630)), m.state.session!!.laps)
        assertEquals(16_630L, m.state.session!!.lastPassAt)

        feed({ LapFrame.Disarmed(it, 0, 4, 1) })
        assertFalse(m.state.armed)
        assertNull(m.state.session)
        assertEquals(4, m.state.lastSession!!.heat)
        assertEquals(5, m.state.nextHeat)
    }

    @Test
    fun dropsCopiesFromBleRepeatsAndUsb() {
        val lap = LapFrame.Lap(7, 0, 1, 0, 15000)
        m.onFrame(LapFrame.Lap(6, 0, 0, 0, 0), Via.USB, now)
        assertTrue(m.onFrame(lap, Via.USB, now))
        assertFalse(m.onFrame(lap, Via.BLE, now + 50))
        assertFalse(m.onFrame(lap, Via.BLE, now + 300))
        assertEquals(1, m.state.session!!.laps.size)
        // Long after, the same seq is a new frame (the counter wrapped).
        assertTrue(m.onFrame(lap.copy(lap = 2), Via.BLE, now + 60_000))
    }

    @Test
    fun raceHeat() {
        feed({ LapFrame.Armed(it, 0, Mode.RACE, 4, 3, 0) })
        text(5, "ARM NOW")
        feed({ LapFrame.Race(it, 0, Stage.STAGING, 0) })
        assertEquals(Stage.STAGING, m.state.session!!.stage)
        assertEquals(3, m.state.session!!.lapLimit)

        now = 5_000
        text(5, "GO!")
        feed({ LapFrame.Race(it, 0, Stage.RACING, 0) })
        assertEquals(5_000L, m.state.session!!.goAt)
        assertEquals("GO!", m.state.osdRows[5])

        now = 9_671
        feed({ LapFrame.Lap(it, 0, 0, 0, 4671) })
        assertEquals(4671L, m.state.session!!.holeshotMs)

        feed({ LapFrame.Lap(it, 0, 1, 1, 14630) })
        feed({ LapFrame.Lap(it, 0, 2, 0, 15000) })
        feed({ LapFrame.Lap(it, 0, 3, LapFrame.FLAG_FINAL, 15100) })
        assertEquals(Stage.FINISHED, m.state.session!!.stage)
        feed({ LapFrame.Race(it, 0, Stage.FINISHED, 49401) })
        assertEquals(49401L, m.state.session!!.raceMs)
        assertTrue(m.state.armed) // finished, still armed until disarmed
    }

    @Test
    fun osdClearAndBlankRows() {
        text(5, "GO!")
        text(15, "L1:0:14.630")
        text(15, "")
        assertEquals(mapOf(5 to "GO!"), m.state.osdRows)
        text(LapFrame.SLOT_OSD_CLEAR, "")
        assertTrue(m.state.osdRows.isEmpty())
    }

    @Test
    fun heartbeatResyncsAMissedArm() {
        feed({ LapFrame.Heartbeat(it, 0, Stage.RACING, Mode.RACE, 9, 123456) })
        assertTrue(m.state.armed)
        assertEquals(9, m.state.session!!.heat)
        assertEquals(Mode.RACE, m.state.session!!.mode)
        feed({ LapFrame.Heartbeat(it, 0, Stage.IDLE, Mode.RACE, 9, 130000) })
        assertFalse(m.state.armed)
    }

    @Test
    fun usbSettingsAreTheSourceOfTruth() {
        m.onUsb(true)
        m.onSetting("event", "Thursday Backyard")
        m.onSetting("heat", "4")
        text(LapFrame.SLOT_EVENT, "Something else")
        assertEquals("Thursday Backyard", m.state.event)
        assertEquals(4, m.state.nextHeat)
        feed({ LapFrame.Armed(it, 0, Mode.PRACTICE, 4, 0, 0) }, Via.USB)
        assertEquals(5, m.state.settings!!.nextHeat) // the timer advanced it on arm
        m.onSetting("state", "idle") // reconnected after the timer was disarmed
        assertFalse(m.state.armed)
        m.onUsb(false)
        assertNull(m.state.settings)
        assertEquals("Something else", m.state.event)
    }
}
