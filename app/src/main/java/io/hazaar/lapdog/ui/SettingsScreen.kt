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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import io.hazaar.lapdog.protocol.TimerSettings.RaceLimit
import io.hazaar.lapdog.protocol.TimerSettings.Sensitivity
import io.hazaar.lapdog.service.HeatStore
import io.hazaar.lapdog.service.LapRepository
import io.hazaar.lapdog.service.LapSound
import io.hazaar.lapdog.service.LapSounds
import io.hazaar.lapdog.state.Ignored
import io.hazaar.lapdog.state.TimerState
import io.hazaar.lapdog.sync.WebSync
import io.hazaar.lapdog.ui.theme.Hud
import io.hazaar.lapdog.ui.theme.body
import io.hazaar.lapdog.ui.theme.heading
import io.hazaar.lapdog.ui.theme.mono
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GRAPH_MAX_DB = 32f

/** One page per category; the list is the screen's home. [phone]: kept on this phone, not the timer. */
private enum class Page(val title: String, val phone: Boolean = false) {
    CHANNEL("Channel & detection"),
    FALSE_READ("False-read protection"),
    RADIO("Radio output"),
    MESSAGES("Race messages"),
    RACE("Race defaults"),
    LAP_SOUND("Lap sound", phone = true),
    SYNC("Website sync", phone = true),
}

private val timerPages = Page.entries.filter { !it.phone }

/** The category's icon tile: blue for timer settings, purple for this phone's. */
@Composable
private fun PageIcon(p: Page, size: androidx.compose.ui.unit.Dp = 40.dp) {
    IconTile(if (p.phone) Hud.best else Hud.accent, if (p.phone) Hud.bestTint else Hud.selected, size) { tint, s ->
        when (p) {
            Page.CHANNEL -> Icons.Antenna(tint, s)
            Page.FALSE_READ -> Icons.Shield(tint, s)
            Page.RADIO -> Icons.Broadcast(tint, s)
            Page.MESSAGES -> Icons.Message(tint, s)
            Page.RACE -> Icons.Flag(tint, s)
            Page.LAP_SOUND -> Icons.Speaker(tint, s)
            Page.SYNC -> Icons.Cloud(tint, s)
        }
    }
}

@Composable
fun SettingsScreen(state: TimerState, onBack: () -> Unit) {
    val timer = state.settings
    val editable = state.usbConnected && timer != null && !state.armed
    val scope = rememberCoroutineScope()

    // The pilot edits a copy; it follows the timer until the first edit. It's kept
    // across pages, so leaving a page loses nothing; leaving Settings asks.
    var base by remember { mutableStateOf(timer) }
    var draft by remember { mutableStateOf(timer ?: TimerSettings()) }
    LaunchedEffect(timer) {
        if (timer != null && (base == null || draft == base)) draft = timer
        base = timer
    }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    var saving by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var askLeave by remember { mutableStateOf(false) }

    val commands = timer?.let { draft.commandsFrom(it) }.orEmpty()
    val unsaved = (commands.size - 1).coerceAtLeast(0) // commandsFrom() ends with "save"
    val edit: (TimerSettings) -> Unit = { draft = it; status = null }

    fun save(then: () -> Unit = {}) {
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
            if (r == LapRepository.Result.Ok) then()
        }
    }
    fun leave() = when {
        page != null -> page = null
        unsaved > 0 -> askLeave = true
        else -> onBack()
    }
    BackHandler { leave() }
    LaunchedEffect(status) {
        if (status?.second == false) {
            delay(3000)
            status = null
        }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(key(page) { rememberScrollState() })
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.padding(end = 8.dp).size(44.dp).clip(RoundedCornerShape(22.dp)).clickable { leave() }
                        .semantics { contentDescription = if (page == null) "Back to timer" else "Back to settings" },
                    contentAlignment = Alignment.Center,
                ) { Icons.Back(Hud.text) }
                page?.let {
                    PageIcon(it, 32.dp)
                    HSpacer(10.dp)
                }
                Text(
                    page?.title ?: "Settings",
                    style = heading(if (page == null) 26.sp else 22.sp, spacing = 0.sp),
                    modifier = Modifier.semantics { heading() },
                )
            }

            when (page) {
                null -> CategoryList(state, timer?.let { draft }, timer, onOpen = { page = it })
                Page.CHANNEL -> {
                    // Live signal for calibration, only while this page is open.
                    DisposableEffect(state.usbConnected) {
                        if (state.usbConnected) LapRepository.fire("rssi on")
                        onDispose { if (state.usbConnected) LapRepository.fire("rssi off") }
                    }
                    ReadOnlyNote(state)
                    ChannelSection(draft, editable, edit)
                    SensitivitySection(draft, editable, edit)
                }
                Page.FALSE_READ -> {
                    ReadOnlyNote(state)
                    FalseReadSection(draft, editable, state.session?.ignored ?: state.lastSession?.ignored, state.session != null, edit)
                }
                Page.RADIO -> {
                    ReadOnlyNote(state)
                    RadioSection(draft, editable, edit)
                }
                Page.MESSAGES -> {
                    ReadOnlyNote(state)
                    MessagesSection(draft, editable, edit)
                }
                Page.RACE -> {
                    ReadOnlyNote(state)
                    RaceDefaultsSection(draft, editable, edit)
                }
                Page.LAP_SOUND -> LapSoundPage()
                Page.SYNC -> SyncPage()
            }
        }
        SaveBar(unsaved, status, saving, editable, state) { save() }
    }

    if (askLeave) {
        HudDialog("Unsaved changes", onDismiss = { askLeave = false }) {
            Text(
                "${changes(unsaved)} not saved to the timer yet. Save them before leaving?",
                style = body(14.sp, Hud.textSoft).copy(lineHeight = 20.sp),
            )
            PrimaryButton(
                "SAVE TO TIMER", { askLeave = false; save(onBack) },
                height = 56.dp, size = 18, enabled = editable && !saving,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Discard", { askLeave = false; onBack() }, Modifier.weight(1f))
                SecondaryButton("Keep editing", { askLeave = false }, Modifier.weight(1f))
            }
        }
    }
}

