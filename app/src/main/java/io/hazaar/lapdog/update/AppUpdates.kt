package io.hazaar.lapdog.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.hazaar.lapdog.R
import io.hazaar.lapdog.sync.WebApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** A release as the website's `/app/latest.json` describes it. */
@Serializable
data class AppRelease(val version: String, val url: String, val size: Long = 0, val sha256: String = "")

/** "1.2.3" (or "v1.2.3", with or without a -suffix) newer than [current]. */
fun isNewer(candidate: String, current: String): Boolean {
    fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    val a = parts(candidate)
    val b = parts(current)
    for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
    return false
}

/**
 * Tells the pilot when the website has a newer app: a notification (once per version,
 * from a daily [UpdateWorker]) and a banner on the timer screen until it's dismissed.
 * Either opens the APK download on the website; Android's installer does the rest.
 */
object AppUpdates {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null
    private var current = "0"

    /** A newer release the pilot hasn't dismissed, or null. */
    private val _available = MutableStateFlow<AppRelease?>(null)
    val available: StateFlow<AppRelease?> = _available

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val a = context.applicationContext
        val p = a.getSharedPreferences("app", Context.MODE_PRIVATE)
        prefs = p
        current = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: "0"
        publish(p.getString(KEY_RELEASE, null)?.let { runCatching { json.decodeFromString<AppRelease>(it) }.getOrNull() })
    }

    /** From the activity: the daily check, plus one now if the last was a while ago. */
    fun start(context: Context) {
        init(context)
        val work = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, work)
        val last = prefs?.getLong(KEY_CHECKED, 0) ?: 0
        if (System.currentTimeMillis() - last > RECHECK_MS) scope.launch { runCatching { check() } }
    }

    /**
     * Asks the website for its latest release; the newer one, if there is one.
     * @throws IOException when the website can't be reached.
     */
    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val c = URL(WebApi.BASE_URL + "/app/latest.json").openConnection() as HttpURLConnection
        val release = try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/json")
            when (c.responseCode) {
                200 -> c.inputStream.use { json.decodeFromString<AppRelease>(it.readBytes().decodeToString()) }
                404 -> null // no release yet
                else -> throw IOException("HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
        prefs?.edit()
            ?.putLong(KEY_CHECKED, System.currentTimeMillis())
            ?.putString(KEY_RELEASE, release?.let { json.encodeToString(AppRelease.serializer(), it) })
            ?.apply()
        publish(release)
        release?.takeIf { isNewer(it.version, current) }
    }

    /** Hides the banner for this version (a later one shows it again). */
    fun dismiss(r: AppRelease) {
        prefs?.edit()?.putString(KEY_DISMISSED, r.version)?.apply()
        _available.value = null
    }

    /** Downloads [r] in the browser, which hands the APK to Android's installer. */
    fun open(context: Context, r: AppRelease) {
        context.startActivity(download(r).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Posts the "update available" notification, once per version. */
    fun notify(context: Context, r: AppRelease) {
        val p = prefs ?: return
        if (p.getString(KEY_NOTIFIED, null) == r.version) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(context, 1, download(r), PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(context, CHANNEL)
            .setContentTitle("LapDog ${r.version} is available")
            .setContentText("You have $current. Tap to download and install it.")
            .setSmallIcon(R.drawable.ic_stat_lapdog)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        p.edit().putString(KEY_NOTIFIED, r.version).apply()
    }

    private fun download(r: AppRelease) = Intent(Intent.ACTION_VIEW, Uri.parse(r.url))

    private fun publish(r: AppRelease?) {
        val dismissed = prefs?.getString(KEY_DISMISSED, null)
        _available.value = r?.takeIf { isNewer(it.version, current) && it.version != dismissed }
        if (r != null) Log.i(TAG, "latest ${r.version}, have $current")
    }

    private const val KEY_RELEASE = "update_release"
    private const val KEY_CHECKED = "update_checked_at"
    private const val KEY_DISMISSED = "update_dismissed"
    private const val KEY_NOTIFIED = "update_notified"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 2
    private const val WORK = "app-update-check"
    private const val RECHECK_MS = 6 * 60 * 60 * 1000L
    private const val TAG = "AppUpdates"
}
