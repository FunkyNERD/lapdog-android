package io.hazaar.lapdog.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hazaar.lapdog.protocol.LapFrame.Mode
import io.hazaar.lapdog.state.PersonalBests
import io.hazaar.lapdog.service.HeatStore
import io.hazaar.lapdog.service.LapRepository
import io.hazaar.lapdog.service.LapService
import io.hazaar.lapdog.ui.theme.Hud
import io.hazaar.lapdog.ui.theme.LapDogTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val permissions = ActivityResultContracts.RequestMultiplePermissions()
    private val requestPermissions = registerForActivityResult(permissions) {
        listening = true
        LapService.start(this)
    }

    /** Permissions asked: from here on, coming back to the app restarts the service. */
    private var listening = false

    override fun onStart() {
        super.onStart()
        LapRepository.setAppVisible(true)
        if (listening) LapService.start(this)
    }

    override fun onStop() {
        // Leaving the app stops the service unless the timer is armed.
        if (!isChangingConfigurations) LapRepository.setAppVisible(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Hud.bg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Hud.bg.toArgb()),
        )
        super.onCreate(savedInstanceState)
        HeatStore.init(applicationContext)
        // Listen from the start: USB and BLE both run in the foreground service.
        requestPermissions.launch(neededPermissions())
        setContent {
            LapDogTheme {
                val state by LapRepository.state.collectAsStateWithLifecycle()
                val running by LapRepository.running.collectAsStateWithLifecycle()
                val heats by HeatStore.heats.collectAsStateWithLifecycle()
                var settings by rememberSaveable { mutableStateOf(false) }
                var history by rememberSaveable { mutableStateOf(false) }
                var heatId by rememberSaveable { mutableStateOf<Long?>(null) }
                var message by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                fun command(vararg c: String) {
                    message = null
                    scope.launch {
                        val r = LapRepository.send(*c)
                        if (r is LapRepository.Result.Failed) message = "Timer: ${r.reason} (${r.command})"
                    }
                }

                // Armed, the phone is propped up by the gate: don't let the screen time out.
                KeepScreenOn(state.armed)

                // Arming takes over the screen; afterwards it's back to the timer, not old history.
                LaunchedEffect(state.armed) {
                    if (state.armed) { history = false; heatId = null }
                }

                Box(Modifier.fillMaxSize().background(Hud.bg).safeDrawingPadding()) {
                    val session = state.session
                    val openHeat = heatId?.let { id -> heats.firstOrNull { it.id == id } ?: state.lastSession?.takeIf { it.id == id } }
                    when {
                        settings -> SettingsScreen(state, onBack = { settings = false })
                        session != null && state.armed && session.mode == Mode.RACE ->
                            RaceScreen(state, session, message, onRestart = { command("restart") }, onStop = { command("disarm") })
                        session != null && state.armed ->
                            PracticeScreen(state, session, message, onNewHeat = { command("heat next") }, onDisarm = { command("disarm") })
                        openHeat != null -> HeatScreen(openHeat, remember(heats) { PersonalBests.of(heats) }, onBack = { heatId = null })
                        history -> HistoryScreen(heats, onBack = { history = false }, onOpen = { heatId = it.id })
                        else -> IdleScreen(
                            state, running, message,
                            lastHeat = state.lastSession ?: heats.firstOrNull(),
                            hasHistory = heats.isNotEmpty(),
                            onSettings = { settings = true },
                            onArm = { command(it.command) },
                            onOpenHeat = { heatId = it.id },
                            onHistory = { history = true },
                        )
                    }
                }
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

/** Holds the screen on (FLAG_KEEP_SCREEN_ON via the view) while [on]; the system timeout applies again after. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}
