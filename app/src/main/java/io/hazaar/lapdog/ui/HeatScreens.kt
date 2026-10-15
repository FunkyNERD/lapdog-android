package io.hazaar.lapdog.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.hazaar.lapdog.protocol.Format
import io.hazaar.lapdog.state.HeatRecord
import io.hazaar.lapdog.state.LapEntry
import io.hazaar.lapdog.state.PersonalBests
import io.hazaar.lapdog.state.groupByDay
import io.hazaar.lapdog.ui.theme.Hud
import io.hazaar.lapdog.ui.theme.body
import io.hazaar.lapdog.ui.theme.heading
import io.hazaar.lapdog.ui.theme.mono
import io.hazaar.lapdog.ui.theme.sectionLabel
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

private val pagePadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp)

@Composable
private fun BackButton(description: String, onBack: () -> Unit) {
    Box(
        Modifier.offset(x = (-10).dp).size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onBack)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icons.Back(Hud.text, 22.dp) }
}

/** PRACTICE / RACE tag. */
@Composable
fun ModeBadge(race: Boolean, large: Boolean = false) {
    Text(
        if (race) "RACE" else "PRACTICE",
        style = mono(11.sp, if (race) Hud.selectedText else Hud.muted).copy(letterSpacing = 0.06.em),
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (race) Hud.selected else Hud.divider)
            .padding(horizontal = if (large) 7.dp else 6.dp, vertical = if (large) 3.dp else 2.dp),
    )
}

// --- History -------------------------------------------------------------------------

@Composable
fun HistoryScreen(heats: List<HeatRecord>, onBack: () -> Unit, onOpen: (HeatRecord) -> Unit) {
    BackHandler(onBack = onBack)
    val text = remember { HeatText() }
    val zone = remember { ZoneId.systemDefault() }
    var filter by rememberSaveable { mutableStateOf(0) } // all, practice, race
    val pb = remember(heats) { PersonalBests.of(heats) }
    val shown = heats.filter { filter == 0 || it.isRace == (filter == 2) }
    val days = remember(shown) { groupByDay(shown, zone) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton("Back to timer", onBack)
                Text("Heats", style = heading(24.sp, spacing = 0.sp), modifier = Modifier.semantics { heading() })
            }
        }
        item {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val lap = pb.lap
                PbTile(
                    lap?.first?.channel?.let { "Best lap · $it" } ?: "Best lap",
                    lap?.second?.let { Format.lap(it.ms) } ?: "—",
                    lap?.let { (h, _) -> "${h.name} · ${text.relativeDay(h)}" } ?: "No laps yet",
                    Hud.best, Modifier.weight(1f),
                    onClick = lap?.let { (h, _) -> { onOpen(h) } },
                )
                val three = pb.three
                PbTile(
                    "Best 3 laps",
                    three?.second?.let { w -> Format.lap(w.sumOf { it.ms }) } ?: "—",
                    three?.let { (h, w) -> "${h.name} · laps ${w.first().lap}–${w.last().lap}" } ?: "Needs 3 in a row",
                    Hud.text, Modifier.weight(1f),
                    onClick = three?.let { (h, _) -> { onOpen(h) } },
                )
            }
        }
        item {
            Segmented(
                listOf("All" to null, "Practice" to null, "Race" to null),
                filter, { filter = it },
                Modifier.padding(top = 12.dp, bottom = 12.dp),
                height = 40.dp,
                description = "Show",
            )
        }
        if (days.isEmpty()) {
            item {
                Text(
                    if (heats.isEmpty()) "No heats yet. Arm the timer and fly: every heat is saved here." else "No ${if (filter == 1) "practice" else "race"} heats yet.",
                    style = body(14.sp, Hud.muted).copy(lineHeight = 20.sp),
                )
            }
        }
        days.forEachIndexed { i, day ->
            item(key = "day-${day.date}") {
                Row(Modifier.fillMaxWidth().padding(top = if (i == 0) 0.dp else 12.dp), verticalAlignment = Alignment.Bottom) {
                    Text(text.dayLabel(day.date).uppercase(), style = sectionLabel, modifier = Modifier.weight(1f).semantics { heading() })
                    if (day.event.isNotEmpty()) Text(day.event, style = body(13.sp, Hud.muted), maxLines = 1)
                }
            }
            items(day.heats, key = { it.id }) { h -> HeatRow(h, text, pb.isBestLap(h)) { onOpen(h) } }
        }
    }
}

