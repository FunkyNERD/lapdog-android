package io.hazaar.lapdog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hazaar.lapdog.ui.theme.Hud
import io.hazaar.lapdog.ui.theme.body
import io.hazaar.lapdog.ui.theme.heading
import kotlin.math.max
import kotlin.math.min

/**
 * The start-up splash: the dog fades in, its signal arcs light from the inside out (a bark,
 * twice), the wordmark settles under it, then the whole thing fades to the app. It sits over
 * the app, which is already loading underneath; a tap skips it. [onDone] when it's gone.
 */
@Composable
fun Splash(onDone: () -> Unit) {
    val clock = remember { Animatable(0f) } // milliseconds into the splash
    LaunchedEffect(Unit) {
        clock.animateTo(TOTAL_MS, tween(TOTAL_MS.toInt(), easing = LinearEasing))
        onDone()
    }
    val t = clock.value
    val overlay = 1f - ramp(t, FADE_OUT_AT, TOTAL_MS)

    Column(
        Modifier
            .fillMaxSize()
            .alpha(overlay)
            .background(Hud.bg)
            .pointerInput(Unit) { detectTapGestures { onDone() } },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val markIn = ramp(t, 0f, 380f)
        Canvas(
            Modifier
                .size(width = 192.dp, height = 176.dp)
                .alpha(markIn)
        ) {
            val s = size.width / MARK_W * (0.88f + 0.12f * markIn) // settles from slightly small
            withTransform({
                translate((size.width - MARK_W * s) / 2f, (size.height - MARK_H * s) / 2f)
                scale(s, s, Offset.Zero)
                translate(-MARK_X, -MARK_Y)
            }) {
                for ((d, c) in DOG) drawPath(d, c, style = Fill)
                for ((d, c) in DOG_OUTLINE) drawPath(d, c, style = Stroke(2f, join = StrokeJoin.Round))
                for ((i, arc) in ARCS.withIndex()) {
                    drawPath(arc, lerp(ARC_OFF, Hud.accent, arcLevel(t, i)), style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        val wordIn = ramp(t, 520f, 900f)
        Text(
            "LapDog",
            style = heading(40.sp, spacing = 0.06.sp),
            modifier = Modifier
                .alpha(wordIn)
                .offset(y = ((1f - wordIn) * 10f).dp),
            textAlign = TextAlign.Center,
        )
        Text(
            "Sits by the gate. Barks on every lap.",
            style = body(14.sp, Hud.muted),
            modifier = Modifier
                .padding(top = 8.dp)
                .alpha(ramp(t, 760f, 1100f)),
            textAlign = TextAlign.Center,
        )
    }
}

private const val TOTAL_MS = 2100f
private const val FADE_OUT_AT = 1800f

// Bark timing: each arc rises, holds and falls; the next starts a little after. Twice.
private const val BARK_AT = 420f
private const val BARK_GAP = 900f
private const val ARC_STEP = 130f
private const val RISE = 90f
private const val HOLD = 330f
private const val FALL = 220f

private fun ramp(t: Float, from: Float, to: Float) = min(1f, max(0f, (t - from) / (to - from)))

/** How lit arc [i] is at [t], 0..1. */
private fun arcLevel(t: Float, i: Int): Float {
    var level = 0f
    for (bark in 0..1) {
        val start = BARK_AT + bark * BARK_GAP + i * ARC_STEP
        val up = ramp(t, start, start + RISE)
        val down = ramp(t, start + RISE + HOLD, start + RISE + HOLD + FALL)
        level = max(level, up * (1f - down))
    }
    return level
}

// The launcher icon's geometry (res/drawable/ic_launcher_foreground.xml, from brand/gen.py),
// in its own coordinates; MARK_* is the box around the dog and its arcs.
private const val MARK_X = 28f
private const val MARK_Y = 4f
private const val MARK_W = 96f
private const val MARK_H = 88f

private fun path(d: String) = PathParser().parsePathString(d).toPath()

private val ARC_OFF = Color(0xFF242C3A)

private val DOG = listOf(
    path("M35 46 L38 12 L52 36 Z") to Color(0xFF8A93A0),
    path("M33.5 90 L30 62 L34 46 L40 38 L50 6 L62 32 L70 34 L76 40 L94 44 Q100 45 99 50 L97 52 L84 56 L95 60 Q96 63 93 63 L78 66 L66 76 L59.8 90 Z") to Color(0xFFF3F5F7),
    path("M49 13 L53 30 L60 31 Z") to Color(0xFFC9CFD6),
    path("M63.6,43 a3.4,3.4 0 1,0 6.8,0 a3.4,3.4 0 1,0 -6.8,0 Z") to Color(0xFF14171C),
)
private val DOG_OUTLINE = listOf(
    path("M35 46 L38 12 L52 36 Z") to Color(0xFF8A93A0),
    path("M33.5 90 L30 62 L34 46 L40 38 L50 6 L62 32 L70 34 L76 40 L94 44 Q100 45 99 50 L97 52 L84 56 L95 60 Q96 63 93 63 L78 66 L66 76 L59.8 90 Z") to Color(0xFFF3F5F7),
)
private val ARCS = listOf(
    path("M107.4 47.8 A11 11 0 0 1 107.4 64.2"),
    path("M112.7 41.9 A19 19 0 0 1 112.7 70.1"),
    path("M118.1 35.9 A27 27 0 0 1 118.1 76.1"),
)
