package io.hazaar.lapdog.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hazaar.lapdog.state.TimerState
import io.hazaar.lapdog.ui.theme.Hud
import io.hazaar.lapdog.ui.theme.body
import io.hazaar.lapdog.update.TimerFirmware

/**
 * "Timer update available" when a timer is plugged in (idle) and the website has newer
 * firmware for its board: Update opens the timer page, Skip this version stops asking until
 * a newer one, Not now asks again next time it's plugged in. Never while armed, calibrating
 * or already flashing.
 */
@Composable
fun TimerUpdatePrompt(state: TimerState, busy: Boolean, onUpdate: () -> Unit) {
    val context = LocalContext.current
    val index by TimerFirmware.index.collectAsStateWithLifecycle()
    val skips by TimerFirmware.skips.collectAsStateWithLifecycle()
    // Once per connection: "Not now" holds until the timer is unplugged.
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(state.usbConnected) {
        if (!state.usbConnected) dismissed = false
        else TimerFirmware.start(context) // an hourly refresh, if due
    }
    val s = state.settings ?: return
    if (!state.usbConnected || state.armed || busy || dismissed || state.calibration.busy) return
    val offer = remember(index, skips, s.board, s.firmware) { TimerFirmware.offer(s.board, s.firmware) } ?: return
    val board = s.board.ifEmpty { null }

    HudDialog("Timer update", onDismiss = { dismissed = true }) {
        Text(
            "v${offer.version} is available for your timer (it has v${s.firmware}). Updating takes about 30 s over this USB cable and keeps its settings.",
            style = body(14.sp, Hud.textSoft).copy(lineHeight = 20.sp),
        )
        PrimaryButton("UPDATE", { dismissed = true; onUpdate() }, height = 56.dp, size = 18)
        SecondaryButton("Skip this version", { TimerFirmware.skip(board, offer.version); dismissed = true }, Modifier.fillMaxWidth())
        SecondaryButton("Not now", { dismissed = true }, Modifier.fillMaxWidth())
    }
}