/** A personal-best tile; tapping it opens the heat it was set in (null onClick: nothing set yet). */
@Composable
private fun PbTile(label: String, value: String, note: String, color: Color, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val tap = if (onClick == null) Modifier else
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Open heat", onClick = onClick)
    HudCard(modifier.then(tap), padding = 13.dp, spacing = 2.dp) {
        Label(label, size = 12)
        Text(value, style = mono(22.sp, color), maxLines = 1)
        Label(note, size = 12)
    }
}

@Composable
private fun HeatRow(h: HeatRecord, text: HeatText, pb: Boolean, onClick: () -> Unit) {
    val best = h.stats.best
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Hud.surface)
            .border(1.dp, Hud.line, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(h.name, style = body(16.sp, weight = FontWeight.SemiBold), maxLines = 1)
                ModeBadge(h.isRace)
            }
            Text(text.meta(h), style = body(13.sp, Hud.muted))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(best?.let { Format.lap(it.ms) } ?: "—", style = mono(17.sp, if (pb) Hud.best else Hud.text))
            Label(if (pb) "Best · PB" else "Best", size = 12)
        }
        Icons.Forward(Hud.chevron)
    }
}

// --- Heat detail ---------------------------------------------------------------------

@Composable
fun HeatScreen(h: HeatRecord, pb: PersonalBests, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val text = remember { HeatText() }
    val context = LocalContext.current
    val st = h.stats
    var selected by rememberSaveable(h.id) { mutableStateOf<Int?>(null) } // index into h.laps
    val pick = { i: Int -> selected = if (selected == i) null else i }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton("Back to heats", onBack)
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(h.name, style = heading(26.sp, spacing = 0.sp), maxLines = 1, modifier = Modifier.semantics { heading() })
                    ModeBadge(h.isRace, large = true)
                }
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable { share(context, h.name, text.share(h, pb)) }
                        .semantics { contentDescription = "Share heat" },
                    contentAlignment = Alignment.Center,
                ) { Icons.Share(Hud.textSoft) }
            }
            Text(text.subtitle(h), style = body(14.sp, Hud.muted).copy(lineHeight = 21.sp))
        }

        // Best-lap hero. The time gets the whole width to itself (the PB chip sits on the label
        // row) and shrinks rather than wraps on narrow phones or with large system fonts.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Hud.surface)
                .border(1.dp, Hud.line, RoundedCornerShape(16.dp))
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(st.best?.let { "Best lap · lap ${it.lap}" } ?: "Best lap", size = 13, modifier = Modifier.weight(1f))
                if (pb.isBestLap(h)) {
                    Text(
                        "PERSONAL BEST",
                        style = mono(11.sp, Hud.best).copy(letterSpacing = 0.08.em),
                        maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Hud.bestTag).padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
            BasicText(
                st.best?.let { Format.lap(it.ms) } ?: "—",
                style = mono(48.sp, Hud.best).copy(lineHeight = 52.sp),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 48.sp, stepSize = 2.sp),
            )
        }

        StatGrid(statTiles(h, text))

        text.ignoredNote(h.ignored)?.let { IgnoredNote(it) }

        if (h.laps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    SectionLabel("Lap times", Modifier.weight(1f).semantics { heading() })
                    Label("seconds · line at average")
                }
                LapChart(h.laps, selected, pick, text)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("All laps", Modifier.padding(bottom = 4.dp).semantics { heading() })
            if (h.laps.isEmpty() && h.passes.isEmpty()) {
                Text("No laps recorded.", style = body(14.sp, Hud.muted))
            } else {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("Lap", Modifier.width(56.dp))
                    Box(Modifier.weight(1f))
                    Text("Time", style = body(12.sp, Hud.muted), modifier = Modifier.width(92.dp), textAlign = TextAlign.End)
                    Text("Gap", style = body(12.sp, Hud.muted), modifier = Modifier.width(72.dp), textAlign = TextAlign.End)
                }
                LapRows(h, selected, pick, text)
            }
        }
    }
}

private data class Tile(val label: String, val value: String, val note: String)

