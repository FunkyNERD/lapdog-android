package io.hazaar.lapdog.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import io.hazaar.lapdog.state.TimerState
import io.hazaar.lapdog.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service so laps keep arriving with goggles on and the phone screen off.
 * Runs every transport at once; TimerModel drops the copies that arrive over both.
 *
 * It runs while the app is on screen or the timer is armed: leave the app while
 * disarmed and it stops, notification and all. The app has to be open to hear the
 * timer arm (Android 12+ won't let the service start from the background).
 */
class LapService : LifecycleService() {
    private val jobs = mutableListOf<Job>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        HeatStore.init(applicationContext)
        LapSounds.init(applicationContext)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (jobs.isEmpty()) {
            val nm = getSystemService(NotificationManager::class.java)
            jobs += lifecycleScope.launch {
                LapRepository.state.map(::armedText).distinctUntilChanged()
                    .collect { nm.notify(NOTIFICATION_ID, notification(it)) }
            }
            jobs += lifecycleScope.launch {
                combine(LapRepository.appVisible, LapRepository.state) { visible, s -> visible || s.armed }
                    .first { !it }
                ServiceCompat.stopForeground(this@LapService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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

    /** "Heat 4 · 3 laps" while armed, null while idle. */
    private fun armedText(state: TimerState): String? {
        val s = state.session?.takeIf { state.armed } ?: return null
        val laps = s.laps.size
        return "${state.heatName(s.heat)} · " + if (laps == 1) "1 lap" else "$laps laps"
    }

    private fun notification(armed: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(NotificationChannel(IDLE_CHANNEL, "Waiting for the timer", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(ARMED_CHANNEL, "Timer armed", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, if (armed != null) ARMED_CHANNEL else IDLE_CHANNEL)
            .setContentTitle(if (armed != null) "Armed - listening for laps" else "LapDog")
            .setContentText(armed ?: "Waiting for the timer to arm")
            .setSmallIcon(R.drawable.ic_stat_lapdog)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val OLD_CHANNEL = "laps"
        private const val IDLE_CHANNEL = "idle"
        private const val ARMED_CHANNEL = "armed"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) = context.startForegroundService(Intent(context, LapService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, LapService::class.java))
    }
}
