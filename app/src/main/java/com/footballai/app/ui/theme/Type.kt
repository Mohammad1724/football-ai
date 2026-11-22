package com.footballai.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.footballai.app.R

/**
 * Vazirmatn, bundled with the app.
 *
 * The whole interface is Persian, and the platform's default Arabic glyphs are a
 * fallback face: thin, uneven in weight, and with a Latin set that looks pasted on. A
 * Persian-first family makes the same text look designed, and it carries the four
 * weights the screens actually use, so a bold headline is really bold instead of being
 * synthetically smeared.
 *
 * Vazirmatn is licensed under the SIL Open Font License; the licence text ships next to
 * the font files.
 */
val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

/**
 * Typography with a strong numeric hierarchy: football analytics screens are read
 * quickly, so headlines are heavier and numbers keep their columns.
 *
 * Persian text needs more line than Latin at the same size — the descenders and the
 * dots below the line sit lower — so every line height here is deliberately generous.
 * Scores and percentages use tabular figures so a column of numbers lines up.
 */
private val Persian = Vazirmatn

val FootballAiTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Persian,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Persian,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Persian,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Persian,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Persian,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Persian,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Persian,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Persian,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 23.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Persian,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Persian,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Persian,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 19.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Persian,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 17.sp,
    ),
)
