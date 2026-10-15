package io.hazaar.lapdog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hazaar.lapdog.protocol.Channels
import io.hazaar.lapdog.protocol.ElrsUid
import io.hazaar.lapdog.protocol.Format
import io.hazaar.lapdog.protocol.LapFrame.Mode
import io.hazaar.lapdog.protocol.TimerSettings
import io.hazaar.lapdog.protocol.TimerSettings.Link
import io.hazaar.lapdog.protocol.TimerSettings.RaceLimit
import io.hazaar.lapdog.protocol.TimerSettings.Sensitivity
import io.hazaar.lapdog.service.LapRepository
import io.hazaar.lapdog.state.Ignored
import io.hazaar.lapdog.state.TimerState
import io.hazaar.lapdog.ui.theme.Hud
import io.hazaar.lapdog.ui.theme.body
import io.hazaar.lapdog.ui.theme.heading
import io.hazaar.lapdog.ui.theme.mono
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GRAPH_MAX_DB = 32f

@Composable
fun SettingsScreen(state: TimerState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val timer = state.settings
    val editable = state.usbConnected && timer != null && !state.armed
    val scope = rememberCoroutineScope()

    // The pilot edits a copy; it follows the timer until the first edit.
    var base by remember { mutableStateOf(timer) }
    var draft by remember { mutableStateOf(timer ?: TimerSettings()) }
    LaunchedEffect(timer) {
        if (timer != null && (base == null || draft == base)) draft = timer
        base = timer
    }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    var saving by remember { mutableStateOf(false) }

    // Live signal for calibration while this screen is open.
    DisposableEffect(state.usbConnected) {
        if (state.usbConnected) LapRepository.fire("rssi on")
        onDispose { if (state.usbConnected) LapRepository.fire("rssi off") }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.padding(end = 8.dp).size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onBack)
                    .semantics { contentDescription = "Back to timer" },
                contentAlignment = Alignment.Center,
            ) { Icons.Back(Hud.text) }
            Text("Settings", style = heading(24.sp, spacing = 0.sp), modifier = Modifier.semantics { heading() })
        }

        ConnectionCard(state)

        ChannelSection(draft, editable) { draft = it }
        SensitivitySection(draft, editable) { draft = it }
        FalseReadSection(draft, editable, state.session?.ignored ?: state.lastSession?.ignored, state.session != null) { draft = it }
        RadioSection(draft, editable) { draft = it }
        MessagesSection(draft, editable) { draft = it }
        RaceDefaultsSection(draft, editable) { draft = it }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val commands = timer?.let { draft.commandsFrom(it) }.orEmpty()
            status?.let { (text, err) -> Text(text, style = body(13.sp, if (err) Hud.slow else Hud.muted)) }
            PrimaryButton(
                if (saving) "SAVING…" else "SAVE TO TIMER",
                onClick = {
                    saving = true
                    status = null
                    scope.launch {
                        val r = LapRepository.send(commands)
                        status = when (r) {
                            LapRepository.Result.Ok -> "Saved to the timer" to false
                            is LapRepository.Result.Failed -> "The timer rejected “${r.command}”: ${r.reason}" to true
                        }
                        LapRepository.refresh()
                        saving = false
                    }
                },
                height = 60.dp,
                size = 18,
                enabled = editable && commands.isNotEmpty() && !saving,
            )
            Text(
                "Stored on the timer, so it works without the phone",
                style = body(13.sp, Hud.muted),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ConnectionCard(state: TimerState) {
    val s = state.settings
    HudCard(radius = 14.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Dot(if (state.usbConnected) Hud.ok else Hud.toggleOff, 10.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (state.usbConnected) "Timer connected · USB" else "Timer not connected",
                    style = body(15.sp, weight = FontWeight.SemiBold),
                )
                Text(
                    when {
                        !state.usbConnected -> "Plug the timer in over USB to read and change its settings"
                        s == null -> "Reading settings…"
                        state.armed -> "Firmware ${s.firmware} · armed — disarm to change settings"
                        else -> "Firmware ${s.firmware} · idle — settings can be changed"
                    },
                    style = body(13.sp, Hud.muted),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, right: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            SectionLabel(title, Modifier.weight(1f).semantics { heading() })
            if (right != null) Text(right, style = mono(14.sp, Hud.accent, FontWeight.Medium))
        }
        content()
    }
}

@Composable
private fun ChannelSection(d: TimerSettings, enabled: Boolean, onChange: (TimerSettings) -> Unit) {
    val label = d.channelLabel
    var band by remember(label) { mutableStateOf(Channels.bands.firstOrNull { it.id == label.firstOrNull() } ?: Channels.bands[0]) }
    Section("Video channel", "$label · ${d.freqMhz} MHz") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Channels.bands.forEach { b ->
                val on = b == band
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (on) Hud.accent else Hud.surface)
                        .border(1.dp, if (on) Hud.accent else Hud.line, RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled, role = Role.Tab) { band = b }
                        .semantics { contentDescription = b.name; selected = on },
                    contentAlignment = Alignment.Center,
                ) { Text("${b.id}", style = body(15.sp, if (on) Hud.onAccent else Hud.textSoft, if (on) FontWeight.Bold else FontWeight.Medium)) }
            }
        }
        Label(band.name, size = 13)
        band.mhz.chunked(4).forEachIndexed { row, freqs ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                freqs.forEachIndexed { i, mhz ->
                    val ch = "${band.id}${row * 4 + i + 1}"
                    val on = ch == label
                    Column(
                        Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) Hud.selected else Hud.surface)
                            .border(1.dp, if (on) Hud.accent else Hud.line, RoundedCornerShape(10.dp))
                            .clickable(enabled = enabled, role = Role.RadioButton) { onChange(d.copy(chan = ch, freqMhz = mhz)) }
                            .semantics { selected = on },
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val c = if (on) Hud.selectedText else Hud.text
                        Text(ch, style = mono(16.sp, c))
                        Text("$mhz", style = mono(12.sp, c.copy(alpha = 0.8f), FontWeight.Medium))
                    }
                }
            }
        }
        Text(
            "HDZero: use Raceband. Mid-band channels detect best; sensitivity drops near 5.9 GHz.",
            style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
        )
    }
}

