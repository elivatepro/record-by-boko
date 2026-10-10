package com.boko.roudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import com.boko.roudio.R

@OptIn(ExperimentalTextApi::class)
private fun lexend(weight: FontWeight) = Font(
    R.font.lexend_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Lexend = FontFamily(
    lexend(FontWeight.Normal),
    lexend(FontWeight.Medium),
    lexend(FontWeight.SemiBold),
)

val LocalRoudioColors = staticCompositionLocalOf { LightRoudioColors }

/** Roudio's colours and type. Read colours with `RoudioTheme.colors`. */
object RoudioTheme {
    val colors: RoudioColors
        @Composable get() = LocalRoudioColors.current
}

fun roudioText(size: TextUnit, lineHeight: TextUnit, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Lexend, fontSize = size, lineHeight = lineHeight, fontWeight = weight)

@Composable
fun RoudioTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkRoudioColors else LightRoudioColors
    // Material components still read MaterialTheme; point its roles at ours.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            background = colors.ground,
            onBackground = colors.ink,
            surface = colors.ground,
            onSurface = colors.ink,
            surfaceVariant = colors.raised,
            onSurfaceVariant = colors.muted,
            outline = colors.hairline,
            error = colors.alert,
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            background = colors.ground,
            onBackground = colors.ink,
            surface = colors.ground,
            onSurface = colors.ink,
            surfaceVariant = colors.raised,
            onSurfaceVariant = colors.muted,
            outline = colors.hairline,
            error = colors.alert,
        )
    }
    val body = roudioText(RoudioTypeScale.BodySize, RoudioTypeScale.BodyLineHeight)
    CompositionLocalProvider(LocalRoudioColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography.copy(bodyLarge = body, bodyMedium = body),
            content = content,
        )
    }
}
