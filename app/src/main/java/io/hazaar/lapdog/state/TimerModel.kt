package io.hazaar.lapdog.state

import io.hazaar.lapdog.protocol.LapFrame
import io.hazaar.lapdog.protocol.LapFrame.Mode
import io.hazaar.lapdog.protocol.LapFrame.Stage
import io.hazaar.lapdog.protocol.TextAssembler
import io.hazaar.lapdog.protocol.TimerSettings

/** How a message reached the app. */
enum class Via(val label: String) { USB("USB"), BLE("Bluetooth") }

/** The heat the timer is running, as far as the app has heard. */
data class Session(
    val heat: Int,
    val mode: Mode,
    val stage: Stage,
    val lapLimit: Int = 0,
    val timeLimitS: Int = 0,
    val holeshotMs: Long? = null,
    val laps: List<LapEntry> = emptyList(),
    /** Local clock (ms) at the last gate pass, i.e. the start of the lap in progress. */
    val lastPassAt: Long? = null,
    /** Local clock (ms) when GO was heard. */
    val goAt: Long? = null,
    /** Race time at the finish. */
    val raceMs: Long? = null,
    /** Passes the timer ignored this heat, by reason. */
    val ignored: Ignored = Ignored(),
) {
    val stats get() = LapStats(laps)
    val started get() = lastPassAt != null
}

/** Gate passes the timer's false-read protection rejected. */
data class Ignored(val tooSoon: Int = 0, val tooLong: Int = 0) {
    val total get() = tooSoon + tooLong

    /** "2 passes ignored (too soon after a lap)", null when none. */
    fun describe(): String? {
        if (total == 0) return null
        val what = if (total == 1) "1 pass ignored" else "$total passes ignored"
        val why = when {
            tooLong == 0 -> "too soon after a lap"
            tooSoon == 0 -> "quad parked at the gate"
            else -> "$tooSoon too soon, $tooLong parked"
        }
        return "$what ($why)"
    }

    fun add(reason: LapFrame.Reason) =
        if (reason == LapFrame.Reason.TOO_SOON) copy(tooSoon = tooSoon + 1) else copy(tooLong = tooLong + 1)
}

data class SessionSummary(val heat: Int, val mode: Mode, val laps: List<LapEntry>, val ignored: Ignored = Ignored()) {
    val stats get() = LapStats(laps)
}

data class TimerState(
    val usbConnected: Boolean = false,
    /** Read from the timer over USB ("get"); null until then. */
    val settings: TimerSettings? = null,
    val session: Session? = null,
    val lastSession: SessionSummary? = null,
    /** Event name and heat prefix as heard in TEXT frames (BLE); settings win when known. */
    val heardEvent: String? = null,
    val heardPrefix: String? = null,
    /** The timer's race messages, by VRxC_ELRS OSD row - what the goggles show. */
    val osdRows: Map<Int, String> = emptyMap(),
    val osdAt: Long? = null,
    val osdVia: Via? = null,
    val lastHeardAt: Long? = null,
    val lastHeardVia: Via? = null,
) {
    val armed get() = session != null && session.stage != Stage.IDLE
    val event get() = settings?.event ?: heardEvent ?: ""
    val heatPrefix get() = settings?.heatPrefix ?: heardPrefix ?: "Heat"

    /** The heat the next arm gets: from the timer if read, else one after the last heard. */
    val nextHeat: Int? get() = settings?.nextHeat ?: lastSession?.heat?.plus(1)

    fun heatName(heat: Int) = if (heatPrefix.isEmpty()) "$heat" else "$heatPrefix $heat"
}

/**
 * Folds everything the timer says into [TimerState]. Pure apart from the
 * caller-supplied clock, so it is unit-tested off-device.
 *
 * Frames arrive over USB and BLE at once, and BLE repeats each one several
 * times per burst, so anything with a seq seen in the last [DEDUPE_MS] is dropped.
 */
class TimerModel {
    var state = TimerState()
        private set

    private val seen = HashMap<Int, Long>()
    private val texts = TextAssembler()

    fun onUsb(connected: Boolean) {
        state = if (connected) state.copy(usbConnected = true) else state.copy(usbConnected = false, settings = null)
    }

    fun onSetting(key: String, value: String) {
        val s = (state.settings ?: TimerSettings()).with(key, value)
        state = state.copy(settings = s)
        // Connected mid-session or after a reboot: trust the timer's own idea of armed.
        if (key == "state" && value == "idle" && state.session != null) endSession()
    }