@Composable
private fun SensitivitySection(d: TimerSettings, enabled: Boolean, onChange: (TimerSettings) -> Unit) {
    val rssi by LapRepository.rssi.collectAsStateWithLifecycle()
    Section("Gate sensitivity") {
        val presets = Sensitivity.entries
        Segmented(
            presets.map { it.label to null },
            presets.indexOf(d.sensitivity),
            { i ->
                val p = presets[i]
                if (p != Sensitivity.CUSTOM) onChange(d.copy(enterDb = p.enterDb, exitDb = p.exitDb))
            },
            height = 40.dp,
            enabled = enabled,
            description = "Sensitivity preset",
        )
        HudCard(radius = 14.dp, padding = 14.dp, spacing = 10.dp) {
            Row {
                Text("Live signal · ${d.channelLabel}", style = body(13.sp, Hud.muted), modifier = Modifier.weight(1f))
                Text(rssi.maxOrNull()?.let { "peak +${it.roundToInt()} dB" } ?: "no signal yet", style = mono(13.sp, weight = FontWeight.Medium))
            }
            SignalGraph(rssi, d.enterDb, d.exitDb, enabled) { enter, exit -> onChange(d.copy(enterDb = enter, exitDb = exit)) }
            Text(
                "Fly a few passes, then drag the lines: enter just under your peaks, exit a little above the floor.",
                style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
            )
        }
        NumberField("Smoothing", "%.2f".format(java.util.Locale.ROOT, d.alpha), Modifier.fillMaxWidth(), enabled) { t ->
            t.toFloatOrNull()?.takeIf { it in 0.01f..1f }?.let { onChange(d.copy(alpha = it)) }
        }
    }
}

