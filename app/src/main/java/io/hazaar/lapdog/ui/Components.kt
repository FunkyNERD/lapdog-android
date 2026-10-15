package io.hazaar.lapdog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import io.hazaar.lapdog.ui.theme.Hud
import io.hazaar.lapdog.ui.theme.body
import io.hazaar.lapdog.ui.theme.heading
import io.hazaar.lapdog.ui.theme.mono
import kotlinx.coroutines.launch

@Composable
fun HudCard(
    modifier: Modifier = Modifier,
    radius: Dp = 12.dp,
    padding: Dp = 12.dp,
    spacing: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(Hud.surface)
            .border(1.dp, Hud.line, RoundedCornerShape(radius))
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Hud.muted, size: Int = 12) =
    Text(text, modifier, style = body(size.sp, color))

/** Upper-case section label. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, style = io.hazaar.lapdog.ui.theme.sectionLabel)

@Composable
fun Dot(color: Color, size: Dp = 8.dp, ring: Color? = null) {
    Box(
        Modifier
            .then(if (ring != null) Modifier.size(size + 8.dp).clip(CircleShape).background(ring) else Modifier.size(size)),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(size).clip(CircleShape).background(color)) }
}

/** Pill-shaped status chip, e.g. "USB · Timer ready". */
@Composable
fun StatusChip(text: String, dot: Color) {
    Row(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Hud.surface)
            .border(1.dp, Hud.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(dot)
        Text(text, style = body(13.sp, Hud.textSoft))
    }
}

/**
 * Segmented control on a surface track; the selected segment is filled with the accent.
 * Each option is (title, optional subtitle).
 */
@Composable
fun Segmented(
    options: List<Pair<String, String?>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    enabled: Boolean = true,
    description: String = "",
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Hud.surface)
            .border(1.dp, Hud.line, RoundedCornerShape(14.dp))
            .padding(4.dp)
            .semantics { if (description.isNotEmpty()) contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, (title, sub) ->
            val on = i == selected
            Column(
                Modifier
                    .weight(1f)
                    .height(height)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Hud.accent else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.Tab) { onSelect(i) }
                    .semantics { this.selected = on },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val c = if (on) Hud.onAccent else Hud.textSoft
                Text(title, style = body(15.sp, c, if (on) FontWeight.SemiBold else FontWeight.Medium))
                if (sub != null) Text(sub, style = body(12.sp, c.copy(alpha = 0.8f)))
            }
        }
    }
}

/** Outlined choice chip; selected = accent border on the selected tint. */
@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Hud.selected else Color.Transparent)
            .border(1.dp, if (selected) Hud.accent else Hud.line, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = body(14.sp, if (selected) Hud.selectedText else Hud.textSoft)) }
}

/** The design's switch: 48x28 track, accent when on, in a 56x44 touch target. */
@Composable
fun HudSwitch(checked: Boolean, onChange: (Boolean) -> Unit, description: String, enabled: Boolean = true) {
    Box(
        Modifier
            .size(56.dp, 44.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .semantics {
                contentDescription = description
                stateDescription = if (checked) "On" else "Off"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(48.dp, 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (checked) Hud.accent else Hud.surface)
                .border(2.dp, if (checked) Hud.accent else Hud.toggleOff, RoundedCornerShape(14.dp))
                .padding(horizontal = 4.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(16.dp).clip(CircleShape).background(if (checked) Hud.onAccent else Hud.knobOff)) }
    }
}

/** A setting row: title + optional subtitle on the left, a control on the right. */
@Composable
fun SettingRow(title: String, subtitle: String?, divider: Boolean = true, control: @Composable RowScope.() -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = body(15.sp, weight = FontWeight.Medium))
                if (subtitle != null) Text(subtitle, style = body(13.sp, Hud.muted))
            }
            control()
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Hud.divider))
    }
}

/** Labelled text field in the design's style. */
@Composable
fun HudField(
    label: String?,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    monoText: Boolean = false,
    password: Boolean = false,
    numeric: Boolean = false,
    maxLength: Int = 64,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    fieldModifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, style = body(13.sp, Hud.muted))
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= maxLength && '\n' !in it) onChange(it) },
            modifier = fieldModifier,
            enabled = enabled,
            singleLine = true,
            textStyle = if (monoText) mono(15.sp, weight = FontWeight.Medium) else body(15.sp),
            cursorBrush = SolidColor(Hud.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    password -> KeyboardType.Password
                    numeric -> KeyboardType.Decimal
                    else -> KeyboardType.Text
                },
            ),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(height)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Hud.surface)
                        .border(1.dp, Hud.line, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) { inner() }
            },
        )
    }
}

/** Big filled accent button (ARM TIMER, SAVE TO TIMER). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 72.dp, size: Int = 24, enabled: Boolean = true) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) Hud.accent else Hud.line)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = heading(size.sp, if (enabled) Hud.onAccent else Hud.muted, spacing = 0.08.em)) }
}

/** Secondary button on a surface (New heat, Restart). */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Hud.surface)
            .border(1.dp, Hud.line, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = body(15.sp, if (enabled) Hud.text else Hud.muted, FontWeight.SemiBold)) }
}

/**
 * Outlined accent button that fires only after being held for [holdMs], so a stray
 * touch with goggles on can't end a heat. The fill shows progress.
 */
