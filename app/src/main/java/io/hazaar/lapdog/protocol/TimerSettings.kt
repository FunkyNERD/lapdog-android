package io.hazaar.lapdog.protocol

/**
 * The timer's settings, as its USB console prints them ("get" -> `S:<key>=<value>`)
 * and takes them ("set <key> <value>"). Key names match lapdog-firmware's
 * include/timer_settings.h. The timer is the source of truth: the app reads these,
 * lets the pilot edit a copy, and writes back only what changed.
 */
data class TimerSettings(
    val chan: String = "",
    val freqMhz: Int = 5800,
    val enterDb: Float = 10f,
    val exitDb: Float = 5f,
    val alpha: Float = 0.2f,
    val minLapMs: Int = 3000,
    val link: Link = Link.BLE,
    val bindPhrase: String = "",
    val event: String = "",
    val heatPrefix: String = "Heat",
    val nextHeat: Int = 1,
    val msgEvent: Boolean = true,
    val msgHeat: Boolean = true,
    val msgStart: Boolean = true,
    val msgLaps: Boolean = true,
    val msgBest: Boolean = true,
    val textStage: String = "ARM NOW",
    val textGo: String = "GO!",
    val textFinish: String = "FINISHED!",
    val mode: LapFrame.Mode = LapFrame.Mode.PRACTICE,
    val raceLimit: RaceLimit = RaceLimit.LAPS,
    val raceLaps: Int = 3,
    val raceTimeS: Int = 120,
    val randomHold: Boolean = true,
    val firmware: String = "",
    /** "idle", "practice" or "race" when read. */
    val state: String = "idle",
) {
    enum class Link(val key: String) { BLE("ble"), ESPNOW("espnow") }
    enum class RaceLimit(val key: String) { LAPS("laps"), TIME("time") }

    enum class Sensitivity(val label: String, val enterDb: Float, val exitDb: Float) {
        LOW("Low", 14f, 8f), MED("Med", 10f, 5f), HIGH("High", 6f, 3f), CUSTOM("Custom", 12f, 6f);

        companion object {
            fun of(enterDb: Float, exitDb: Float) =
                entries.firstOrNull { it != CUSTOM && it.enterDb == enterDb && it.exitDb == exitDb } ?: CUSTOM
        }
    }

    val sensitivity get() = Sensitivity.of(enterDb, exitDb)
    val channelLabel get() = chan.ifEmpty { Channels.labelOf(freqMhz) ?: "$freqMhz" }

    /**
     * The commands that turn [current] into this, followed by "save". Empty when
     * nothing changed. Thresholds are ordered so the timer's enter > exit check
     * holds after every single command.
     */
    fun commandsFrom(current: TimerSettings): List<String> {
        val out = mutableListOf<String>()
        fun set(key: String, v: Any, old: Any) { if (v != old) out += "set $key $v" }
        fun onOff(b: Boolean) = if (b) "on" else "off"

        if (chan.isNotEmpty() && chan != current.chan) out += "set chan $chan"
        else if (chan.isEmpty()) set("freq", freqMhz, current.freqMhz)
        if (enterDb != current.enterDb || exitDb != current.exitDb) {
            val enter = "set enter ${fmt(enterDb)}"
            val exit = "set exit ${fmt(exitDb)}"
            if (enterDb > current.exitDb) { out += enter; out += exit } else { out += exit; out += enter }
        }
        set("alpha", fmt2(alpha), fmt2(current.alpha))
        set("minlap", minLapMs, current.minLapMs)
        set("link", link.key, current.link.key)
        set("phrase", bindPhrase, current.bindPhrase)
        set("event", event, current.event)
        set("prefix", heatPrefix, current.heatPrefix)
        set("heat", nextHeat, current.nextHeat)
        set("msg.event", onOff(msgEvent), onOff(current.msgEvent))
        set("msg.heat", onOff(msgHeat), onOff(current.msgHeat))
        set("msg.start", onOff(msgStart), onOff(current.msgStart))
        set("msg.laps", onOff(msgLaps), onOff(current.msgLaps))
        set("msg.best", onOff(msgBest), onOff(current.msgBest))
        set("text.stage", textStage, current.textStage)
        set("text.go", textGo, current.textGo)
        set("text.finish", textFinish, current.textFinish)
        set("mode", mode.name.lowercase(), current.mode.name.lowercase())
        set("race.limit", raceLimit.key, current.raceLimit.key)
        set("race.laps", raceLaps, current.raceLaps)
        set("race.time", raceTimeS, current.raceTimeS)
        set("race.hold", onOff(randomHold), onOff(current.randomHold))
        if (out.isNotEmpty()) out += "save"
        return out.map { it.trimEnd() } // "set event " with an empty value clears it
    }

    /** Applies one `S:` line; unknown keys and bad values leave it unchanged. */
    fun with(key: String, v: String): TimerSettings = when (key) {
        "chan" -> copy(chan = v)
        "freq" -> v.toIntOrNull()?.let { copy(freqMhz = it) } ?: this
        "enter" -> v.toFloatOrNull()?.let { copy(enterDb = it) } ?: this
        "exit" -> v.toFloatOrNull()?.let { copy(exitDb = it) } ?: this
        "alpha" -> v.toFloatOrNull()?.let { copy(alpha = it) } ?: this
        "minlap" -> v.toIntOrNull()?.let { copy(minLapMs = it) } ?: this
        "link" -> Link.entries.firstOrNull { it.key == v }?.let { copy(link = it) } ?: this
        "phrase" -> copy(bindPhrase = v)
        "event" -> copy(event = v)
        "prefix" -> copy(heatPrefix = v)
        "heat" -> v.toIntOrNull()?.let { copy(nextHeat = it) } ?: this
        "msg.event" -> copy(msgEvent = v == "on")
        "msg.heat" -> copy(msgHeat = v == "on")
        "msg.start" -> copy(msgStart = v == "on")
        "msg.laps" -> copy(msgLaps = v == "on")
        "msg.best" -> copy(msgBest = v == "on")
        "text.stage" -> copy(textStage = v)
        "text.go" -> copy(textGo = v)
        "text.finish" -> copy(textFinish = v)
        "mode" -> copy(mode = if (v == "race") LapFrame.Mode.RACE else LapFrame.Mode.PRACTICE)
        "race.limit" -> RaceLimit.entries.firstOrNull { it.key == v }?.let { copy(raceLimit = it) } ?: this
        "race.laps" -> v.toIntOrNull()?.let { copy(raceLaps = it) } ?: this
        "race.time" -> v.toIntOrNull()?.let { copy(raceTimeS = it) } ?: this
        "race.hold" -> copy(randomHold = v == "on")
        "fw" -> copy(firmware = v)
        "state" -> copy(state = v)
        else -> this
    }

    companion object {
        const val EVENT_MAX = 40
        const val PREFIX_MAX = 16
        const val MSG_MAX = 16

        private fun fmt(v: Float) = "%.1f".format(java.util.Locale.ROOT, v)
        private fun fmt2(v: Float) = "%.2f".format(java.util.Locale.ROOT, v)
    }
}
