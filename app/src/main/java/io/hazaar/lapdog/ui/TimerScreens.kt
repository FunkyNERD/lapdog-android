package io.hazaar.lapdog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.hazaar.lapdog.protocol.Format
import io.hazaar.lapdog.protocol.LapFrame.Mode
import io.hazaar.lapdog.protocol.LapFrame.Stage
import io.hazaar.lapdog.protocol.TimerSettings
import io.hazaar.lapdog.service.LapRepository
import io.hazaar.lapdog.state.HeatRecord
import io.hazaar.lapdog.state.LapEntry
import io.hazaar.lapdog.state.Session
import io.hazaar.lapdog.state.TimerState
import io.hazaar.lapdog.state.Via
import io.hazaar.lapdog.ui.theme.ChakraPetch
import io.hazaar.lapdog.ui.theme.Hud
import io.hazaar.lapdog.ui.theme.body
import io.hazaar.lapdog.ui.theme.heading
import io.hazaar.lapdog.ui.theme.mono
import kotlinx.coroutines.delay

/** Local clock, ticking every [periodMs] for running lap timers. */
@Composable
fun rememberNow(periodMs: Long = 100): Long {
    val now by produceState(LapRepository.now()) {
        while (true) {
            value = LapRepository.now()
            delay(periodMs)
        }
    }
    return now
}

/** Status text and dot for the header chip. */
fun linkStatus(state: TimerState, running: Boolean, now: Long): Pair<String, Color> {
    val heardBle = state.lastHeardVia == Via.BLE && state.lastHeardAt != null && now - state.lastHeardAt < 15_000
    return when {
        state.usbConnected -> "USB · " + (if (state.armed) "Timer armed" else "Timer ready") to Hud.ok
        heardBle -> "BLE · " + (if (state.armed) "Timer armed" else "Timer heard") to Hud.ok
        running -> "Listening for timer" to Hud.muted
        else -> "Not listening" to Hud.toggleOff
    }
}

private val screenPadding = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp)

// --- Idle --------------------------------------------------------------------------

sealed interface ArmChoice {
    data object Practice : ArmChoice
    data class RaceLaps(val laps: Int) : ArmChoice
    data class RaceTime(val seconds: Int) : ArmChoice

    val command
        get() = when (this) {
            Practice -> "arm practice"
            is RaceLaps -> "arm race laps $laps"
            is RaceTime -> "arm race time $seconds"
        }
}

