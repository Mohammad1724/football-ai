package com.footballai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.footballai.app.core.Formatters
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.TeamMonogram
import com.footballai.app.ui.matchStatusLabel
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.viewmodel.MatchesUiState
import com.footballai.app.ui.viewmodel.MatchesViewModel
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchesScreen(onMatchClick: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: MatchesViewModel = viewModel(
        factory = viewModelFactory { initializer { MatchesViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.title_matches),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.selectDay(0) }) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = stringResource(R.string.filter_today),
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.isSyncing) {
                        if (state.isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.action_sync),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            RecentDayTabs(
                selectedDayOffset = state.selectedDayOffset,
                languageTag = languageTag,
                onDaySelected = viewModel::selectDay,
            )
            MatchFilters(state = state, viewModel = viewModel)

            if (state.isLoading) {
                LoadingState()
            } else if (state.matches.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.empty_matches_title),
                    message = stringResource(R.string.empty_matches_message),
                )
            } else {
                GroupedMatchList(
                    state = state,
                    languageTag = languageTag,
                    onMatchClick = onMatchClick,
                )
            }
        }
    }
}

@Composable
private fun RecentDayTabs(
    selectedDayOffset: Int,
    languageTag: String,
    onDaySelected: (Int) -> Unit,
) {
    val now = System.currentTimeMillis()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        for (offset in MatchesViewModel.OLDEST_DAY_OFFSET..0) {
            val selected = offset == selectedDayOffset
            val epochMs = Instant.ofEpochMilli(now)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .plusDays(offset.toLong())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onDaySelected(offset) }
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when (offset) {
                        0 -> stringResource(R.string.filter_today)
                        -1 -> stringResource(R.string.filter_yesterday)
                        else -> Formatters.dateOnlyShort(epochMs, languageTag)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(if (selected) 40.dp else 0.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MatchFilters(state: MatchesUiState, viewModel: MatchesViewModel) {
    var competitionMenuOpen by remember { mutableStateOf(false) }
    val selectedCompetition = state.competitions.firstOrNull { it.id == state.competitionId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box {
            FilterChip(
                selected = state.competitionId != null,
                onClick = { competitionMenuOpen = true },
                label = {
                    Text(
                        selectedCompetition?.name ?: stringResource(R.string.filter_country_continent),
                        maxLines = 1,
                    )
                },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            )
            DropdownMenu(
                expanded = competitionMenuOpen,
                onDismissRequest = { competitionMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.filter_all_competitions)) },
                    onClick = {
                        viewModel.setCompetition(null)
                        competitionMenuOpen = false
                    },
                )
                state.competitions.forEach { competition ->
                    DropdownMenuItem(
                        text = { Text(competition.name) },
                        onClick = {
                            viewModel.setCompetition(competition.id)
                            competitionMenuOpen = false
                        },
                    )
                }
            }
        }
        FilterChip(
            selected = state.followedOnly,
            onClick = viewModel::toggleFollowedOnly,
            label = { Text(stringResource(R.string.filter_my_matches)) },
        )
        FilterChip(
            selected = state.liveOnly,
            onClick = viewModel::toggleLiveOnly,
            label = { Text(stringResource(R.string.filter_live_results)) },
        )
    }
}

@Composable
private fun GroupedMatchList(
    state: MatchesUiState,
    languageTag: String,
    onMatchClick: (String) -> Unit,
) {
    val groups = state.matches
        .groupBy { it.competitionId }
        .values
        .sortedBy { group -> group.minOf { it.kickoffEpochMs } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        groups.forEach { group ->
            item(key = group.first().competitionId) {
                val competition = state.competitions.firstOrNull { it.id == group.first().competitionId }
                CompetitionCard(
                    competitionName = group.first().competitionName,
                    country = competition?.country,
                    matches = group,
                    languageTag = languageTag,
                    onMatchClick = onMatchClick,
                )
            }
        }
    }
}

@Composable
private fun CompetitionCard(
    competitionName: String,
    country: String?,
    matches: List<MatchSummary>,
    languageTag: String,
    onMatchClick: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TeamMonogram(competitionName, size = 30)
            Spacer(Modifier.width(10.dp))
            Text(
                text = listOfNotNull(country?.takeIf { it.isNotBlank() }, competitionName.ifBlank { null })
                    .joinToString(" · ")
                    .ifBlank { stringResource(R.string.unknown_competition) },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        matches.forEachIndexed { index, match ->
            RecentMatchRow(
                match = match,
                languageTag = languageTag,
                onClick = { onMatchClick(match.id) },
            )
            if (index < matches.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecentMatchRow(match: MatchSummary, languageTag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MatchTeam(match.home, Modifier.weight(1f))
        MatchMoment(match, languageTag, Modifier.width(78.dp))
        MatchTeam(match.away, Modifier.weight(1f))
    }
}

@Composable
private fun MatchTeam(team: TeamRef, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        TeamMonogram(team.name, size = 32)
        Spacer(Modifier.width(8.dp))
        Text(
            text = team.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MatchMoment(match: MatchSummary, languageTag: String, modifier: Modifier = Modifier) {
    val liveColor = FootballAiThemeExtra.colors.live
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (match.status.isFinished) {
            Text(
                text = stringResource(R.string.status_full_time_short),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
        } else if (match.status.isLive) {
            Text(
                text = if (match.minute == null) {
                    matchStatusLabel(match.status)
                } else {
                    Formatters.elapsedLabel(match.minute, languageTag)
                },
                style = MaterialTheme.typography.labelSmall,
                color = liveColor,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
        }
        Text(
            text = if (match.status.hasScore) {
                Formatters.localizeDigits(
                    "${match.homeGoals ?: 0} - ${match.awayGoals ?: 0}",
                    languageTag,
                )
            } else if (match.status == com.footballai.app.domain.model.MatchStatus.SCHEDULED) {
                Formatters.timeOnly(match.kickoffEpochMs, languageTag)
            } else {
                matchStatusLabel(match.status)
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
