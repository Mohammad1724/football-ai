package com.footballai.app.v2.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Design language #2 (spec step 4): dark, professional, data-driven — deep
 * background, dark-grey cards with hairline borders, electric-blue accent —
 * with an equally deliberate light mode. All v2 surfaces read their colours
 * from [LocalV2Palette]; nothing hardcodes a hex per screen.
 */
data class V2Palette(
    val background: Color,
    val card: Color,
    val hairline: Color,
    val rowDivider: Color,
    val accent: Color,
    val accentText: Color,
    val onAccent: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    /** Fully opaque fill of the floating bottom bar (content scrolls beneath it). */
    val barBackground: Color,
    val live: Color,
    val liveContainer: Color,
    val selectedDay: Color,
    val onSelectedDay: Color,
)

val V2DarkPalette = V2Palette(
    background = Color(0xFF0D1117),
    card = Color(0xFF161B22),
    hairline = Color(0xFF26303C),
    rowDivider = Color(0xFF212A34),
    accent = Color(0xFF1F6FEB),
    accentText = Color(0xFF58A6FF),
    onAccent = Color.White,
    ink = Color(0xFFE8ECF1),
    inkMuted = Color(0xFF8B98A5),
    inkFaint = Color(0xFF7D8B99),
    barBackground = Color(0xFF161B22),
    live = Color(0xFFE5534B),
    liveContainer = Color(0xFF2A1518),
    selectedDay = Color(0xFF1F6FEB),
    onSelectedDay = Color.White,
)

val V2LightPalette = V2Palette(
    background = Color(0xFFF6F8FA),
    card = Color(0xFFFFFFFF),
    hairline = Color(0xFFD8DEE4),
    rowDivider = Color(0xFFE3E8ED),
    accent = Color(0xFF1F6FEB),
    accentText = Color(0xFF1F6FEB),
    onAccent = Color.White,
    ink = Color(0xFF1C2330),
    inkMuted = Color(0xFF5A6B7B),
    inkFaint = Color(0xFF5F7285),
    barBackground = Color(0xFFFFFFFF),
    live = Color(0xFFC62F27),
    liveContainer = Color(0xFFFDECEA),
    selectedDay = Color(0xFF1F6FEB),
    onSelectedDay = Color.White,
)

val LocalV2Palette = staticCompositionLocalOf { V2DarkPalette }

@Composable
fun FootballAiV2Theme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) V2DarkPalette else V2LightPalette
    // MaterialTheme is still provided (typography, ripples, Switch, …) with the
    // same tokens mapped onto its slots, so stock components match the palette.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.background,
            onBackground = palette.ink,
            surface = palette.card,
            onSurface = palette.ink,
            surfaceVariant = palette.card,
            onSurfaceVariant = palette.inkMuted,
            outline = palette.hairline,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.background,
            onBackground = palette.ink,
            surface = palette.card,
            onSurface = palette.ink,
            surfaceVariant = palette.card,
            onSurfaceVariant = palette.inkMuted,
            outline = palette.hairline,
        )
    }
    CompositionLocalProvider(LocalV2Palette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
