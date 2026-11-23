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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.ui.theme.AuroraBaseBottom
import com.footballai.app.ui.theme.AuroraBaseMid
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
 *
 * There is deliberately no elevation shadow. A shadow is drawn for the card's whole
 * silhouette, blurred and offset downwards; behind a *translucent* fill its darkened
 * core stays visible through the sheet as a phantom inset panel — a hard-edged
 * rectangle floating inside the card, widest at the top where the offset leaves the
 * first rows unshadowed. The same shadow also fell on whatever scrolled beneath a
 * pinned card, dimming a band of the first row under it. Like [SoftCard], a glass
 * card is told apart by its fill, its edge and its sheen, not by a shadow.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(26.dp),
    hero: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** Overrides the translucent fill; see [GlassFillOpaque] for when that is needed. */
    fill: Brush? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val light = LocalScrollLight.current
    var cardModifier = modifier
        .clip(shape)
        .background(fill ?: if (hero) GlassFillHero else GlassFill)
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

/**
 * Fully opaque dark glass, for a surface that other content scrolls *beneath*.
 *
 * The translucent fills are the material everywhere else, but a pinned (sticky)
 * header sits on top of moving rows: at 24% white the rows underneath stay legible
 * through the sheet and the two layers of text read as one garbled card, and even
 * at 97–98% a bright badge still ghosted through the last few percent. This fill
 * is the aurora's own ink bases at full alpha — the glass cues (the specular finish
 * and the bright edge drawn by [scrollSpecular]) stay, and nothing passes below
 * can show through at all. Anything that wraps such a surface (a sticky item's
 * padding, for instance) must be painted with it too, or the uncovered strip
 * becomes a window onto the rows scrolling underneath.
 */
val GlassFillOpaque = Brush.linearGradient(
    colors = listOf(
        AuroraBaseMid,
        AuroraBaseBottom,
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
