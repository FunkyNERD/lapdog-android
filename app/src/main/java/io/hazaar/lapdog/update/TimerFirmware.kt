package io.hazaar.lapdog.update

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
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
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One timer firmware release for one board, as the website's /flash/versions.json lists it. */
@Serializable
data class FirmwareRelease(
    val version: String,
    val date: String? = null,
    val notes: String = "",
    val size: Long = 0,
    val sha256: String = "",
    val url: String = "",
)

/** What a board is, from the website: its product (the timer or the LCD dashboard) and whether the newest release has it. */
@Serializable
data class BoardInfo(val product: String = "", val repo: String = "", val name: String = "", val current: Boolean = true)

/** Board id ("c5-mini", ...) to its releases, newest first; [boards] says what each one is. */
@Serializable
data class FirmwareIndex(
    val devices: Map<String, List<FirmwareRelease>> = emptyMap(),
    val boards: Map<String, BoardInfo> = emptyMap(),
) {
    fun versions(board: String): List<FirmwareRelease> = devices[board].orEmpty()
    fun latest(board: String): FirmwareRelease? = versions(board).firstOrNull()

    /** The timer's boards (not the LCD dashboard's); every board from a website that doesn't say. */
    private val timerBoards get() = devices.keys.filter { b -> boards[b]?.let { it.repo == TIMER_REPO } ?: boards.isEmpty() }

    /** Today's timer boards, for asking which one an older timer is. */
    val currentTimerBoards get() = timerBoards.filter { boards[it]?.current ?: true }.sorted()

    fun nameOf(board: String) = boards[board]?.name?.ifEmpty { null } ?: board

    /**
     * The update to offer a timer on [firmware] (its S:fw), or null: the newest release for
     * its [board] if that's newer and not [skipped]. Firmware too old to report its board gets
     * the newest version any board has, so the pilot still hears about it (the update page asks
     * which board it is).
     */
    fun offer(board: String?, firmware: String, skipped: String?): FirmwareRelease? {
        if (firmware.isEmpty()) return null
        val newest = if (!board.isNullOrEmpty()) latest(board)
        else timerBoards.mapNotNull { latest(it) }.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
        return newest?.takeIf { isNewer(it.version, firmware) && it.version != skipped }
    }
}

/** The timer firmware's repo, as the website's versions.json names it (the LCD dashboard has its own). */
const val TIMER_REPO = "jamie/lapdog-firmware"

/** MAJOR.MINOR.PATCH order, like compareTo. */
fun compareVersions(a: String, b: String): Int = when {
    isNewer(a, b) -> 1
    isNewer(b, a) -> -1
    else -> 0
}

/**
 * Timer firmware releases from the website (lapdog-android#3): fetched when the app starts
 * (if the last copy is over an hour old) and kept for offline use, so a timer plugged in at the
 * field can still be checked. Remembers which version the pilot skipped, per board, and
 * downloads a release's app image, checking its SHA-256.
 */
object TimerFirmware {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null
    private var cacheDir: File? = null
    private var debuggable = false

    private val _index = MutableStateFlow(FirmwareIndex())
    val index: StateFlow<FirmwareIndex> = _index

    /** Bumped when a version is skipped, so the offer is worked out again. */
    private val _skips = MutableStateFlow(0)
    val skips: StateFlow<Int> = _skips

    private const val KEY_INDEX = "fw_index"
    private const val KEY_CHECKED = "fw_checked"
    private const val KEY_BASE = "fw_base" // debug builds only: test against a local website
    private const val RECHECK_MS = 60 * 60 * 1000L

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val a = context.applicationContext
        val p = a.getSharedPreferences("app", Context.MODE_PRIVATE)
        prefs = p
        cacheDir = File(a.cacheDir, "firmware").apply { mkdirs() }
        debuggable = a.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        p.getString(KEY_INDEX, null)?.let { s -> runCatching { json.decodeFromString<FirmwareIndex>(s) }.getOrNull() }?.let { _index.value = it }
    }

    /** From the activity: fetch now if the last copy is over an hour old. */
    fun start(context: Context, force: Boolean = false) {
        init(context)
        val last = prefs?.getLong(KEY_CHECKED, 0) ?: 0
        if (force || System.currentTimeMillis() - last > RECHECK_MS) scope.launch { runCatching { refresh() } }
    }

    /** Debug builds: point at another website (adb: --es firmware_base http://localhost:8000), "" = back to normal. */
    fun setBase(base: String?) {
        if (!debuggable) return
        prefs?.edit()?.apply { if (base.isNullOrEmpty()) remove(KEY_BASE) else putString(KEY_BASE, base) }?.apply()
        prefs?.edit()?.putLong(KEY_CHECKED, 0)?.apply()
    }

    private val base get() = (if (debuggable) prefs?.getString(KEY_BASE, null) else null) ?: WebApi.BASE_URL

    /** Fetches /flash/versions.json. @throws IOException when the website can't be reached. */
    suspend fun refresh(): FirmwareIndex = withContext(Dispatchers.IO) {
        val c = URL("$base/flash/versions.json").openConnection() as HttpURLConnection
        val idx = try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { json.decodeFromString<FirmwareIndex>(it.readBytes().decodeToString()) }
        } finally {
            c.disconnect()
        }
        prefs?.edit()?.putLong(KEY_CHECKED, System.currentTimeMillis())
            ?.putString(KEY_INDEX, json.encodeToString(FirmwareIndex.serializer(), idx))?.apply()
        _index.value = idx
        idx
    }

    fun skipped(board: String?): String? = prefs?.getString("fw_skip_" + (board ?: "?"), null)

    fun skip(board: String?, version: String) {
        prefs?.edit()?.putString("fw_skip_" + (board ?: "?"), version)?.apply()
        _skips.value++
    }

    /** The update to offer a timer, or null (see [FirmwareIndex.offer]). */
    fun offer(board: String?, firmware: String): FirmwareRelease? =
        _index.value.offer(board?.takeIf { it.isNotEmpty() }, firmware, skipped(board?.takeIf { it.isNotEmpty() }))

    /**
     * Downloads [r]'s app image for [board] (cached), checking its size and SHA-256.
     * @throws IOException on a network error or a bad image.
     */
    suspend fun download(board: String, r: FirmwareRelease, progress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val dir = cacheDir ?: throw IOException("not started")
        val file = File(dir, "$board-${r.version}-firmware.bin")
        if (file.isFile && sha256(file.readBytes()) == r.sha256) return@withContext file
        val c = URL(if (r.url.startsWith("http")) r.url else base + r.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            if (c.responseCode != 200) throw IOException("the website answered HTTP ${c.responseCode}")
            val out = java.io.ByteArrayOutputStream(r.size.toInt().coerceAtLeast(1024))
            c.inputStream.use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (r.size > 0) progress(out.size() / r.size.toFloat())
                }
            }
            val bytes = out.toByteArray()
            if (r.size > 0 && bytes.size.toLong() != r.size) throw IOException("the download was ${bytes.size} bytes, expected ${r.size}")
            if (r.sha256.isNotEmpty() && sha256(bytes) != r.sha256) throw IOException("the download failed its SHA-256 check")
            dir.listFiles()?.filter { it.name.startsWith("$board-") }?.forEach { it.delete() } // keep one per board
            file.writeBytes(bytes)
            file
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
