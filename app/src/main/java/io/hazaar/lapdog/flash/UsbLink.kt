package io.hazaar.lapdog.flash

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/**
 * [SerialLink] on the timer's USB-Serial/JTAG port (usb-serial-for-android CDC-ACM).
 * The control lines are set one at a time, in esptool's order: raising RTS before
 * dropping DTR, so going from "IO0 low" to "reset" passes through both-on, never
 * both-off (which would let IO0 go before the reset).
 */
class UsbLink(private val port: UsbSerialPort) : SerialLink {
    // Start from what the port has (UsbTransport opens it with DTR on).
    private var dtr = runCatching { port.dtr }.getOrDefault(true)
    private var rts = runCatching { port.rts }.getOrDefault(false)

    override fun write(bytes: ByteArray) = port.write(bytes, 2_000)

    override fun read(buf: ByteArray, timeoutMs: Int): Int =
        try {
            port.read(buf, timeoutMs.coerceAtLeast(1))
        } catch (e: IOException) {
            throw FlashException("USB connection lost: ${e.message}")
        }

    override fun setLines(dtr: Boolean, rts: Boolean) {
        if (rts) {
            if (!this.rts) port.rts = true
            if (this.dtr != dtr) port.dtr = dtr
        } else {
            if (this.dtr != dtr) port.dtr = dtr
            if (this.rts) port.rts = false
        }
        this.dtr = dtr
        this.rts = rts
    }
}
