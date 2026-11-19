package com.footballai.app.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.TableVenue
import com.footballai.app.domain.model.MatchTableMark
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.theme.Spacing

/** One line of a league table, as the table draws it. */
data class TableRow(
    val rank: Int,
    val teamId: String,
    val teamName: String,
    val crestUrl: String?,
    val played: Int,
    val goalDifference: Int,
    val points: Int,
    /** Null when the source published no such column; the row prints a dash. */
    val wins: Int? = null,
    val draws: Int? = null,
    val losses: Int? = null,
    val goalsFor: Int? = null,
    val goalsAgainst: Int? = null,
    val form: String?,
    /** A row whose club is stored here can be opened; a borrowed row may have no page. */
    val navigable: Boolean = false,
)

/** Dashes stand where a column was never published; a zero would be a claim. */
private const val DASH = "—"

/**
 * The column headings of a league table.
 *
 * One header for every place the table appears — the table page, a competition's table tab
 * and a club's — because three slightly different headers make the same numbers look like
 * different numbers.
 */
@Composable
fun LeagueTableHeader(modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Laid out in reading order, so the rank sits at the start of the row in Persian and
        // in English alike without a special case.
        Spacer(Modifier.width(22.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = stringResource(R.string.table_team),
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            modifier = Modifier.weight(1f),
        )
        for (label in listOf(
            R.string.table_played, R.string.table_won, R.string.table_drawn,
            R.string.table_lost, R.string.table_goal_difference, R.string.table_points,
        )) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(if (label == R.string.table_goal_difference) 32.dp else 26.dp),
            )
        }
    }
}

/**
 * One row of a league table.
 *
 * [highlighted] marks the club whose page this is: the eye should find its own row before it
 * reads a single position. The mark is a stronger card and a bolder name, not a second colour
 * scheme. A match may additionally pass a result mark; its on-row text and the match-tab
 * legend explicitly tie the tint to this fixture rather than qualification or relegation.
 */
@Composable
fun LeagueTableRow(
    row: TableRow,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    matchMark: MatchTableMark? = null,
) {
    val colors = FootballAiThemeExtra.colors
    val languageTag = LocalLanguageTag.current
    val markColor = when (matchMark) {
        MatchTableMark.WIN, MatchTableMark.LEADING -> colors.positive
        MatchTableMark.LOSS, MatchTableMark.TRAILING -> colors.danger
        MatchTableMark.DRAW, MatchTableMark.LEVEL -> colors.awaySeries
        MatchTableMark.TEAM -> MaterialTheme.colorScheme.primary
        null -> Color.Transparent
    }
    val markLabel = when (matchMark) {
        MatchTableMark.WIN -> R.string.table_match_win
        MatchTableMark.LOSS -> R.string.table_match_loss
        MatchTableMark.DRAW -> R.string.table_match_draw
        MatchTableMark.LEADING -> R.string.table_match_leading
        MatchTableMark.TRAILING -> R.string.table_match_trailing
        MatchTableMark.LEVEL -> R.string.table_match_level
        MatchTableMark.TEAM -> R.string.table_match_team
        null -> null
    }
    SoftCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        containerColor = when {
            matchMark != null -> markColor.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface)
            highlighted -> colors.cardStrong
            row.rank <= 3 -> colors.cardStrong
            else -> colors.card
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = Formatters.integer(row.rank, languageTag),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(22.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
            TeamBadge(
                team = TeamRef(id = row.teamId, name = row.teamName, crestUrl = row.crestUrl),
                size = 22,
            )
            Spacer(Modifier.width(Spacing.sm))
            // The Persian name when the catalog has one, the published name otherwise.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = NameLocalization.namePair(row.teamName, languageTag).first,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (highlighted || matchMark != null) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (markLabel != null) {
                    Text(stringResource(markLabel), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                }
            }
            NumberCell(Formatters.integer(row.played, languageTag), 26)
            NumberCell(row.wins?.let { Formatters.integer(it, languageTag) } ?: DASH, 26)
            NumberCell(row.draws?.let { Formatters.integer(it, languageTag) } ?: DASH, 26)
            NumberCell(row.losses?.let { Formatters.integer(it, languageTag) } ?: DASH, 26)
            // A signed single number rather than "goals for-goals against": a hyphen between
            // two numbers is read the other way round in a right-to-left row.
            NumberCell(
                value = when {
                    row.goalDifference > 0 -> "+" + Formatters.integer(row.goalDifference, languageTag)
                    row.goalDifference < 0 -> "−" + Formatters.integer(-row.goalDifference, languageTag)
                    else -> Formatters.integer(0, languageTag)
                },
                width = 32,
            )
            NumberCell(Formatters.integer(row.points, languageTag), 26, bold = true)
        }
    }
}

