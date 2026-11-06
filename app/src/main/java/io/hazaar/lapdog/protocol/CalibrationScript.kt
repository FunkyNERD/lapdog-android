package io.hazaar.lapdog.protocol

import io.hazaar.lapdog.protocol.Calibration.Event
import io.hazaar.lapdog.protocol.Calibration.Verdict
import kotlin.math.roundToInt

/**
 * What the phone says during a guided calibration, so a pilot with goggles on knows
 * what to do. Pure text, so it's unit-tested; Voice speaks it, the screen shows it.
 */
object CalibrationScript {
    const val COUNTDOWN_S = 20
    const val TARGET_PASSES = 6 // calib::AUTO_PASSES in the firmware

    val start = "Calibration. Keep the quad's battery out. Measuring the floor."
    val takeOff = "Floor done. Plug in the battery and take off. Recording starts in $COUNTDOWN_S seconds."
    val tenSeconds = "Ten seconds."
    val cancelled = "Calibration cancelled."
    val notConnected = "The timer isn't connected."

    private val numbers = listOf("One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")

    /** The line for [e], or null to stay quiet. */
    fun line(e: Event): String? = when (e) {
        Event.FloorStarted -> null // [start] already said it
        is Event.FloorDone -> null // the guide says [takeOff] and counts down
        is Event.RoundStarted ->
            if (e.round <= 1) "Recording. Fly laps through the gate."
            else "Round ${e.round}. Recording at gain ${e.gain}."
        is Event.Pass -> numbers.getOrNull(e.count - 1)
        is Event.RoundDone -> when (e.result.verdict) {
            Verdict.OK -> null // Applied / AutoDone follow
            Verdict.LOWER, Verdict.RAISE -> "Gain changed to ${e.result.nextGain}. Keep flying."
            Verdict.MORE -> "Not enough passes. Keep flying through the gate."
        }
        is Event.Applied -> null // AutoDone has the summary
        is Event.AutoDone -> done(e)
        Event.Cleared -> "Calibration cleared."
        Event.Aborted -> "Calibration stopped: the timer was armed."
        is Event.Failed -> "Calibration failed: ${e.reason}."
    }

    private fun done(e: Event.AutoDone): String {
        val r = e.result
        if (e.ok && r != null) {
            val contrast = r.contrastDb?.let { " Contrast ${it.roundToInt()} dB." } ?: ""
            val warn = if (r.lowContrast) " Contrast is low: laps may be missed." else ""
            return "Calibrated.$contrast$warn You can land."
        }
        val why = when (r?.verdict) {
            Verdict.MORE -> "not enough passes through the gate"
            Verdict.LOWER, Verdict.RAISE -> "the gain is still not right"
            else -> "no result"
        }
        return "Calibration didn't finish: $why. You can land."
    }
}
