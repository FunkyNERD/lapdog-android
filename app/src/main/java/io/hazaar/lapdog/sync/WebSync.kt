package io.hazaar.lapdog.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.hazaar.lapdog.service.HeatStore
import io.hazaar.lapdog.state.HeatRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads finished heats to the pilot's account on the LapDog website, like LapDog LCD
 * does with its SD card: pair with a code from the website, then each sync checks in
 * (`GET /device`) and sends every heat it hasn't sent yet, oldest first.
 *
 * With [SyncState.auto] on, each finished heat queues a [SyncWorker], which waits for a
 * network and retries; [syncNow] is the Sync now button. One sync runs at a time.
 */
object WebSync {
    /** What the sync screens show: progress while [running], else the last result. */
    data class Status(val running: Boolean = false, val message: String? = null, val error: Boolean = false)

    enum class Outcome { OK, RETRY, FAILED }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var app: Context? = null
    private var file: File? = null
    private var api = WebApi(version = "?")

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        val a = context.applicationContext
        app = a
        val version = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: "?"
        api = WebApi(version = version)
        val f = File(a.filesDir, "sync.json")
        file = f
        _state.value = runCatching { json.decodeFromString<SyncState>(f.readText()) }.getOrDefault(SyncState())
    }

    /** Heats not on the website yet. */
    fun pending(heats: List<HeatRecord>, s: SyncState = _state.value) =
        heats.filter { it.uploadable() && it.id !in s.uploaded }

    fun setAuto(on: Boolean) {
        update { it.copy(auto = on) }
        if (on) request()
    }

    /** From LapRepository as each heat is saved. */
    fun onHeatFinished(h: HeatRecord) {
        if (h.uploadable() && _state.value.let { it.paired && it.auto }) request()
    }

    /** Queues a background sync for when there's a network. */
    fun request() {
        val a = app ?: return
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // A heat finished during a sync gets a pass of its own after it.
        WorkManager.getInstance(a).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    fun syncNow() {
        scope.launch { sync() }
    }

    fun pair(code: String) {
        scope.launch {
            lock.withLock {
                _status.value = Status(true, "Pairing…")
                val name = "LapDog app · ${Build.MODEL}".take(64)
                val r = call("Pairing") { api.pair(code, name) } ?: return@withLock
                val token = r.str("token")
                if (r.status != 201 || token == null) return@withLock fail(r.message("Pairing"))
                // A fresh numbering epoch: the first sync takes the website's numbers.
                update {
                    it.unpaired().copy(
                        token = token,
                        deviceName = r.str("device", "name") ?: name,
                        pilotName = r.str("user", "name").orEmpty(),
                    )
                }
                _status.value = Status(message = "Paired as ${_state.value.deviceName}")
            }
            sync()
        }
    }

    /** Tells the website (best effort) and forgets the pairing either way. */
    fun unpair() {
        scope.launch {
            lock.withLock {
                val token = _state.value.token ?: return@withLock
                _status.value = Status(true, "Unpairing…")
                runCatching { api.unpair(token) }
                update { it.unpaired() }
                _status.value = Status()
            }
        }
    }

    suspend fun sync(): Outcome = lock.withLock { syncLocked() }

    private suspend fun syncLocked(): Outcome {
        val token = _state.value.token ?: return Outcome.FAILED
        _status.value = Status(true, "Checking in with the website…")
        val dev = call("Sync") { api.device(token) } ?: return Outcome.RETRY
        if (lostPairing(dev)) return Outcome.FAILED
        if (!dev.ok) return failed(dev, "Sync")
        update { it.copy(deviceName = dev.str("device", "name") ?: it.deviceName, pilotName = dev.str("user", "name") ?: it.pilotName) }

        val heats = pending(HeatStore.all()).sortedBy { it.startedAt }
        val counters = dev.counters ?: _state.value.let { Counters(it.nextRace, it.epoch ?: 0) }
        update { it.numbered(counters, heats.map { h -> h.id }) }

        heats.forEachIndexed { i, h ->
            _status.value = Status(true, "Uploading heat ${i + 1} of ${heats.size}…")
            val localId = _state.value.assigned[h.id] ?: return@forEachIndexed
            val r = call("Upload") { api.race(token, localId, RaceUpload.of(h)) } ?: return Outcome.RETRY
            if (lostPairing(r)) return Outcome.FAILED
            // 422: the website will never take this one, so don't send it every sync.
            if (!r.ok && r.status != 422) return failed(r, "Upload")
            if (r.status == 422) Log.w(TAG, "heat ${h.id} refused: ${r.message("Upload")}")
            update { it.markUploaded(h.id) }
        }
        update { it.copy(lastSyncAt = System.currentTimeMillis()) }
        _status.value = Status(
            message = when (heats.size) {
                0 -> "Synced · nothing new to upload"
                1 -> "Uploaded 1 heat"
                else -> "Uploaded ${heats.size} heats"
            },
        )
        return Outcome.OK
    }

    /** Runs [block] off the main thread; null (with the status set) if the website can't be reached. */
    private suspend fun call(what: String, block: () -> WebApi.Response): WebApi.Response? =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: IOException) {
                Log.w(TAG, "$what: ${e.message}")
                fail("$what failed · can't reach the website")
                null
            }
        }

    /** A 401: the device was unpaired on the website. */
    private fun lostPairing(r: WebApi.Response): Boolean {
        if (r.status != 401) return false
        update { it.unpaired() }
        fail("Unpaired on the website · pair again")
        return true
    }

    /** Busy or broken website: worth a retry. Anything else: not. */
    private fun failed(r: WebApi.Response, what: String): Outcome {
        fail(r.message(what))
        return if (r.status == 429 || r.status >= 500) Outcome.RETRY else Outcome.FAILED
    }

    private fun fail(message: String) {
        _status.value = Status(message = message, error = true)
    }

    /** Changes the state and saves it straight away (it's small), in order. */
    @Synchronized
    private fun update(f: (SyncState) -> SyncState) {
        _state.update(f)
        val out = file ?: return
        val tmp = File(out.parentFile, "sync.tmp")
        runCatching {
            tmp.writeText(json.encodeToString(SyncState.serializer(), _state.value))
            tmp.renameTo(out)
        }.onFailure { Log.e(TAG, "saving sync state", it) }
    }

    private const val WORK = "web-sync"
    private const val TAG = "WebSync"
}