    /** Returns false if the frame was a duplicate. */
    fun onFrame(f: LapFrame, via: Via, now: Long): Boolean {
        seen.entries.removeAll { now - it.value > DEDUPE_MS }
        if (seen.containsKey(f.seq)) return false
        seen[f.seq] = now
        state = state.copy(lastHeardAt = now, lastHeardVia = via)
        when (f) {
            is LapFrame.Armed -> armed(f)
            is LapFrame.Disarmed -> endSession(f.ignored)
            is LapFrame.Ignored -> ignored(f, now)
            is LapFrame.Heartbeat -> heartbeat(f)
            is LapFrame.Race -> race(f, now)
            is LapFrame.Lap -> lap(f, now)
            is LapFrame.Text -> texts.add(f)?.let { text(f.slot, it, via, now) }
        }
        return true
    }

    private fun armed(f: LapFrame.Armed) {
        val stage = if (f.mode == Mode.RACE) Stage.STAGING else Stage.PRACTICE
        state = state.copy(
            session = Session(f.heat, f.mode, stage, f.lapLimit, f.timeLimitS),
            // The timer advanced its heat counter when it armed.
            settings = state.settings?.let { it.copy(nextHeat = maxOf(it.nextHeat, f.heat + 1), state = f.mode.name.lowercase()) },
        )
    }

    private fun endSession(timerIgnored: Int? = null) {
        val s = state.session ?: return
        // If BLE missed some IGNORED bursts, the timer's total wins; unknown ones count as too soon.
        val ig = timerIgnored?.takeIf { it > s.ignored.total }
            ?.let { s.ignored.copy(tooSoon = it - s.ignored.tooLong) } ?: s.ignored
        state = state.copy(
            session = null,
            lastSession = if (s.started || ig.total > 0) SessionSummary(s.heat, s.mode, s.laps, ig) else state.lastSession,
            settings = state.settings?.copy(state = "idle"),
        )
    }

    private fun heartbeat(f: LapFrame.Heartbeat) {
        val s = state.session
        if (f.stage == Stage.IDLE) return endSession()
        state = state.copy(
            session = if (s == null || s.heat != f.heat) Session(f.heat, f.mode, f.stage) else s.copy(stage = f.stage),
        )
    }

    private fun ignored(f: LapFrame.Ignored, now: Long) {
        val s = current(now)
        var ig = s.ignored.add(f.reason)
        // The frame carries the heat's running total, so missed bursts still count.
        if (f.count > ig.total) ig = if (f.reason == LapFrame.Reason.TOO_SOON) ig.copy(tooSoon = f.count - ig.tooLong) else ig.copy(tooLong = f.count - ig.tooSoon)
        state = state.copy(session = s.copy(ignored = ig))
    }

    private fun current(now: Long): Session = state.session ?: Session(0, Mode.PRACTICE, Stage.PRACTICE)

    private fun race(f: LapFrame.Race, now: Long) {
        val s = current(now).copy(mode = Mode.RACE)
        state = state.copy(
            session = when (f.stage) {
                Stage.RACING -> s.copy(stage = f.stage, goAt = s.goAt ?: (now - f.raceMs))
                Stage.FINISHED -> s.copy(stage = f.stage, raceMs = f.raceMs)
                else -> s.copy(stage = f.stage)
            },
        )
    }

    private fun lap(f: LapFrame.Lap, now: Long) {
        var s = current(now)
        s = if (f.lap == 0) {
            s.copy(lastPassAt = now, holeshotMs = if (s.mode == Mode.RACE) f.ms else null)
        } else {
            val laps = (s.laps.filter { it.lap != f.lap } + LapEntry(f.lap, f.ms)).sortedBy { it.lap }
            s.copy(laps = laps, lastPassAt = now, stage = if (f.final) Stage.FINISHED else s.stage)
        }
        state = state.copy(session = s)
    }

    private fun text(slot: Int, text: String, via: Via, now: Long) {
        state = when (slot) {
            LapFrame.SLOT_EVENT -> state.copy(heardEvent = text)
            LapFrame.SLOT_HEAT_PREFIX -> state.copy(heardPrefix = text)
            LapFrame.SLOT_OSD_CLEAR -> state.copy(osdRows = emptyMap(), osdAt = now, osdVia = via)
            in 0 until OSD_ROWS -> state.copy(
                osdRows = if (text.isEmpty()) state.osdRows - slot else state.osdRows + (slot to text),
                osdAt = now, osdVia = via,
            )
            else -> state
        }
    }

    companion object {
        const val DEDUPE_MS = 5_000L
        const val OSD_ROWS = 18
    }
}
