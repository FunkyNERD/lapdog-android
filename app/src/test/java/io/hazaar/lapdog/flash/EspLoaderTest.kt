package io.hazaar.lapdog.flash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.random.Random

class EspProtocolTest {
    @Test
    fun slipEscapesAndFrames() {
        val packet = byteArrayOf(1, 0xC0.toByte(), 2, 0xDB.toByte(), 3)
        val framed = EspProtocol.slip(packet)
        assertArrayEquals(
            byteArrayOf(0xC0.toByte(), 1, 0xDB.toByte(), 0xDC.toByte(), 2, 0xDB.toByte(), 0xDD.toByte(), 3, 0xC0.toByte()),
            framed,
        )
        // Boot text before it, split across reads, then a second frame.
        val r = EspProtocol.SlipReader()
        val text = "ESP-ROM:esp32c5\r\n".toByteArray()
        assertTrue(r.feed(text + framed.copyOfRange(0, 4)).isEmpty())
        val frames = r.feed(framed.copyOfRange(4, framed.size) + EspProtocol.slip(byteArrayOf(9)))
        assertEquals(2, frames.size)
        assertArrayEquals(packet, frames[0])
        assertArrayEquals(byteArrayOf(9), frames[1])
    }

    @Test
    fun requestLayoutAndChecksum() {
        val data = byteArrayOf(1, 2, 3)
        assertEquals(0xEF xor 1 xor 2 xor 3, EspProtocol.checksum(data))
        val req = EspProtocol.request(EspProtocol.FLASH_DEFL_DATA, data, 0x12345678)
        assertArrayEquals(byteArrayOf(0, 0x11, 3, 0, 0x78, 0x56, 0x34, 0x12, 1, 2, 3), req)
    }

