package io.hazaar.lapdog.flash

import io.hazaar.lapdog.flash.EspProtocol.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.Deflater

/** The serial port as the loader needs it: bytes both ways, and the two control lines. */
interface SerialLink {
    fun write(bytes: ByteArray)
    /** Up to buf.size bytes, waiting at most [timeoutMs]; 0 on timeout. */
    fun read(buf: ByteArray, timeoutMs: Int): Int
    fun setLines(dtr: Boolean, rts: Boolean)
    fun sleep(ms: Long) = Thread.sleep(ms)
    fun now(): Long = System.currentTimeMillis()
}

class FlashException(message: String) : IOException(message)

/**
 * Writes an app image into an ESP32-C5 (or C3) through its ROM serial loader over the
 * USB-Serial/JTAG port, as esptool does without a stub: DTR/RTS into download mode,
 * SYNC, check the chip, attach and size the flash, write deflated 1 KB blocks, check
 * the MD5, then a hard reset back into the app. Only the app image at [APP_OFFSET] is
 * written, so the bootloader, partition table and NVS (settings) are never touched.
 */
class EspLoader(private val link: SerialLink) {
    private val slip = EspProtocol.SlipReader()
    private val pending = ArrayDeque<ByteArray>()
    private val buf = ByteArray(4096)

    enum class Stage { RESET, SYNC, ERASE, WRITE, VERIFY, RESTART }

    data class Progress(val stage: Stage, val done: Int = 0, val total: Int = 0)

    /**
     * esptool's USB-Serial/JTAG reset into download mode (esp_pylib usb_jtag_bootloader_reset):
     * idle, IO0 low (DTR), reset (RTS) while releasing IO0, release; 100 ms each.
     */
    fun enterDownloadMode() {
        link.setLines(dtr = false, rts = false); link.sleep(100)
        link.setLines(dtr = true, rts = false); link.sleep(100)
        link.setLines(dtr = false, rts = true); link.sleep(100)
        link.setLines(dtr = false, rts = false)
        link.sleep(250) // the ROM prints its banner, then waits for SYNC
        drain(100)
    }

    /** esptool's hard reset for USB-Serial/JTAG: RTS pulse with DTR released. */
    fun hardReset() {
        link.setLines(dtr = false, rts = true); link.sleep(100)
        link.setLines(dtr = false, rts = false)
    }

    fun sync(attempts: Int = 10) {
        repeat(attempts) {
            link.write(EspProtocol.slip(EspProtocol.request(EspProtocol.SYNC, EspProtocol.syncData)))
            val r = runCatching { await(EspProtocol.SYNC, 200) }.getOrNull()
            if (r != null && !r.failed) {
                drain(100) // the ROM answers a SYNC several times
                return
            }
        }
        throw FlashException("the timer's bootloader didn't answer")
    }

    fun chipId(): Int? = EspProtocol.chipIdOf(command(EspProtocol.GET_SECURITY_INFO, ByteArray(0)))

    fun attachFlash(sizeBytes: Int) {
        command(EspProtocol.SPI_ATTACH, EspProtocol.spiAttachData)
        command(EspProtocol.SPI_SET_PARAMS, EspProtocol.spiSetParams(sizeBytes))
    }

    /** Writes [image] at [offset] and checks it; [progress] gets erase, then blocks written, then verify. */
    fun flash(image: ByteArray, offset: Int = APP_OFFSET, progress: (Progress) -> Unit = {}) {
        val compressed = deflate(image)
        val blocks = (compressed.size + EspProtocol.FLASH_WRITE_SIZE - 1) / EspProtocol.FLASH_WRITE_SIZE
        progress(Progress(Stage.ERASE))
        // The ROM erases the whole region before answering: ~30 s per MB, as esptool allows.
        val eraseMs = (30_000L * image.size / 1_000_000).coerceAtLeast(10_000L)
        command(EspProtocol.FLASH_DEFL_BEGIN, EspProtocol.flashDeflBegin(image.size, compressed.size, offset), timeoutMs = eraseMs)
        for (seq in 0 until blocks) {
            val from = seq * EspProtocol.FLASH_WRITE_SIZE
            val block = compressed.copyOfRange(from, minOf(from + EspProtocol.FLASH_WRITE_SIZE, compressed.size))
            command(
                EspProtocol.FLASH_DEFL_DATA, EspProtocol.flashDeflData(block, seq),
                checksum = EspProtocol.checksum(block), timeoutMs = 10_000,
            )
            progress(Progress(Stage.WRITE, seq + 1, blocks))
        }
        progress(Progress(Stage.VERIFY))
        val want = md5Hex(image)
        val got = EspProtocol.md5Of(command(EspProtocol.SPI_FLASH_MD5, EspProtocol.md5Data(offset, image.size), timeoutMs = 30_000))
        if (got != want) throw FlashException("verify failed: the timer read back $got, expected $want")
        // No FLASH_DEFL_END: on the ROM it means "run the app now", which left USB dead in
        // the spike (docs/dev/timer-firmware-update.md). The hard reset does it cleanly.
    }

    private fun command(op: Int, data: ByteArray, checksum: Int = 0, timeoutMs: Long = 3_000): Response {
        link.write(EspProtocol.slip(EspProtocol.request(op, data, checksum)))
        val r = await(op, timeoutMs)
        if (r.failed) throw FlashException("the bootloader refused command 0x%02x (error 0x%02x)".format(op, r.error))
        return r
    }

    private fun await(op: Int, timeoutMs: Long): Response {
        val end = link.now() + timeoutMs
        while (true) {
            while (pending.isNotEmpty()) {
                val r = EspProtocol.response(pending.removeFirst()) ?: continue
                if (r.op == op) return r
            }
            val left = end - link.now()
            if (left <= 0) throw FlashException("no reply from the timer's bootloader (command 0x%02x)".format(op))
            val n = link.read(buf, left.coerceAtMost(200).toInt())
            if (n > 0) pending.addAll(slip.feed(buf, n))
        }
    }

    private fun drain(ms: Long) {
        val end = link.now() + ms
        while (link.now() < end) {
            val n = link.read(buf, 20)
            if (n > 0) slip.feed(buf, n)
        }
        pending.clear()
    }

    companion object {
        /** Where the app image goes on every LapDog board (partitions.csv, partitions_touch.csv). */
        const val APP_OFFSET = 0x10000

        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_COMPRESSION) // zlib format, like Python's zlib.compress
            d.setInput(data)
            d.finish()
            val out = java.io.ByteArrayOutputStream(data.size / 2)
            val chunk = ByteArray(64 * 1024)
            while (!d.finished()) out.write(chunk, 0, d.deflate(chunk))
            d.end()
            return out.toByteArray()
        }

        fun md5Hex(data: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
    }
}