private fun changes(n: Int) = if (n == 1) "1 change" else "$n changes"

/** Pinned under every page while there's something to save, or a result to show. */
@Composable
private fun SaveBar(unsaved: Int, status: Pair<String, Boolean>?, saving: Boolean, editable: Boolean, state: TimerState, onSave: () -> Unit) {
    if (unsaved == 0 && status == null) return
    Column(
        Modifier.fillMaxWidth().background(Hud.surface).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val (text, err) = when {
            status != null -> status
            !state.usbConnected -> "${changes(unsaved)} not saved · plug the timer in to save" to false
            state.armed -> "${changes(unsaved)} not saved · disarm to save" to false
            else -> "${changes(unsaved)} not saved to the timer yet" to false
        }
        Text(text, style = body(13.sp, if (err) Hud.slow else Hud.textSoft), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (unsaved > 0) {
            PrimaryButton(if (saving) "SAVING…" else "SAVE TO TIMER", onSave, height = 52.dp, size = 18, enabled = editable && !saving)
        }
    }
}

/** On a timer page: why the controls are greyed out, when they are. */
@Composable
private fun ReadOnlyNote(state: TimerState) {
    val why = when {
        !state.usbConnected -> "Read only: plug the timer in over USB to change these."
        state.settings == null -> "Reading the timer's settings…"
        state.armed -> "Read only while the timer is armed. Disarm it to change these."
        else -> return
    }
    IgnoredNote(why)
}

@Composable
private fun CategoryList(state: TimerState, d: TimerSettings?, timer: TimerSettings?, onOpen: (Page) -> Unit) {
    val sound by LapSounds.sound.collectAsStateWithLifecycle()
    val sync by WebSync.state.collectAsStateWithLifecycle()
    val heats by HeatStore.heats.collectAsStateWithLifecycle()
    ConnectionCard(state)
    Group("Timer", "Saved on the timer") {
        timerPages.forEachIndexed { i, p ->
            CategoryRow(
                p,
                d?.let { p.summary(it) } ?: "Not read yet",
                edited = d != null && timer != null && p.changed(d, timer),
                divider = i < timerPages.lastIndex,
            ) { onOpen(p) }
        }
    }
    Group("This phone", "Saved right away") {
        CategoryRow(Page.LAP_SOUND, "${sound.label} · plays on every gate pass", edited = false, divider = true) { onOpen(Page.LAP_SOUND) }
        CategoryRow(Page.SYNC, syncSummary(sync, WebSync.pending(heats, sync).size), edited = false, divider = false) { onOpen(Page.SYNC) }
    }
}

/** A labelled group of category rows on one card. */
@Composable
private fun Group(title: String, right: String, rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            SectionLabel(title, Modifier.weight(1f).semantics { heading() })
            Text(right, style = body(12.sp, Hud.muted))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Hud.surface)
                .border(1.dp, Hud.line, RoundedCornerShape(16.dp)),
        ) { rows() }
    }
}