@Composable
private fun FalseReadSection(d: TimerSettings, enabled: Boolean, ignored: Ignored?, live: Boolean, onChange: (TimerSettings) -> Unit) {
    Section("False-read protection") {
        HudCard(radius = 14.dp, padding = 16.dp, spacing = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Minimum lap time", style = body(15.sp, weight = FontWeight.Medium))
                    Text("Passes sooner than this are ignored", style = body(13.sp, Hud.muted))
                }
                val range = TimerSettings.MIN_LAP_RANGE_MS
                val step = TimerSettings.MIN_LAP_STEP_MS
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.semantics { contentDescription = "Minimum lap time" },
                ) {
                    StepButton("−", "Shorter", enabled && d.minLapMs > range.first) {
                        onChange(d.copy(minLapMs = (d.minLapMs - step).coerceIn(range)))
                    }
                    Text(
                        "%.1f s".format(java.util.Locale.ROOT, d.minLapMs / 1000f),
                        style = mono(18.sp),
                        modifier = Modifier.widthIn(min = 64.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    StepButton("+", "Longer", enabled && d.minLapMs < range.last) {
                        onChange(d.copy(minLapMs = (d.minLapMs + step).coerceIn(range)))
                    }
                }
            }
            Text(
                "Stops a crash into the gate, or a bounce back through it, from counting as a lap. Set it a few seconds under your fastest real lap.",
                style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
            )
        }
        SettingRow(
            "Ignore parked quads",
            "A signal peak longer than ${fmtSeconds(d.maxPeakMs)} s is a quad sitting at the gate, not a flyby",
            divider = false,
        ) {
            HudSwitch(d.ignoreParked, { onChange(d.copy(ignoreParked = it)) }, "Ignore parked quads", enabled)
        }
        ignored?.describe()?.let { IgnoredNote((if (live) "This heat: " else "Last session: ") + it) }
    }
}

private fun fmtSeconds(ms: Int) = if (ms % 1000 == 0) "${ms / 1000}" else "%.1f".format(java.util.Locale.ROOT, ms / 1000f)

@Composable
private fun StepButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Hud.bg)
            .border(1.dp, Hud.line, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = body(20.sp, if (enabled) Hud.text else Hud.toggleOff)) }
}

/** A text field that keeps what's typed and reports it; [onText] applies it when valid. */
@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, enabled: Boolean, onText: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    HudField(label, text, { text = it; onText(it) }, modifier, monoText = true, numeric = true, maxLength = 8, enabled = enabled, height = 44.dp)
}

