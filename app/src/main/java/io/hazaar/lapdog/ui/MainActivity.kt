package io.hazaar.lapdog.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hazaar.lapdog.service.LapRepository
import io.hazaar.lapdog.service.LapService

class MainActivity : ComponentActivity() {
    private val permissions = ActivityResultContracts.RequestMultiplePermissions()
    private val requestPermissions = registerForActivityResult(permissions) { LapService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by LapRepository.state.collectAsStateWithLifecycle()
                LapScreen(
                    state = state,
                    onStart = { requestPermissions.launch(neededPermissions()) },
                    onStop = { LapService.stop(this) },
                    onClear = LapRepository::clear,
                )
            }
        }
    }

    private fun neededPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

fun formatLap(ms: Long) = "%d:%02d.%03d".format(ms / 60000, (ms / 1000) % 60, ms % 1000)

@Composable
fun LapScreen(state: LapRepository.State, onStart: () -> Unit, onStop: () -> Unit, onClear: () -> Unit) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when {
                    !state.running -> "Stopped"
                    state.armed -> "Armed" + (state.lastHeard?.let { " · $it" } ?: "")
                    else -> "Waiting for timer…"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(state.laps.lastOrNull()?.let(::formatLap) ?: "--:--.---", fontSize = 56.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.running) Button(onClick = onStop) { Text("Stop") } else Button(onClick = onStart) { Text("Start") }
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
            val best = state.laps.minOrNull()
            LazyColumn(Modifier.fillMaxWidth()) {
                itemsIndexed(state.laps.reversed()) { i, lap ->
                    val n = state.laps.size - i
                    Text("Lap $n   ${formatLap(lap)}" + if (lap == best) "   best" else "")
                }
            }
        }
    }
}
