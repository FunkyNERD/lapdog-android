package io.hazaar.lapdog.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hazaar.lapdog.flash.TimerFlasher
import io.hazaar.lapdog.state.TimerState
import io.hazaar.lapdog.ui.theme.Hud
import io.hazaar.lapdog.ui.theme.body
import io.hazaar.lapdog.ui.theme.heading
import io.hazaar.lapdog.ui.theme.mono
import io.hazaar.lapdog.update.FirmwareRelease
import io.hazaar.lapdog.update.TimerFirmware
import io.hazaar.lapdog.update.compareVersions
import kotlinx.coroutines.launch

/**
 * The connected timer: its firmware, the update to the newest release for its board, and
 * any other version to install (rolling back), all from the website and written over USB
 * (lapdog-android#3). Firmware too old to say its board asks which it is first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FirmwarePage(state: TimerState) {
    val flash by TimerFlasher.state.collectAsStateWithLifecycle()
    val index by TimerFirmware.index.collectAsStateWithLifecycle()
    val s = state.settings
    val reported = s?.board.orEmpty()
    var picked by remember { mutableStateOf<String?>(null) } // for firmware that doesn't report its board
    val board = reported.ifEmpty { picked.orEmpty() }
    var showAll by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<FirmwareRelease?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canInstall = state.usbConnected && !state.armed && !flash.running && s != null && board.isNotEmpty()
    val current = s?.firmware.orEmpty()

    val progressView = remember { BringIntoViewRequester() }
    LaunchedEffect(flash.running) {
        if (flash.running) {
            showAll = false
            progressView.bringIntoView()
        }
    }

    fun install(r: FirmwareRelease) {
        val older = current.isNotEmpty() && compareVersions(r.version, current) < 0
        if (older || reported.isEmpty()) confirm = r else TimerFlasher.install(board, r)
    }

    HudCard(radius = 14.dp, padding = 16.dp, spacing = 6.dp) {
        Text("On the timer", style = heading(16.sp))
        Text(
            if (s == null) "Connect the timer over USB to read its firmware."
            else "v${current.ifEmpty { "?" }} · ${reported.ifEmpty { "board not reported (older firmware)" }}",
            style = mono(15.sp),
        )
        if (state.armed) Text("Disarm the timer to update it.", style = body(13.sp, Hud.slow))
    }

    // While it installs, its progress goes right under the timer, in view; the version
    // list folds away so the page doesn't look idle.
    if (flash.log.isNotEmpty()) {
        HudCard(Modifier.bringIntoViewRequester(progressView), radius = 14.dp, padding = 16.dp, spacing = 8.dp) {
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
            if (flash.ok == false) Text(
                "The timer still has a working bootloader: try again, or unplug it and plug it in again first. " +
                    "The browser flasher on the website can always reinstall it.",
                style = body(13.sp, Hud.textSoft).copy(lineHeight = 19.sp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                flash.log.forEach { Text(it, style = mono(12.sp, Hud.textSoft)) }
            }
        }
    }


    if (s != null && reported.isEmpty() && index.boards.isNotEmpty() && !flash.running) {
        HudCard(radius = 14.dp, padding = 16.dp, spacing = 10.dp) {
            Text("Which timer is this?", style = heading(16.sp))
            Text(
                "Its firmware is too old to say, and every LapDog board has the same chip, so the app can't check. " +
                    "The wrong board's firmware leaves it not working until it's flashed again.",
                style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
            )
            index.boards.forEach { b ->
                ChoiceChip(b, picked == b, { picked = b })
            }
        }
    }

    val versions = if (board.isNotEmpty()) index.versions(board) else emptyList()
    val newest = versions.firstOrNull()
    if (board.isNotEmpty() && !flash.running) {
        HudCard(radius = 14.dp, padding = 16.dp, spacing = 10.dp) {
            when {
                newest == null -> {
                    Text("No releases found", style = heading(16.sp))
                    Text(
                        if (index.boards.isEmpty()) "The app hasn't been able to reach the website yet."
                        else "The website has no firmware for a $board.",
                        style = body(13.sp, Hud.muted),
                    )
                }
                current.isNotEmpty() && compareVersions(newest.version, current) > 0 -> {
                    Text("Update available: v${newest.version}", style = heading(16.sp))
                    ReleaseNotes(newest)
                    Text(
                        "Writes just the app, so the timer keeps its settings, calibration and heats. Takes about 30 s; keep it plugged in.",
                        style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
                    )
                    PrimaryButton("UPDATE TO v${newest.version}", { install(newest) }, height = 56.dp, size = 18, enabled = canInstall)
                }
                else -> {
                    Text("Up to date", style = heading(16.sp, Hud.ok))
                    Text("v${newest.version} is the newest release for a $board.", style = body(13.sp, Hud.muted))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showAll) "Hide other versions" else "Other versions (${versions.size})…",
                    style = body(14.sp, Hud.accent, FontWeight.SemiBold),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button) { showAll = !showAll }.padding(vertical = 8.dp),
                )
                Text(
                    if (checking) "Checking…" else "Check again",
                    style = body(13.sp, Hud.muted),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, enabled = !checking) {
                        checking = true
                        scope.launch { runCatching { TimerFirmware.refresh() }; checking = false }
                    }.padding(8.dp),
                )
            }
            if (showAll) {
                versions.forEach { r ->
                    VersionRow(r, isCurrent = r.version == current, enabled = canInstall) { install(r) }
                }
            }
        }
    }

    confirm?.let { r ->
        val older = current.isNotEmpty() && compareVersions(r.version, current) < 0
        HudDialog(if (older) "Install the older v${r.version}?" else "Is this a $board?", onDismiss = { confirm = null }) {
            if (reported.isEmpty()) Text(
                "Installs v${r.version} for a $board. This timer can't say which board it is, so only go ahead if it really is a $board: the wrong board's firmware leaves it not working until it's flashed again.",
                style = body(14.sp, Hud.textSoft).copy(lineHeight = 20.sp),
            )
            if (older) Text(
                "Going back from v$current to v${r.version}: if the older version saved its settings differently, the timer starts from its defaults, and versions before 0.6 don't know about calibration, so it would need calibrating again after updating. Updating forward again keeps whatever it has then.",
                style = body(14.sp, Hud.textSoft).copy(lineHeight = 20.sp),
            )
            // The version is in the title or the text: "INSTALL v0.5.0" wraps in a half-width button.
            DialogButtons("INSTALL", { confirm = null; TimerFlasher.install(board, r) }, { confirm = null })
        }
    }
}

@Composable
private fun ReleaseNotes(r: FirmwareRelease) {
    val notes = r.notes.lines().map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("#").trim() }
        .filter { it.isNotEmpty() }.joinToString("\n")
    if (notes.isNotEmpty()) Text(notes, style = body(13.sp, Hud.textSoft).copy(lineHeight = 19.sp))
    r.date?.let { Text("Released $it", style = body(12.sp, Hud.muted)) }
}

@Composable
private fun VersionRow(r: FirmwareRelease, isCurrent: Boolean, enabled: Boolean, onInstall: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(Hud.bg)
            .clickable(role = Role.Button, enabled = enabled && !isCurrent, onClickLabel = "Install v${r.version}", onClick = onInstall)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("v${r.version}", style = mono(15.sp), modifier = Modifier.weight(1f))
        Text(r.date.orEmpty(), style = body(12.sp, Hud.muted))
        Text(
            if (isCurrent) "installed" else "Install",
            style = body(13.sp, if (isCurrent) Hud.muted else if (enabled) Hud.accent else Hud.muted, FontWeight.SemiBold),
        )
    }
}