@Composable
private fun SignalGraph(samples: List<Float>, enterDb: Float, exitDb: Float, enabled: Boolean, onChange: (Float, Float) -> Unit) {
    var dragging by remember { mutableStateOf<Boolean?>(null) } // true = enter line
    var enter by remember(enterDb) { mutableStateOf(enterDb) }
    var exit by remember(exitDb) { mutableStateOf(exitDb) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Hud.bg)
            .semantics { contentDescription = "Live signal with enter line at +${enter.roundToInt()} dB and exit line at +${exit.roundToInt()} dB" }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    fun dbAt(y: Float) = ((1 - y / size.height) * GRAPH_MAX_DB).coerceIn(0f, GRAPH_MAX_DB)
                    detectVerticalDragGestures(
                        onDragStart = { o -> val db = dbAt(o.y); dragging = abs(db - enter) <= abs(db - exit) },
                        onDragEnd = { dragging = null; onChange(enter, exit) },
                        onDragCancel = { dragging = null; onChange(enter, exit) },
                    ) { change, _ ->
                        val db = (dbAt(change.position.y) * 2).roundToInt() / 2f
                        if (dragging == true) enter = db.coerceAtLeast(exit + 0.5f)
                        else exit = db.coerceAtMost(enter - 0.5f).coerceAtLeast(0f)
                    }
                },
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            fun y(db: Float) = size.height * (1 - (db / GRAPH_MAX_DB).coerceIn(0f, 1f))
            if (samples.size > 1) {
                val path = Path()
                val step = size.width / (200 - 1)
                val x0 = size.width - step * (samples.size - 1)
                samples.forEachIndexed { i, db -> if (i == 0) path.moveTo(x0, y(db)) else path.lineTo(x0 + i * step, y(db)) }
                drawPath(path, Hud.textSoft, style = Stroke(2.dp.toPx()))
            }
            drawLine(Hud.accent, Offset(0f, y(enter)), Offset(size.width, y(enter)), 2.dp.toPx())
            drawLine(
                Hud.muted, Offset(0f, y(exit)), Offset(size.width, y(exit)), 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            )
        }
        val density = androidx.compose.ui.platform.LocalDensity.current
        with(density) {
            Text(
                "ENTER +${fmtDb(enter)}", style = mono(11.sp, Hud.accent),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = (140.dp * (1 - enter / GRAPH_MAX_DB) - 18.dp).coerceAtLeast(0.dp)),
            )
            Text(
                "EXIT +${fmtDb(exit)}", style = mono(11.sp, Hud.muted),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = (140.dp * (1 - exit / GRAPH_MAX_DB) - 18.dp).coerceAtLeast(0.dp)),
            )
        }
    }
}

private fun fmtDb(v: Float) = if (v == v.toInt().toFloat()) "${v.toInt()}" else "%.1f".format(java.util.Locale.ROOT, v)

@Composable
private fun RadioSection(d: TimerSettings, enabled: Boolean, onChange: (TimerSettings) -> Unit) {
    Section("Radio output") {
        Segmented(
            listOf("Bluetooth" to "LapDog app", "ESP-NOW" to "Goggles · RacePanel"),
            if (d.link == Link.ESPNOW) 1 else 0,
            { onChange(d.copy(link = if (it == 1) Link.ESPNOW else Link.BLE)) },
            height = 64.dp,
            enabled = enabled,
            description = "Radio output",
        )
        Text(
            if (d.link == Link.BLE)
                "The timer has one radio, so it sends over Bluetooth or ESP-NOW, not both. Bluetooth reaches this app with the phone unplugged; goggles get nothing."
            else
                "The timer has one radio, so it sends over Bluetooth or ESP-NOW, not both. Goggles and RacePanel show laps on the OSD; this app only hears the timer over USB.",
            style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
        )
        if (d.link == Link.ESPNOW) {
            HudField("ELRS bind phrase", d.bindPhrase, { onChange(d.copy(bindPhrase = it)) }, password = true, maxLength = 63, enabled = enabled)
            Row {
                Label("UID ", size = 12)
                Text(ElrsUid.format(ElrsUid.fromBindPhrase(d.bindPhrase)), style = mono(12.sp, Hud.muted, FontWeight.Medium))
                Label(" · 2.4 GHz ch 1", size = 12)
            }
        }
        SettingRow("USB", "Everything, whenever the phone is plugged in", divider = false) {
            Text("Always", style = body(13.sp, Hud.muted))
        }
    }
}