private fun statTiles(h: HeatRecord, text: HeatText): List<Tile> {
    val st = h.stats
    val dash = "—"
    val avg = Tile("Average", st.averageMs?.let(Format::lap) ?: dash, "per lap")
    val consistency = Tile("Consistency", st.stdDevMs?.takeIf { st.count > 1 }?.let(text::consistency) ?: dash, "std dev")
    val slowest = Tile("Slowest", st.slowest?.let { Format.lap(it.ms) } ?: dash, st.slowest?.let { "lap ${it.lap}" } ?: "")
    return if (h.isRace) {
        val laps = when {
            h.lapLimit > 0 -> Tile("Laps", "${st.count} / ${h.lapLimit}", if (h.finished) "finished" else "stopped")
            else -> Tile("Laps", "${st.count}", "${Format.minutes(h.timeLimitS)} limit")
        }
        listOf(
            laps,
            Tile("Race time", h.raceTimeMs?.let(Format::lap) ?: dash, "GO to finish"),
            Tile("Holeshot", h.holeshotMs?.let(Format::lap) ?: dash, "GO to gate"),
            avg, consistency, slowest,
        )
    } else {
        val three = st.bestThree
        listOf(
            Tile("Laps", "${st.count}", "from first pass"),
            Tile("Total time", Format.lap(st.totalMs), "all laps"),
            avg,
            Tile("Best 3 laps", three?.let { w -> Format.lap(w.sumOf { it.ms }) } ?: dash, three?.let { "laps ${it.first().lap}–${it.last().lap}" } ?: "needs 3 in a row"),
            consistency, slowest,
        )
    }
}

@Composable
private fun StatGrid(tiles: List<Tile>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    HudCard(Modifier.weight(1f)) {
                        Label(t.label)
                        Text(t.value, style = mono(17.sp), maxLines = 1, softWrap = false)
                        Label(t.note, size = 11)
                    }
                }
            }
        }
    }
}

