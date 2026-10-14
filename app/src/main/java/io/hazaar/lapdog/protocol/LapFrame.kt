package io.hazaar.lapdog.protocol

/**
 * The one LapDog message frame, identical on every transport (USB `F:<hex>` lines,
 * BLE manufacturer data). Mirrors lapdog-firmware's include/lap_frame.h - keep in sync.
 *
 *   [0] version (high nibble) | type (low nibble)
 *   [1] seq u8      - per-frame counter, de-dupes repeated BLE adverts
 *   [2] pilot u8
 *   [3..6] value u32 LE - lap_ms for LAP, device uptime_ms otherwise
 */
data class LapFrame(val type: Type, val seq: Int, val pilot: Int, val value: Long) {

    enum class Type(val code: Int) {
        LAP(1), ARMED(2), DISARMED(3), HEARTBEAT(4);

        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code }
        }
    }

    companion object {
        const val VERSION = 1
        const val SIZE = 7
        const val BLE_COMPANY_ID = 0xFFFF // test ID until there's a real one

        fun decode(b: ByteArray, offset: Int = 0): LapFrame? {
            if (b.size - offset < SIZE) return null
            val head = b[offset].toInt() and 0xFF
            if (head shr 4 != VERSION) return null
            val type = Type.of(head and 0x0F) ?: return null
            var value = 0L
            for (i in 0 until 4) value = value or ((b[offset + 3 + i].toLong() and 0xFF) shl (8 * i))
            return LapFrame(type, b[offset + 1].toInt() and 0xFF, b[offset + 2].toInt() and 0xFF, value)
        }

        /** Parses one USB text line; null for anything that isn't a frame (logs, OK/ERR, R: rssi). */
        fun fromLine(line: String): LapFrame? {
            val hex = line.trim().removePrefix("F:").takeIf { it.length == SIZE * 2 && line.trim().startsWith("F:") }
                ?: return null
            val bytes = ByteArray(SIZE) { i -> hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null }
            return decode(bytes)
        }
    }
}
