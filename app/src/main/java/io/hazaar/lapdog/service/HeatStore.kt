package io.hazaar.lapdog.service

import android.content.Context
import android.util.Log
import io.hazaar.lapdog.state.HeatRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Every finished heat, one small JSON file each under files/heats/. A home pilot logs
 * a few hundred heats of a few hundred bytes, all loaded at once for the history and
 * personal bests, so plain files beat a database: no queries, no schema migrations
 * (unknown keys are ignored, new fields get defaults) and a file is never rewritten.
 */
object HeatStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dir: File? = null

    /** Newest first. */
    private val _heats = MutableStateFlow<List<HeatRecord>>(emptyList())
    val heats: StateFlow<List<HeatRecord>> = _heats

    private val loaded = CompletableDeferred<Unit>()

    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, "heats").apply { mkdirs() }
        dir = d
        scope.launch {
            val loaded = d.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
                runCatching { json.decodeFromString<HeatRecord>(f.readText()) }
                    .onFailure { Log.w(TAG, "skipping ${f.name}", it) }.getOrNull()
            }
            // Heats saved before the load finished are already in the list.
            _heats.update { (it + loaded).distinctBy { h -> h.id }.sortedByDescending { h -> h.startedAt } }
            this@HeatStore.loaded.complete(Unit)
        }
    }

    /** Every heat, once the saved ones are loaded ([init] first). */
    suspend fun all(): List<HeatRecord> {
        loaded.await()
        return _heats.value
    }

    fun save(h: HeatRecord) {
        _heats.update { (listOf(h) + it.filter { o -> o.id != h.id }).sortedByDescending { o -> o.startedAt } }
        val d = dir ?: return
        scope.launch {
            val f = File(d, "${h.id}.json")
            val tmp = File(d, "${h.id}.tmp")
            runCatching {
                tmp.writeText(json.encodeToString(HeatRecord.serializer(), h))
                tmp.renameTo(f)
            }.onFailure { Log.e(TAG, "saving heat ${h.id}", it) }
        }
    }

    fun get(id: Long) = _heats.value.firstOrNull { it.id == id }

    private const val TAG = "HeatStore"
}