@Composable
fun IdleScreen(
    state: TimerState,
    running: Boolean,
    message: String?,
    lastHeat: HeatRecord?,
    hasHistory: Boolean,
    onSettings: () -> Unit,
    onArm: (ArmChoice) -> Unit,
    onOpenHeat: (HeatRecord) -> Unit,
    onHistory: () -> Unit,
) {
    val now = rememberNow(1000)
    val s = state.settings
    var race by remember(s?.mode) { mutableStateOf(s?.mode == Mode.RACE) }
    var byTime by remember(s?.raceLimit) { mutableStateOf(s?.raceLimit == TimerSettings.RaceLimit.TIME) }
    val laps = s?.raceLaps ?: 3
    val timeS = s?.raceTimeS ?: 120

    Column(Modifier.fillMaxSize().then(screenPadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    append("LAP")
                    withStyle(SpanStyle(color = Hud.accent)) { append("DOG") }
                },
                style = heading(22.sp),
                modifier = Modifier.weight(1f),
            )
            val (status, dot) = linkStatus(state, running, now)
            StatusChip(status, dot)
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onSettings)
                    .semantics { contentDescription = "Settings" },
                contentAlignment = Alignment.Center,
            ) { Icons.Gear(Hud.textSoft) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.event.isNotEmpty()) SectionLabel(state.event)
            Text(
                state.nextHeat?.let { state.heatName(it) } ?: state.heatPrefix,
                style = heading(40.sp, spacing = 0.em),
                modifier = Modifier.semantics { heading() },
            )
            Label(
                when {
                    s != null -> "Next heat, from the timer"
                    state.nextHeat != null -> "Next heat, from the last one heard"
                    else -> "Plug the timer in to read its settings"
                },
                size = 13,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoCard("Channel", s?.channelLabel ?: "—", s?.let { "${it.freqMhz} MHz" } ?: "", Modifier.weight(1f))
            InfoCard(
                "Sensitivity",
                s?.sensitivity?.label?.uppercase() ?: "—",
                s?.let { "+${it.enterDb.toInt()} / +${it.exitDb.toInt()} dB" } ?: "",
                Modifier.weight(1f),
            )
            val link = s?.link ?: if (state.lastHeardVia == Via.BLE) TimerSettings.Link.BLE else null
            InfoCard(
                "Radio out",
                when (link) { TimerSettings.Link.BLE -> "BLE"; TimerSettings.Link.ESPNOW -> "ESP-NOW"; null -> "—" },
                when (link) { TimerSettings.Link.BLE -> "to this app"; TimerSettings.Link.ESPNOW -> "to goggles"; null -> "" },
                Modifier.weight(1f),
                monoDetail = false,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Segmented(
                listOf("Practice" to null, "Race" to null),
                if (race) 1 else 0,
                { race = it == 1 },
                description = "Session mode",
            )
            if (!race) {
                HudCard(padding = 16.dp, spacing = 6.dp) {
                    Text("Free practice", style = body(15.sp, weight = FontWeight.SemiBold))
                    Text(
                        "Arm, then fly. Your first pass through the gate starts lap 1. No limit — disarm when you land.",
                        style = body(14.sp, Hud.muted).copy(lineHeight = 20.sp),
                    )
                }
            } else {
                HudCard(padding = 16.dp, spacing = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Race to", style = body(15.sp, weight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        ChoiceChip("$laps laps", !byTime, { byTime = false })
                        HSpacer(6.dp)
                        ChoiceChip(Format.minutes(timeS), byTime, { byTime = true })
                    }
                    Text(
                        buildAnnotatedString {
                            append("Goggles show ")
                            withStyle(SpanStyle(fontFamily = io.hazaar.lapdog.ui.theme.JetBrainsMono, color = Hud.text)) {
                                append(s?.textStage ?: "ARM NOW")
                            }
                            append(", then ")
                            withStyle(SpanStyle(fontFamily = io.hazaar.lapdog.ui.theme.JetBrainsMono, color = Hud.text)) {
                                append(s?.textGo ?: "GO!")
                            }
                            append(if (s?.randomHold == false) " after a 2 s hold." else " after a random 1–3 s hold.")
                        },
                        style = body(14.sp, Hud.muted).copy(lineHeight = 20.sp),
                    )
                }
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val last = lastHeat
            if (last != null || hasHistory) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Last session", Modifier.weight(1f))
                    if (hasHistory) {
                        Text(
                            "View all heats",
                            style = body(14.sp, Hud.accent),
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onHistory).padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
            if (last != null) {
                val st = last.stats
                val best = st.best?.let { Format.lap(it.ms) } ?: "—"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Hud.surface)
                        .border(1.dp, Hud.line, RoundedCornerShape(12.dp))
                        .clickable { onOpenHeat(last) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Last session: ${last.name}, ${st.count} laps, best $best. Show heat details"
                        }
                        .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Stat(last.name, "${st.count} laps", Modifier.weight(1f), size = 17)
                    Stat("Best", best, Modifier.weight(1f), Hud.best, 17)
                    Stat("Average", st.averageMs?.let(Format::lap) ?: "—", Modifier.weight(1f), size = 17)
                    Icons.Forward(Hud.chevron)
                }
                last.ignored.describe()?.let { IgnoredNote(it) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (message != null) Text(message, style = body(13.sp, Hud.slow), modifier = Modifier.fillMaxWidth())
            PrimaryButton(
                if (race) "STAGE RACE" else "ARM TIMER",
                onClick = {
                    onArm(
                        when {
                            !race -> ArmChoice.Practice
                            byTime -> ArmChoice.RaceTime(timeS)
                            else -> ArmChoice.RaceLaps(laps)
                        },
                    )
                },
                enabled = state.usbConnected,
            )
            Text(
                if (state.usbConnected) "Or long-press BOOT on the timer" else "Long-press BOOT on the timer to arm it",
                style = body(13.sp, Hud.muted),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String, detail: String, modifier: Modifier, monoDetail: Boolean = true) {
    HudCard(modifier) {
        Label(label)
        Text(value, style = mono(20.sp), maxLines = 1)
        Text(detail, style = if (monoDetail) mono(12.sp, Hud.muted, FontWeight.Medium) else body(12.sp, Hud.muted), maxLines = 1)
    }
}

// --- Armed header ----------------------------------------------------------------------

@Composable
private fun ArmedHeader(title: String, subtitle: String, right: String, via: Via?) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(Hud.accent, 12.dp, ring = Hud.accentRing)
        HSpacer(10.dp)
        Text(title, style = heading(18.sp, Hud.accent, 0.1.em))
        HSpacer(10.dp)
        Text(subtitle, style = body(14.sp, Hud.muted), modifier = Modifier.weight(1f), maxLines = 1)
        if (via == Via.BLE) Icons.Bluetooth(Hud.muted) else if (via == Via.USB) Icons.Usb(Hud.muted)
        HSpacer(6.dp)
        Text(right, style = mono(14.sp, Hud.muted, FontWeight.Medium))
    }
}

@Composable
private fun ControlHint(state: TimerState) {
    if (!state.usbConnected) {
        Text(
            "Long-press BOOT on the timer to disarm",
            style = body(13.sp, Hud.muted),
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// --- Armed practice ------------------------------------------------------------------

@Composable
fun PracticeScreen(state: TimerState, session: Session, message: String?, onNewHeat: () -> Unit, onDisarm: () -> Unit) {
    val now = rememberNow()
    val stats = session.stats
    Column(Modifier.fillMaxSize().then(screenPadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ArmedHeader(
            "ARMED",
            "${state.heatName(session.heat)} · Practice",
            if (state.lastHeardVia == Via.BLE) "BLE" else "USB",
            state.lastHeardVia,
        )

        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SectionLabel(if (session.started) "Lap ${stats.count + 1} · in progress" else "Waiting for the first pass")
            Text(
                Format.running(session.lastPassAt?.let { now - it } ?: 0),
                style = mono(84.sp).copy(letterSpacing = (-0.02).em, lineHeight = 84.sp),
            )
        }

        val last = stats.last
        HudCard(radius = 16.dp, padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label(last?.let { "Last lap · ${it.lap}" } ?: "Last lap")
                    Text(last?.let { Format.lap(it.ms) } ?: "—", style = mono(34.sp))
                }
                val delta = stats.lastDeltaMs
                if (last != null && delta != null) {
                    val best = delta == 0L
                    Text(
                        if (best) "BEST" else Format.delta(delta),
                        style = mono(18.sp, if (best) Hud.best else Hud.slow),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (best) Hud.selected else Hud.slowBg)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(stats.best?.let { "Best · lap ${it.lap}" } ?: "Best", stats.best?.let { Format.lap(it.ms) } ?: "—", Modifier.weight(1f), Hud.best)
            Stat("Average", stats.averageMs?.let(Format::lap) ?: "—", Modifier.weight(1f))
            Stat("Best 3 consec.", stats.bestThreeMs?.let(Format::lap) ?: "—", Modifier.weight(1f))
        }

        session.ignored.describe()?.let { IgnoredNote(it) }
        LapList(stats.laps, Modifier.weight(1f))

        if (message != null) Text(message, style = body(13.sp, Hud.slow))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("New heat", onNewHeat, Modifier.weight(1f), enabled = state.usbConnected)
            HoldButton("HOLD TO DISARM", onDisarm, Modifier.weight(2f), enabled = state.usbConnected)
        }
        ControlHint(state)
    }
}

@Composable
private fun LapList(laps: List<LapEntry>, modifier: Modifier) {
    val best = laps.minOfOrNull { it.ms }
    val max = laps.maxOfOrNull { it.ms }
    LazyColumn(modifier.semantics { contentDescription = "Laps" }) {
        items(laps.reversed(), key = { it.lap }) { lap ->
            val isBest = lap.ms == best
            val span = (max ?: 0) - (best ?: 0)
            val pct = 0.4f + 0.6f * (if (span > 0) (lap.ms - best!!).toFloat() / span else 0f)
            Row(
                Modifier.fillMaxWidth().height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("%02d".format(lap.lap), style = mono(14.sp, Hud.muted, FontWeight.Medium), modifier = Modifier.width(36.dp))
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Hud.divider)) {
                    Box(Modifier.fillMaxWidth(pct).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (isBest) Hud.best else Hud.bar))
                }
                Text(
                    Format.lap(lap.ms),
                    style = mono(16.sp, if (isBest) Hud.best else Hud.text),
                    modifier = Modifier.width(96.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                Text(
                    if (isBest) "BEST" else Format.delta(lap.ms - best!!),
                    style = mono(13.sp, if (isBest) Hud.best else Hud.muted, FontWeight.Medium),
                    modifier = Modifier.width(64.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.divider))
        }
    }
}

// --- Race ----------------------------------------------------------------------------

@Composable
fun RaceScreen(state: TimerState, session: Session, message: String?, onRestart: () -> Unit, onStop: () -> Unit) {
    val now = rememberNow()
    val stats = session.stats
    val raceMs = session.raceMs ?: session.goAt?.let { now - it } ?: 0
    val limit = if (session.lapLimit > 0) "${session.lapLimit} laps" else Format.minutes(session.timeLimitS)
    Column(Modifier.fillMaxSize().then(screenPadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ArmedHeader("RACE", "${state.heatName(session.heat)} · $limit", "T ${Format.running(raceMs)}", state.lastHeardVia)

        RaceProgress(session, raceMs)

        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (session.stage) {
                Stage.STAGING -> {
                    Text("STAGING", style = heading(22.sp, Hud.muted, 0.1.em))
                    Text(state.osdRows[5] ?: state.settings?.textStage ?: "ARM NOW", style = heading(64.sp, Hud.accent, 0.04.em))
                }
                Stage.FINISHED -> {
                    Text("FINISHED", style = heading(22.sp, Hud.accent, 0.1.em))
                    Text(Format.lap(raceMs), style = mono(64.sp).copy(letterSpacing = (-0.02).em))
                }
                else -> {
                    val lapNo = stats.count + 1
                    Text(
                        buildAnnotatedString {
                            append(if (session.started) "LAP $lapNo " else "HOLESHOT ")
                            if (session.lapLimit > 0 && session.started) withStyle(SpanStyle(color = Hud.muted)) { append("/ ${session.lapLimit}") }
                        },
                        style = heading(22.sp, spacing = 0.1.em),
                    )
                    Text(
                        Format.running((session.lastPassAt ?: session.goAt)?.let { now - it } ?: 0),
                        style = mono(84.sp).copy(letterSpacing = (-0.02).em, lineHeight = 84.sp),
                    )
                }
            }
        }

        Column {
            val rows = buildList {
                session.holeshotMs?.let { add(Triple("Hole", "Start → first gate", it)) }
                stats.laps.forEach { add(Triple("Lap ${it.lap}", if (stats.isBest(it)) "BEST" else "", it.ms)) }
            }
            rows.takeLast(4).forEach { (name, note, ms) ->
                val best = note == "BEST"
                RaceRow(name, note, Format.lap(ms), if (best) Hud.best else Hud.text, best)
            }
            if (session.stage == Stage.RACING && session.started) RaceRow("Lap ${stats.count + 1}", "", "running", Hud.muted, false, divider = false)
        }

        session.ignored.describe()?.let { IgnoredNote(it) }
        TimerMessages(state, now, Modifier.weight(1f))

        if (message != null) Text(message, style = body(13.sp, Hud.slow))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Restart", onRestart, Modifier.weight(1f), enabled = state.usbConnected)
            HoldButton(if (session.stage == Stage.FINISHED) "HOLD TO END HEAT" else "HOLD TO STOP RACE", onStop, Modifier.weight(2f), enabled = state.usbConnected)
        }
        ControlHint(state)
    }
}

@Composable
private fun RaceProgress(session: Session, raceMs: Long) {
    if (session.lapLimit > 0) {
        Row(Modifier.fillMaxWidth().semantics { contentDescription = "Race progress" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val done = session.laps.size
            repeat(session.lapLimit) { i ->
                val c = when {
                    i < done -> Hud.accent
                    i == done && session.started && session.stage == Stage.RACING -> Hud.accentDim
                    else -> Hud.divider
                }
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
            }
        }
    } else if (session.timeLimitS > 0) {
        val f = (raceMs / (session.timeLimitS * 1000f)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Hud.divider).semantics { contentDescription = "Race time used" }) {
            Box(Modifier.fillMaxWidth(f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Hud.accent))
        }
    }
}

@Composable
private fun RaceRow(name: String, note: String, value: String, color: Color, bold: Boolean, divider: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = body(14.sp, Hud.muted), modifier = Modifier.width(64.dp))
        Text(
            note,
            style = if (note == "BEST") body(12.sp, Hud.best).copy(letterSpacing = 0.06.em) else body(13.sp, Hud.muted),
            modifier = Modifier.weight(1f),
        )
        Text(value, style = mono(16.sp, color, if (bold) FontWeight.Bold else FontWeight.Medium))
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.divider))
}

