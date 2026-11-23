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
 * Light-glass palette (home feed and match detail, the wallet-style redesign).
 *
 * The ground is a near-white page with only a whisper of pastel on it — a faint
 * pink-lavender wash near the top settling into plain paper — with three very pale
 * colour pools behind it, and the glass on top is plain translucent white. The
 * first version of this ground ran the full pastel rainbow (pink, lavender, sky,
 * mint, all at full strength) and on device it shouted: the reference wallet app
 * is calm because its ground is almost white, so the gradient stops now sit within
 * a few points of white and the mint stop is gone entirely. The accent is a
 * violet, the colour the reference app builds its selected states from; the
 * champagne gold does not survive the move, because on a pale ground it reads as
 * dirt rather than as jewellery.
 *
 * The ink scale is a violet-grey, not a neutral grey: on a lavender ground a pure
 * grey text turns green at the edges, the same trap the neutral scale above avoids
 * by going blue-cold against the pitch green.
 */
val PastelTop = Color(0xFFFBF3F8)
val PastelUpperMid = Color(0xFFF7F5FC)
val PastelLowerMid = Color(0xFFF4F8FC)
val PastelBottom = Color(0xFFFCFBFD)
val PastelGlowViolet = Color(0xFFB79CFF)
val PastelGlowPeach = Color(0xFFFFBE8F)
val PastelGlowBlue = Color(0xFFA9C6FF)

val GlassLightInk = Color(0xFF26232E)
val GlassLightInkSecondary = Color(0xFF5A576B)
val GlassLightInkMuted = Color(0xFF767184)
val GlassLightInkFaint = Color(0xFF86829A)

val GlassViolet = Color(0xFF6C5CE7)
val GlassVioletBright = Color(0xFF7D6FE8)
val GlassSteelBlueLight = Color(0xFF8FB0E8)
val GlassChipAmber = Color(0xFFFFF1DB)
