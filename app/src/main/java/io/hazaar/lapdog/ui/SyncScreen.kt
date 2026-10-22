package io.hazaar.lapdog.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import io.hazaar.lapdog.ui.theme.mono
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
    val valid = code.length == 6
    Text(
        "Upload your heats to your account on the LapDog website, the same way a LapDog LCD dashboard does. " +
            "On ${WebApi.BASE_URL.removePrefix("https://")}, sign in, open Devices and choose Pair a device, then enter the code it shows.",
        style = body(14.sp, Hud.textSoft).copy(lineHeight = 20.sp),
    )
    PairCodeField(code, { code = it }, enabled = !busy) { if (!busy) WebSync.pair(code) }
    PrimaryButton("PAIR", { WebSync.pair(code) }, height = 56.dp, size = 18, enabled = valid && !busy)
}

/**
 * The pairing code as the website shows it: six character boxes with a dash after the
 * third. One hidden text field underneath, so typing, backspace and pasting a whole code
 * (with or without the dash, any case) all work; [code] only ever holds valid characters.
 */
@Composable
private fun PairCodeField(code: String, onChange: (String) -> Unit, enabled: Boolean, onDone: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = code,
        onValueChange = { onChange(normaliseCode(it).take(6)) },
        enabled = enabled,
        singleLine = true,
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (normaliseCode(code).length == 6) onDone() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = "Pairing code" },
        decorationBox = { _ ->
            // 44 x 58 boxes like the website's phone layout, narrower on a small screen.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val w = minOf(44.dp, (maxWidth - 24.dp - 6.dp * 6) / 6)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in 0 until 6) {
                        if (i == 3) Box(Modifier.padding(horizontal = 2.dp).size(14.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Hud.muted))
                        // The box the next character goes in is outlined while typing.
                        val active = focused && enabled && i == code.length.coerceAtMost(5)
                        Box(
                            Modifier.size(w, w * 58 / 44).clip(RoundedCornerShape(8.dp)).background(Hud.panel)
                                .border(1.dp, if (active) Hud.accent else Hud.grid, RoundedCornerShape(8.dp))
                                .drawBehind {
                                    val h = 3.dp.toPx()
                                    drawRect(Hud.accent.copy(alpha = 0.6f), Offset(0f, size.height - h), Size(size.width, h))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(code.getOrNull(i)?.toString() ?: "", style = mono(30.sp, Hud.text, FontWeight.Bold))
                        }
                    }
                }
            }
        },
    )
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
