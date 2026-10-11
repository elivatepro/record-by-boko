package com.boko.roudio.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line icons copied from the Paper file's SVGs (24×24 viewBox, 1.75 stroke). */
object RoudioIcons {
    val Back = line("M19 12H5M11 6l-6 6 6 6")
    val Shield = line("M12 3.5 5 6.5v5c0 4.4 3 7.9 7 9 4-1.1 7-4.6 7-9v-5z")
    val Cloud = line("M7 18.5h10a4.5 4.5 0 0 0 .6-9A6 6 0 0 0 6 10.5a4 4 0 0 0 1 8z")
    val Alert = line("M12 7.5v5.5M12 16.3v.1M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z", width = 1.9f)

    // 11a Welcome
    val Mic = icon("Mic", lines = listOf("M12 3a4 4 0 0 1 4 4v5a4 4 0 0 1-8 0V7a4 4 0 0 1 4-4z", "M12 16v4M8 20h8"))
    val Notes = icon(
        "Notes",
        lines = listOf("M5 6.5h14M5 11h14M5 15.5h9"),
        fills = listOf("M17 15l1.2 2.6L21 18.5l-2.8.9L17 22l-1.2-2.6-2.8-.9 2.8-.9z"),
    )
    val CloudCheck = icon(
        "CloudCheck",
        lines = listOf("M7 18.5h10a4.5 4.5 0 0 0 .6-9A6 6 0 0 0 6 10.5a4 4 0 0 0 1 8z", "m9.5 13.5 2 2 3.5-3.5"),
    )

    // 11c Permissions
    val Bluetooth = line("m7 7.5 10 9-5 4.5V3l5 4.5-10 9")
    val Bell = icon("Bell", lines = listOf("M6.5 10a5.5 5.5 0 0 1 11 0c0 4.6 1.9 6 1.9 6H4.6s1.9-1.4 1.9-6z", "M10 19.5a2.1 2.1 0 0 0 4 0"))
    val Wifi = icon(
        "Wifi",
        lines = listOf("M3.5 9.5a12 12 0 0 1 17 0M6.5 12.8a7.7 7.7 0 0 1 11 0M9.5 16a3.4 3.4 0 0 1 5 0"),
        fills = listOf("M12 17.7a1.3 1.3 0 1 0 0 2.6 1.3 1.3 0 1 0 0-2.6z"),
    )

    // 11e Unlink (warning callout)
    val Warning = icon("Warning", lines = listOf("M12 3.5 2.8 19.5h18.4z"), thickLines = listOf("M12 9.5v4.5M12 16.8v.1"))

    // 11f–11g, 12a–12e, 13a
    val Recorder = icon("Recorder", lines = listOf("M9.5 3h5A2.5 2.5 0 0 1 17 5.5v13a2.5 2.5 0 0 1-2.5 2.5h-5A2.5 2.5 0 0 1 7 18.5v-13A2.5 2.5 0 0 1 9.5 3z", "M10.5 7.5h3"))
    val RecorderMissing = icon(
        "RecorderMissing",
        lines = listOf("M9.5 3h5A2.5 2.5 0 0 1 17 5.5v13a2.5 2.5 0 0 1-2.5 2.5h-5A2.5 2.5 0 0 1 7 18.5v-13A2.5 2.5 0 0 1 9.5 3z", "M10 10.5l4 4M14 10.5l-4 4"),
    )
    val BluetoothOff = icon("BluetoothOff", lines = listOf("m7 7.5 10 9-5 4.5V3l5 4.5-10 9"), thickLines = listOf("M4 4l16 16"))
    val Lock = icon(
        "Lock",
        lines = listOf("M7.5 10.5h9a2.5 2.5 0 0 1 2.5 2.5v5a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 18v-5a2.5 2.5 0 0 1 2.5-2.5z", "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5"),
        fills = listOf("M12 14.1a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 1 0 0-2.8z"),
    )
    val Link = icon(
        "Link",
        lines = listOf("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"),
    )
    val ShieldCheck = icon("ShieldCheck", lines = listOf("M12 3.5 5 6.5v5c0 4.4 3 7.9 7 9 4-1.1 7-4.6 7-9v-5z", "m9.3 12 1.9 1.9 3.6-3.6"))
    val Chevron = line("m9.5 6 6 6-6 6")

    // 01 Today recorder chip
    val RecorderChip = icon(
        "RecorderChip",
        lines = listOf("M9.5 3h5a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3h-5a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z", "M10 16.5h4"),
        fills = listOf("M12 6.6a1.4 1.4 0 1 0 0 2.8 1.4 1.4 0 1 0 0-2.8z"),
    )

    val Google = ImageVector.Builder("Google", 24.dp, 24.dp, 24f, 24f).apply {
        fill("M21.6 12.2c0-.7-.1-1.4-.2-2H12v3.8h5.4a4.6 4.6 0 0 1-2 3v2.5h3.2c1.9-1.7 3-4.3 3-7.3z", 0xFF4285F4)
        fill("M12 22c2.7 0 5-.9 6.6-2.5l-3.2-2.5c-.9.6-2 1-3.4 1-2.6 0-4.8-1.8-5.6-4.1H3.1v2.6A10 10 0 0 0 12 22z", 0xFF34A853)
        fill("M6.4 13.9a6 6 0 0 1 0-3.8V7.5H3.1a10 10 0 0 0 0 9z", 0xFFFBBC05)
        fill("M12 6c1.5 0 2.8.5 3.8 1.5l2.9-2.9A10 10 0 0 0 3.1 7.5l3.3 2.6C7.2 7.8 9.4 6 12 6z", 0xFFEA4335)
    }.build()

    // Tinted at the call site with Icon(tint = …), so the stroke colour here doesn't matter.
    private fun line(d: String, width: Float = 1.75f) =
        ImageVector.Builder(d.take(12), 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    private fun icon(
        name: String,
        lines: List<String> = emptyList(),
        fills: List<String> = emptyList(),
        thickLines: List<String> = emptyList(),
    ): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        lines.forEach { stroke(it, 1.75f) }
        thickLines.forEach { stroke(it, 2f) }
        fills.forEach { fill(it, 0xFF000000) }
    }.build()

    private fun ImageVector.Builder.stroke(d: String, width: Float) = addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )

    private fun ImageVector.Builder.fill(d: String, argb: Long) =
        addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color(argb)))
}
