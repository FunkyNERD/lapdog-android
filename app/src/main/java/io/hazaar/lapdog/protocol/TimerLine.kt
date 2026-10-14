package io.hazaar.lapdog.protocol

/**
 * One line from the timer's USB console (see lapdog-firmware src/console.h):
 *   F:<hex>        a LapDog frame
 *   S:<key>=<value> a setting, from "get"
 *   R:<dB>         live signal above baseline, while "rssi on"
 *   OK / ERR ...   reply to a command
 * Anything else (ESP-IDF log lines) is ignored.
 */
sealed interface TimerLine {
    data class Frame(val frame: LapFrame) : TimerLine
    data class Setting(val key: String, val value: String) : TimerLine
    data class Rssi(val db: Float) : TimerLine
    data class Reply(val ok: Boolean, val message: String) : TimerLine

    companion object {
        fun parse(raw: String): TimerLine? {
            val line = raw.trimEnd('\r', '\n')
            return when {
                line.startsWith("F:") -> LapFrame.fromHex(line.substring(2).trim())?.let(::Frame)
                line.startsWith("S:") -> line.substring(2).split('=', limit = 2)
                    .takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { Setting(it[0], it[1]) }
                line.startsWith("R:") -> line.substring(2).trim().toFloatOrNull()?.let(::Rssi)
                line == "OK" -> Reply(true, "")
                line == "ERR" || line.startsWith("ERR ") -> Reply(false, line.removePrefix("ERR").trim())
                else -> null
            }
        }
    }
}
