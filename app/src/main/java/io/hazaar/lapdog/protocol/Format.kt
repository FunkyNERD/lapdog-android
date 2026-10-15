package io.hazaar.lapdog.protocol

import kotlin.math.abs

/** Lap time formatting shared by every screen; matches the timer's OSD text. */
object Format {
    /** 14630 -> "0:14.630" */
    fun lap(ms: Long) = "%d:%02d.%03d".format(ms / 60000, (ms / 1000) % 60, ms % 1000)

    /** Running clock, tenths: 8412 -> "0:08.4" */
    fun running(ms: Long): String {
        val t = ms.coerceAtLeast(0) / 100
        return "%d:%02d.%d".format(t / 600, (t / 10) % 60, t % 10)
    }

    /** 300 -> "+0.300", -120 -> "-0.120" */
    fun delta(ms: Long) = (if (ms < 0) "-" else "+") + "%d.%03d".format(abs(ms) / 1000, abs(ms) % 1000)

    /**
     * An OSD row as it reads on the goggles. HDZero's font draws lone lower-case letters as
     * symbols, and timers wrap messages in them ("w ARM NOW x", "x L2:17.62 | T35.24 w"): those
     * are dropped and the padding collapsed, the same way RacePanel reads them.
     */
    fun osd(text: String): String =
        text.split(' ').filter { it.isNotEmpty() && !(it.length == 1 && it[0] in 'a'..'z') }.joinToString(" ")

    /** Race time limit: 120 -> "2:00" */
    fun minutes(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    /** "2:00" or "120" -> 120 */
    fun parseMinutes(text: String): Int? {
        val t = text.trim()
        if (':' !in t) return t.toIntOrNull()
        val (m, s) = t.split(':', limit = 2)
        val mi = m.toIntOrNull() ?: return null
        val si = s.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return mi * 60 + si
    }
}
