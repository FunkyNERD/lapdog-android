package io.hazaar.lapdog.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import io.hazaar.lapdog.protocol.LapFrame
import io.hazaar.lapdog.service.LapRepository
import io.hazaar.lapdog.state.Via
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/**
 * Receives frames from the timer's non-connectable advert bursts (when its radio
 * output is Bluetooth). One long-running scan (Android allows 5 scan starts per 30 s),
 * filtered on manufacturer ID so it keeps delivering with the screen off. The model
 * drops the repeats within a burst by seq.
 */
class BleTransport(private val context: Context) : Transport {
    override val name = "BLE"

    // Caller (LapService) checks BLUETOOTH_SCAN before starting.
    @SuppressLint("MissingPermission")
    override suspend fun run() = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "no BLE scanner (Bluetooth off?)")
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(LapFrame.BLE_COMPANY_ID) ?: return
                LapFrame.decode(data)?.let { trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "scan failed: $errorCode")
                close()
            }
        }
        val filter = ScanFilter.Builder()
            .setManufacturerData(LapFrame.BLE_COMPANY_ID, byteArrayOf())
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        scanner.startScan(listOf(filter), settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }.collect { LapRepository.onFrame(it, Via.BLE) }

    private companion object {
        const val TAG = "BleTransport"
    }
}
