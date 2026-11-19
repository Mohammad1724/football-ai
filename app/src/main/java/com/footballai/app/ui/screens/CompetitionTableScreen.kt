package com.footballai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.footballai.app.domain.model.TableVenue
import com.footballai.app.core.Formatters
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.SoftBackButton
import com.footballai.app.ui.components.SoftCard
import com.footballai.app.ui.components.TableProvenanceNote
import com.footballai.app.ui.components.LeagueTableEmpty
import com.footballai.app.ui.components.LeagueTableHeader
import com.footballai.app.ui.components.LeagueTableRow
import com.footballai.app.ui.components.ProbabilityBar
import com.footballai.app.ui.components.VenueChips
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.theme.Spacing
import com.footballai.app.ui.viewmodel.CompetitionTableViewModel

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
                                text = stringResource(R.string.table_season_identifier, Formatters.integer(season, languageTag)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    // A table can be stale or have failed to arrive; the reader should be able
                    // to ask again without leaving the page.
                    IconButton(onClick = viewModel::retry, enabled = !state.isLoading) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_sync),
                        )
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
                    if (state.canSplitByVenue) {
                        item(key = "table-venue") {
                            VenueChips(
                                selected = state.venue,
                                onSelect = viewModel::setVenue,
                            )
                        }
                    }
                    if (state.isComputed) {
                        item(key = "table-computed") {
                            TableProvenanceNote(matches = state.computedMatches)
                        }
                    }
                    state.leagueStats?.let { stats ->
                        item(key = "league-stats") { LeagueStatsCard(stats) }
                    }
                    if (state.rows.isEmpty()) {
                        item(key = "table-empty") {
                            // A knockout tie has no positions; a league with no table has
                            // simply not been downloaded, and that one can be asked for.
                            LeagueTableEmpty(
                                isKnockout = state.isKnockout,
                                onRequest = viewModel::retry,
                            )
                        }
                    } else {
                        item(key = "table-header") {
                            LeagueTableHeader()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.md)
                                    .height(1.dp)
                                    .background(FootballAiThemeExtra.colors.hairline),
                            )
                        }
                        items(state.rows, key = { it.teamId }) { row ->
                            LeagueTableRow(
                                row = row,
                                modifier = Modifier.padding(vertical = Spacing.xxs),
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
