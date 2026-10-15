package io.hazaar.lapdog.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.hazaar.lapdog.R

/**
 * Dark race-HUD palette from the LapDog design canvas. The accent is a muted blue;
 * red is only ever a highlight (slower lap, alerts, errors), never a primary.
 */
object Hud {
    val bg = Color(0xFF0A0C0F)
    val surface = Color(0xFF14171C)
    val line = Color(0xFF262B33)
    val divider = Color(0xFF1B1F26)
    val text = Color(0xFFF3F5F7)
    val textSoft = Color(0xFFC9CFD6)
    val muted = Color(0xFFA0A8B3)
    val accent = Color(0xFF3D9BE9)
    val onAccent = Color(0xFF06121F)
    val selected = Color(0xFF0E1C2A)
    val selectedText = Color(0xFF8CC4F5)
    val accentRing = Color(0xFF0F2A40)
    val accentDim = Color(0xFF1E4566)
    val best = Color(0xFFC4A1FF)
    val bestTint = Color(0xFF1C1729)
    val bestTag = Color(0xFF2C2342)
    val slow = Color(0xFFFF8A8A)
    val slowBg = Color(0xFF2A1416)
    val slowEdge = Color(0xFF4A2226)
    val grid = Color(0xFF1F242C)
    val avgLine = Color(0xFF5A626E)
    val chevron = Color(0xFF6B7480)
    val ok = Color(0xFF3DD68C)
    val bar = Color(0xFF3A414C)
    val toggleOff = Color(0xFF4A525E)
    val knobOff = Color(0xFF8A93A0)
    val panel = Color(0xFF050607)
    val panelText = Color(0xFFE8F0F2)
    val panelRowNo = Color(0xFF4D5560)
}

val ChakraPetch = FontFamily(
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** Headings and big labels: Chakra Petch. */
fun heading(size: TextUnit, color: Color = Hud.text, spacing: TextUnit = 0.04.em) =
    TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = size, color = color, letterSpacing = spacing)

/** Times and numbers: JetBrains Mono with tabular digits. */
fun mono(size: TextUnit, color: Color = Hud.text, weight: FontWeight = FontWeight.Bold) =
    TextStyle(fontFamily = JetBrainsMono, fontWeight = weight, fontSize = size, color = color, fontFeatureSettings = "tnum")

/** Body text: IBM Plex Sans. */
fun body(size: TextUnit = 15.sp, color: Color = Hud.text, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = PlexSans, fontWeight = weight, fontSize = size, color = color)

/** Small upper-case section labels. */
val sectionLabel = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Hud.muted, letterSpacing = 0.08.em)

@Composable
fun LapDogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Hud.accent,
            onPrimary = Hud.onAccent,
            background = Hud.bg,
            onBackground = Hud.text,
            surface = Hud.surface,
            onSurface = Hud.text,
            onSurfaceVariant = Hud.muted,
            outline = Hud.line,
            error = Hud.slow,
        ),
        typography = MaterialTheme.typography.let { t ->
            t.copy(
                bodyLarge = t.bodyLarge.copy(fontFamily = PlexSans),
                bodyMedium = t.bodyMedium.copy(fontFamily = PlexSans),
                bodySmall = t.bodySmall.copy(fontFamily = PlexSans),
                labelLarge = t.labelLarge.copy(fontFamily = PlexSans),
            )
        },
        content = content,
    )
}
