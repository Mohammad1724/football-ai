package com.footballai.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.ui.theme.GlassGold
import com.footballai.app.ui.theme.GlassGoldBright
import com.footballai.app.ui.theme.GlassInk
import com.footballai.app.ui.theme.GlassInkMuted
import com.footballai.app.ui.theme.GlassSteelBlue

/**
 * The glass slab of the redesigned surfaces, counterpart of `SoftCard` on the aurora.
 *
 * The fill is a translucent white gradient — brighter at the top start, fading down —
 * over the fixed aurora, finished by [scrollSpecular]: a bright edge and a highlight
 * that moves only when the card itself moves under the fixed light. A [hero] card (the
 * prediction verdict) gets a stronger fill and a gold-tinted edge, so the eye lands on
 * the forecast before anything else on the page.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(26.dp),
    hero: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val light = LocalScrollLight.current
    var cardModifier = modifier
        .shadow(elevation = 16.dp, shape = shape, clip = false)
        .clip(shape)
        .background(if (hero) GlassFillHero else GlassFill)
    if (onClick != null) {
        cardModifier = cardModifier.clickable(onClick = onClick)
    }
    cardModifier = cardModifier.scrollSpecular(shape, light, hero)
    Box(modifier = cardModifier) {
        Column(content = content)
    }
}

private val GlassFill = Brush.linearGradient(
    colorStops = arrayOf(
        0.0f to Color.White.copy(alpha = 0.24f),
        0.45f to Color.White.copy(alpha = 0.08f),
        1.0f to Color.White.copy(alpha = 0.05f),
    ),
)

private val GlassFillHero = Brush.linearGradient(
    colorStops = arrayOf(
        0.0f to Color.White.copy(alpha = 0.30f),
        0.45f to Color.White.copy(alpha = 0.11f),
        1.0f to Color.White.copy(alpha = 0.07f),
    ),
)

/** A small pill that rests on glass: confidence levels and short facts. */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(
                width = 1.dp,
                color = if (accent) GlassGold.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.22f),
                shape = CircleShape,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (accent) GlassGoldBright else GlassInk,
            maxLines = 1,
        )
    }
}

/**
 * The 1X2 bar as drawn on glass.
 *
 * The leading outcome is gold — the colour of the verdict everywhere in the redesign —
 * the other side is a soft steel blue and the draw a translucent white, matching the
 * approved mockups. Percentages sit under their segments; in the hero the team names
 * join them.
 */
@Composable
fun GlassProbabilityBar(
    homeWin: Double,
    draw: Double,
    awayWin: Double,
    languageTag: String,
    modifier: Modifier = Modifier,
    homeLabel: String? = null,
    awayLabel: String? = null,
    showLabels: Boolean = true,
) {
    val values = listOf(homeWin.glassFraction(), draw.glassFraction(), awayWin.glassFraction())
    val leading = values.indices.maxByOrNull { values[it] } ?: 0
    val fills = listOf(
        segmentBrush(index = 0, leading = leading, base = GlassSteelBlue),
        segmentBrush(index = 1, leading = leading, base = Color.White.copy(alpha = 0.45f)),
        segmentBrush(index = 2, leading = leading, base = GlassSteelBlue),
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            values.forEachIndexed { index, value ->
                if (value <= 0.0) return@forEachIndexed
                Box(
                    modifier = Modifier
                        .weight(value.toFloat().coerceAtLeast(0.001f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(fills[index]),
                )
            }
        }
        if (showLabels) {
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                val labels = listOf(
                    homeLabel ?: stringResource(R.string.selection_home),
                    stringResource(R.string.selection_draw),
                    awayLabel ?: stringResource(R.string.selection_away),
                )
                val raw = listOf(homeWin, draw, awayWin)
                labels.forEachIndexed { index, label ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = Formatters.percent(raw[index], languageTag),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (index == leading) GlassGoldBright else GlassInk,
                            maxLines = 1,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = GlassInkMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun segmentBrush(index: Int, leading: Int, base: Color): Brush =
    if (index == leading) {
        Brush.verticalGradient(listOf(GlassGoldBright, GlassGold))
    } else {
        Brush.verticalGradient(listOf(base, base))
    }

/** NaN-safe unit fraction, mirroring the guard in the Material probability bar. */
private fun Double.glassFraction(): Double = if (isFinite()) coerceIn(0.0, 1.0) else 0.0
