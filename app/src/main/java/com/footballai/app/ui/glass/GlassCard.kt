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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.ui.theme.Amber
import com.footballai.app.ui.theme.GlassChipAmber
import com.footballai.app.ui.theme.GlassLightInk
import com.footballai.app.ui.theme.GlassLightInkMuted
import com.footballai.app.ui.theme.GlassLightInkSecondary
import com.footballai.app.ui.theme.GlassSteelBlueLight
import com.footballai.app.ui.theme.GlassViolet
import com.footballai.app.ui.theme.GlassVioletBright
import com.footballai.app.ui.theme.PastelLowerMid
import com.footballai.app.ui.theme.PastelTop
import com.footballai.app.ui.theme.PastelUpperMid

/**
 * The glass slab of the light redesign, counterpart of `SoftCard` on the pastel.
 *
 * The fill is translucent white — a whisper of a vertical gradient, half-white at the
 * top settling to 0.40 at the bottom — over the fixed pastel ground, finished by
 * [scrollSpecular]: a bright one-pixel edge and a highlight that moves only when the
 * card itself moves under the fixed light. A [hero] card (a top pick, the prediction
 * verdict) gets a stronger fill and a brighter edge, so the eye lands on the forecast
 * before anything else on the page.
 *
 * Restraint is the material. There are no gloss blobs and no painted reflections:
 * the earlier dark glass taught that an exaggerated sheen reads as amateur, and the
 * wallet app this design follows gets its calm from glass that is barely there.
 *
 * There is deliberately no elevation shadow. A shadow is drawn for the card's whole
 * silhouette, blurred and offset downwards; behind a *translucent* fill its darkened
 * core stays visible through the sheet as a phantom inset panel — a hard-edged
 * rectangle floating inside the card, widest at the top where the offset leaves the
 * first rows unshadowed. The same shadow also fell on whatever scrolled beneath a
 * pinned card, dimming a band of the first row under it. Like the app's SoftCard,
 * a glass card is told apart by its fill, its edge and its sheen, not by a shadow.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    hero: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** Overrides the translucent fill; see [GlassFillHeader] for when that is needed. */
    fill: Brush? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val light = LocalScrollLight.current
    var cardModifier = modifier
        .clip(shape)
        .background(fill ?: if (hero) GlassFillHero else LightGlassFill)
    if (onClick != null) {
        cardModifier = cardModifier.clickable(onClick = onClick)
    }
    cardModifier = cardModifier.scrollSpecular(shape, light, hero)
    Box(modifier = cardModifier) {
        Column(content = content)
    }
}

/**
 * The one glass fill, shared by every translucent surface of the redesign — cards,
 * the top bar's capsule and circles, the quick-action buttons, the navigation pill —
 * so the whole app is cut from the same sheet.
 */
val LightGlassFill: Brush = Brush.verticalGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.50f),
        Color.White.copy(alpha = 0.40f),
    ),
)

private val GlassFillHero = Brush.verticalGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.58f),
        Color.White.copy(alpha = 0.46f),
    ),
)

/** The selected state of a glass control: the accent itself, at a whisper. */
val LightGlassSelectedFill: Brush = SolidColor(GlassViolet.copy(alpha = 0.13f))

/**
 * Fully opaque light glass, for a surface that other content scrolls *beneath*.
 *
 * The translucent fills are the material everywhere else, but a pinned (sticky)
 * header sits on top of moving rows: through a translucent sheet the rows underneath
 * stay legible and the two layers of text read as one garbled card, and even at
 * 97–98% a bright badge still ghosted through the last few percent. This fill is the
 * pastel ground itself at full alpha — the same pink-to-sky settle the background
 * paints — so the pinned bar reads as a pane of the same sky, frozen, and nothing
 * passing below can show through at all. Anything that wraps such a surface (a
 * sticky item's padding, for instance) must be painted with it too, or the uncovered
 * strip becomes a window onto the rows scrolling underneath.
 */