    @Test
    fun deflBeginRoundsTheEraseUp() {
        val b = ByteBuffer.wrap(EspProtocol.flashDeflBegin(size = 2500, compressedSize = 1500, offset = 0x10000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3 * 1024, b.int) // 2500 bytes erase as 3 whole blocks
        assertEquals(2, b.int)        // 1500 compressed bytes are 2 blocks
        assertEquals(1024, b.int)
        assertEquals(0x10000, b.int)
        assertEquals(0, b.int)        // not encrypted
        assertFalse(b.hasRemaining())
    }

    @Test
    fun responsesAndStatus() {
        val ok = byteArrayOf(1, 0x08, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val r = EspProtocol.response(ok)!!
        assertEquals(0x08, r.op)
        assertFalse(r.failed)
        val bad = EspProtocol.response(byteArrayOf(1, 0x10, 4, 0, 0, 0, 0, 0, 1, 0x05, 0, 0))!!
        assertTrue(bad.failed)
        assertEquals(5, bad.error)
        assertNull(EspProtocol.response(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun chipIdFromSecurityInfo() {
        val payload = ByteBuffer.allocate(20 + 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).put(0).put(ByteArray(7)).putInt(EspProtocol.CHIP_ID_ESP32C5).putInt(3).put(ByteArray(4)).array()
        val frame = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(1).put(0x14).putShort(payload.size.toShort()).putInt(0).put(payload).array()
        assertEquals(EspProtocol.CHIP_ID_ESP32C5, EspProtocol.chipIdOf(EspProtocol.response(frame)!!))
    }
}

/**
 * A pretend ESP32-C5 ROM loader on the other end of the USB port: decodes requests,
 * checks checksums, inflates FLASH_DEFL_DATA into its flash, and answers like the ROM.
 */
private class FakeRom(val chipId: Int = EspProtocol.CHIP_ID_ESP32C5) : SerialLink {
    val lines = mutableListOf<Pair<Boolean, Boolean>>()
    val ops = mutableListOf<Int>()
    val flash = HashMap<Int, Byte>()
    var inDownloadMode = false
    var failMd5 = false
    var syncReplies = 0
    private val out = ArrayDeque<Byte>()
    private val slip = EspProtocol.SlipReader()
    private var inflater: Inflater? = null
    private var writeAt = 0
    private var clock = 0L

    override fun now() = clock
    override fun sleep(ms: Long) { clock += ms }

    override fun setLines(dtr: Boolean, rts: Boolean) {
        lines += dtr to rts
        // DTR then RTS-with-DTR-released, as esptool's USB-JTAG sequence: download mode.
        val n = lines.size
        if (n >= 3 && lines[n - 3] == (true to false) && lines[n - 2] == (false to true) && lines[n - 1] == (false to false)) {
            inDownloadMode = true
            out.addAll("ESP-ROM:esp32c5-eco2\r\nwaiting for download\r\n".toByteArray().toList())
        }
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        if (out.isEmpty()) { clock += timeoutMs; return 0 }
        var n = 0
        while (n < buf.size && out.isNotEmpty()) buf[n++] = out.removeFirst()
        return n
    }

    override fun write(bytes: ByteArray) {
        for (f in slip.feed(bytes)) handle(f)
    }

    private fun handle(f: ByteArray) {
        if (!inDownloadMode) return
        val b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        b.get()
        val op = b.get().toInt() and 0xFF
        val len = b.short.toInt()
        val chk = b.int
        val data = ByteArray(len).also { b.get(it) }
        ops += op
        var payload = ByteArray(0)
        var fail = false
        when (op) {
            EspProtocol.SYNC -> repeat(3) { syncReplies++; reply(op, ByteArray(0)) }.also { return }
            EspProtocol.GET_SECURITY_INFO -> payload = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).put(0).put(ByteArray(7)).putInt(chipId).putInt(3).array()
            EspProtocol.FLASH_DEFL_BEGIN -> {
                val p = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                p.int; p.int; p.int
                writeAt = p.int
                inflater = Inflater()
            }
            EspProtocol.FLASH_DEFL_DATA -> {
                val p = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val size = p.int
                p.int; p.int; p.int
                val block = ByteArray(size).also { p.get(it) }
                if (EspProtocol.checksum(block) != chk) fail = true
                else {
                    val inf = inflater!!
                    inf.setInput(block)
                    val chunk = ByteArray(8192)
                    while (true) {
                        val k = inf.inflate(chunk)
                        if (k == 0) break
                        for (i in 0 until k) flash[writeAt++] = chunk[i]
                    }
                }
            }
            EspProtocol.SPI_FLASH_MD5 -> {
                val p = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val at = p.int
                val size = p.int
                val bytes = ByteArray(size) { flash[at + it] ?: 0xFF.toByte() }
                val hex = if (failMd5) "0".repeat(32) else EspLoader.md5Hex(bytes)
                payload = hex.toByteArray()
            }
        }
        reply(op, payload, fail)
    }

    private fun reply(op: Int, payload: ByteArray, fail: Boolean = false) {
        val data = payload + byteArrayOf(if (fail) 1 else 0, if (fail) 0x07 else 0, 0, 0)
        val frame = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(1).put(op.toByte()).putShort(data.size.toShort()).putInt(0).put(data).array()
        out.addAll(EspProtocol.slip(frame).toList())
    }
}

class EspLoaderTest {
    private val image = ByteArray(300_000).also { Random(7).nextBytes(it.sliceArray(0 until 100_000)).copyInto(it) }

    @Test
    fun flashesAnAppImageAndChecksIt() {
        val rom = FakeRom()
        val loader = EspLoader(rom)
        loader.enterDownloadMode()
        assertTrue(rom.inDownloadMode)
        loader.sync()
        assertEquals(EspProtocol.CHIP_ID_ESP32C5, loader.chipId())
        loader.attachFlash(4 * 1024 * 1024)
        val stages = mutableListOf<EspLoader.Stage>()
        loader.flash(image) { if (stages.lastOrNull() != it.stage) stages += it.stage }
        assertEquals(listOf(EspLoader.Stage.ERASE, EspLoader.Stage.WRITE, EspLoader.Stage.VERIFY), stages)
        val written = ByteArray(image.size) { rom.flash[EspLoader.APP_OFFSET + it]!! }
        assertArrayEquals(image, written)
        assertFalse(EspProtocol.FLASH_DEFL_END in rom.ops) // the ROM's "run app" broke USB in the spike
        loader.hardReset()
        assertEquals(false to false, rom.lines.last())
    }

    @Test
    fun aBadReadBackFails() {
        val rom = FakeRom().apply { failMd5 = true }
        val loader = EspLoader(rom)
        loader.enterDownloadMode()
        loader.sync()
        assertThrows(FlashException::class.java) { loader.flash(image) }
    }

    @Test
    fun noBootloaderNoSync() {
        val rom = object : SerialLink {
            var t = 0L
            override fun write(bytes: ByteArray) {}
            override fun read(buf: ByteArray, timeoutMs: Int): Int { t += timeoutMs; return 0 }
            override fun setLines(dtr: Boolean, rts: Boolean) {}
            override fun sleep(ms: Long) { t += ms }
            override fun now() = t
        }
        assertThrows(FlashException::class.java) { EspLoader(rom).sync(attempts = 3) }
    }

    @Test
    fun deflateRoundTrips() {
        val z = EspLoader.deflate(image)
        assertTrue(z.size < image.size)
        val inf = Inflater().apply { setInput(z) }
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (!inf.finished()) out.write(chunk, 0, inf.inflate(chunk))
        assertArrayEquals(image, out.toByteArray())
    }
}