@Composable
private fun CategoryRow(p: Page, summary: String, edited: Boolean, divider: Boolean, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PageIcon(p)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.title, style = body(16.sp, weight = FontWeight.Medium))
                    if (edited) {
                        Text(
                            "UNSAVED",
                            style = mono(10.sp, Hud.selectedText, FontWeight.Bold),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Hud.accentRing).padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(summary, style = body(13.sp, Hud.muted))
            }
            Icons.Forward(Hud.chevron)
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.divider))
    }
}

private fun Page.summary(d: TimerSettings): String = when (this) {
    Page.CHANNEL -> {
        val sens = if (d.sensitivity == Sensitivity.MED) "Medium" else d.sensitivity.label
        "${d.channelLabel} · ${d.freqMhz} MHz · $sens sensitivity"
    }
    Page.FALSE_READ ->
        "${fmtSeconds(d.minLapMs)} s min lap · " + if (d.ignoreParked) "parked quads ignored" else "parked quads count"
    Page.RADIO -> listOfNotNull("Bluetooth".takeIf { d.ble }, "ESP-NOW".takeIf { d.espnow }).joinToString(" + ").ifEmpty { "USB only" }
    Page.MESSAGES -> listOfNotNull(
        d.event.takeIf { d.msgEvent && it.isNotBlank() },
        (if (d.heatPrefix.isEmpty()) "#${d.nextHeat}" else "${d.heatPrefix} ${d.nextHeat}").takeIf { d.msgHeat },
    ).joinToString(" · ").ifEmpty { "No event or heat name" }
    Page.RACE -> (if (d.raceLimit == RaceLimit.LAPS) laps(d.raceLaps) else "${Format.minutes(d.raceTimeS)} race") +
        " · BOOT arms " + if (d.mode == Mode.RACE) "a race" else "practice"
    Page.LAP_SOUND, Page.SYNC -> ""
}

/** Whether this page's settings differ between [a] and [b]. */
private fun Page.changed(a: TimerSettings, b: TimerSettings): Boolean {
    fun TimerSettings.fields(): List<Any> = when (this@changed) {
        Page.CHANNEL -> listOf(chan, freqMhz, enterDb, exitDb, alpha)
        Page.FALSE_READ -> listOf(minLapMs, ignoreParked, maxPeakMs)
        Page.RADIO -> listOf(ble, espnow, bindPhrase)
        Page.MESSAGES -> listOf(msgEvent, event, msgHeat, heatPrefix, nextHeat, msgStart, textStage, textGo, textFinish, msgLaps, msgBest)
        Page.RACE -> listOf(raceLaps, raceTimeS, raceLimit, mode, randomHold)
        Page.LAP_SOUND, Page.SYNC -> emptyList()
    }
    return a.fields() != b.fields()
}

private fun laps(n: Int) = if (n == 1) "1 lap" else "$n laps"

/** A phone setting: tapping a sound uses it straight away and plays it once; ▶ only previews. */
@Composable
private fun LapSoundPage() {
    val current by LapSounds.sound.collectAsStateWithLifecycle()
    Group("On every gate pass", "Saved on this phone") {
        Column(Modifier.padding(6.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LapSound.entries.forEach { s ->
                val on = s == current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Hud.selected else Color.Transparent),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).heightIn(min = 56.dp)
                            .selectable(on, role = Role.RadioButton) { LapSounds.select(s); LapSounds.play(s) }
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (on) Hud.accent else Hud.toggleOff, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(Hud.accent)) }
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(s.label, style = body(15.sp, if (on) Hud.selectedText else Hud.text, FontWeight.Medium))
                            Text(s.hint, style = body(12.sp, Hud.muted))
                        }
                    }
                    if (s.res != null) {
                        Box(
                            Modifier.padding(end = 4.dp).size(48.dp).clip(RoundedCornerShape(12.dp)).background(Hud.bg)
                                .border(1.dp, Hud.line, RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { LapSounds.play(s) }
                                .semantics { contentDescription = "Play ${s.label}" },
                            contentAlignment = Alignment.Center,
                        ) { Icons.Play(Hud.accent) }
                    }
                }
            }
        }
    }
    Text(
        "Tap a sound to use it; it plays once so you hear what you picked. ▶ plays one without choosing it. Uses the media volume, so turn it up before you fly.",
        style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
    )
}

