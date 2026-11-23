package com.footballai.app.ui.glass

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.footballai.app.ui.theme.GlassGold

/**
 * The one fixed light source of the glass theme.
 *
 * The light never moves and never animates. It is anchored near the top of the window
 * by the app shell (see [trackScrollLight]) and every glass surface reads it while
 * drawing: a card's reflection is the light's position minus the card's own position
 * in the window, so when a list scrolls, each card slides under the light and its
 * highlight travels across the glass — the behaviour of a real sheet of glass passing
 * under a lamp, not a looping animation.
 */
class ScrollLight {
    var positionInWindow: Offset = Offset.Zero
}

val LocalScrollLight = compositionLocalOf { ScrollLight() }

/**
 * Anchors the [ScrollLight] of this subtree: the light sits above the start-side of
 * the top area of the window, like ceiling light over a table. Applied once, on the
 * app shell's root, so every glass surface in every destination shares one light.
 */
fun Modifier.trackScrollLight(light: ScrollLight): Modifier = onGloballyPositioned { coordinates ->
    val origin = coordinates.positionInWindow()
    light.positionInWindow = Offset(
        x = origin.x + coordinates.size.width * 0.72f,
        y = origin.y + coordinates.size.height * 0.14f,
    )
}

/**
 * Draws the glass finish over a clipped surface: the moving specular highlight, a
 * quiet gloss across the top, and the bright one-pixel edge that sells the material.
 *
 * The card's window position is held in snapshot state that is written from layout
 * and read only inside the draw block, so a position change (which is exactly what
 * scrolling produces) invalidates the draw phase alone — no recomposition, no
 * animation. Hold the phone still and the reflection is still.
 *
 * Written with draw-phase state rather than DrawModifierNode.invalidateDraw(),
 * which only exists in Compose UI 1.7+; this project is on 1.6.8.
 */
fun Modifier.scrollSpecular(
    shape: Shape,
    light: ScrollLight,
    hero: Boolean = false,
): Modifier = composed {
    var positionInWindow by remember { mutableStateOf(Offset.Zero) }
    Modifier
        .onGloballyPositioned { coordinates ->
            positionInWindow = coordinates.positionInWindow()
        }
        .drawWithContent {
            drawContent()
            val cardOrigin = positionInWindow
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = outline.toPath()
            val center = Offset(
                x = light.positionInWindow.x - cardOrigin.x,
                y = light.positionInWindow.y - cardOrigin.y,
            )
            val radius = size.maxDimension * 1.25f
            val sheenAlpha = if (hero) 0.22f else 0.15f
            clipPath(path) {
                // The specular pool: brightest where the surface passes nearest the light.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = sheenAlpha),
                            Color.White.copy(alpha = sheenAlpha * 0.35f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                )
                // A still gloss across the top of the sheet, like light catching a bevel.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (hero) 0.10f else 0.07f),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = size.height * 0.55f,
                    ),
                )
            }
            // The edge is drawn last and unclipped, so it stays a full one-pixel line.
            drawPath(
                path = path,
                color = if (hero) GlassGold.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.32f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
}

private fun Outline.toPath(): Path = when (this) {
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
}
