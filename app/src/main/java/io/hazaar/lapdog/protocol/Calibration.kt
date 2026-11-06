package io.hazaar.lapdog.protocol

/**
 * The timer's receiver calibration (lapdog-firmware src/calibration.h), from its
 * `S:cal...` lines. "get" brings the summary (cal, cal.gain, cal.floor, cal.state,
 * cal.floors); a running calibration streams progress and results. Each line is
 * folded in with [with], which also says if it completed an [Event].
 *
 * A result is a run of lines ending with `cal.done=<step>`: they're collected in
 * [pending] and become an event at the done line. `S:enter` / `S:exit` inside an
 * apply run are settings as well; pass those through [withSetting].
 */
data class Calibration(
    /** Calibrated: the detector runs from a fixed floor with calibrated thresholds. */
    val on: Boolean = false,
    val gain: Int? = null,
    val floorDb: Float? = null,
    /** The timer has a floor measurement for its current channel (needed to fly). */
    val haveFloors: Boolean = false,
    val step: Step = Step.IDLE,
    /** Rounds of "cal auto" (0 while idle or for a single "cal fly"). */
    val round: Int = 0,
    val flyGain: Int? = null,
    val floorAt: Int = 0,
    val floorOf: Int = 0,
    val livePasses: Int = 0,
    val flySeconds: Int = 0,
    val last: Result? = null,
    private val pending: Map<String, String> = emptyMap(),
) {
    enum class Step { IDLE, FLOOR, FLY }

    val busy get() = step != Step.IDLE

    enum class Verdict(val key: String) {
        OK("ok"), LOWER("lower"), RAISE("raise"), MORE("more");

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key }
        }
    }

    /** One flight's analysis and the advice that follows from it. */
    data class Result(
        val verdict: Verdict,
        val flownGain: Int,
        val nextGain: Int,
        val passes: Int,
        val clippedPasses: Int,
        val inFlightDb: Float?,
        val passDb: Float?,
        val contrastDb: Float?,
        val headroomDb: Float?,
        val lowContrast: Boolean,
        val enterDb: Float?,
        val exitDb: Float?,
    )

    sealed interface Event {
        data object FloorStarted : Event
        data class FloorDone(val floorDb: Float?) : Event
        data class RoundStarted(val round: Int, val gain: Int) : Event
        data class Pass(val count: Int) : Event
        data class RoundDone(val result: Result) : Event
        data class Applied(val gain: Int, val enterDb: Float?, val exitDb: Float?) : Event
        data class AutoDone(val ok: Boolean, val result: Result?) : Event
        data object Cleared : Event
        data object Aborted : Event
        data class Failed(val reason: String) : Event
    }

    /** Folds in one `S:` line whose key [isCalKey]; returns the new state and any event it completed. */
    fun with(key: String, value: String): Pair<Calibration, Event?> = when (key) {
        "cal" -> copy(on = value == "on") to null
        "cal.gain" -> copy(gain = value.toIntOrNull(), pending = pending + (key to value)) to null
        // During a sweep it's the floor at the gain in use, not the calibrated one.
        "cal.floor" -> (if (step == Step.FLOOR) copy(pending = pending + (key to value))
            else copy(floorDb = value.toFloatOrNull(), pending = pending + (key to value))) to null
        "cal.floors" -> copy(haveFloors = value == "yes" || value.contains("gains")) to null
        "cal.state" -> copy(
            step = when (value) { "floor" -> Step.FLOOR; "fly", "auto" -> Step.FLY; else -> Step.IDLE },
        ) to null
        "cal.started" ->
            if (value == "floor") copy(step = Step.FLOOR, floorAt = 0, floorOf = 0, pending = emptyMap()) to Event.FloorStarted
            else copy(step = Step.FLY, livePasses = 0, flySeconds = 0, pending = emptyMap()) to null // "round" follows
        "cal.round" -> {
            val parts = value.trim().split(' ')
            val r = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val g = parts.getOrNull(1)?.toIntOrNull() ?: 0
            copy(step = Step.FLY, round = r, flyGain = g, livePasses = 0, flySeconds = 0) to Event.RoundStarted(r, g)
        }
        "cal.floor_at" -> {
            val parts = value.split('/')
            copy(floorAt = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: floorAt,
                floorOf = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: floorOf) to null
        }
        "cal.live_passes" -> {
            val n = value.toIntOrNull() ?: livePasses
            copy(livePasses = n) to Event.Pass(n)
        }
        "cal.fly_s" -> copy(flySeconds = value.toIntOrNull() ?: flySeconds) to null
        "cal.error" -> copy(step = Step.IDLE, round = 0, pending = emptyMap()) to Event.Failed(value)
        "cal.done" -> done(value)
        else -> copy(pending = pending + (key to value)) to null // result fields, warnings, ...
    }

    fun withSetting(key: String, value: String): Calibration =
        if (pending.isNotEmpty() && (key == "enter" || key == "exit")) copy(pending = pending + (key to value)) else this

    private fun done(what: String): Pair<Calibration, Event?> = when (what) {
        "floor" -> copy(step = Step.IDLE, haveFloors = true, floorAt = floorOf, pending = emptyMap()) to
            Event.FloorDone(pending["cal.floor"]?.toFloatOrNull())
        "fly" -> {
            val r = resultOf(pending)
            // In "cal auto" (round > 0) the next round or the apply follows; a single flight ends here.
            copy(last = r ?: last, step = if (round > 0) Step.FLY else Step.IDLE, pending = emptyMap()) to
                r?.let(Event::RoundDone)
        }
        "apply" -> {
            val g = pending["cal.gain"]?.toIntOrNull() ?: gain
            copy(on = true, gain = g, floorDb = pending["cal.floor"]?.toFloatOrNull() ?: floorDb, pending = emptyMap()) to
                Event.Applied(g ?: 0, pending["enter"]?.toFloatOrNull(), pending["exit"]?.toFloatOrNull())
        }
        "auto" -> copy(step = Step.IDLE, round = 0, pending = emptyMap()) to Event.AutoDone(pending["cal.auto"] == "ok", last)
        "clear" -> copy(on = false, gain = null, floorDb = null, pending = emptyMap()) to Event.Cleared
        "aborted" -> copy(step = Step.IDLE, round = 0, pending = emptyMap()) to Event.Aborted
        else -> copy(pending = emptyMap()) to null
    }

    companion object {
        fun isCalKey(key: String) = key == "cal" || key.startsWith("cal.")

        private fun resultOf(p: Map<String, String>): Result? {
            val verdict = Verdict.of(p["cal.result"]) ?: return null
            fun f(k: String) = p[k]?.toFloatOrNull()?.takeIf { !it.isNaN() }
            fun i(k: String) = p[k]?.toIntOrNull()
            return Result(
                verdict = verdict,
                flownGain = i("cal.flown_gain") ?: 0,
                nextGain = i("cal.next_gain") ?: i("cal.flown_gain") ?: 0,
                passes = i("cal.passes") ?: 0,
                clippedPasses = i("cal.clipped") ?: 0,
                inFlightDb = f("cal.inflight"),
                passDb = f("cal.pass"),
                contrastDb = f("cal.contrast"),
                headroomDb = f("cal.headroom"),
                lowContrast = p["cal.low_contrast"] == "1",
                enterDb = f("cal.enter"),
                exitDb = f("cal.exit"),
            )
        }
    }
}
