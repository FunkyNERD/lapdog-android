package io.hazaar.lapdog.protocol

/**
 * The one LapDog message frame, identical on every transport (USB `F:<hex>` lines,
 * BLE manufacturer data). Mirrors lapdog-firmware's include/lap_frame.h (version 2) -
 * keep in sync; LapFrameTest shares its hex vectors with the firmware's test_frame.
 *
 * At most [MAX_SIZE] = 24 bytes, so one frame fits a 31-byte legacy advert
 * (3 bytes of flags + 4 of manufacturer-data header). Longer text is chunked.
 *
 *   [0] version (high nibble) | type (low nibble)
 *   [1] seq u8    - per-frame counter; de-dupes BLE repeats and USB+BLE copies
 *   [2] pilot u8
 *   LAP       [3] lap (0 = start / holeshot) [4] flags [5..8] ms u32
 *   ARMED     [3] mode [4..5] heat u16 [6] lap limit [7..8] time limit s u16
 *   DISARMED  [3..4] heat u16 [5] laps completed
 *   HEARTBEAT [3] stage [4] mode [5..6] heat u16 [7..10] uptime ms u32
 *   RACE      [3] stage [4..7] race time ms u32 (since GO)
 *   TEXT      [3] slot [4] part<<4 | parts [5..] text (<= 19 bytes)
 */
sealed interface LapFrame {
    val seq: Int
    val pilot: Int

    data class Lap(override val seq: Int, override val pilot: Int, val lap: Int, val flags: Int, val ms: Long) : LapFrame {
        val best get() = flags and FLAG_BEST != 0
        val final get() = flags and FLAG_FINAL != 0
    }

    data class Armed(
        override val seq: Int, override val pilot: Int,
        val mode: Mode, val heat: Int, val lapLimit: Int, val timeLimitS: Int,
    ) : LapFrame

    data class Disarmed(override val seq: Int, override val pilot: Int, val heat: Int, val laps: Int) : LapFrame

    data class Heartbeat(
        override val seq: Int, override val pilot: Int,
        val stage: Stage, val mode: Mode, val heat: Int, val uptimeMs: Long,
    ) : LapFrame

    data class Race(override val seq: Int, override val pilot: Int, val stage: Stage, val raceMs: Long) : LapFrame

    data class Text(
        override val seq: Int, override val pilot: Int,
        val slot: Int, val part: Int, val parts: Int, val text: String,
    ) : LapFrame

    enum class Mode { PRACTICE, RACE; companion object { fun of(v: Int) = if (v == 1) RACE else PRACTICE } }

    enum class Stage {
        IDLE, PRACTICE, STAGING, RACING, FINISHED;

        companion object { fun of(v: Int) = entries.getOrElse(v) { IDLE } }
    }

    companion object {
        const val VERSION = 2
        const val HEADER = 3
        const val MAX_SIZE = 24
        const val MAX_TEXT_CHUNK = MAX_SIZE - HEADER - 2
        const val BLE_COMPANY_ID = 0xFFFF // test ID until there's a real one

        const val LAP = 1
        const val ARMED = 2
        const val DISARMED = 3
        const val HEARTBEAT = 4
        const val RACE = 5
        const val TEXT = 6

        const val FLAG_BEST = 0x01
        const val FLAG_FINAL = 0x02

        /** TEXT slots: 0..17 are VRxC_ELRS OSD rows, these are the rest. */
        const val SLOT_OSD_CLEAR = 0x7F
        const val SLOT_EVENT = 0x80
        const val SLOT_HEAT_PREFIX = 0x81

        private fun bodySize(type: Int) = when (type) {
            LAP -> 6; ARMED -> 6; DISARMED -> 3; HEARTBEAT -> 8; RACE -> 5; TEXT -> 2; else -> 0
        }

        fun decode(b: ByteArray, offset: Int = 0, length: Int = b.size - offset): LapFrame? {
            if (length < HEADER || length > MAX_SIZE) return null
            fun u8(i: Int) = b[offset + i].toInt() and 0xFF
            fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
            fun u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
            if (u8(0) shr 4 != VERSION) return null
            val type = u8(0) and 0x0F
            val body = bodySize(type)
            if (body == 0 || length < HEADER + body) return null
            val seq = u8(1)
            val pilot = u8(2)
            return when (type) {
                LAP -> Lap(seq, pilot, u8(3), u8(4), u32(5))
                ARMED -> Armed(seq, pilot, Mode.of(u8(3)), u16(4), u8(6), u16(7))
                DISARMED -> Disarmed(seq, pilot, u16(3), u8(5))
                HEARTBEAT -> Heartbeat(seq, pilot, Stage.of(u8(3)), Mode.of(u8(4)), u16(5), u32(7))
                RACE -> Race(seq, pilot, Stage.of(u8(3)), u32(4))
                else -> {
                    val part = u8(4) shr 4
                    val parts = u8(4) and 0x0F
                    if (parts == 0 || part >= parts) return null
                    val text = String(b, offset + HEADER + body, length - HEADER - body, Charsets.ISO_8859_1)
                    Text(seq, pilot, u8(3), part, parts, text)
                }
            }
        }

        fun encode(f: LapFrame): ByteArray {
            val out = ArrayList<Byte>(MAX_SIZE)
            fun u8(v: Int) { out += v.toByte() }
            fun u16(v: Int) { u8(v); u8(v shr 8) }
            fun u32(v: Long) { u16(v.toInt()); u16((v shr 16).toInt()) }
            val type = when (f) {
                is Lap -> LAP; is Armed -> ARMED; is Disarmed -> DISARMED
                is Heartbeat -> HEARTBEAT; is Race -> RACE; is Text -> TEXT
            }
            u8((VERSION shl 4) or type); u8(f.seq); u8(f.pilot)
            when (f) {
                is Lap -> { u8(f.lap); u8(f.flags); u32(f.ms) }
                is Armed -> { u8(f.mode.ordinal); u16(f.heat); u8(f.lapLimit); u16(f.timeLimitS) }
                is Disarmed -> { u16(f.heat); u8(f.laps) }
                is Heartbeat -> { u8(f.stage.ordinal); u8(f.mode.ordinal); u16(f.heat); u32(f.uptimeMs) }
                is Race -> { u8(f.stage.ordinal); u32(f.raceMs) }
                is Text -> {
                    u8(f.slot); u8((f.part shl 4) or f.parts)
                    f.text.toByteArray(Charsets.ISO_8859_1).take(MAX_TEXT_CHUNK).forEach { out += it }
                }
            }
            return out.toByteArray()
        }

        fun fromHex(hex: String): LapFrame? {
            if (hex.length % 2 != 0 || hex.length / 2 > MAX_SIZE) return null
            val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null }
            return decode(bytes)
        }

        fun toHex(f: LapFrame) = encode(f).joinToString("") { "%02X".format(it) }
    }
}
