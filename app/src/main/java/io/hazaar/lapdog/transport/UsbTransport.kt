package io.hazaar.lapdog.transport

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.hazaar.lapdog.protocol.LapFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

/**
 * Reads `F:<hex>` lines from the timer's native USB (Espressif USB Serial/JTAG, CDC-ACM).
 * USB permission comes from the device_filter intent filter when the timer is plugged in.
 */
class UsbTransport(private val context: Context) : Transport {
    override val name = "USB"

    override fun frames(): Flow<LapFrame> = flow {
        val manager = context.getSystemService(UsbManager::class.java) ?: return@flow
        val driver = prober.findAllDrivers(manager).firstOrNull() ?: run {
            Log.i(TAG, "no timer attached")
            return@flow
        }
        if (!manager.hasPermission(driver.device)) {
            Log.w(TAG, "no USB permission for ${driver.device.deviceName}")
            return@flow
        }
        val connection = manager.openDevice(driver.device) ?: return@flow
        val port = driver.ports.first()
        try {
            port.open(connection)
            port.setParameters(115200, 8, 1, 0)
            port.dtr = true
            val buf = ByteArray(256)
            val line = StringBuilder()
            while (true) {
                val n = port.read(buf, 0)
                for (i in 0 until n) {
                    val c = buf[i].toInt().toChar()
                    if (c == '\n') {
                        LapFrame.fromLine(line.toString())?.let { emit(it) }
                        line.clear()
                    } else if (line.length < 256) {
                        line.append(c)
                    }
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "USB closed: ${e.message}")
        } finally {
            runCatching { port.close() }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "UsbTransport"
        val prober = UsbSerialProber(
            ProbeTable().addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java)
        )
    }
}
