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

    private fun ImageVector.Builder.fill(d: String, argb: Long) =
        addPath(pathData = PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color(argb)))
}
