package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.TableVenue
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.components.SoftBackButton
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.MatchCard
import com.footballai.app.ui.components.LeagueTableBlock
import com.footballai.app.ui.components.LeagueTableEmpty
import com.footballai.app.ui.components.SoftCard
import com.footballai.app.ui.components.TableProvenanceNote
import com.footballai.app.ui.components.VenueChips
import com.footballai.app.ui.theme.Spacing
import com.footballai.app.ui.viewmodel.CompetitionUiState
import com.footballai.app.ui.viewmodel.CompetitionViewModel
import com.footballai.app.ui.viewmodel.LeagueTableUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompetitionScreen(
    competitionId: String,
    onBack: () -> Unit,
    onMatchClick: (String) -> Unit,
    onTableClick: () -> Unit,
    onTeamClick: (String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val container = LocalAppContainer.current
    val viewModel: CompetitionViewModel = viewModel(
        key = competitionId,
        factory = viewModelFactory { initializer { CompetitionViewModel(container, competitionId) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current

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
                    val competition = state.competition
                    Text(
                        competition?.let {
                            NameLocalization.competitionNamePair(it.name, it.id, languageTag).first
                        } ?: stringResource(R.string.title_competition),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading) {
                LoadingState()
                return@Column
            }
            // The two things a competition page is opened for: its matches, and where the
            // teams stand. Same two tabs as a club page, in the same order of importance.
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.competition_tab_fixtures), style = MaterialTheme.typography.labelMedium) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tab_table), style = MaterialTheme.typography.labelMedium) },
                )
            }
            if (selectedTab == 1) {
                CompetitionTableTab(
                    table = state.table,
                    onVenue = viewModel::setVenue,
                    onOpenFullTable = onTableClick,
                    onTeamClick = onTeamClick,
                )
                return@Column
            }
            // Same bottom inset as the other lists: without it the last card sits under the
            // gesture bar and looks cut off.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                // The table is the reason most readers open a competition, so it is one tap
                // away at the top of the fixtures too — and still reachable when the list is
                // empty.
                item(key = "competition-table-entry") {
                    SoftCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        onClick = { selectedTab = 1 },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.TableChart,
                                contentDescription = null,
                                tint = FootballAiThemeExtra.colors.iconMuted,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.table_entry_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = secondPlaceLine(state),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (state.fixtures.isEmpty()) {
                    item(key = "competition-empty") {
                        EmptyState(
                            title = stringResource(R.string.empty_competition_title),
                            message = stringResource(R.string.empty_competition_message),
                        )
                    }
                } else {
                    items(state.fixtures, key = { it.id }) { match ->
                        MatchCard(
                            match = match,
                            languageTag = languageTag,
                            onClick = { onMatchClick(match.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The competition's table, as its own tab.
 *
 * A published table is drawn as it was published; a counted one says how many matches it was
 * counted from, and can be read at home, away or altogether because those numbers are ours.
 */
@Composable
private fun CompetitionTableTab(
    table: LeagueTableUiState,
    onVenue: (TableVenue) -> Unit,
    onOpenFullTable: () -> Unit,
    onTeamClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.sm)) {
                if (table.isComputed) TableProvenanceNote(matches = table.matches)
                if (table.canSplitByVenue) VenueChips(selected = table.venue, onSelect = onVenue)
            }
        }
        if (table.isEmpty) {
            item { LeagueTableEmpty(isKnockout = table.isKnockout, onRequest = onOpenFullTable) }
        } else {
            item {
                LeagueTableBlock(
                    rows = table.rows,
                    modifier = Modifier.padding(horizontal = Spacing.page),
                    onTeamClick = onTeamClick,
                )
            }
            item {
                SoftCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.page, vertical = Spacing.cardGap),
                    onClick = onOpenFullTable,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.TableChart,
                            contentDescription = null,
                            tint = FootballAiThemeExtra.colors.iconMuted,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.table_open_full_page),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** The line under the table entry: what the tab will show, in as few words as possible. */
@Composable
private fun secondPlaceLine(state: CompetitionUiState): String {
    val leader = state.table.rows.firstOrNull()
        ?: return stringResource(R.string.table_entry_subtitle)
    return stringResource(
        R.string.table_entry_leader,
        NameLocalization.namePair(leader.teamName, LocalLanguageTag.current).first,
        Formatters.integer(leader.points, LocalLanguageTag.current),
    )
}
