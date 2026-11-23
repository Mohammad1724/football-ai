package com.footballai.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.outlined.TableChart
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.footballai.app.data.remote.CrestImages
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.confidenceLevelLabel
import com.footballai.app.ui.glass.GlassCard
import com.footballai.app.ui.matchStatusLabel
import com.footballai.app.ui.qualityLevelLabel
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.theme.Radii
import com.footballai.app.ui.theme.Spacing
import com.footballai.app.R
import kotlinx.coroutines.delay

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel(text = title, actionLabel = actionLabel, onAction = onAction)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun TeamMonogram(name: String, modifier: Modifier = Modifier, size: Int = 36) {
    val colors = FootballAiThemeExtra.colors
    // Tokens that start with a digit carry no identity: "1. FC Magdeburg" is FM, not 1F.
    val initials = name.trim().split(" ", ".")
        .filter { token -> token.firstOrNull()?.isLetter() == true }
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

    SoftCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = 5.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompetitionNameText(
                    name = match.competitionName.ifBlank { "—" },
                    competitionId = match.competitionId,
                    languageTag = languageTag,
                    modifier = Modifier.weight(1f),
                    primaryStyle = MaterialTheme.typography.labelSmall,
                    secondaryStyle = MaterialTheme.typography.labelSmall,
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
    // A nation is shown by its flag. A club is never shown by a flag: it gets the provider's
    // crest when there is one, and its initials otherwise — every club row looks the same
    // kind of row, whether or not the provider supplies crests.
    val flag = if (team.isNational) NameLocalization.flagEmoji(team.name, team.shortName) else null
    val crest = team.crestUrl?.takeIf { it.isNotBlank() }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // The monogram is the fallback, not a layer: drawing it under a flag printed the
        // initials on top of the flag. It is only shown when there is nothing better.
        // A crest that is still downloading — or that the source has removed — falls back
        // to the same initials instead of leaving an empty circle, so a club row never
        // shows a hole where its badge should be.
        if (flag == null) {
            if (crest == null) {
                TeamMonogram(team.name, size = size)
            } else {
                // The crest is fetched by the app's own HTTP stack, not by an image library:
                // when a badge stayed empty on a real phone there was nothing to look at,
                // because the library reported neither the status nor the reason.
                val image by produceState<ImageBitmap?>(initialValue = null, crest) {
                    do {
                        value = CrestImages.load(crest)
                        if (value == null) delay(CrestImages.RETRY_DELAY_MS)
                    } while (value == null)
                }
                val loaded = image
                if (loaded == null) {
                    TeamMonogram(team.name, size = size)
                } else {
                    Image(
                        bitmap = loaded,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(size.dp)
                            .clip(CircleShape),
                    )
                }
            }
        }
        if (flag != null) {
            Text(text = flag, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A competition name in both languages.
 *
 * Competitions were the last thing shown only in English, which is odd in an app whose
 * whole point is Persian naming: "UEFA Nations League" is written here as
 * "لیگ ملت‌های اروپا" with the published name underneath.
 */
@Composable
fun CompetitionNameText(
    name: String,
    competitionId: String?,
    languageTag: String,
    modifier: Modifier = Modifier,
    primaryStyle: TextStyle = MaterialTheme.typography.labelMedium,
    secondaryStyle: TextStyle = MaterialTheme.typography.labelSmall,
    primaryMaxLines: Int = 2,
    showLatin: Boolean = true,
    primaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /**
     * Colour of the Latin sub-line. The default keeps the long-standing softened
     * secondary; the glass match page passes the solid secondary, because there the
     * sub-line is a real name on a pale ground and the softened version fell below
     * reading contrast.
     */
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    /**
     * Opens the competition's table. The name of the competition is the one place on a match
     * or a team page that already says which league this is, so it is also the place to go
     * from there to the table — with a small table mark, because a plain name does not look
     * like a door.
     */
    onTableClick: (() -> Unit)? = null,
) {
    val pair = NameLocalization.competitionNamePair(name, competitionId, languageTag)
    val rowModifier = if (onTableClick != null) {
        modifier.clickable(onClick = onTableClick)
    } else {
        modifier
    }
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                // A left-to-right primary (a league with no Persian name) gets the
                // same wrap the Latin sub-line gets below: a left-to-right mark on
                // both sides. The leading mark alone is not enough — a trailing
                // full stop in "Termalica B-B." is direction-neutral, and the bidi
                // algorithm settles a neutral at the end of a run from the
                // paragraph's own direction, so inside a Persian paragraph it
                // still jumped to the front of the line. Between two strong
                // left-to-right characters it keeps the name's own order instead.
                text = if (pair.first.isLeftToRightText()) "\u200E" + pair.first + "\u200E" else pair.first,
                style = primaryStyle,
                color = primaryColor,
                maxLines = primaryMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = pair.second
            if (showLatin && secondary != null && secondary != pair.first) {
                Text(
                    text = "\u200E" + secondary + if (secondary.isLeftToRightText()) "\u200E" else "",
                    style = secondaryStyle,
                    color = secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onTableClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Outlined.TableChart,
                contentDescription = androidx.compose.ui.res.stringResource(R.string.action_table),
                tint = FootballAiThemeExtra.colors.iconMuted,
                modifier = Modifier.size(14.dp),
            )
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
    secondaryMaxLines: Int = 1,
) {
    val pair = NameLocalization.namePair(team.name, languageTag, team.shortName)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            // The primary is the Latin name itself whenever the dictionary has no
            // Persian one, and then it needs the left-to-right marks the sub-line
            // below already gets — on both sides of the name. The leading mark
            // alone (all this line used to add) did not hold the end of the name:
            // a trailing full stop, as in "Termalica B-B.", is direction-neutral,
            // and the bidi algorithm settles a neutral at the end of a run from
            // the paragraph's direction, so inside a Persian paragraph it was
            // still reordered to the front of the line, where it read as a
            // clipped name. With a mark after the name too, the stop sits between
            // two strong left-to-right characters and stays where it was typed.
            text = if (pair.first.isLeftToRightText()) "\u200E" + pair.first + "\u200E" else pair.first,
            style = primaryStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = primaryMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
        val secondary = pair.second
        if (secondary != null && secondary != pair.first) {
            Text(
                text = "\u200E" + secondary + if (secondary.isLeftToRightText()) "\u200E" else "",
                style = secondaryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = secondaryMaxLines,
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
            // Goals in a fixed-width column: a two-goal line and a one-goal line keep
            // the same width, so the eye compares the numbers, not their centring.
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
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
    homeTeam: TeamRef? = null,
    awayTeam: TeamRef? = null,
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
                    team = homeTeam, languageTag = languageTag,
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
                    team = awayTeam, languageTag = languageTag,
                    value = Formatters.percent(awayWin, languageTag),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, value: String, modifier: Modifier = Modifier,
    team: TeamRef? = null, languageTag: String = "en") {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            if (team == null) {
                Text(text = label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            } else {
                TeamNameLines(team = team, languageTag = languageTag, modifier = Modifier.weight(1f),
                    primaryStyle = MaterialTheme.typography.labelSmall, primaryMaxLines = 2, secondaryMaxLines = 2)
            }
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

/** Numbers follow the same layout direction as the teams, independent of Unicode bidi. */
@Composable
fun HomeAwayScore(home: Int?, away: Int?, languageTag: String, style: TextStyle, color: Color = MaterialTheme.colorScheme.onSurface) {
    if (home == null || away == null) {
        Text("—", style = style, color = color)
        return
    }
    val homeText = Formatters.integer(home, languageTag)
    val awayText = Formatters.integer(away, languageTag)
    val description = androidx.compose.ui.res.stringResource(R.string.score_home_away_description, homeText, awayText)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics { contentDescription = description }) {
        Text(homeText, style = style, color = color)
        Text(" – ", style = style, color = color)
        Text(awayText, style = style, color = color)
    }
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
            HomeAwayScore(score.homeGoals, score.awayGoals, languageTag,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (highlight) colors.positive else MaterialTheme.colorScheme.onSurface)
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
    containerColor: Color? = null,
    /** On the pastel surfaces the card is glass instead of a grey slab. */
    glass: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Row(modifier = Modifier.padding(16.dp)) {
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
    if (glass) {
        GlassCard(modifier = modifier.fillMaxWidth()) { content() }
    } else {
        SoftCard(modifier = modifier.fillMaxWidth(), containerColor = containerColor) { content() }
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
    // A row that opens something is a slab, and its arrow points the way the eye travels
    // (left, in a right-to-left layout) instead of a hard-coded glyph that mirrors badly.
    SoftCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = 4.dp),
    ) {
        SoftRow(
            title = title,
            subtitle = subtitle,
            showChevron = true,
        )
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
 * True when a name carries no right-to-left letters, so it is read left-to-right
 * even inside a right-to-left paragraph: a Latin club name on a Persian screen.
 * Persian and Arabic names contain Arabic-script letters and answer false.
 */
internal fun String.isLeftToRightText(): Boolean =
    filter { it.isLetter() }.none {
        val script = Character.UnicodeScript.of(it.code)
        script == Character.UnicodeScript.ARABIC || script == Character.UnicodeScript.HEBREW
    }

/**
 * A value that is safe to hand to Compose as a layout fraction.
 *
 * Kotlin's `coerceIn` returns NaN unchanged, and Compose rejects a NaN fraction with an
 * exception — which on Android closes the app. Any number that is not finite becomes
 * zero here, and the rest is clamped to the unit interval.
 */
private fun Double.chartFraction(): Double = if (isFinite()) coerceIn(0.0, 1.0) else 0.0
