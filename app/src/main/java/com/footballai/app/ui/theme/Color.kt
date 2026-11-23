package com.footballai.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Brand palette: pitch green with a warm analytical accent. */
val PitchGreen = Color(0xFF0F7B5F)
val PitchGreenLight = Color(0xFF5BD3AC)
val PitchGreenDark = Color(0xFF00281F)
val SlateInk = Color(0xFF12201B)
val Amber = Color(0xFFB26A00)
val Sand = Color(0xFFF7F9F8)
val Charcoal = Color(0xFF0B1210)
val CardDark = Color(0xFF151F1B)
val OutlineLight = Color(0xFF6F7975)
val OutlineDark = Color(0xFF42524B)

val PositiveGreen = Color(0xFF1E8E5A)
val WarningAmber = Color(0xFFB26A00)
val DangerRed = Color(0xFFB3261E)

/*
 * Neutral scale.
 *
 * The interface is now built the way a finance app is built: a plain white page, and
 * content sitting on soft grey slabs instead of raised white cards. Elevation is gone —
 * a card is told apart by its fill and its radius, not by a shadow — which keeps long
 * lists of matches calm to scroll. These greys are deliberately blue-cold: against the
 * green accent they read as "neutral paper", whereas a warm grey turns the green olive.
 */
val Mist = Color(0xFFF3F5F7)
val MistStrong = Color(0xFFEAEDF1)
val Hairline = Color(0xFFE6EAEE)
val Ink = Color(0xFF101614)
val InkMuted = Color(0xFF6C7680)

val NightBackground = Color(0xFF0A0D0C)
val NightSurface = Color(0xFF141917)
val NightSurfaceStrong = Color(0xFF1C2320)
val NightHairline = Color(0xFF262E2B)
val NightInk = Color(0xFFE8EDEA)
val NightInkMuted = Color(0xFF98A3A0)

/*
 * Liquid-glass palette (home feed and match detail).
 *
 * A fixed deep ink-blue aurora sits behind translucent white glass. The accent is a
 * champagne gold rather than the pitch green: on a dark blue ground the green turned
 * muddy, while the gold keeps the "premium analysis" reading the redesign is built on.
 * The aurora glows are deliberately muted — they exist so the glass has something to
 * refract, not to decorate the page themselves.
 */
val AuroraBaseTop = Color(0xFF131C2E)
val AuroraBaseMid = Color(0xFF1B2942)
val AuroraBaseBottom = Color(0xFF101827)
val AuroraGlowBlue = Color(0xFF60A5FA)
val AuroraGlowViolet = Color(0xFFA78BFA)
val AuroraGlowTeal = Color(0xFF2DD4BF)
val AuroraGlowAmber = Color(0xFFFB923C)

val GlassGold = Color(0xFFE3C078)
val GlassGoldBright = Color(0xFFF0D089)
val GlassSteelBlue = Color(0xFF8FB4D9)
val GlassInk = Color(0xFFF4F1EA)
val GlassInkMuted = Color(0xFFB9C4CD)
val GlassInkFaint = Color(0xFF8FA0AF)

/*
 * Light-glass palette (home feed and match detail, the wallet-style redesign).
 *
 * The dark aurora above is retired on these two screens: the ground is now a fixed
 * pastel gradient — pink settling through lavender and sky into a trace of mint —
 * with three soft colour pools behind it, and the glass on top is plain translucent
 * white. The accent is a violet, the colour the reference wallet app builds its
 * selected states from; the champagne gold does not survive the move, because on a
 * pastel ground it reads as dirt rather than as jewellery.
 *
 * The ink scale is a violet-grey, not a neutral grey: on a lavender ground a pure
 * grey text turns green at the edges, the same trap the neutral scale above avoids
 * by going blue-cold against the pitch green.
 */
val PastelTop = Color(0xFFF9E4EE)
val PastelUpperMid = Color(0xFFEDE8FB)
val PastelLowerMid = Color(0xFFE2EDFB)
val PastelBottom = Color(0xFFE9F7F0)
val PastelGlowViolet = Color(0xFFA082FF)
val PastelGlowPeach = Color(0xFFFFAA6E)
val PastelGlowBlue = Color(0xFF8CB4FF)

val GlassLightInk = Color(0xFF26232E)
val GlassLightInkSecondary = Color(0xFF5A576B)
val GlassLightInkMuted = Color(0xFF767184)
val GlassLightInkFaint = Color(0xFF86829A)

val GlassViolet = Color(0xFF6C5CE7)
val GlassVioletBright = Color(0xFF7D6FE8)
val GlassSteelBlueLight = Color(0xFF8FB0E8)
val GlassChipAmber = Color(0xFFFFF1DB)
