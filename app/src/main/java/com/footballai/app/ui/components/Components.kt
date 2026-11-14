package com.footballai.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.footballai.app.core.Formatters
import com.footballai.app.domain.model.CalibrationPoint
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.QualityLevel
import com.footballai.app.domain.model.ScoreLine
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.confidenceLevelLabel
import com.footballai.app.ui.matchStatusLabel
import com.footballai.app.ui.qualityLevelLabel
import com.footballai.app.ui.theme.FootballAiThemeExtra
import coil.compose.AsyncImage
import com.footballai.app.R

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun TeamMonogram(name: String, modifier: Modifier = Modifier, size: Int = 36) {
    val colors = FootballAiThemeExtra.colors
    val initials = name.trim().split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "?" }
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(colors.chartGrid),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = if (filled) color else color.copy(alpha = 0.14f),
        contentColor = if (filled) Color.White else color,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun MatchCard(
    match: MatchSummary,
    languageTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FootballAiThemeExtra.colors
    val isLive = match.status.isLive
    val statusColor by animateColorAsState(
        targetValue = when {
            isLive -> colors.live
            match.status.isFinished -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.primary
        },
        label = "statusColor",
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = match.competitionName.ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(
                    text = if (isLive && match.minute != null) {
                        "${matchStatusLabel(match.status)} ${Formatters.elapsedLabel(match.minute, languageTag)}"
                    } else {
                        matchStatusLabel(match.status)
                    },
                    color = statusColor,
                )
            }
            Spacer(Modifier.height(8.dp))
            TeamRow(match.home, match.homeGoals, match.kickoffEpochMs, match.status.hasScore, languageTag)
            Spacer(Modifier.height(4.dp))
            TeamRow(match.away, match.awayGoals, match.kickoffEpochMs, match.status.hasScore, languageTag)
            match.prediction?.let { prediction ->
                Spacer(Modifier.height(10.dp))
                ProbabilityBar(
                    homeWin = prediction.homeWin,
                    draw = prediction.draw,
                    awayWin = prediction.awayWin,
                    languageTag = languageTag,
                    showLegend = false,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = Formatters.percent(prediction.homeWin, languageTag),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.homeSeries,
                    )
                    Text(
                        text = Formatters.percent(prediction.draw, languageTag),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.drawSeries,
                    )
                    Text(
                        text = Formatters.percent(prediction.awayWin, languageTag),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.awaySeries,
                    )
                    Spacer(Modifier.weight(1f))
                    ConfidenceChip(level = prediction.confidenceLevel)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = Formatters.kickoffDateTime(match.kickoffEpochMs, languageTag),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        match.prediction != null -> QualityDot(match.prediction.qualityLevel)
                        match.status == MatchStatus.SCHEDULED -> StatusChip(
                            text = androidx.compose.ui.res.stringResource(R.string.badge_no_analysis),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (match.isNational) {
                        StatusChip(
                            text = androidx.compose.ui.res.stringResource(R.string.badge_national),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (match.isWomen) {
                        StatusChip(
                            text = androidx.compose.ui.res.stringResource(R.string.badge_women),
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    if (match.isNeutralVenue) {
                        StatusChip(
                            text = androidx.compose.ui.res.stringResource(R.string.badge_neutral),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Team identity badge: a flag for a nation, a crest for a club, initials when neither
 * is known.
 *
 * The flag comes from the name dictionary, not from a network request, so it is there
 * the moment a fixture is. The crest is the provider's own image: it appears when it
 * loads and the initials stay visible underneath until then, so a slow connection never
 * leaves an empty circle. A team the dictionary does not know and the provider has no
 * crest for keeps its initials rather than a made-up badge.
 */
@Composable
fun TeamBadge(team: TeamRef, size: Int = 26) {
    val flag = NameLocalization.flagEmoji(team.name, team.shortName)
    val crest = team.crestUrl?.takeIf { it.isNotBlank() }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (flag != null) {
            Text(text = flag, style = MaterialTheme.typography.bodyMedium)
        } else {
            TeamMonogram(team.name, size = size)
            if (crest != null) {
                AsyncImage(
                    model = crest,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(size.dp)
                        .clip(CircleShape),
                )
            }
        }
    }
}

/**
 * A team name in both languages.
 *
 * On a Persian screen the Persian name leads and the published Latin one sits under it,
 * so a team is still recognisable when the Persian name is not the one the user knows.
 * The Latin line is wrapped in a left-to-right mark: inside a right-to-left paragraph a
 * bare "Al Hilal" would otherwise be reordered around its spaces.
 */
@Composable
fun TeamNameLines(
    team: TeamRef,
    languageTag: String,
    modifier: Modifier = Modifier,
    primaryStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    secondaryStyle: TextStyle = MaterialTheme.typography.labelSmall,
    primaryMaxLines: Int = 1,
) {
    val pair = NameLocalization.namePair(team.name, languageTag, team.shortName)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = pair.first,
            style = primaryStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = primaryMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
        val secondary = pair.second
        if (secondary != null && secondary != pair.first) {
            Text(
                text = "\u200E" + secondary,
                style = secondaryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TeamRow(
    team: TeamRef,
    goals: Int?,
    kickoffEpochMs: Long,
    showScore: Boolean,
    languageTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            TeamBadge(team, size = 26)
            Spacer(Modifier.width(10.dp))
            TeamNameLines(
                team = team,
                languageTag = languageTag,
                modifier = Modifier.weight(1f, fill = false),
                primaryStyle = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            text = when {
                showScore && goals != null -> Formatters.integer(goals, languageTag)
                showScore -> "—"
                else -> Formatters.timeOnly(kickoffEpochMs, languageTag)
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Stacked 1X2 probability bar with a compact legend. */
@Composable
fun ProbabilityBar(
    homeWin: Double,
    draw: Double,
    awayWin: Double,
    languageTag: String,
    modifier: Modifier = Modifier,
    homeLabel: String? = null,
    awayLabel: String? = null,
    showLegend: Boolean = true,
) {
    val colors = FootballAiThemeExtra.colors
    // `coerceIn` passes NaN straight through, and a NaN fraction makes Compose throw
    // while measuring the bar. A probability that is not a number is drawn as zero.
    val segments = listOf(
        homeWin.chartFraction() to colors.homeSeries,
        draw.chartFraction() to colors.drawSeries,
        awayWin.chartFraction() to colors.awaySeries,
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            for ((probability, color) in segments) {
                if (probability <= 0.0) continue
                Box(
                    modifier = Modifier
                        .weight(probability.chartFraction().toFloat().coerceAtLeast(0.001f))
                        .height(12.dp)
                        .background(color),
                )
            }
        }
        if (showLegend) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LegendItem(
                    color = colors.homeSeries,
                    label = homeLabel ?: androidx.compose.ui.res.stringResource(R.string.selection_home),
                    value = Formatters.percent(homeWin, languageTag),
                    modifier = Modifier.weight(1f),
                )
                LegendItem(
                    color = colors.drawSeries,
                    label = androidx.compose.ui.res.stringResource(R.string.selection_draw),
                    value = Formatters.percent(draw, languageTag),
                    modifier = Modifier.weight(1f),
                )
                LegendItem(
                    color = colors.awaySeries,
                    label = awayLabel ?: androidx.compose.ui.res.stringResource(R.string.selection_away),
                    value = Formatters.percent(awayWin, languageTag),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Single market row: label, probability and a proportional bar. */
@Composable
fun MarketBarRow(
    label: String,
    probability: Double,
    languageTag: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    val colors = FootballAiThemeExtra.colors
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = Formatters.percent(probability, languageTag, decimals = if (probability < 0.1) 1 else 0),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.chartGrid),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(probability.chartFraction().toFloat())
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (highlight) MaterialTheme.colorScheme.primary else colors.homeSeries),
            )
        }
    }
}

@Composable
fun QualityBadge(level: QualityLevel, completeness: Double, languageTag: String) {
    val colors = FootballAiThemeExtra.colors
    val color = when (level) {
        QualityLevel.HIGH -> colors.positive
        QualityLevel.MEDIUM -> MaterialTheme.colorScheme.primary
        QualityLevel.LOW -> colors.warning
        QualityLevel.INSUFFICIENT -> colors.danger
    }
    StatusChip(
        text = "${qualityLevelLabel(level)} · ${Formatters.percent(completeness, languageTag)}",
        color = color,
    )
}

@Composable
fun ScoreChip(score: ScoreLine, languageTag: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val colors = FootballAiThemeExtra.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (highlight) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = Formatters.localizeDigits("${score.homeGoals} - ${score.awayGoals}", languageTag),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (highlight) colors.positive else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = Formatters.percent(score.probability, languageTag, decimals = 1),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun InfoCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Inbox,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Inbox,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Text(androidx.compose.ui.res.stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
    }
}

@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.offline_notice),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Reliability diagram: predicted probability versus observed frequency. */
@Composable
fun CalibrationChart(points: List<CalibrationPoint>, modifier: Modifier = Modifier) {
    val colors = FootballAiThemeExtra.colors
    val gridColor = colors.chartGrid
    val lineColor = MaterialTheme.colorScheme.primary
    val diagonalColor = MaterialTheme.colorScheme.outline

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(8.dp),
    ) {
        val size = size.minDimension
        val left = 8f
        val top = 8f
        val plotSize = size - 16f

        // Frame
        drawRect(
            color = gridColor,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(plotSize, plotSize),
            style = Stroke(width = 2f),
        )
        // Perfect calibration diagonal
        drawLine(
            color = diagonalColor,
            start = Offset(left, top + plotSize),
            end = Offset(left + plotSize, top),
            strokeWidth = 2f,
        )
        // Model curve
        val valid = points.filter { it.count > 0 && !it.meanPredicted.isNaN() && !it.observedFrequency.isNaN() }
        if (valid.size >= 2) {
            val path = Path()
            valid.sortedBy { it.meanPredicted }.forEachIndexed { index, point ->
                val x = left + (point.meanPredicted.coerceIn(0.0, 1.0) * plotSize).toFloat()
                val y = top + plotSize - (point.observedFrequency.coerceIn(0.0, 1.0) * plotSize).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
            for (point in valid) {
                val x = left + (point.meanPredicted.coerceIn(0.0, 1.0) * plotSize).toFloat()
                val y = top + plotSize - (point.observedFrequency.coerceIn(0.0, 1.0) * plotSize).toFloat()
                drawCircle(color = lineColor, radius = 5f, center = Offset(x, y))
            }
        }
    }
}

/** Vertical comparison bar used by the live statistics section. */
@Composable
fun StatComparisonRow(
    label: String,
    homeValue: Double?,
    awayValue: Double?,
    languageTag: String,
    modifier: Modifier = Modifier,
    isPercentage: Boolean = false,
) {
    val colors = FootballAiThemeExtra.colors
    val home = homeValue ?: 0.0
    val away = awayValue ?: 0.0
    val total = (home + away).takeIf { it > 0.0 } ?: 1.0
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = homeValue?.let {
                    if (isPercentage) Formatters.percent(it / 100.0, languageTag) else Formatters.decimal(it, languageTag, 0)
                } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = awayValue?.let {
                    if (isPercentage) Formatters.percent(it / 100.0, languageTag) else Formatters.decimal(it, languageTag, 0)
                } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth().height(6.dp)) {
            Box(
                modifier = Modifier
                    .weight((home / total).toFloat().coerceAtLeast(0.0001f))
                    .height(6.dp)
                    .background(colors.homeSeries),
            )
            Box(
                modifier = Modifier
                    .weight((away / total).toFloat().coerceAtLeast(0.0001f))
                    .height(6.dp)
                    .background(colors.awaySeries),
            )
        }
    }
}

/** Tappable row that opens another screen (settings-style navigation). */
@Composable
fun NavigationRow(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = "›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small coloured dot that communicates analysis quality without numbers. */
@Composable
fun QualityDot(level: QualityLevel, modifier: Modifier = Modifier) {
    val colors = FootballAiThemeExtra.colors
    val color = when (level) {
        QualityLevel.HIGH -> colors.positive
        QualityLevel.MEDIUM -> MaterialTheme.colorScheme.primary
        QualityLevel.LOW -> colors.warning
        QualityLevel.INSUFFICIENT -> colors.danger
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = qualityLevelLabel(level),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Evidence level, not a probability. Kept visually distinct (outlined chip) so it is
 * never read as a confidence percentage of the forecast.
 */
@Composable
fun ConfidenceChip(level: String?, modifier: Modifier = Modifier) {
    if (level.isNullOrBlank()) return
    val colors = FootballAiThemeExtra.colors
    val color = when (level) {
        "HIGH" -> colors.positive
        "MEDIUM" -> MaterialTheme.colorScheme.primary
        "LOW" -> colors.warning
        else -> colors.danger
    }
    StatusChip(text = confidenceLevelLabel(level), color = color)
}

/**
 * A value that is safe to hand to Compose as a layout fraction.
 *
 * Kotlin's `coerceIn` returns NaN unchanged, and Compose rejects a NaN fraction with an
 * exception — which on Android closes the app. Any number that is not finite becomes
 * zero here, and the rest is clamped to the unit interval.
 */
private fun Double.chartFraction(): Double = if (isFinite()) coerceIn(0.0, 1.0) else 0.0
