package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.LeagueStats
import com.footballai.app.core.Formatters
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.SoftBackButton
import com.footballai.app.ui.components.SoftCard
import com.footballai.app.ui.components.ProbabilityBar
import com.footballai.app.ui.components.TeamBadge
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.theme.Spacing
import com.footballai.app.ui.viewmodel.CompetitionTableViewModel
import com.footballai.app.ui.viewmodel.TableRow

/**
 * The table of one tournament.
 *
 * Reached by tapping the pinned tournament name in the fixtures list, which is where a
 * reader already is when the question "who is above whom" comes up. Every row shows the
 * club's crest (or its initials), the played count, goal difference and points — the four
 * columns a table is read by — and the ranking number is drawn the way the language reads,
 * so it stays on the right in Persian.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CompetitionTableScreen(
    competitionId: String,
    onBack: () -> Unit,
    onTeamClick: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: CompetitionTableViewModel = viewModel(
        key = competitionId,
        factory = viewModelFactory { initializer { CompetitionTableViewModel(container, competitionId) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current
    val competitionName = NameLocalization
        .competitionNamePair(state.competitionName, competitionId, languageTag).first

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    SoftBackButton(
                        onClick = onBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = competitionName.ifBlank { stringResource(R.string.title_competition) },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.season?.let { season ->
                            Text(
                                text = seasonLabel(season),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> LoadingState()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Spacing.page, end = Spacing.page,
                        top = Spacing.sm, bottom = Spacing.xxl,
                    ),
                ) {
                    // Drawn even when there is no table: these numbers come from the results
                    // the app holds, so a league whose table the source never publishes still
                    // gets its home/draw/away split instead of an apology.
                    state.leagueStats?.let { stats ->
                        item(key = "league-stats") { LeagueStatsCard(stats) }
                    }
                    if (state.rows.isEmpty()) {
                        item(key = "table-empty") {
                            EmptyState(
                                title = stringResource(R.string.empty_table_title),
                                message = stringResource(R.string.empty_table_message),
                            )
                        }
                    } else {
                        item(key = "table-header") { TableHeader() }
                        items(state.rows, key = { it.teamId }) { row ->
                            TableRowCard(
                                row = row,
                                onClick = { if (row.navigable) onTeamClick(row.teamId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The league's own results, read as numbers.
 *
 * Everything here is counted from matches the app already holds, so it works for leagues
 * whose table the source does not publish. Card averages carry the size of the sample they
 * were computed from, because per-match statistics are downloaded for a handful of matches,
 * not for a season.
 */
@Composable
private fun LeagueStatsCard(stats: LeagueStats) {
    val languageTag = LocalLanguageTag.current
    val colors = FootballAiThemeExtra.colors
    SoftCard(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                text = stringResource(R.string.league_stats_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    R.string.league_stats_matches,
                    Formatters.integer(stats.matches, languageTag),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.iconMuted,
            )
            Spacer(Modifier.height(Spacing.sm))
            // The same bar the forecast uses, over the league's own results: home, draw, away.
            ProbabilityBar(
                homeWin = stats.homeWinShare,
                draw = stats.drawShare,
                awayWin = stats.awayWinShare,
                languageTag = languageTag,
                homeLabel = stringResource(R.string.league_stats_home_wins),
                awayLabel = stringResource(R.string.league_stats_away_wins),
            )
            Spacer(Modifier.height(Spacing.sm))
            InfoRow(
                label = stringResource(R.string.league_stats_goals),
                value = Formatters.decimal(stats.goalsPerMatch, languageTag, decimals = 1),
            )
            InfoRow(
                label = stringResource(R.string.league_stats_goals_home),
                value = Formatters.decimal(stats.goalsHomePerMatch, languageTag, decimals = 1),
            )
            InfoRow(
                label = stringResource(R.string.league_stats_goals_away),
                value = Formatters.decimal(stats.goalsAwayPerMatch, languageTag, decimals = 1),
            )
            // Only when a sample exists: an average over no matches is not a zero.
            val yellow = stats.yellowCardsPerMatch
            if (yellow != null) {
                InfoRow(
                    label = stringResource(
                        R.string.league_stats_cards,
                        Formatters.integer(stats.cardMatches, languageTag),
                    ),
                    value = stringResource(
                        R.string.league_stats_cards_value,
                        Formatters.decimal(yellow, languageTag, decimals = 1),
                        Formatters.decimal(stats.redCardsPerMatch ?: 0.0, languageTag, decimals = 1),
                    ),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TableHeader() {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
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

@Composable
private fun TableRowCard(row: TableRow, onClick: () -> Unit) {
    val colors = FootballAiThemeExtra.colors
    val languageTag = LocalLanguageTag.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxs)) {
        SoftCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick.takeIf { row.navigable },
            // The top three are the places a season is decided in; a slightly stronger card
            // says so without a second colour scheme.
            containerColor = if (row.rank <= 3) colors.cardStrong else colors.card,
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
                Text(
                    text = NameLocalization.namePair(row.teamName, languageTag).first,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                NumberCell(Formatters.integer(row.played, languageTag), 26)
                NumberCell(row.wins?.let { Formatters.integer(it, languageTag) } ?: DASH, 26)
                NumberCell(row.draws?.let { Formatters.integer(it, languageTag) } ?: DASH, 26)
                NumberCell(row.losses?.let { Formatters.integer(it, languageTag) } ?: DASH, 26)
                // A signed single number rather than "goals for-goals against": a hyphen
                // between two numbers is read the other way round in a right-to-left row.
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
}

private const val DASH = "—"

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

/** A season is written as a single year or a span, the way the source numbers it. */
private fun seasonLabel(season: Int): String =
    if (season >= 2000) "${season - 1}/${season.toString().takeLast(2)}" else season.toString()
