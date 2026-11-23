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
import com.footballai.app.ui.theme.AuroraBaseBottom
import com.footballai.app.ui.theme.AuroraBaseMid
import com.footballai.app.ui.theme.AuroraBaseTop
import com.footballai.app.ui.theme.AuroraGlowAmber
import com.footballai.app.ui.theme.AuroraGlowBlue
import com.footballai.app.ui.theme.AuroraGlowTeal
import com.footballai.app.ui.theme.AuroraGlowViolet

/**
 * The fixed sky behind the glass.
 *
 * It is drawn once, behind the scrolling content, and never moves: the whole point of
 * the material is that the *cards* travel over a still background, so their blur and
 * their reflection have something to change against. Four soft colour pools — blue at
 * the top end, violet down the start side, teal at the bottom end, a trace of warm
 * amber at the bottom start — sit on a deep ink-blue gradient. They are dim on
 * purpose; this is the light in the room, not a poster.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(AuroraBaseTop, AuroraBaseMid, AuroraBaseBottom),
                ),
            )
            .drawBehind {
                drawGlow(AuroraGlowBlue, Offset(size.width * 0.92f, size.height * 0.02f), size.width * 0.78f, alpha = 0.42f)
                drawGlow(AuroraGlowViolet, Offset(size.width * 0.02f, size.height * 0.30f), size.width * 0.72f, alpha = 0.36f)
                drawGlow(AuroraGlowTeal, Offset(size.width * 0.94f, size.height * 0.80f), size.width * 0.80f, alpha = 0.30f)
                drawGlow(AuroraGlowAmber, Offset(size.width * 0.06f, size.height * 1.02f), size.width * 0.70f, alpha = 0.24f)
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
