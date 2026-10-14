package io.hazaar.lapdog.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerSettingsTest {
    private val timer = listOf(
        "chan=R4", "freq=5769", "enter=10.0", "exit=5.0", "alpha=0.20", "minlap=3000", "link=ble",
        "phrase=my bind phrase", "event=Thursday Backyard", "prefix=Heat", "heat=4", "msg.best=off",
        "race.limit=time", "race.time=90", "mode=race", "fw=0.2.0", "state=idle",
    ).fold(TimerSettings()) { s, kv -> kv.split('=', limit = 2).let { s.with(it[0], it[1]) } }

    @Test
    fun readsGetOutput() {
        assertEquals("R4", timer.chan)
        assertEquals(5769, timer.freqMhz)
        assertEquals("Thursday Backyard", timer.event)
        assertEquals(4, timer.nextHeat)
        assertEquals(false, timer.msgBest)
        assertEquals(TimerSettings.RaceLimit.TIME, timer.raceLimit)
        assertEquals(TimerSettings.Sensitivity.MED, timer.sensitivity)
        assertEquals("0.2.0", timer.firmware)
        assertEquals(timer, timer.with("nope", "1").with("freq", "abc"))
    }

    @Test
    fun noChangesNoCommands() {
        assertTrue(timer.commandsFrom(timer).isEmpty())
    }

    @Test
    fun writesOnlyWhatChangedThenSaves() {
        val edited = timer.copy(event = "Club night", link = TimerSettings.Link.ESPNOW, nextHeat = 1, msgBest = true)
        assertEquals(
            listOf("set link espnow", "set event Club night", "set heat 1", "set msg.best on", "save"),
            edited.commandsFrom(timer),
        )
    }

    @Test
    fun falseReadProtection() {
        val read = timer.with("park", "off").with("maxpeak", "2000").with("minlap", "4500")
        assertEquals(false, read.ignoreParked)
        assertEquals(2000, read.maxPeakMs)
        assertEquals(4500, read.minLapMs)
        assertEquals(
            listOf("set minlap 5000", "set park on", "save"),
            read.copy(minLapMs = 5000, ignoreParked = true).commandsFrom(read),
        )
        assertEquals(1000..30000, TimerSettings.MIN_LAP_RANGE_MS) // same as the timer's set minlap
    }

    @Test
    fun clearingTextSendsEmptyValue() {
        assertEquals(listOf("set event", "save"), timer.copy(event = "").commandsFrom(timer))
    }

    @Test
    fun channelPrefersLabel() {
        assertEquals(listOf("set chan F1", "save"), timer.copy(chan = "F1", freqMhz = 5740).commandsFrom(timer))
    }

    @Test
    fun thresholdOrderKeepsEnterAboveExit() {
        // The timer rejects enter <= exit, so each step must be valid on its own.
        fun check(from: TimerSettings, to: TimerSettings) {
            var enter = from.enterDb
            var exit = from.exitDb
            to.commandsFrom(from).filter { it.startsWith("set enter") || it.startsWith("set exit") }.forEach {
                val v = it.substringAfterLast(' ').toFloat()
                if (it.startsWith("set enter")) enter = v else exit = v
                assertTrue("$it leaves enter $enter <= exit $exit", enter > exit)
            }
            assertEquals(to.enterDb, enter)
            assertEquals(to.exitDb, exit)
        }
        val low = timer.copy(enterDb = 14f, exitDb = 8f)
        val high = timer.copy(enterDb = 6f, exitDb = 3f)
        check(low, high)
        check(high, low)
        check(timer, high)
        check(timer, low)
    }
}