val GlassFillHeader = Brush.verticalGradient(
    colors = listOf(
        PastelTop,
        PastelUpperMid,
        PastelLowerMid,
    ),
)

/**
 * Fully opaque light glass for the floating navigation bar — the other surface
 * that content scrolls *beneath*.
 *
 * The pill and the search circle float over the matches feed as an overlay, so
 * the feed passes under them exactly as it passes under a pinned header, and
 * through the translucent [LightGlassFill] the passing rows stayed legible:
 * a kickoff time and a Latin team name showed through the sheet and collided
 * with the bar's own icons and labels, and the row resting half under the bar
 * read as broken rather than covered. The bar therefore takes the header's
 * medicine — a fill at full alpha, white settling to the faintest pastel, so
 * nothing below can show through at all. What keeps it glass rather than a
 * white slab is what the header also keeps: the bright one-pixel edge and the
 * scroll-reactive specular that [scrollSpecular] draws over the fill, not a
 * see-through sheet.
 */
val GlassFillBar = Brush.verticalGradient(
    colors = listOf(
        Color.White,
        PastelUpperMid,
    ),
)

/**
 * A small pill that rests on glass: confidence levels and short facts.
 *
 * The plain chip is more glass — white at a third, a white rim, secondary ink. The
 * accent chip is the confidence pill of the mockups: a warm amber wash with amber
 * words, the one warm note in a violet-and-pastel page, kept for the one judgement
 * (how much to trust this number) that should never blend into the decoration.
 */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                if (accent) GlassChipAmber.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.34f),
            )
            .border(
                width = 1.dp,
                color = if (accent) Amber.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.60f),
                shape = CircleShape,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (accent) Amber else GlassLightInkSecondary,
            maxLines = 1,
        )
    }
}

/**
 * The 1X2 bar as drawn on glass.
 *
 * The leading outcome is violet — the colour of the verdict everywhere in the
 * redesign — the other side is a soft steel blue and the draw a quiet grey, matching
 * the approved mockups. Percentages sit under their segments; in the hero the team
 * names join them.
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
        segmentBrush(index = 0, leading = leading, base = GlassSteelBlueLight),
        segmentBrush(index = 1, leading = leading, base = Color(0xFFC9C4D6)),
        segmentBrush(index = 2, leading = leading, base = GlassSteelBlueLight),
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
                        .height(6.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(fills[index]),
                )
            }
        }
        if (showLabels) {
            Spacer(Modifier.height(6.dp))
            // The labels divide the row exactly as the bar above them does — same
            // weights, same gaps — and each label is centred in its own segment's
            // cell, so a percentage always sits under the segment it describes.
            // Equal thirds put a small away share's label under the draw segment
            // and the draw's label under the home segment. A narrow cell does not
            // clip its label: the texts are measured unbounded and centred on the
            // cell, so they keep their natural width and stay inside the card,
            // whose own clip bounds them at its edge.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val labels = listOf(
                    homeLabel ?: stringResource(R.string.selection_home),
                    stringResource(R.string.selection_draw),
                    awayLabel ?: stringResource(R.string.selection_away),
                )
                val raw = listOf(homeWin, draw, awayWin)
                labels.forEachIndexed { index, label ->
                    Column(
                        modifier = Modifier.weight(values[index].toFloat().coerceAtLeast(0.001f)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = Formatters.percent(raw[index], languageTag),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (index == leading) GlassViolet else GlassLightInk,
                            maxLines = 1,
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = GlassLightInkMuted,
                            maxLines = 1,
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                        )
                    }
                }
            }
        }
    }
}

private fun segmentBrush(index: Int, leading: Int, base: Color): Brush =
    if (index == leading) {
        Brush.verticalGradient(listOf(GlassVioletBright, GlassViolet))
    } else {
        Brush.verticalGradient(listOf(base, base))
    }

/** NaN-safe unit fraction, mirroring the guard in the Material probability bar. */
private fun Double.glassFraction(): Double = if (isFinite()) coerceIn(0.0, 1.0) else 0.0
