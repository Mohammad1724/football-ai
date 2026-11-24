package com.footballai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.footballai.app.ui.glass.GlassCard
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.theme.Radii

/**
 * The one slab the whole interface is built from.
 *
 * A card here is a soft grey shape on a plain page: no border, no shadow, no tonal
 * elevation. Nested content gets a slightly stronger grey instead of a lighter one,
 * which is the opposite of the old elevation model and is what keeps a screen of twenty
 * match cards calm — nothing is competing for the eye with a drop shadow.
 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = Radii.cardShape,
    containerColor: Color? = null,
    /**
     * On the pastel surfaces the slab is glass instead of a flat fill: the same
     * translucent sheet, bright edge and scroll-reactive sheen as the glass
     * screens' own cards. [containerColor] does not apply there — glass has one
     * fill. Off by default, so every existing caller renders exactly as before.
     */
    glass: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (glass) {
        GlassCard(modifier = modifier, shape = shape, onClick = onClick) { content() }
        return
    }
    val colors = FootballAiThemeExtra.colors
    val color = containerColor ?: colors.card
    // The content slot is a Column, not a bare Box: a card that holds a list of rows must
    // stack them. A plain Surface stacks every child on top of the first, which is how a
    // card of four fixtures ended up drawn as one illegible pile.
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color) {
            Column(content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color) {
            Column(content = content)
        }
    }
}

/**
 * The small grey caption that names a group of rows: "امنیتی", "تراکنش‌ها".
 *
 * It is deliberately quieter than the rows it introduces — a section title that competes
 * with its content makes every screen look like a settings page.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = FootballAiThemeExtra.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = colors.iconMuted,
        )
        if (actionLabel != null && onAction != null) {
            Surface(onClick = onAction, shape = CircleShape, color = colors.cardStrong) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * A row inside a card: leading icon, a title, an optional value, and the arrow that says
 * "this opens something".
 *
 * The arrow points the way the reader's eye travels — left in a right-to-left interface —
 * and it is only drawn when the row is actually clickable, so a row that merely shows a
 * value is not dressed up as a control.
 */
@Composable
fun SoftRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    showChevron: Boolean = false,
    titleColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = FootballAiThemeExtra.colors
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = titleColor ?: colors.iconMuted,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = titleColor ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.iconMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (value != null) {
                Spacer(Modifier.width(12.dp))
                Text(text = value, style = MaterialTheme.typography.titleSmall, color = colors.iconMuted)
            }
            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                // AutoMirrored already flips with the layout direction: ArrowRight points
                // right in a left-to-right interface and left in a right-to-left one. Asking
                // for ArrowLeft in RTL mirrored it twice and sent the reader backwards.
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.iconMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = rowModifier, color = Color.Transparent) { content() }
    } else {
        Box(modifier = rowModifier) { content() }
    }
}

/** The hairline between two rows of the same card; it stops short of both edges. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    val colors = FootballAiThemeExtra.colors
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = colors.hairline,
    )
}

/**
 * The round back button of the top bar.
 *
 * A bare arrow floating on a white page is hard to hit and hard to see; the filled circle
 * gives it an obvious tap area and separates it from the title beside it.
 */
@Composable
fun SoftBackButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = FootballAiThemeExtra.colors
    IconButton(
        onClick = onClick,
        modifier = modifier
            .padding(4.dp)
            .background(colors.cardStrong, CircleShape)
            .size(40.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}
