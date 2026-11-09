package com.footballai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

private val LightColors = lightColorScheme(
    primary = PitchGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F0DC),
    onPrimaryContainer = PitchGreenDark,
    secondary = Color(0xFF4E6357),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E8DA),
    onSecondaryContainer = Color(0xFF0B1F16),
    tertiary = Amber,
    onTertiary = Color.White,
    background = Sand,
    onBackground = SlateInk,
    surface = Color.White,
    onSurface = SlateInk,
    surfaceVariant = Color(0xFFDFE9E3),
    onSurfaceVariant = Color(0xFF3F4945),
    outline = OutlineLight,
    error = DangerRed,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = PitchGreenLight,
    onPrimary = PitchGreenDark,
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFFB8F0DC),
    secondary = Color(0xFFB4CCC0),
    onSecondary = Color(0xFF203529),
    secondaryContainer = Color(0xFF374B40),
    onSecondaryContainer = Color(0xFFD0E8DA),
    tertiary = Color(0xFFFFB95C),
    onTertiary = Color(0xFF452B00),
    background = Charcoal,
    onBackground = Color(0xFFE2E8E4),
    surface = CardDark,
    onSurface = Color(0xFFE2E8E4),
    surfaceVariant = Color(0xFF26332D),
    onSurfaceVariant = Color(0xFFBFC9C3),
    outline = OutlineDark,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** Semantic colours that are not part of the Material scheme. */
data class FootballAiColors(
    val positive: Color,
    val warning: Color,
    val danger: Color,
    val live: Color,
    val homeSeries: Color,
    val drawSeries: Color,
    val awaySeries: Color,
    val chartGrid: Color,
)

private val LightExtra = FootballAiColors(
    positive = PositiveGreen,
    warning = WarningAmber,
    danger = DangerRed,
    live = Color(0xFFD32F2F),
    homeSeries = PitchGreen,
    drawSeries = Color(0xFF8A9A92),
    awaySeries = Color(0xFF1B5E8C),
    chartGrid = Color(0xFFD3DEDA),
)

private val DarkExtra = FootballAiColors(
    positive = Color(0xFF5BD3AC),
    warning = Color(0xFFFFB95C),
    danger = Color(0xFFFF897D),
    live = Color(0xFFFF6B6B),
    homeSeries = PitchGreenLight,
    drawSeries = Color(0xFF9DB0A7),
    awaySeries = Color(0xFF7FB8E0),
    chartGrid = Color(0xFF2C3A34),
)

val LocalFootballAiColors = staticCompositionLocalOf { LightExtra }

@Composable
fun FootballAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Forces the layout direction; used by the in-app language override. */
    languageTag: String = Locale.getDefault().language,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val extra = if (darkTheme) DarkExtra else LightExtra
    // Region tags such as "fa-IR" must mirror too, so compare the language subtag.
    val layoutDirection = if (languageTag.lowercase(Locale.ROOT).startsWith("fa")) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

    CompositionLocalProvider(
        LocalFootballAiColors provides extra,
        LocalLayoutDirection provides layoutDirection,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = FootballAiTypography,
            content = content,
        )
    }
}

object FootballAiThemeExtra {
    val colors: FootballAiColors
        @Composable get() = LocalFootballAiColors.current
}