@Composable
private fun ConnectionCard(state: TimerState) {
    val s = state.settings
    HudCard(radius = 14.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(if (state.usbConnected) Hud.ok else Hud.muted, if (state.usbConnected) Hud.okTint else Hud.bg) { tint, s -> Icons.Usb(tint, s) }
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
            Dot(if (state.usbConnected) Hud.ok else Hud.toggleOff, 10.dp, ring = if (state.usbConnected) Hud.okTint else null)
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
                Stepper(
                    "Minimum lap time", d.minLapMs, TimerSettings.MIN_LAP_RANGE_MS, { onChange(d.copy(minLapMs = it)) },
                    format = { "${fmtSeconds(it)} s" },
                    step = TimerSettings.MIN_LAP_STEP_MS,
                    enabled = enabled,
                    editText = { fmtSeconds(it) },
                    parse = { t ->
                        t.trim().toFloatOrNull()?.let { sec ->
                            val step = TimerSettings.MIN_LAP_STEP_MS
                            ((sec * 1000).roundToInt() + step / 2) / step * step
                        }
                    },
                )
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

private fun fmtSeconds(ms: Int) = "%.1f".format(java.util.Locale.ROOT, ms / 1000f)

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
        SettingRow("Bluetooth", "Laps to this app, phone unplugged") {
            HudSwitch(d.ble, { onChange(d.copy(ble = it)) }, "Bluetooth output", enabled)
        }
        SettingRow("ESP-NOW", "Laps on the goggles OSD and LapDog LCD") {
            HudSwitch(d.espnow, { onChange(d.copy(espnow = it)) }, "ESP-NOW output", enabled)
        }
        if (d.espnow) {
            HudField("ELRS bind phrase", d.bindPhrase, { onChange(d.copy(bindPhrase = it)) }, password = true, maxLength = 63, enabled = enabled)
            Row {
                Label("UID ", size = 12)
                Text(ElrsUid.format(ElrsUid.fromBindPhrase(d.bindPhrase)), style = mono(12.sp, Hud.muted, FontWeight.Medium))
                Label(" · 2.4 GHz ch 1", size = 12)
            }
        }
        if (d.ble && d.espnow) {
            Text(
                "With both on, the goggles get each lap first and this app about half a second later. Lap times are measured on the timer, so they're unaffected.",
                style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
            )
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
            "Kept on the timer. It counts heats itself and sends these with the laps over every radio output that's on, so goggles and this app show the same thing.",
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
            SettingRow("Lap times", "Lap and position on row 3 for 3 s, each lap's time on row 15 for 4 s", divider = false) {
                HudSwitch(d.msgLaps, { onChange(d.copy(msgLaps = it)) }, "Broadcast lap times", enabled)
            }
        }
    }
}

@Composable
private fun RaceDefaultsSection(d: TimerSettings, enabled: Boolean, onChange: (TimerSettings) -> Unit) {
    Section("Race defaults") {
        HudCard(radius = 16.dp, padding = 16.dp, spacing = 0.dp) {
            SettingRow("Race ends on", null) {
                ChoiceChip("Laps", d.raceLimit == RaceLimit.LAPS, { onChange(d.copy(raceLimit = RaceLimit.LAPS)) }, enabled)
                ChoiceChip("Time", d.raceLimit == RaceLimit.TIME, { onChange(d.copy(raceLimit = RaceLimit.TIME)) }, enabled)
            }
            SettingRow("Lap limit", "Used when a race ends on laps") {
                Stepper("Lap limit", d.raceLaps, 1..99, { onChange(d.copy(raceLaps = it)) }, format = ::laps, enabled = enabled)
            }
            SettingRow("Time limit", "Used when a race ends on time") {
                Stepper(
                    "Time limit", d.raceTimeS, 30..3600, { onChange(d.copy(raceTimeS = it)) },
                    format = Format::minutes,
                    step = 30,
                    enabled = enabled,
                    editText = Format::minutes,
                    parse = Format::parseMinutes,
                )
            }
            SettingRow("BOOT button arms", "Long press on the timer") {
                ChoiceChip("Practice", d.mode == Mode.PRACTICE, { onChange(d.copy(mode = Mode.PRACTICE)) }, enabled)
                ChoiceChip("Race", d.mode == Mode.RACE, { onChange(d.copy(mode = Mode.RACE)) }, enabled)
            }
            SettingRow("Random start hold", "1–3 s between “${d.textStage}” and “${d.textGo}”", divider = false) {
                HudSwitch(d.randomHold, { onChange(d.copy(randomHold = it)) }, "Random start hold", enabled)
            }
        }
        Text(
            "Stored on the timer, so a race started with the BOOT button uses these without the phone.",
            style = body(13.sp, Hud.muted).copy(lineHeight = 19.sp),
        )
    }
}
