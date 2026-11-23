package com.footballai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.Locale

private val LightColors = lightColorScheme(
    primary = PitchGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F2E6),
    onPrimaryContainer = PitchGreenDark,
    secondary = Color(0xFF51605A),
    onSecondary = Color.White,
    secondaryContainer = MistStrong,
    onSecondaryContainer = Ink,
    tertiary = Amber,
    onTertiary = Color.White,
    // A white page with grey slabs: the page is the lightest thing on screen.
    background = Color.White,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = InkMuted,
    surfaceTint = Color.Transparent,
    outline = Color(0xFFB6BEC6),
    outlineVariant = Hairline,
    error = DangerRed,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = PitchGreenLight,
    onPrimary = PitchGreenDark,
    primaryContainer = Color(0xFF0B3E31),
    onPrimaryContainer = Color(0xFFB8F0DC),
    secondary = Color(0xFFB4CCC0),
    onSecondary = Color(0xFF203529),
    secondaryContainer = NightSurfaceStrong,
    onSecondaryContainer = NightInk,
    tertiary = Color(0xFFFFB95C),
    onTertiary = Color(0xFF452B00),
    background = NightBackground,
    onBackground = NightInk,
    surface = NightBackground,
    onSurface = NightInk,
    surfaceVariant = NightSurface,
    onSurfaceVariant = NightInkMuted,
    surfaceTint = Color.Transparent,
    outline = Color(0xFF4A5451),
    outlineVariant = NightHairline,
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
    /** Fill of a content slab. Cards are told apart by this, not by a shadow. */
    val card: Color,
    /** A card nested inside another slab, or a chip resting on one. */
    val cardStrong: Color,
    /** The one-pixel line between rows inside a card. */
    val hairline: Color,
    /** Icons that label a row rather than call for attention. */
    val iconMuted: Color,
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
    card = Mist,
    cardStrong = MistStrong,
    hairline = Hairline,
    iconMuted = InkMuted,
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
    card = NightSurface,
    cardStrong = NightSurfaceStrong,
    hairline = NightHairline,
    iconMuted = NightInkMuted,
)

/**
 * One corner language for the whole app: generous, even radii and no elevation. The
 * smaller shapes exist for chips and inputs; the large ones for sheets and the floating
 * navigation pill.
 */
private val FootballAiShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/*
 * The light-glass scheme of the home feed and the match page.
 *
 * These two screens live in their own material world — translucent white over a
 * pastel ground — so they take a scheme of their own rather than bending the app's
 * light scheme: the primary is the glass violet (every accent on those screens,
 * from the selected tab to the confidence number, is that violet), the ink is the
 * violet-grey of the glass palette, and the surfaces the shared components reach
 * for (surfaceVariant under a team badge, primaryContainer behind a highlighted
 * score chip) are the soft lavender-greys of the mockups. Everything the screens
 * draw themselves is glass; this scheme is what the *shared* components see.
 */
private val LightGlassColors = lightColorScheme(
    primary = GlassViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF2E2470),
    secondary = GlassLightInkSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E5F5),
    onSecondaryContainer = GlassLightInk,
    tertiary = Amber,
    onTertiary = Color.White,
    background = Color(0xFFF5F1FA),
    onBackground = GlassLightInk,
    surface = Color.White,
    onSurface = GlassLightInk,
    surfaceVariant = Color(0xFFEAE6F4),
    onSurfaceVariant = GlassLightInkSecondary,
    surfaceTint = Color.Transparent,
    outline = Color(0xFFB7B0CC),
    outlineVariant = Color(0xFFDCD7EA),
    error = DangerRed,
    onError = Color.White,
)

private val LightGlassExtra = FootballAiColors(
    positive = PositiveGreen,
    warning = WarningAmber,
    danger = DangerRed,
    live = Color(0xFFD32F2F),
    homeSeries = GlassViolet,
    drawSeries = Color(0xFF9A94AD),
    awaySeries = Color(0xFF5B8DD9),
    chartGrid = Color(0xFFE3DEF0),
    // A slab on glass is the glass fill itself: translucent white, told apart from
    // the ground by its edge and its sheen, exactly like the glass cards.
    card = Color.White.copy(alpha = 0.46f),
    cardStrong = Color.White.copy(alpha = 0.62f),
    // rgba(120,110,160,0.13): the hairline of the mockups, a violet-grey breath
    // between two rows of one card rather than a ruled line.
    hairline = Color(0x21786EA0),
    iconMuted = GlassLightInkMuted,
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
            shapes = FootballAiShapes,
            content = content,
        )
    }
}

/**
 * The theme of the two light-glass screens (home feed, match page).
 *
 * Same shape as [FootballAiTheme] — same typography, same shapes, same
 * language-driven layout direction — with the glass scheme and glass extras
 * above. The rest of the app keeps the user's own theme; these screens opt into
 * the glass world locally, the way they used to opt into the dark one.
 */
@Composable
fun FootballAiLightGlassTheme(
    /** Forces the layout direction; used by the in-app language override. */
    languageTag: String = Locale.getDefault().language,
    content: @Composable () -> Unit,
) {
    val layoutDirection = if (languageTag.lowercase(Locale.ROOT).startsWith("fa")) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

    CompositionLocalProvider(
        LocalFootballAiColors provides LightGlassExtra,
        LocalLayoutDirection provides layoutDirection,
    ) {
        MaterialTheme(
            colorScheme = LightGlassColors,
            typography = FootballAiTypography,
            shapes = FootballAiShapes,
            content = content,
        )
    }
}

object FootballAiThemeExtra {
    val colors: FootballAiColors
        @Composable get() = LocalFootballAiColors.current
}
