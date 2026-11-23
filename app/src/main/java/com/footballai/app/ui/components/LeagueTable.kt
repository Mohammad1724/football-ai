package com.footballai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.domain.model.TableVenue
import com.footballai.app.domain.model.MatchTableMark
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.glass.GlassCard
import com.footballai.app.ui.theme.AuroraBaseMid
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.theme.GlassGoldBright
import com.footballai.app.ui.theme.GlassInkMuted
import com.footballai.app.ui.theme.Spacing
import com.footballai.app.ui.theme.Radii

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
 * scheme. A match may additionally pass a result mark, announced to accessibility services;
 * the match-tab legend ties the tint to the fixture rather than qualification.
 */
@Composable
fun LeagueTableRow(
    row: TableRow,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    matchMark: MatchTableMark? = null,
    /**
     * Renders the row as glass on the aurora instead of a grey slab. Only the match
     * page's table opts in; the table page, competition and team pages keep the slab.
     */
    glass: Boolean = false,
) {
    val colors = FootballAiThemeExtra.colors
    val markColor = when (matchMark) {
        MatchTableMark.WIN, MatchTableMark.LEADING -> colors.positive
        MatchTableMark.LOSS, MatchTableMark.TRAILING -> colors.danger
        MatchTableMark.DRAW, MatchTableMark.LEVEL -> colors.awaySeries
        MatchTableMark.TEAM -> MaterialTheme.colorScheme.onSurfaceVariant
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
    val matchState = markLabel?.let { stringResource(it) }
    val rowModifier = modifier.fillMaxWidth()
        .then(if (!glass && matchMark == MatchTableMark.TEAM)
            Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), Radii.cardShape)
            else Modifier)
        .semantics { if (matchState != null) stateDescription = matchState }
    if (glass) {
        // The same marks in the glass language: the fixture's own teams get the hero
        // sheet (stronger fill, gold edge) where the slab used a border, a result
        // mark tints the sheet its colour over the aurora's ink, and a highlighted
        // or podium row gets a stronger flat tint where the slab used cardStrong.
        GlassCard(
            modifier = rowModifier,
            onClick = onClick,
            hero = matchMark == MatchTableMark.TEAM,
            fill = when {
                matchMark != null && matchMark != MatchTableMark.TEAM -> SolidColor(
                    markColor.copy(alpha = 0.16f).compositeOver(AuroraBaseMid.copy(alpha = 0.82f)),
                )
                highlighted || row.rank <= 3 -> SolidColor(Color.White.copy(alpha = 0.16f))
                else -> null
            },
        ) {
            LeagueTableRowContent(row = row, highlighted = highlighted, matchMark = matchMark)
        }
        return
    }
    SoftCard(
        modifier = rowModifier,
        onClick = onClick,
        containerColor = when {
            matchMark == MatchTableMark.TEAM -> colors.card
            matchMark != null -> markColor.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface)
            highlighted -> colors.cardStrong
            row.rank <= 3 -> colors.cardStrong
            else -> colors.card
        },
    ) {
        LeagueTableRowContent(row = row, highlighted = highlighted, matchMark = matchMark)
    }
}

/** The cells of a table row, shared by its slab and glass renderings. */
@Composable
private fun LeagueTableRowContent(
    row: TableRow,
    highlighted: Boolean,
    matchMark: MatchTableMark?,
) {
    val languageTag = LocalLanguageTag.current
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
        TeamNameLines(
            team = TeamRef(id = row.teamId, name = row.teamName, crestUrl = row.crestUrl),
            languageTag = languageTag,
            modifier = Modifier.weight(1f),
            primaryStyle = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (highlighted || matchMark != null) FontWeight.SemiBold else FontWeight.Normal,
            ),
            primaryMaxLines = 3,
            secondaryMaxLines = 3,
        )
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
    /** Glass rows on the aurora; see [LeagueTableRow]. Only the match page opts in. */
    glass: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LeagueTableHeader()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .height(1.dp)
                // The theme hairline is a near-black line for slabs on a page; on the
                // aurora it would vanish, so glass draws the divider in white instead.
                .background(
                    if (glass) Color.White.copy(alpha = 0.14f) else FootballAiThemeExtra.colors.hairline,
                ),
        )
        rows.forEach { row ->
            LeagueTableRow(
                row = row,
                modifier = Modifier.padding(vertical = Spacing.xxs),
                highlighted = row.teamId in highlightTeamIds,
                matchMark = matchMarks[row.teamId],
                onClick = onTeamClick?.takeIf { row.navigable }?.let { click -> { click(row.teamId) } },
                glass = glass,
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
    /** Glass chips on the aurora; only the match page's table opts in. */
    glass: Boolean = false,
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
            if (glass) {
                // The selected chip is the hero sheet — gold edge, gold words — where
                // the slab tints itself with the theme's primary.
                GlassCard(
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(venue) },
                    hero = isSelected,
                    shape = Radii.cardShape,
                ) {
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) GlassGoldBright else GlassInkMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
                    )
                }
                continue
            }
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
fun TableProvenanceNote(matches: Int, glass: Boolean = false) {
    val content: @Composable () -> Unit = {
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
    if (glass) {
        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) { content() }
    } else {
        SoftCard(
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
            containerColor = FootballAiThemeExtra.colors.cardStrong,
        ) { content() }
    }
}

/** A published table can be a source group or a cached season, not a verified complete table. */
@Composable
fun PublishedTableCaution() {
    Text(
        text = stringResource(R.string.table_published_caution),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
    )
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
