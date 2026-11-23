package com.footballai.app.ui.glass

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
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
 *
 * The position is a plain field on purpose. It is written from layout callbacks and
 * read only in the draw phase, so scrolling redraws the affected cards without
 * recomposing anything.
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
 * The node redraws when its layout moves in the window (which is exactly what
 * scrolling does) and at no other time. There is no animation and no state: hold the
 * phone still and the reflection is still.
 */
fun Modifier.scrollSpecular(
    shape: Shape,
    light: ScrollLight,
    hero: Boolean = false,
): Modifier = this then ScrollSpecularElement(shape, light, hero)

private data class ScrollSpecularElement(
    val shape: Shape,
    val light: ScrollLight,
    val hero: Boolean,
) : ModifierNodeElement<ScrollSpecularNode>() {
    override fun create(): ScrollSpecularNode = ScrollSpecularNode(shape, light, hero)

    override fun update(node: ScrollSpecularNode) {
        node.shape = shape
        node.light = light
        node.hero = hero
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "scrollSpecular"
        properties["hero"] = hero
    }
}

private class ScrollSpecularNode(
    var shape: Shape,
    var light: ScrollLight,
    var hero: Boolean,
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    private var coordinates: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val coords = coordinates ?: return
        if (!coords.isAttached) return
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = outline.toPath()
        val cardOrigin = coords.positionInWindow()
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
        drawOutline(
            outline = outline,
            color = if (hero) GlassGold.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.32f),
            style = Stroke(width = 1.dp.toPx()),
        )
    }

    private fun Outline.toPath(): Path = when (this) {
        is Outline.Rectangle -> Path().apply { addRect(rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
        is Outline.Generic -> path
    }
}