@Composable
fun HoldButton(text: String, onHeld: () -> Unit, modifier: Modifier = Modifier, holdMs: Int = 1000, enabled: Boolean = true) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val color = if (enabled) Hud.accent else Hud.line
    Box(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, color, RoundedCornerShape(16.dp))
            .semantics {
                role = Role.Button
                contentDescription = "$text, press and hold"
            }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val run = scope.launch {
                            progress.animateTo(1f, tween((holdMs * (1 - progress.value)).toInt(), easing = LinearEasing))
                            onHeld()
                            progress.snapTo(0f)
                        }
                        waitForUpOrCancellation()
                        if (run.isActive) {
                            run.cancel()
                            scope.launch { progress.animateTo(0f, tween(150)) }
                        }
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value).background(Hud.selected))
        Text(text, style = heading(18.sp, color, spacing = 0.08.em), textAlign = TextAlign.Center)
    }
}

/** Red alert strip, e.g. "Last session: 2 passes ignored (too soon after a lap)". */
@Composable
fun IgnoredNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Hud.slowBg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icons.Alert(Hud.slow)
        Text(text, style = body(13.sp, Hud.slow))
    }
}

/** Small stat: label over a mono value. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Hud.text, size: Int = 18) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = body(12.sp, Hud.muted))
        Text(value, style = mono(size.sp, color))
    }
}

@Composable
fun Spacer(height: Dp) = androidx.compose.foundation.layout.Spacer(Modifier.height(height))

@Composable
fun HSpacer(width: Dp) = androidx.compose.foundation.layout.Spacer(Modifier.width(width))

private val Double.em get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

/** A dialog in the HUD style: a surface card, a heading, then [content]. */
@Composable
fun HudDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        HudCard(Modifier.fillMaxWidth(), radius = 18.dp, padding = 18.dp, spacing = 12.dp) {
            Text(title, style = heading(20.sp, spacing = 0.sp), modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** Cancel / confirm buttons along the bottom of a [HudDialog]. */
@Composable
fun DialogButtons(confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit, confirmEnabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Cancel", onCancel, Modifier.weight(1f))
        PrimaryButton(confirm, onConfirm, Modifier.weight(1f), height = 64.dp, size = 18, enabled = confirmEnabled)
    }
}

/**
 * A number with − and + either side. Hold a button to repeat, speeding up; tap the
 * number to type one. TalkBack treats the whole as an adjustable value.
 * [editText] / [parse] convert to and from what's typed (whole numbers by default).
 */
@Composable
fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    format: (Int) -> String,
    step: Int = 1,
    enabled: Boolean = true,
    editText: (Int) -> String = { "$it" },
    parse: (String) -> Int? = { it.trim().toIntOrNull() },
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    var typing by remember { mutableStateOf(false) }
    // Steps land on multiples of [step], so an odd value typed in snaps back onto the grid.
    fun nudge(by: Int) {
        val v = (if (by > 0) (current / step + 1) * step else ((current - 1) / step) * step).coerceIn(range)
        if (v != current) change(v)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.semantics {
            contentDescription = label
            stateDescription = format(value)
            progressBarRangeInfo = ProgressBarRangeInfo(
                value.toFloat(), range.first.toFloat()..range.last.toFloat(),
                ((range.last - range.first) / step - 1).coerceAtLeast(0),
            )
            if (enabled) setProgress { t ->
                val v = (range.first + ((t.roundToInt() - range.first) / step) * step).coerceIn(range)
                if (v != current) change(v)
                true
            }
        },
    ) {
        RepeatButton("−", "Decrease $label", enabled && value > range.first) { nudge(-step) }
        Box(
            Modifier.widthIn(min = 76.dp).height(48.dp).clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Type a value") { typing = true },
            contentAlignment = Alignment.Center,
        ) { Text(format(value), style = mono(18.sp, if (enabled) Hud.text else Hud.muted), textAlign = TextAlign.Center) }
        RepeatButton("+", "Increase $label", enabled && value < range.last) { nudge(step) }
    }
    if (typing) {
        var text by remember { mutableStateOf(editText(value)) }
        val typed = parse(text)?.takeIf { it in range }
        val focus = remember { FocusRequester() }
        HudDialog(label, onDismiss = { typing = false }) {
            HudField(
                "${format(range.first)} – ${format(range.last)}", text, { text = it },
                monoText = true, numeric = true, maxLength = 8, fieldModifier = Modifier.focusRequester(focus),
            )
            DialogButtons("OK", { typed?.let(change); typing = false }, { typing = false }, confirmEnabled = typed != null)
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

/** A step button: one step on tap, then repeating (and speeding up) while held. */
@Composable
private fun RepeatButton(text: String, description: String, enabled: Boolean, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Hud.bg)
            .border(1.dp, Hud.line, RoundedCornerShape(10.dp))
            .semantics {
                role = Role.Button
                contentDescription = description
                if (!enabled) disabled()
                onClick { step(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    step()
                    val repeat = scope.launch {
                        delay(450)
                        var gap = 160L
                        while (true) {
                            step()
                            delay(gap)
                            gap = (gap * 0.85f).toLong().coerceAtLeast(40L)
                        }
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        repeat.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(text, style = body(22.sp, if (enabled) Hud.text else Hud.toggleOff)) }
}

/** A category's icon on a tinted rounded square. */
@Composable
fun IconTile(tint: Color, background: Color, size: Dp = 40.dp, icon: @Composable (Color, Dp) -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(background),
        contentAlignment = Alignment.Center,
    ) { icon(tint, size * 0.55f) }
}
