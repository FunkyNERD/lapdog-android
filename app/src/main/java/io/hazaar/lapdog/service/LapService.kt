package io.hazaar.lapdog.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.hazaar.lapdog.transport.BleTransport
import io.hazaar.lapdog.transport.Transport
import io.hazaar.lapdog.transport.UsbTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service so laps keep arriving with goggles on and the phone screen off.
 * Runs every transport at once; the same lap can arrive over USB and BLE, which is fine
 * for now (TODO: de-dupe across transports by seq).
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
            listOf<Transport>(UsbTransport(this), BleTransport(this)).forEach { t ->
                jobs += lifecycleScope.launch { t.frames().collect { LapRepository.onFrame(it, t.name) } }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        LapRepository.setRunning(false)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lap timer", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("LapDog")
            .setContentText("Listening for laps")
            .setSmallIcon(android.R.drawable.ic_media_play)
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
