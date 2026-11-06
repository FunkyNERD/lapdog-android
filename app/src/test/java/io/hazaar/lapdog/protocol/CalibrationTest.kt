package io.hazaar.lapdog.protocol

import io.hazaar.lapdog.protocol.Calibration.Event
import io.hazaar.lapdog.protocol.Calibration.Step
import io.hazaar.lapdog.protocol.Calibration.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    private var c = Calibration()
    private val events = mutableListOf<Event>()

    /** Feeds `S:` lines as the timer prints them; returns the events. */
    private fun feed(vararg lines: String): List<Event> {
        events.clear()
        for (l in lines) {
            val (k, v) = l.removePrefix("S:").split('=', limit = 2)
            if (Calibration.isCalKey(k)) {
                val (n, e) = c.with(k, v)
                c = n
                e?.let(events::add)
            } else c = c.withSetting(k, v)
        }
        return events.toList()
    }

    private val okResult = arrayOf(
        "S:cal.result=ok", "S:cal.flown_gain=20", "S:cal.next_gain=20", "S:cal.passes=6", "S:cal.clipped=0",
        "S:cal.inflight=-45.4", "S:cal.pass=-19.4", "S:cal.contrast=26.0", "S:cal.headroom=12.9",
        "S:cal.low_contrast=0", "S:cal.enter=21.9", "S:cal.exit=15.4", "S:cal.done=fly",
    )

    @Test
    fun summaryFromGet() {
        feed("S:cal=on", "S:cal.gain=20", "S:cal.floor=-51.6", "S:cal.state=idle", "S:cal.floors=yes")
        assertTrue(c.on)
        assertEquals(20, c.gain)
        assertEquals(-51.6f, c.floorDb!!, 0.01f)
        assertTrue(c.haveFloors)
        assertFalse(c.busy)
    }

    @Test
    fun presetReference() {
        feed("S:cal=off", "S:cal.ref=20.0/13.0")
        assertEquals(20f, c.refEnterDb!!, 0.01f)
        assertEquals(13f, c.refExitDb!!, 0.01f)
        assertEquals(Presets.Kind.MED, Presets.of(20f, 13f, 20f, 13f))
        assertEquals(Presets.Kind.HIGH, Presets.of(17f, 10f, 20f, 13f))
        assertEquals(Presets.Kind.CUSTOM, Presets.of(18f, 13f, 20f, 13f))
        assertEquals(24.9f to 18.4f, Presets.apply(Presets.Kind.LOW, 21.9f, 15.4f).let { (e, x) -> (e * 10).toInt() / 10f to (x * 10).toInt() / 10f })
        assertEquals(0f, Presets.apply(Presets.Kind.HIGH, 18f, 2f).second, 0.001f) // never under the floor
    }

    @Test
    fun floorSweep() {
        feed("S:cal=on", "S:cal.floor=-51.6")
        assertEquals(listOf(Event.FloorStarted), feed("S:cal.started=floor"))
        assertEquals(Step.FLOOR, c.step)
        feed("S:cal.floor_at=40/84")
        assertEquals(40, c.floorAt)
        assertEquals(84, c.floorOf)
        val e = feed("S:cal.floors=84 gains at 5658 MHz", "S:cal.floor=-52.7", "S:cal.floor_gain=40", "S:cal.done=floor")
        assertEquals(listOf(Event.FloorDone(-52.7f)), e)
        assertEquals(Step.IDLE, c.step)
        assertTrue(c.haveFloors)
        assertEquals(-51.6f, c.floorDb!!, 0.01f) // the calibrated floor, not the sweep's
    }

    @Test
    fun autoCalibrationInTwoRounds() {
        assertEquals(listOf(Event.RoundStarted(1, 20)), feed("S:cal.started=fly", "S:cal.round=1 20"))
        assertEquals(Step.FLY, c.step)
        assertEquals(listOf(Event.Pass(1)), feed("S:cal.fly_s=5", "S:cal.live_passes=1"))
        feed("S:cal.live_passes=2", "S:cal.live_passes=3", "S:cal.live_passes=4", "S:cal.live_passes=5")
        assertEquals(5, c.livePasses)

        // Round 1 clipped: the timer starts round 2 at gain 12 by itself.
        val r1 = feed(
            "S:cal.live_passes=6", "S:cal.result=lower", "S:cal.flown_gain=20", "S:cal.next_gain=12",
            "S:cal.passes=6", "S:cal.clipped=4", "S:cal.done=fly",
        )
        assertEquals(Event.Pass(6), r1[0])
        val done1 = r1[1] as Event.RoundDone
        assertEquals(Verdict.LOWER, done1.result.verdict)
        assertEquals(12, done1.result.nextGain)
        assertEquals(Step.FLY, c.step) // still in "cal auto"

        assertEquals(listOf(Event.RoundStarted(2, 12)), feed("S:cal.started=fly", "S:cal.round=2 12"))
        assertEquals(0, c.livePasses)
        val end = feed(
            *okResult, "S:cal=on", "S:cal.gain=20", "S:cal.floor=-51.6", "S:enter=21.9", "S:exit=15.4",
            "S:cal.done=apply", "S:cal.auto=ok", "S:cal.done=auto",
        )
        assertEquals(3, end.size)
        assertEquals(Verdict.OK, (end[0] as Event.RoundDone).result.verdict)
        assertEquals(Event.Applied(20, 21.9f, 15.4f), end[1])
        val auto = end[2] as Event.AutoDone
        assertTrue(auto.ok)
        assertEquals(26.0f, auto.result!!.contrastDb!!, 0.01f)
        assertTrue(c.on)
        assertEquals(Step.IDLE, c.step)
    }

    @Test
    fun singleFlightEndsIdle() {
        assertEquals(listOf(Event.RoundStarted(0, 24)), feed("S:cal.started=fly", "S:cal.round=0 24"))
        val e = feed(*okResult)
        assertEquals(Verdict.OK, (e.single() as Event.RoundDone).result.verdict)
        assertEquals(Step.IDLE, c.step)
        assertEquals(21.9f, c.last!!.enterDb!!, 0.01f)
    }

    @Test
    fun failedAuto() {
        feed("S:cal.started=fly", "S:cal.round=3 36")
        val e = feed("S:cal.result=more", "S:cal.passes=1", "S:cal.done=fly", "S:cal.auto=failed", "S:cal.done=auto")
        val auto = e.last() as Event.AutoDone
        assertFalse(auto.ok)
        assertEquals(Verdict.MORE, auto.result!!.verdict)
        assertEquals("Calibration didn't finish: not enough passes through the gate. You can land.", CalibrationScript.line(auto))
    }

    @Test
    fun nanFieldsAreNull() {
        feed("S:cal.started=fly", "S:cal.round=0 20")
        val e = feed("S:cal.result=more", "S:cal.passes=0", "S:cal.pass=nan", "S:cal.headroom=nan", "S:cal.done=fly")
        val r = (e.single() as Event.RoundDone).result
        assertNull(r.passDb)
        assertNull(r.headroomDb)
    }

    @Test
    fun clearAndAbort() {
        feed("S:cal=on", "S:cal.gain=20")
        assertEquals(listOf(Event.Cleared), feed("S:cal=off", "S:cal.done=clear"))
        assertFalse(c.on)
        feed("S:cal.started=fly", "S:cal.round=1 20")
        assertEquals(listOf(Event.Aborted), feed("S:cal.done=aborted"))
        assertFalse(c.busy)
    }

    @Test
    fun spokenLines() {
        assertEquals("Recording. Fly laps through the gate.", CalibrationScript.line(Event.RoundStarted(1, 20)))
        assertEquals("Round 2. Recording at gain 12.", CalibrationScript.line(Event.RoundStarted(2, 12)))
        assertEquals("Three", CalibrationScript.line(Event.Pass(3)))
        assertNull(CalibrationScript.line(Event.Pass(11)))
        feed("S:cal.started=fly", "S:cal.round=1 20")
        val lower = feed("S:cal.result=lower", "S:cal.next_gain=12", "S:cal.done=fly").single()
        assertEquals("Gain changed to 12. Keep flying.", CalibrationScript.line(lower))
        val ok = feed(*okResult, "S:cal.auto=ok", "S:cal.done=auto").last()
        assertEquals("Calibrated. Contrast 26 dB. You can land.", CalibrationScript.line(ok))
    }
}
