package io.hazaar.lapdog.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.hazaar.lapdog.protocol.TimerLine
import io.hazaar.lapdog.service.LapRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The timer's native USB (Espressif USB Serial/JTAG, CDC-ACM): reads `F:`/`S:`/`R:`/OK
 * lines and writes console commands. Waits for the timer to be plugged in, asks for
 * permission once if the device_filter intent didn't already grant it, and reconnects
 * after an unplug.
 */
class UsbTransport(private val context: Context) : Transport {
    override val name = "USB"

    override suspend fun run() = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(UsbManager::class.java) ?: return@withContext
        var asked = false
        while (isActive) {
            val driver = prober.findAllDrivers(manager).firstOrNull()
            if (driver == null) {
                asked = false
                delay(POLL_MS)
                continue
            }
            if (!manager.hasPermission(driver.device)) {
                if (!asked) {
                    asked = true
                    val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
                    manager.requestPermission(
                        driver.device,
                        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE),
                    )
                }
                delay(POLL_MS)
                continue
            }
            val connection = manager.openDevice(driver.device)
            if (connection == null) {
                delay(POLL_MS)
                continue
            }
            val port = driver.ports.first()
            try {
                port.open(connection)
                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                port.dtr = true
                session(port)
            } catch (e: IOException) {
                Log.i(TAG, "USB closed: ${e.message}")
            } finally {
                LapRepository.onUsb(false)
                runCatching { port.close() }
            }
            delay(POLL_MS)
        }
    }

    private suspend fun session(port: UsbSerialPort) = coroutineScope {
        LapRepository.onUsb(true)
        val writer = launch {
            for (cmd in LapRepository.outgoing) port.write("$cmd\n".toByteArray(), WRITE_TIMEOUT_MS)
        }
        launch { LapRepository.refresh() }
        try {
            val buf = ByteArray(512)
            val line = StringBuilder()
            while (isActive) {
                val n = port.read(buf, READ_TIMEOUT_MS)
                for (i in 0 until n) {
                    val c = (buf[i].toInt() and 0xFF).toChar()
                    if (c == '\n') {
                        TimerLine.parse(line.toString())?.let(LapRepository::onLine)
                        line.clear()
                    } else if (line.length < 256) {
                        line.append(c)
                    }
                }
            }
        } finally {
            writer.cancel()
        }
    }

    private companion object {
        const val TAG = "UsbTransport"
        const val ACTION_PERMISSION = "io.hazaar.lapdog.USB_PERMISSION"
        const val POLL_MS = 1500L
        const val READ_TIMEOUT_MS = 200
        const val WRITE_TIMEOUT_MS = 500
        val prober = UsbSerialProber(
            ProbeTable().addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java)
        )
    }
}
