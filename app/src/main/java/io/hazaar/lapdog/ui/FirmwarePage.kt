package io.hazaar.lapdog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hazaar.lapdog.flash.TimerFlasher
import io.hazaar.lapdog.state.TimerState
import io.hazaar.lapdog.ui.theme.Hud
import io.hazaar.lapdog.ui.theme.body
import io.hazaar.lapdog.ui.theme.heading
import io.hazaar.lapdog.ui.theme.mono
import java.io.File

/**
 * The connected timer's firmware, and (for now, while lapdog-android#3 is a spike)
 * writing an image from the app's files folder over USB.
 */
@Composable
fun FirmwarePage(state: TimerState) {
    val context = LocalContext.current
    val flash by TimerFlasher.state.collectAsStateWithLifecycle()
    val s = state.settings
    var images by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(flash.running) { images = TimerFlasher.images(context) }
    val canFlash = state.usbConnected && !state.armed && !flash.running

    HudCard(radius = 14.dp, padding = 16.dp, spacing = 8.dp) {
        Text("On the timer", style = heading(16.sp))
        Text(
            if (s == null) "Connect the timer over USB to read its firmware."
            else "v${s.firmware.ifEmpty { "?" }} · ${s.board.ifEmpty { "board not reported (older firmware)" }}",
            style = mono(15.sp),
        )
    }

    HudCard(radius = 14.dp, padding = 16.dp, spacing = 10.dp) {
        Text("Update over USB (test)", style = heading(16.sp))
        Text(
            "Writes the app image only, so the timer keeps its settings, calibration and heats. Copy an image to this phone first:\n" +
                "adb push <board>-firmware.bin /sdcard/Android/data/io.hazaar.lapdog/files/",
            style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
        )
        if (images.isEmpty()) Text("No images found.", style = body(14.sp, Hud.textSoft))
        images.forEach { f ->
            SecondaryButton("Write ${f.name} · ${f.length() / 1024} KB", { TimerFlasher.start(f) }, Modifier.fillMaxWidth(), enabled = canFlash)
        }
        if (state.armed) Text("Disarm the timer to update it.", style = body(13.sp, Hud.slow))
    }

    if (flash.log.isNotEmpty()) {
        HudCard(radius = 14.dp, padding = 16.dp, spacing = 8.dp) {
            Text(
                flash.stage, style = heading(16.sp, if (flash.ok == false) Hud.slow else if (flash.ok == true) Hud.ok else Hud.text),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            flash.progress?.let { p ->
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Hud.line)) {
                    Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).fillMaxHeight().background(Hud.accent))
                }
                Text("${(p * 100).toInt()} %", style = mono(13.sp, Hud.muted))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                flash.log.forEach { Text(it, style = mono(12.sp, Hud.textSoft)) }
            }
        }
    }
}