@Composable
private fun MessagesSection(d: TimerSettings, enabled: Boolean, onChange: (TimerSettings) -> Unit) {
    Section("Race messages") {
        Text(
            "Kept on the timer. It counts heats itself and sends these over ${if (d.link == Link.BLE) "Bluetooth" else "ESP-NOW"} with the laps, so goggles and this app show the same thing.",
            style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
        )
        Column {
            SettingRow("Event name", null) {
                HudSwitch(d.msgEvent, { onChange(d.copy(msgEvent = it)) }, "Broadcast event name", enabled)
            }
            if (d.msgEvent) {
                HudField("Shown while staging", d.event, { onChange(d.copy(event = it)) }, Modifier.padding(vertical = 10.dp), maxLength = TimerSettings.EVENT_MAX, enabled = enabled)
            }
            SettingRow("Heat name", "Numbers itself each session") {
                HudSwitch(d.msgHeat, { onChange(d.copy(msgHeat = it)) }, "Broadcast heat name", enabled)
            }
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudField("Prefix", d.heatPrefix, { onChange(d.copy(heatPrefix = it)) }, Modifier.weight(2f), maxLength = TimerSettings.PREFIX_MAX, enabled = enabled)
                NumberField("Next # (timer)", "${d.nextHeat}", Modifier.weight(1f), enabled) { t ->
                    t.toIntOrNull()?.takeIf { it in 1..65535 }?.let { onChange(d.copy(nextHeat = it)) }
                }
            }
            SettingRow("Race start & finish", "Race mode only") {
                HudSwitch(d.msgStart, { onChange(d.copy(msgStart = it)) }, "Broadcast race start and finish", enabled)
            }
            if (d.msgStart) {
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudField("Stage", d.textStage, { onChange(d.copy(textStage = it.uppercase())) }, Modifier.weight(1f), monoText = true, maxLength = TimerSettings.MSG_MAX, enabled = enabled, height = 44.dp)
                    HudField("Start", d.textGo, { onChange(d.copy(textGo = it.uppercase())) }, Modifier.weight(1f), monoText = true, maxLength = TimerSettings.MSG_MAX, enabled = enabled, height = 44.dp)
                    HudField("Finish", d.textFinish, { onChange(d.copy(textFinish = it.uppercase())) }, Modifier.weight(1f), monoText = true, maxLength = TimerSettings.MSG_MAX, enabled = enabled, height = 44.dp)
                }
            }
            // CMRC's lap row has no room for a "BEST" callout, so there's no switch for one.
            SettingRow("Lap times", "Lap and position on row 3, each lap's time on row 15", divider = false) {
                HudSwitch(d.msgLaps, { onChange(d.copy(msgLaps = it)) }, "Broadcast lap times", enabled)
            }
        }
    }
}

@Composable
private fun RaceDefaultsSection(d: TimerSettings, enabled: Boolean, onChange: (TimerSettings) -> Unit) {
    Section("Race defaults") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Lap limit", "${d.raceLaps} laps", Modifier.weight(1f), enabled) { t ->
                t.removeSuffix("laps").trim().toIntOrNull()?.takeIf { it in 1..99 }?.let { onChange(d.copy(raceLaps = it)) }
            }
            NumberField("Time limit", Format.minutes(d.raceTimeS), Modifier.weight(1f), enabled) { t ->
                Format.parseMinutes(t)?.takeIf { it in 10..3600 }?.let { onChange(d.copy(raceTimeS = it)) }
            }
        }
        Column {
            SettingRow("Race ends on", null) {
                ChoiceChip("Laps", d.raceLimit == RaceLimit.LAPS, { onChange(d.copy(raceLimit = RaceLimit.LAPS)) }, enabled)
                ChoiceChip("Time", d.raceLimit == RaceLimit.TIME, { onChange(d.copy(raceLimit = RaceLimit.TIME)) }, enabled)
            }
            SettingRow("BOOT button arms", "Long press on the timer") {
                ChoiceChip("Practice", d.mode == Mode.PRACTICE, { onChange(d.copy(mode = Mode.PRACTICE)) }, enabled)
                ChoiceChip("Race", d.mode == Mode.RACE, { onChange(d.copy(mode = Mode.RACE)) }, enabled)
            }
            SettingRow("Random start hold", "1–3 s between “${d.textStage}” and “${d.textGo}”", divider = false) {
                HudSwitch(d.randomHold, { onChange(d.copy(randomHold = it)) }, "Random start hold", enabled)
            }
        }
    }
}

