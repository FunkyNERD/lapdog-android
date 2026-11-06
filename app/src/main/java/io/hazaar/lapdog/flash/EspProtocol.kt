package io.hazaar.lapdog.flash

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ESP32 ROM serial loader's wire format, as esptool speaks it (esptool/loader.py,
 * targets/esp32c5.py): SLIP frames carrying
 *   request   00 op len16 checksum32 data
 *   response  01 op len16 value32 data, the ROM's last 4 data bytes being status
 *             (failed, error, 2 reserved).
 * Pure, so it's unit-tested off-device. See docs/dev/timer-firmware-update.md.
 */
object EspProtocol {
    const val SYNC = 0x08
    const val WRITE_REG = 0x09
    const val READ_REG = 0x0A
    const val SPI_SET_PARAMS = 0x0B
    const val SPI_ATTACH = 0x0D
    const val FLASH_DEFL_BEGIN = 0x10
    const val FLASH_DEFL_DATA = 0x11
    const val FLASH_DEFL_END = 0x12
    const val SPI_FLASH_MD5 = 0x13
    const val GET_SECURITY_INFO = 0x14

    /** The ROM writes flash in blocks of this many (uncompressed-erase and compressed-data) bytes. */
    const val FLASH_WRITE_SIZE = 0x400
    const val CHECKSUM_SEED = 0xEF
    const val ROM_STATUS_BYTES = 4

    /** IMAGE_CHIP_ID / GET_SECURITY_INFO chip ids. */
    const val CHIP_ID_ESP32C3 = 5
    const val CHIP_ID_ESP32C5 = 23

    private const val END = 0xC0.toByte()
    private const val ESC = 0xDB.toByte()
    private const val ESC_END = 0xDC.toByte()
    private const val ESC_ESC = 0xDD.toByte()

    fun slip(packet: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(packet.size + 8)
        out.write(END.toInt())
        for (b in packet) when (b) {
            END -> { out.write(ESC.toInt()); out.write(ESC_END.toInt()) }
            ESC -> { out.write(ESC.toInt()); out.write(ESC_ESC.toInt()) }
            else -> out.write(b.toInt())
        }
        out.write(END.toInt())
        return out.toByteArray()
    }

    /** Collects SLIP frames from a byte stream; bytes outside frames (boot log text) are dropped. */
    class SlipReader {
        private val frame = ByteArrayOutputStream()
        private var inFrame = false
        private var escaped = false

        fun feed(bytes: ByteArray, n: Int = bytes.size): List<ByteArray> {
            val out = mutableListOf<ByteArray>()
            for (i in 0 until n) {
                val b = bytes[i]
                if (!inFrame) {
                    if (b == END) { inFrame = true; frame.reset() }
                    continue
                }
                when {
                    escaped -> {
                        frame.write((if (b == ESC_END) END else if (b == ESC_ESC) ESC else b).toInt())
                        escaped = false
                    }
                    b == ESC -> escaped = true
                    b == END -> if (frame.size() > 0) {
                        out += frame.toByteArray()
                        frame.reset()
                        // Stay in a frame: the END that closes one may open the next.
                    }
                    else -> frame.write(b.toInt())
                }
            }
            return out
        }
    }

    fun checksum(data: ByteArray, from: Int = 0): Int {
        var c = CHECKSUM_SEED
        for (i in from until data.size) c = c xor (data[i].toInt() and 0xFF)
        return c
    }

    fun request(op: Int, data: ByteArray = ByteArray(0), checksum: Int = 0): ByteArray =
        le(8 + data.size).apply {
            put(0); put(op.toByte()); putShort(data.size.toShort()); putInt(checksum); put(data)
        }.array()

    data class Response(val op: Int, val value: Long, val data: ByteArray) {
        /** The ROM's status: failed flag and error code, from the last 4 data bytes. */
        val failed get() = data.size >= ROM_STATUS_BYTES && data[data.size - ROM_STATUS_BYTES].toInt() != 0
        val error get() = if (data.size >= ROM_STATUS_BYTES) data[data.size - ROM_STATUS_BYTES + 1].toInt() and 0xFF else 0
        /** Data without the status bytes. */
        val payload get() = data.copyOfRange(0, (data.size - ROM_STATUS_BYTES).coerceAtLeast(0))
    }

    fun response(frame: ByteArray): Response? {
        if (frame.size < 8 || frame[0].toInt() != 1) return null
        val b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        b.get()
        val op = b.get().toInt() and 0xFF
        val len = b.short.toInt() and 0xFFFF
        val value = b.int.toLong() and 0xFFFFFFFFL
        if (frame.size < 8 + len) return null
        return Response(op, value, frame.copyOfRange(8, 8 + len))
    }

    val syncData: ByteArray = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }

    /** SPI_ATTACH for the ROM: hspi_arg 0 plus 4 reserved bytes. */
    val spiAttachData: ByteArray = ByteArray(8)

    fun spiSetParams(totalSize: Int): ByteArray =
        le(24).putInt(0).putInt(totalSize).putInt(64 * 1024).putInt(4 * 1024).putInt(256).putInt(0xFFFF).array()

    /**
     * FLASH_DEFL_BEGIN for the ROM: the erase size is the uncompressed size rounded up to
     * whole [FLASH_WRITE_SIZE] blocks, then the number of compressed blocks, the block
     * size, the offset, and "not encrypted" (the C5 ROM takes that fifth word).
     */
    fun flashDeflBegin(size: Int, compressedSize: Int, offset: Int): ByteArray {
        val blocks = (compressedSize + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE
        val eraseBlocks = (size + FLASH_WRITE_SIZE - 1) / FLASH_WRITE_SIZE
        return le(20).putInt(eraseBlocks * FLASH_WRITE_SIZE).putInt(blocks).putInt(FLASH_WRITE_SIZE).putInt(offset).putInt(0).array()
    }

    fun flashDeflData(block: ByteArray, seq: Int): ByteArray =
        le(16 + block.size).putInt(block.size).putInt(seq).putInt(0).putInt(0).put(block).array()

    fun md5Data(offset: Int, size: Int): ByteArray = le(16).putInt(offset).putInt(size).putInt(0).putInt(0).array()

    /** The ROM answers SPI_FLASH_MD5 with the digest as 32 hex characters. */
    fun md5Of(r: Response): String? = r.payload.takeIf { it.size >= 32 }?.copyOfRange(0, 32)?.toString(Charsets.US_ASCII)?.lowercase()

    /** GET_SECURITY_INFO: flags u32, crypt count u8, key purposes 7 bytes, chip id u32, ... */
    fun chipIdOf(r: Response): Int? {
        val p = r.payload
        if (p.size < 16) return null
        return ByteBuffer.wrap(p, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
}