/** "From the timer": the race messages exactly as the goggles get them, by OSD row. */
@Composable
private fun TimerMessages(state: TimerState, now: Long, modifier: Modifier) {
    Column(modifier.semantics { contentDescription = "Race messages from the timer" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            SectionLabel("From the timer", Modifier.weight(1f))
            val at = state.osdAt
            if (at != null) Label("${state.osdVia?.label ?: ""} · ${"%.1f".format((now - at) / 1000f)} s ago")
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Hud.panel)
                .border(1.dp, Hud.line, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val rows = state.osdRows.toSortedMap()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.filterKeys { it < 15 }.forEach { (r, t) -> PanelRow(r, t, state) }
                if (rows.isEmpty()) Text("Nothing yet", style = mono(13.sp, Hud.panelRowNo, FontWeight.Medium))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.filterKeys { it >= 15 }.forEach { (r, t) -> PanelRow(r, t, state) }
            }
        }
    }
}

@Composable
private fun PanelRow(row: Int, text: String, state: TimerState) {
    val go = row == 5 && text == (state.settings?.textGo ?: "GO!")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("%02d".format(row), style = mono(13.sp, Hud.panelRowNo, FontWeight.Medium), modifier = Modifier.width(22.dp))
        Text(text, style = mono(13.sp, if (go) Hud.accent else Hud.panelText, FontWeight.Medium))
    }
}
