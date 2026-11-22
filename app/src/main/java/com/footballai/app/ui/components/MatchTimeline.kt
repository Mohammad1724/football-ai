package com.footballai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.domain.model.TimelineEntry
import com.footballai.app.domain.model.TimelineSide
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.theme.Spacing

/**
 * One line of a match timeline: the minute, a marker, what happened, and which side it
 * happened to.
 *
 * The team is shown as its badge rather than as a word, so a Persian reader does not have to
 * map an English club name back to a side, and the row keeps its shape for both teams.
 */
@Composable
fun TimelineRow(
    entry: TimelineEntry,
    home: TeamRef?,
    away: TeamRef?,
    languageTag: String,
    showSpine: Boolean = true,
) {
    val colors = FootballAiThemeExtra.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Formatters.integer(entry.minute, languageTag),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = colors.iconMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Box(
            modifier = Modifier
                .width(14.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            if (showSpine) {
                // The spine is what makes three rows a timeline instead of a list.
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(colors.hairline),
                )
            }
            EventMarker(kind = entry.kind)
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.primaryLabel(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (entry.isGoal) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val team = when (entry.side) {
            TimelineSide.HOME -> home
            TimelineSide.AWAY -> away
            TimelineSide.UNKNOWN -> null
        }
        if (team != null) {
            Spacer(Modifier.width(Spacing.sm))
            TeamBadge(team = team, size = 18)
        }
    }
}

/** The half-time break: a rule with the score the teams walked off with, when it is known. */
@Composable
fun HalfTimeRow(halfTimeHomeGoals: Int?, halfTimeAwayGoals: Int?, languageTag: String) {
    val colors = FootballAiThemeExtra.colors
    val score = Formatters.scoreOrNull(halfTimeHomeGoals, halfTimeAwayGoals, languageTag)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(colors.hairline))
        Text(
            text = if (score != null) {
                stringResource(R.string.timeline_half_time_score, score)
            } else {
                stringResource(R.string.timeline_half_time)
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.iconMuted,
        )
        Box(modifier = Modifier.weight(1f).height(1.dp).background(colors.hairline))
    }
}

/**
 * A bookable card is drawn as a card and a substitution as two arrows, because a coloured
 * dot for everything makes a timeline of five identical circles.
 */
@Composable
private fun EventMarker(kind: TimelineEntry.Kind) {
    val colors = FootballAiThemeExtra.colors
    when (kind) {
        TimelineEntry.Kind.YELLOW_CARD,
        TimelineEntry.Kind.SECOND_YELLOW -> Box(
            modifier = Modifier
                .size(width = 9.dp, height = 13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.warning),
        )

        TimelineEntry.Kind.RED_CARD -> Box(
            modifier = Modifier
                .size(width = 9.dp, height = 13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.danger),
        )

        TimelineEntry.Kind.SUBSTITUTION -> Box(
            modifier = Modifier
                .size(11.dp)
                .clip(CircleShape)
                .background(colors.awaySeries),
        )

        TimelineEntry.Kind.GOAL,
        TimelineEntry.Kind.PENALTY_GOAL,
        TimelineEntry.Kind.OWN_GOAL -> Box(
            modifier = Modifier
                .size(11.dp)
                .clip(CircleShape)
                .background(if (kind == TimelineEntry.Kind.OWN_GOAL) colors.iconMuted else colors.positive),
        )

        TimelineEntry.Kind.HALF_TIME -> Unit

        TimelineEntry.Kind.PENALTY_MISSED,
        TimelineEntry.Kind.VAR,
        TimelineEntry.Kind.OTHER -> Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(colors.chartGrid),
        )
    }
}

/**
 * What the row says, in the app's own words.
 *
 * The source's free-text detail is never printed: it arrives in English, and a Persian page
 * with half a sentence of English in every row reads as a broken page.
 */
@Composable
private fun TimelineEntry.primaryLabel(): String {
    val player = playerName ?: stringResource(R.string.timeline_unnamed_player)
    return when (kind) {
        TimelineEntry.Kind.GOAL -> assistName?.let {
            stringResource(R.string.timeline_goal_with_assist, player, it)
        } ?: stringResource(R.string.timeline_goal, player)

        TimelineEntry.Kind.PENALTY_GOAL -> stringResource(R.string.timeline_penalty_goal, player)
        TimelineEntry.Kind.OWN_GOAL -> stringResource(R.string.timeline_own_goal, player)
        TimelineEntry.Kind.PENALTY_MISSED -> stringResource(R.string.timeline_penalty_missed, player)
        TimelineEntry.Kind.YELLOW_CARD -> stringResource(R.string.timeline_yellow_card, player)
        TimelineEntry.Kind.RED_CARD -> stringResource(R.string.timeline_red_card, player)
        TimelineEntry.Kind.SECOND_YELLOW -> stringResource(R.string.timeline_second_yellow, player)
        TimelineEntry.Kind.SUBSTITUTION -> assistName?.let {
            stringResource(R.string.timeline_substitution, player, it)
        } ?: stringResource(R.string.timeline_substitution_in_only, player)

        TimelineEntry.Kind.VAR -> stringResource(R.string.timeline_var)
        TimelineEntry.Kind.OTHER -> stringResource(R.string.timeline_other)
        TimelineEntry.Kind.HALF_TIME -> stringResource(R.string.timeline_half_time)
    }
}

