package io.hazaar.lapdog.protocol

import kotlin.math.abs

/**
 * Gate sensitivity presets as the timer defines them (lapdog-firmware include/presets.h):
 * steps around a reference, the calibrated enter/exit or else the timer's defaults, all
 * in dB above the receiver floor. Low = +3 dB (fewer false reads), Medium = the
 * reference, High = -3 dB (catches weaker passes).
 */
object Presets {
    const val STEP_DB = 3f

    enum class Kind(val label: String, val offsetDb: Float) {
        LOW("Low", STEP_DB), MED("Med", 0f), HIGH("High", -STEP_DB), CUSTOM("Custom", 0f)
    }

    /** enter to exit for [k] around the reference. */
    fun apply(k: Kind, refEnter: Float, refExit: Float): Pair<Float, Float> =
        (refEnter + k.offsetDb) to (refExit + k.offsetDb).coerceAtLeast(0f)

    fun of(enter: Float, exit: Float, refEnter: Float, refExit: Float): Kind =
        Kind.entries.firstOrNull { k ->
            k != Kind.CUSTOM && apply(k, refEnter, refExit).let { (e, x) -> abs(e - enter) < 0.05f && abs(x - exit) < 0.05f }
        } ?: Kind.CUSTOM
}
