package io.hazaar.lapdog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hazaar.lapdog.protocol.Calibration
import io.hazaar.lapdog.service.CalibrationGuide
import io.hazaar.lapdog.service.LapRepository
import io.hazaar.lapdog.service.Voice
import io.hazaar.lapdog.state.TimerState
import io.hazaar.lapdog.ui.theme.Hud
import io.hazaar.lapdog.ui.theme.body
import io.hazaar.lapdog.ui.theme.heading
import io.hazaar.lapdog.ui.theme.mono
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Top of the gate sensitivity page: calibrate the timer (talked through by the phone's
 * voice, goggles on), its progress while it runs, and the result. A calibrated timer
 * measures from its receiver floor, so the Low/Med/High presets don't apply to it.
 */
@Composable
fun CalibrationCard(state: TimerState, enabled: Boolean) {
    val guiding by CalibrationGuide.active.collectAsStateWithLifecycle()
    val line by CalibrationGuide.line.collectAsStateWithLifecycle()
    val voice by Voice.status.collectAsStateWithLifecycle()
    val cal = state.calibration
    var askClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    HudCard(radius = 14.dp, padding = 16.dp, spacing = 12.dp) {
        when {
            guiding || cal.busy -> {
                Text("Calibrating", style = heading(18.sp))
                Text(progressText(cal), style = mono(15.sp, weight = FontWeight.Medium))
                if (cal.step == Calibration.Step.FLOOR && cal.floorOf > 0) Progress(cal.floorAt / cal.floorOf.toFloat())
                if (cal.step == Calibration.Step.FLY) Progress(cal.livePasses / 6f)
                line?.let {
                    Text(
                        "“$it”", style = body(14.sp, Hud.textSoft).copy(lineHeight = 20.sp),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                SecondaryButton("Cancel calibration", { CalibrationGuide.cancel() }, Modifier.fillMaxWidth(), enabled = guiding)
            }
            cal.on -> {
                Row {
                    Text("Calibrated", style = heading(18.sp), modifier = Modifier.weight(1f))
                    Text(
                        listOfNotNull(cal.gain?.let { "gain $it" }, cal.floorDb?.let { "floor ${fmt1(it)} dBFS" }).joinToString(" · "),
                        style = mono(13.sp, Hud.muted, FontWeight.Medium),
                    )
                }
                cal.last?.takeIf { it.verdict == Calibration.Verdict.OK }?.let { Text(resultText(it), style = body(13.sp, Hud.muted)) }
                Text(
                    "Thresholds are measured from the receiver floor. Recalibrate after changing channel, antenna or where the timer sits.",
                    style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("RECALIBRATE", { CalibrationGuide.start() }, Modifier.weight(1f), height = 56.dp, size = 16, enabled = enabled)
                    SecondaryButton("Clear", { askClear = true }, Modifier.weight(0.6f), enabled = enabled)
                }
            }
            else -> {
                Text("Calibrate the gate", style = heading(18.sp))
                Text(
                    "The timer measures your gate and sets its gain and thresholds. About 3 minutes and one battery: start with the quad's battery out, and the phone talks you through the rest, goggles on.",
                    style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
                )
                cal.last?.let { Text("Last try: " + resultText(it), style = body(13.sp, Hud.muted)) }
                PrimaryButton("CALIBRATE", { CalibrationGuide.start() }, height = 56.dp, size = 18, enabled = enabled)
            }
        }
        when (voice) {
            Voice.Status.NO_VOICE_DATA -> {
                Text("No voice installed for text-to-speech: you'll hear tones instead.", style = body(13.sp, Hud.slow))
                SecondaryButton("Install a voice", { context.startActivity(Voice.installIntent()) }, Modifier.fillMaxWidth())
            }
            Voice.Status.NO_ENGINE ->
                Text("This phone has no text-to-speech engine: you'll hear tones instead.", style = body(13.sp, Hud.slow))
            else -> Unit
        }
        if (!guiding && line != null && !cal.busy) Text("“$line”", style = body(13.sp, Hud.muted))
    }

    if (askClear) {
        HudDialog("Clear calibration?", onDismiss = { askClear = false }) {
            Text(
                "The timer goes back to its defaults: gain 20 and Medium sensitivity, measured from the receiver's typical floor.",
                style = body(14.sp, Hud.textSoft).copy(lineHeight = 20.sp),
            )
            DialogButtons("CLEAR", {
                askClear = false
                scope.launch { LapRepository.send("cal clear"); LapRepository.refresh() } // the timer resets its thresholds
            }, { askClear = false })
        }
    }
}

@Composable
private fun Progress(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Hud.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().background(Hud.accent))
    }
}

private fun progressText(c: Calibration): String = when (c.step) {
    Calibration.Step.FLOOR -> "Quad off · measuring the floor" + if (c.floorOf > 0) " · ${c.floorAt}/${c.floorOf}" else ""
    Calibration.Step.FLY -> listOfNotNull(
        c.round.takeIf { it > 0 }?.let { "Round $it" },
        c.flyGain?.let { "gain $it" },
        if (c.livePasses == 1) "1 pass" else "${c.livePasses} passes",
        "${c.flySeconds} s",
    ).joinToString(" · ")
    Calibration.Step.IDLE -> "Get ready"
}

private fun resultText(r: Calibration.Result): String = listOfNotNull(
    "${r.passes} passes",
    r.contrastDb?.let { "contrast ${it.roundToInt()} dB" },
    r.headroomDb?.let { "headroom ${it.roundToInt()} dB" },
    if (r.lowContrast) "low contrast" else null,
).joinToString(" · ")

private fun fmt1(v: Float) = "%.1f".format(java.util.Locale.ROOT, v)
