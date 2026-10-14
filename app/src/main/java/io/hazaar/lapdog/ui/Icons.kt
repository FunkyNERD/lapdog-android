package io.hazaar.lapdog.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The design's stroked 24x24 line icons, decorative (callers label the control). */
object Icons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) {
            b.addPath(
                PathParser().parsePathString(p).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private val gear = icon(
        "gear",
        "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
        "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h0a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h0a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v0a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
    )
    private val bluetooth = icon("bluetooth", "M7 7l10 10-5 5V2l5 5L7 17")
    private val usb = icon("usb", "M12 2v14", "M9 5l3-3 3 3", "M12 12l-4-3V7", "M12 14l4-3V9", "M12 16a2 2 0 1 0 0 4a2 2 0 1 0 0-4z")
    private val back = icon("back", "M15 18l-6-6 6-6")
    private val alert = icon("alert", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 8v5", "M12 16h.01")

    @Composable
    private fun Draw(v: ImageVector, tint: Color, size: Dp) =
        Image(v, contentDescription = null, modifier = Modifier.size(size), colorFilter = ColorFilter.tint(tint))

    @Composable fun Gear(tint: Color, size: Dp = 22.dp) = Draw(gear, tint, size)
    @Composable fun Bluetooth(tint: Color, size: Dp = 16.dp) = Draw(bluetooth, tint, size)
    @Composable fun Usb(tint: Color, size: Dp = 16.dp) = Draw(usb, tint, size)
    @Composable fun Alert(tint: Color, size: Dp = 16.dp) = Draw(alert, tint, size)
    @Composable fun Back(tint: Color, size: Dp = 24.dp) = Draw(back, tint, size)
}
