package com.footballai.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.footballai.app.ui.theme.PastelBottom
import com.footballai.app.ui.theme.PastelGlowBlue
import com.footballai.app.ui.theme.PastelGlowPeach
import com.footballai.app.ui.theme.PastelGlowViolet
import com.footballai.app.ui.theme.PastelLowerMid
import com.footballai.app.ui.theme.PastelTop
import com.footballai.app.ui.theme.PastelUpperMid

/**
 * The fixed sky behind the light glass.
 *
 * It is drawn once, behind the scrolling content, and never moves: the whole point of
 * the material is that the *cards* travel over a still background, so their sheen has
 * something to change against. The ground is a near-white page — the palette stops
 * sit within a few points of white, with a faint pink-lavender wash near the top —
 * and the three colour pools on it (violet at the top corner, a light blue down the
 * end side, peach at the bottom corner) are drawn at a whisper of alpha, roughly a
 * third of what the first version used. On device that first version read as a
 * rainbow; this is the light in the room, not a poster.
 *
 * (The file keeps the name of the dark aurora it replaces; the sky is pastel now.)
 */
@Composable
fun PastelBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to PastelTop,
                        0.40f to PastelUpperMid,
                        0.75f to PastelLowerMid,
                        1.0f to PastelBottom,
                    ),
                ),
            )
            .drawBehind {
                drawGlow(PastelGlowViolet, Offset(size.width * 0.04f, size.height * 0.02f), size.width * 0.78f, alpha = 0.10f)
                drawGlow(PastelGlowBlue, Offset(size.width * 0.96f, size.height * 0.38f), size.width * 0.72f, alpha = 0.08f)
                drawGlow(PastelGlowPeach, Offset(size.width * 0.94f, size.height * 1.02f), size.width * 0.76f, alpha = 0.09f)
            },
    )
}

private fun DrawScope.drawGlow(color: Color, center: Offset, radius: Float, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        center = center,
        radius = radius,
    )
}
