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
    private val forward = icon("forward", "M9 18l6-6-6-6")
    private val share = icon("share", "M12 3v12", "M7 8l5-5 5 5", "M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5")
    private val cross = icon("cross", "M6 6l12 12", "M18 6L6 18")
    private val antenna = icon(
        "antenna", "M12 13v8", "M12 13a1.5 1.5 0 1 0 0-3a1.5 1.5 0 1 0 0 3z", "M8.5 15.5a5 5 0 0 1 0-7",
        "M15.5 8.5a5 5 0 0 1 0 7", "M5.6 18.4a9 9 0 0 1 0-12.8", "M18.4 5.6a9 9 0 0 1 0 12.8",
    )
    private val shield = icon("shield", "M12 3l8 3v6c0 4.5-3.4 8-8 9c-4.6-1-8-4.5-8-9V6z", "M8.5 12l2.5 2.5 4.5-5")
    private val broadcast = icon(
        "broadcast", "M4 19a1 1 0 1 0 2 0a1 1 0 1 0-2 0z", "M4 13a6 6 0 0 1 6 6", "M4 7a12 12 0 0 1 12 12", "M15 4l3 3-3 3", "M18 7h-4",
    )
    private val message = icon("message", "M4 5h16a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-9l-5 4v-4H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", "M7.5 9.5h9", "M7.5 12.5h5")
    private val flag = icon("flag", "M5 21V4", "M5 4h13l-2.5 4.5L18 13H5", "M9.5 4v9", "M14 4v9", "M5 8.5h11.5")
    private val speaker = icon("speaker", "M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M15.5 9a4 4 0 0 1 0 6", "M18.5 6.5a7.5 7.5 0 0 1 0 11")
    private val play = icon("play", "M8 5.5v13l10.5-6.5z")
    private val cloud = icon(
        "cloud", "M7 19h10a4 4 0 0 0 .6-7.96A6 6 0 0 0 6.1 9.6A4.7 4.7 0 0 0 7 19z", "M12 16.5v-5", "M9.5 14l2.5-2.5 2.5 2.5",
    )
    private val alert = icon("alert", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 8v5", "M12 16h.01")

    @Composable
    private fun Draw(v: ImageVector, tint: Color, size: Dp) =
        Image(v, contentDescription = null, modifier = Modifier.size(size), colorFilter = ColorFilter.tint(tint))

    @Composable fun Gear(tint: Color, size: Dp = 22.dp) = Draw(gear, tint, size)
    @Composable fun Bluetooth(tint: Color, size: Dp = 16.dp) = Draw(bluetooth, tint, size)
    @Composable fun Usb(tint: Color, size: Dp = 16.dp) = Draw(usb, tint, size)
    @Composable fun Alert(tint: Color, size: Dp = 16.dp) = Draw(alert, tint, size)
    @Composable fun Forward(tint: Color, size: Dp = 18.dp) = Draw(forward, tint, size)
    @Composable fun Share(tint: Color, size: Dp = 20.dp) = Draw(share, tint, size)
    @Composable fun Cross(tint: Color, size: Dp = 14.dp) = Draw(cross, tint, size)
    @Composable fun Antenna(tint: Color, size: Dp = 22.dp) = Draw(antenna, tint, size)
    @Composable fun Shield(tint: Color, size: Dp = 22.dp) = Draw(shield, tint, size)
    @Composable fun Broadcast(tint: Color, size: Dp = 22.dp) = Draw(broadcast, tint, size)
    @Composable fun Message(tint: Color, size: Dp = 22.dp) = Draw(message, tint, size)
    @Composable fun Flag(tint: Color, size: Dp = 22.dp) = Draw(flag, tint, size)
    @Composable fun Speaker(tint: Color, size: Dp = 22.dp) = Draw(speaker, tint, size)
    @Composable fun Play(tint: Color, size: Dp = 20.dp) = Draw(play, tint, size)
    @Composable fun Cloud(tint: Color, size: Dp = 22.dp) = Draw(cloud, tint, size)
    @Composable fun Back(tint: Color, size: Dp = 24.dp) = Draw(back, tint, size)
}
