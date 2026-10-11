package com.boko.roudio.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Line icons for Search, Review, Settings, Updates and Backups (05–15c), drawn like
 * [RoudioIcons]: a 24×24 viewBox and a 1.75 stroke, tinted at the call site.
 */
object OrganiseIcons {
    val Close = icon("Close", thickLines = listOf("M6.5 6.5l11 11M17.5 6.5l-11 11"))
    val Search = icon("Search", lines = listOf("M17 10.75a6.25 6.25 0 1 1-12.5 0 6.25 6.25 0 0 1 12.5 0z", "M15.5 15.5 20 20"))
    val History = icon("History", lines = listOf("M4.5 12a7.5 7.5 0 1 0 2.2-5.3L4.5 8.9", "M4.5 4.9v4h4", "M12 8.2v4l2.7 1.7"))
    val ArrowUpRight = icon("ArrowUpRight", lines = listOf("M7.5 16.5l9-9M9.5 7.5h7v7"))
    val Play = icon("Play", fills = listOf("M8 5.6v12.8a.8.8 0 0 0 1.2.7l10-6.4a.8.8 0 0 0 0-1.4l-10-6.4a.8.8 0 0 0-1.2.7z"))
    val Check = icon("Check", thickLines = listOf("m5 12.5 4.5 4.5L19 7.5"))
    val Pencil = icon("Pencil", lines = listOf("M15 5.5l3.5 3.5L9 18.5 4.8 19.2l.7-4.2z", "M12.8 7.7l3.5 3.5"))
    val Retry = icon("Retry", lines = listOf("M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4.5v4h-4"))
    val DoubleCheck = icon("DoubleCheck", lines = listOf("m2.5 12.5 4 4 8.5-9", "m11.5 15.5 1 1 8.5-9"))
    val Trash = icon(
        "Trash",
        lines = listOf("M4.5 7h15M9.5 7V5.2c0-.4.3-.7.7-.7h3.6c.4 0 .7.3.7.7V7", "M6.5 7l.9 12a1.5 1.5 0 0 0 1.5 1.4h6.2a1.5 1.5 0 0 0 1.5-1.4l.9-12"),
    )
    val Info = icon("Info", lines = listOf("M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z"), thickLines = listOf("M12 11v5.2M12 7.8v.1"))
    val CircleCheck = icon("CircleCheck", lines = listOf("M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z", "m8.3 12.2 2.5 2.5 4.9-5"))
    val CloudAlert = icon(
        "CloudAlert",
        lines = listOf("M7 18.5h10a4.5 4.5 0 0 0 .6-9A6 6 0 0 0 6 10.5a4 4 0 0 0 1 8z"),
        thickLines = listOf("M12 10.5v3.2M12 15.9v.1"),
    )
    val Plus = icon("Plus", thickLines = listOf("M12 5.5v13M5.5 12h13"))

    private fun icon(
        name: String,
        lines: List<String> = emptyList(),
        fills: List<String> = emptyList(),
        thickLines: List<String> = emptyList(),
    ): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        lines.forEach { stroke(it, 1.75f) }
        thickLines.forEach { stroke(it, 2f) }
        fills.forEach { d -> addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black)) }
    }.build()

    private fun ImageVector.Builder.stroke(d: String, width: Float) = addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}