@Composable
private fun LapRows(h: HeatRecord, selected: Int?, pick: (Int) -> Unit, text: HeatText) {
    val st = h.stats
    val best = st.best
    val slowest = st.slowest.takeIf { !h.isRace && st.count > 1 }
    // Ignored passes go in sequence: after the last lap completed before them.
    val passesAfter = h.passes.groupBy { p -> h.laps.lastOrNull { it.lap <= p.afterLap }?.lap ?: 0 }
    passesAfter[0]?.forEach { IgnoredRow(text.pass(it)) }
    h.laps.forEachIndexed { i, lap ->
        val isBest = lap == best
        val isSlowest = lap == slowest
        val sel = selected == i
        val shape = RoundedCornerShape(10.dp)
        val edge = when { isBest -> Hud.best; sel -> Hud.accent; else -> Color.Transparent }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(when { isBest -> Hud.bestTint; sel -> Hud.selected; else -> Hud.surface })
                .drawBehind { drawRect(edge, size = Size(3.dp.toPx(), size.height)) }
                .then(if (isBest && sel) Modifier.border(1.dp, Hud.accent, shape) else Modifier)
                .clickable(role = Role.Button) { pick(i) }
                .semantics { this.selected = sel }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("%02d".format(lap.lap), style = mono(14.sp, if (isBest) Hud.best else Hud.muted, FontWeight.Medium), modifier = Modifier.width(56.dp))
            Box(Modifier.weight(1f)) {
                if (isBest || isSlowest) {
                    Text(
                        if (isBest) "BEST" else "SLOWEST",
                        style = mono(10.sp, if (isBest) Hud.best else Hud.slow).copy(letterSpacing = 0.08.em),
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (isBest) Hud.bestTag else Hud.slowBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(Format.lap(lap.ms), style = mono(16.sp, if (isBest) Hud.best else Hud.text), modifier = Modifier.width(92.dp), textAlign = TextAlign.End)
            Text(
                if (isBest || best == null) "—" else Format.delta(lap.ms - best.ms),
                style = mono(13.sp, if (isSlowest) Hud.slow else Hud.muted, FontWeight.Medium),
                modifier = Modifier.width(72.dp),
                textAlign = TextAlign.End,
            )
        }
        passesAfter[lap.lap]?.forEach { IgnoredRow(text.pass(it)) }
    }
}

@Composable
private fun IgnoredRow(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRoundRect(
                    Hud.slowEdge,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icons.Cross(Hud.slow)
        Text(label, style = body(13.sp, Hud.slow))
    }
}

// --- Lap chart -----------------------------------------------------------------------

/**
 * Lap times as a line with dots. The y-axis hugs the data in 0.5 s steps (not from
 * zero) so small differences show; the best dot is bigger, purple and labelled BEST,
 * since purple vs blue alone is too close for protan vision.
 */
@Composable
private fun LapChart(laps: List<LapEntry>, active: Int?, pick: (Int) -> Unit, text: HeatText) {
    val measurer = rememberTextMeasurer()
    val n = laps.size
    val bestIdx = laps.indices.minBy { laps[it].ms }
    val best = laps[bestIdx].ms
    val worst = laps.maxOf { it.ms }
    val avg = laps.sumOf { it.ms }.toDouble() / n
    val lo = floor((best - 300) / 500.0).toLong() * 500
    val hi = ceil((worst + 300) / 500.0).toLong() * 500
    val axisStyle = mono(10.sp, Hud.knobOff, FontWeight.Medium)
    val description = "Lap times for $n laps, from ${Format.lap(best)} to ${Format.lap(worst)}, " +
        "average ${Format.lap(avg.toLong())}. Best lap ${laps[bestIdx].lap}."

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Hud.surface)
            .border(1.dp, Hud.line, RoundedCornerShape(14.dp)),
    ) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val ht = constraints.maxHeight.toFloat()
        val px = { v: Int -> with(density) { v.dp.toPx() } }
        val left = px(40); val right = px(16); val top = px(26); val bottom = px(30)
        val x = { i: Int -> if (n == 1) (left + w - right) / 2 else left + (w - left - right) * i / (n - 1) }
        val y = { ms: Double -> top + (ht - top - bottom) * ((hi - ms) / (hi - lo)).toFloat() }

        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(laps) {
                    detectTapGestures { o ->
                        val i = laps.indices.minBy { hypot(x(it) - o.x, y(laps[it].ms.toDouble()) - o.y) }
                        if (hypot(x(i) - o.x, y(laps[i].ms.toDouble()) - o.y) < px(28)) pick(i)
                    }
                },
        ) {
            var v = lo
            while (v <= hi) {
                val gy = y(v.toDouble())
                drawLine(Hud.grid, Offset(left, gy), Offset(w - right, gy), 1.dp.toPx())
                val label = measurer.measure(text.seconds(v, 1), axisStyle)
                drawText(label, topLeft = Offset(px(36) - label.size.width, gy - label.size.height / 2))
                v += 500
            }
            val ay = y(avg)
            drawLine(Hud.avgLine, Offset(left, ay), Offset(w - right, ay), 1.dp.toPx())
            val avgLabel = measurer.measure("avg ${text.seconds(avg.roundToInt().toLong())}", axisStyle.copy(color = Hud.muted))
            drawText(avgLabel, topLeft = Offset(w - right - px(2) - avgLabel.size.width, ay - px(3) - avgLabel.size.height))

            if (n > 1) {
                val path = Path().apply {
                    moveTo(x(0), y(laps[0].ms.toDouble()))
                    for (i in 1 until n) lineTo(x(i), y(laps[i].ms.toDouble()))
                }
                drawPath(path, Hud.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            laps.forEachIndexed { i, lap ->
                val c = Offset(x(i), y(lap.ms.toDouble()))
                val isBest = i == bestIdx
                val r = (if (isBest) 6 else 4).dp.toPx()
                drawCircle(if (active == i) Hud.text else Hud.surface, r + 2.dp.toPx(), c)
                drawCircle(if (isBest) Hud.best else Hud.accent, r, c)
                val xl = measurer.measure("${lap.lap}", axisStyle.copy(color = if (active == i) Hud.text else Hud.knobOff))
                drawText(xl, topLeft = Offset(c.x - xl.size.width / 2, ht - bottom + px(8)))
            }
            val tag = measurer.measure("BEST", mono(10.sp, Hud.best).copy(letterSpacing = 0.08.em))
            val b = Offset(x(bestIdx), y(best.toDouble()))
            drawText(tag, topLeft = Offset(b.x - tag.size.width / 2, b.y + px(10)))
        }

        if (active != null && active in laps.indices) {
            val ax = x(active)
            val ay = y(laps[active].ms.toDouble())
            val tx = (ax - px(56)).coerceIn(px(6), w - px(118))
            val ty = if (ay < px(90)) ay + px(14) else ay - px(74)
            val gap = laps[active].ms - best
            Column(
                Modifier
                    .offset { IntOffset(tx.roundToInt(), ty.roundToInt()) }
                    .width(112.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Hud.bg)
                    .border(1.dp, Hud.bar, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Label("Lap ${laps[active].lap}", size = 11)
                Text(Format.lap(laps[active].ms), style = mono(14.sp))
                Label(if (gap == 0L) "Best lap" else "${Format.delta(gap)} to best", size = 11)
            }
        }
    }
}

private fun share(context: Context, title: String, summary: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "LapDog · $title")
        putExtra(Intent.EXTRA_TEXT, summary)
    }
    context.startActivity(Intent.createChooser(send, "Share $title"))
}