/** A number in a fixed-width column, so two-digit and one-digit values line up. */
@Composable
private fun NumberCell(value: String, width: Int, bold: Boolean = false) {
    Text(
        text = value,
        style = if (bold) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(width.dp),
    )
}

/**
 * A whole table as one block, for the pages that show it inside their own list.
 *
 * Rows are spaced here instead of by the caller so the height of a table is the same whether
 * it is the page's only content or a section of a club's page.
 */
@Composable
fun LeagueTableBlock(
    rows: List<TableRow>,
    modifier: Modifier = Modifier,
    /** The clubs whose page this table sits on: a match marks both of its teams. */
    highlightTeamIds: Set<String> = emptySet(),
    onTeamClick: ((String) -> Unit)? = null,
    matchMarks: Map<String, MatchTableMark> = emptyMap(),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LeagueTableHeader()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .height(1.dp)
                .background(FootballAiThemeExtra.colors.hairline),
        )
        rows.forEach { row ->
            LeagueTableRow(
                row = row,
                modifier = Modifier.padding(vertical = Spacing.xxs),
                highlighted = row.teamId in highlightTeamIds,
                matchMark = matchMarks[row.teamId],
                onClick = onTeamClick?.takeIf { row.navigable }?.let { click -> { click(row.teamId) } },
            )
        }
    }
}

/**
 * All matches, home, away.
 *
 * Shown only when the table was counted from results in this app: a published table is one
 * combined set of numbers, and offering a split of it would be offering a number nobody
 * computed.
 */
@Composable
fun VenueChips(
    selected: TableVenue,
    onSelect: (TableVenue) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        val options = listOf(
            TableVenue.ALL to R.string.table_venue_all,
            TableVenue.HOME to R.string.table_venue_home,
            TableVenue.AWAY to R.string.table_venue_away,
        )
        for ((venue, label) in options) {
            val isSelected = venue == selected
            SoftCard(
                modifier = Modifier.weight(1f),
                onClick = { onSelect(venue) },
                containerColor = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    FootballAiThemeExtra.colors.card
                },
            ) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
                )
            }
        }
    }
}

/**
 * The line that keeps a counted table honest.
 *
 * Its numbers come from the matches in this app, not from the league's own table, so it says
 * so and names the size of the sample: a table over eight matches is a shape, not a verdict.
 */
@Composable
fun TableProvenanceNote(matches: Int) {
    SoftCard(
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
        containerColor = FootballAiThemeExtra.colors.cardStrong,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.TableChart,
                contentDescription = null,
                tint = FootballAiThemeExtra.colors.iconMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(
                    R.string.table_computed_note,
                    Formatters.integer(matches, LocalLanguageTag.current),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What a table page says when there are no rows.
 *
 * Two different absences, said differently: a knockout has no positions at all, while a league
 * has simply not been downloaded — and that one can be asked for again.
 */
@Composable
fun LeagueTableEmpty(
    isKnockout: Boolean,
    onRequest: (() -> Unit)? = null,
) {
    if (isKnockout) {
        EmptyState(
            title = stringResource(R.string.empty_table_knockout_title),
            message = stringResource(R.string.empty_table_knockout_message),
        )
    } else {
        EmptyState(
            title = stringResource(R.string.empty_table_title),
            message = stringResource(R.string.empty_table_message),
            actionLabel = onRequest?.let { stringResource(R.string.empty_table_retry) },
            onAction = onRequest,
        )
    }
}
