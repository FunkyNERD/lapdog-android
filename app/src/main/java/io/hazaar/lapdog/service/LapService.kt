package io.hazaar.lapdog.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import io.hazaar.lapdog.R
import androidx.lifecycle.lifecycleScope
import io.hazaar.lapdog.transport.BleTransport
import io.hazaar.lapdog.transport.Transport
import io.hazaar.lapdog.transport.UsbTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service so laps keep arriving with goggles on and the phone screen off.
 * Runs every transport at once; TimerModel drops the copies that arrive over both.
 */
class LapService : LifecycleService() {
    private val jobs = mutableListOf<Job>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (jobs.isEmpty()) {
            LapRepository.setRunning(true)
            // BLE scanning needs the runtime permission; without it, USB still works.
            val transports = buildList<Transport> {
                add(UsbTransport(this@LapService))
                if (canScan()) add(BleTransport(this@LapService))
            }
            transports.forEach { t ->
                jobs += lifecycleScope.launch { t.run() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        LapRepository.setRunning(false)
        super.onDestroy()
    }

    private fun canScan(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lap timer", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("LapDog")
            .setContentText("Listening for laps")
            .setSmallIcon(R.drawable.ic_stat_lapdog)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "laps"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) = context.startForegroundService(Intent(context, LapService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, LapService::class.java))
    }
}
