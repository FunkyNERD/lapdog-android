package io.hazaar.lapdog.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hazaar.lapdog.service.HeatStore
import io.hazaar.lapdog.sync.SyncState
import io.hazaar.lapdog.sync.WebApi
import io.hazaar.lapdog.sync.WebSync
import io.hazaar.lapdog.ui.theme.Hud
import io.hazaar.lapdog.ui.theme.body

/** Pairing codes use these (no 0 O 1 I L); case and the dash don't matter. */
private const val CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

private fun normaliseCode(s: String) = s.uppercase().filter { it in CODE_CHARS }

/** The settings list row's summary. */
fun syncSummary(s: SyncState, waiting: Int): String = when {
    !s.paired -> "Not paired · upload heats to lapdog.funkynerd.com"
    waiting > 0 -> "${heats(waiting)} waiting · " + if (s.auto) "syncs after each heat" else "sync is manual"
    else -> "Up to date · " + if (s.auto) "syncs after each heat" else "sync is manual"
}

private fun heats(n: Int) = if (n == 1) "1 heat" else "$n heats"

/** Settings > Website sync: pair with the pilot's account, auto sync, Sync now. */
@Composable
fun SyncPage() {
    val s by WebSync.state.collectAsStateWithLifecycle()
    val status by WebSync.status.collectAsStateWithLifecycle()
    val heats by HeatStore.heats.collectAsStateWithLifecycle()
    val waiting = remember(heats, s) { WebSync.pending(heats, s).size }

    if (!s.paired) PairForm(status.running) else Paired(s, waiting, status.running)

    status.message?.let {
        Text(
            it,
            style = body(14.sp, if (status.error) Hud.slow else Hud.textSoft, FontWeight.Medium),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun PairForm(busy: Boolean) {
    var code by rememberSaveable { mutableStateOf("") }
    val valid = normaliseCode(code).length == 6
    Text(
        "Upload your heats to your account on the LapDog website, the same way a LapDog LCD dashboard does. " +
            "On ${WebApi.BASE_URL.removePrefix("https://")}, sign in, open Devices and choose Pair a device, then enter the code it shows.",
        style = body(14.sp, Hud.textSoft).copy(lineHeight = 20.sp),
    )
    HudField("Pairing code", code, { code = it }, monoText = true, maxLength = 7, enabled = !busy)
    PrimaryButton("PAIR", { WebSync.pair(normaliseCode(code)) }, height = 56.dp, size = 18, enabled = valid && !busy)
}

@Composable
private fun Paired(s: SyncState, waiting: Int, busy: Boolean) {
    var askUnpair by remember { mutableStateOf(false) }
    HudCard(radius = 14.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Hud.ok, Hud.okTint) { tint, size -> Icons.Cloud(tint, size) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Paired as ${s.deviceName}", style = body(15.sp, weight = FontWeight.SemiBold))
                Text(
                    listOfNotNull(
                        s.pilotName.takeIf { it.isNotEmpty() }?.let { "$it's account" },
                        s.lastSyncAt?.let { "last synced " + DateUtils.getRelativeTimeSpanString(it).toString().lowercase() },
                    ).joinToString(" · ").ifEmpty { "Not synced yet" },
                    style = body(13.sp, Hud.muted),
                )
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        SettingRow("Sync after each heat", "Uploads a heat when it ends, or when the phone is next online") {
            HudSwitch(s.auto, WebSync::setAuto, "Sync after each heat")
        }
        SettingRow("Waiting to upload", "Finished heats with at least one lap", divider = false) {
            Text(if (waiting == 0) "None" else heats(waiting), style = body(15.sp, Hud.accent, FontWeight.Medium))
        }
    }
    PrimaryButton(if (busy) "SYNCING…" else "SYNC NOW", WebSync::syncNow, height = 56.dp, size = 18, enabled = !busy)
    SecondaryButton("Unpair this phone", { askUnpair = true }, Modifier.fillMaxWidth(), enabled = !busy)

    if (askUnpair) {
        HudDialog("Unpair this phone?", onDismiss = { askUnpair = false }) {
            Text(
                "Heats already uploaded stay on the website. To sync again you'll need a new pairing code.",
                style = body(14.sp, Hud.textSoft).copy(lineHeight = 20.sp),
            )
            DialogButtons("Unpair", { askUnpair = false; WebSync.unpair() }, { askUnpair = false })
        }
    }
}
