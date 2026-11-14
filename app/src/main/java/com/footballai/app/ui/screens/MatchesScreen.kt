package com.footballai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
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
import com.footballai.app.ui.components.ScrollableRefreshContent
import com.footballai.app.ui.components.SwipeRefresh
import com.footballai.app.ui.components.SyncStatusStrip
import com.footballai.app.ui.components.TeamMonogram
import com.footballai.app.ui.matchStatusLabel
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.viewmodel.MatchGenderFilter
import com.footballai.app.ui.viewmodel.MatchKindFilter
import com.footballai.app.ui.viewmodel.MatchesUiState
import com.footballai.app.ui.viewmodel.MatchSearchFilter
import com.footballai.app.ui.viewmodel.MatchesViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchesScreen(onMatchClick: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: MatchesViewModel = viewModel(
        factory = viewModelFactory { initializer { MatchesViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current
    var calendarOpen by remember { mutableStateOf(false) }

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
                    IconButton(onClick = { viewModel.setSearchOpen(true) }) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search_matches))
                    }
                    IconButton(onClick = { calendarOpen = true }) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = stringResource(R.string.open_calendar))
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
            MatchScopeFilters(state = state, viewModel = viewModel)
            if (state.sourceName.isNotBlank()) {
                Text(
                    text = stringResource(R.string.source_label, state.sourceName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            SyncStatusStrip(
                isSyncing = state.isSyncing,
                isAuto = state.autoSyncing,
                progressDone = 0,
                progressTotal = 0,
                lastSyncEpochMs = state.lastSyncEpochMs,
                nowEpochMs = state.nowEpochMs,
                added = state.justSynced,
                error = state.lastError,
                isOnline = state.isOnline,
                languageTag = languageTag,
            )

            SwipeRefresh(
                isRefreshing = state.isSyncing,
                onRefresh = viewModel::refresh,
                enabled = state.isOnline,
                modifier = Modifier.weight(1f),
            ) {
                // Swiping over the list changes the day. It is a separate axis from the
                // pull gesture, so the two never compete, and horizontal chip rows keep
                // their own scrolling because a child that consumes the drag wins it.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .daySwipe(
                            selectedDayOffset = state.selectedDayOffset,
                            onDaySelected = viewModel::selectDay,
                        ),
                ) {
                    if (state.isLoading) {
                        LoadingState()
                    } else if (state.matches.isEmpty()) {
                        ScrollableRefreshContent {
                            // An empty day is usually a coverage fact, not a missing
                            // fixture, so the empty state says which source served it and
                            // offers the one-tap switch.
                            EmptyState(
                                title = stringResource(
                                    if (state.dailyAnalysisMode) R.string.empty_coverage_title else R.string.empty_filtered_matches_title,
                                ),
                                message = when {
                                    state.dailyAnalysisMode -> stringResource(R.string.empty_daily_mode_message, state.sourceName)
                                    state.kind == MatchKindFilter.NATIONAL -> stringResource(R.string.empty_no_national_message, state.sourceName)
                                    state.selectedDayOffset < 0 -> stringResource(R.string.empty_recent_results_message)
                                    else -> stringResource(R.string.empty_upcoming_message)
                                },
                                actionLabel = when {
                                    state.isSyncing || state.switchingSource -> null
                                    state.dailyAnalysisMode -> stringResource(R.string.action_enable_global_coverage)
                                    else -> stringResource(R.string.action_sync)
                                },
                                onAction = when {
                                    state.isSyncing || state.switchingSource -> null
                                    state.dailyAnalysisMode -> ({ viewModel.enableGlobalCoverage() })
                                    else -> ({ viewModel.refresh() })
                                },
                            )
                            if (state.switchingSource) {
                                Text(
                                    text = stringResource(R.string.switching_source),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                )
                            }
                            // Both gestures are invisible by nature; say they exist.
                            Text(
                                text = stringResource(R.string.pull_refresh_hint) + " " +
                                    stringResource(R.string.day_swipe_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                            )
                        }
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
    }

    if (calendarOpen) {
        MatchCalendarDialog(
            selectedDayOffset = state.selectedDayOffset,
            onSelectDate = viewModel::selectDate,
            onToday = { viewModel.selectDay(0) },
            onDismiss = { calendarOpen = false },
        )
    }
    if (state.searchOpen) {
        MatchSearchDialog(
            state = state,
            onQueryChange = viewModel::updateSearchQuery,
            onFilterChange = viewModel::setSearchFilter,
            onDismiss = { viewModel.setSearchOpen(false) },
            onCompetitionClick = { competitionId ->
                viewModel.setCompetition(competitionId)
                state.searchResults.matches.filter { it.competitionId == competitionId }
                    .minByOrNull { kotlin.math.abs(it.kickoffEpochMs - System.currentTimeMillis()) }
                    ?.let { viewModel.selectDate(it.kickoffEpochMs) }
                viewModel.setSearchOpen(false)
            },
            onTeamClick = { team ->
                viewModel.updateSearchQuery(team.name)
                viewModel.setSearchFilter(MatchSearchFilter.MATCHES)
            },
            onMatchClick = { match ->
                viewModel.setSearchOpen(false)
                onMatchClick(match.id)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchCalendarDialog(
    selectedDayOffset: Int,
    onSelectDate: (Long) -> Unit,
    onToday: () -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val selectedDate = LocalDate.now(zone).plusDays(selectedDayOffset.toLong())
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                    val utcDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    onSelectDate(utcDate.atStartOfDay(zone).toInstant().toEpochMilli())
                }
                onDismiss()
            }) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onToday()
                onDismiss()
            }) { Text(stringResource(R.string.filter_today)) }
        },
    ) { DatePicker(state = pickerState, showModeToggle = false) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchSearchDialog(
    state: MatchesUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (MatchSearchFilter) -> Unit,
    onDismiss: () -> Unit,
    onCompetitionClick: (String) -> Unit,
    onTeamClick: (TeamRef) -> Unit,
    onMatchClick: (MatchSummary) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        modifier = Modifier.fillMaxWidth(),
        title = {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_matches_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
        },
        text = {
            val hasVisibleResults = when (state.searchFilter) {
                MatchSearchFilter.ALL -> !state.searchResults.isEmpty
                MatchSearchFilter.COMPETITIONS -> state.searchResults.competitions.isNotEmpty()
                MatchSearchFilter.TEAMS -> state.searchResults.teams.isNotEmpty()
                MatchSearchFilter.MATCHES -> state.searchResults.matches.isNotEmpty()
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MatchSearchFilter.entries.size) { index ->
                        val filter = MatchSearchFilter.entries[index]
                        FilterChip(
                            selected = state.searchFilter == filter,
                            onClick = { onFilterChange(filter) },
                            label = { Text(stringResource(filter.labelRes())) },
                        )
                    }
                }
                if (state.searchQuery.isBlank()) {
                    Text(
                        stringResource(R.string.search_local_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (!hasVisibleResults) {
                    EmptyState(stringResource(R.string.empty_matches_title), stringResource(R.string.no_search_results))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(520.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if ((state.searchFilter == MatchSearchFilter.ALL || state.searchFilter == MatchSearchFilter.COMPETITIONS) && state.searchResults.competitions.isNotEmpty()) {
                            item { SearchSectionTitle(stringResource(R.string.search_competitions)) }
                            items(state.searchResults.competitions.size) { index ->
                                val competition = state.searchResults.competitions[index]
                                SearchResultRow(
                                    title = competition.name,
                                    subtitle = competition.country ?: stringResource(R.string.search_competitions),
                                    icon = { Icon(Icons.Rounded.Groups, contentDescription = null) },
                                    onClick = { onCompetitionClick(competition.id) },
                                )
                            }
                        }
                        if ((state.searchFilter == MatchSearchFilter.ALL || state.searchFilter == MatchSearchFilter.TEAMS) && state.searchResults.teams.isNotEmpty()) {
                            item { SearchSectionTitle(stringResource(R.string.search_teams)) }
                            items(state.searchResults.teams.size) { index ->
                                val team = state.searchResults.teams[index]
                                SearchResultRow(
                                    title = team.name,
                                    subtitle = stringResource(R.string.search_team_matches),
                                    icon = { TeamMonogram(name = team.name) },
                                    onClick = { onTeamClick(team) },
                                )
                            }
                        }
                        if ((state.searchFilter == MatchSearchFilter.ALL || state.searchFilter == MatchSearchFilter.MATCHES) && state.searchResults.matches.isNotEmpty()) {
                            item { SearchSectionTitle(stringResource(R.string.search_match_results)) }
                            items(state.searchResults.matches.size) { index ->
                                val match = state.searchResults.matches[index]
                                RecentMatchRow(match = match, languageTag = LocalLanguageTag.current, onClick = { onMatchClick(match) })
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun MatchSearchFilter.labelRes(): Int = when (this) {
    MatchSearchFilter.ALL -> R.string.search_all
    MatchSearchFilter.COMPETITIONS -> R.string.search_competitions
    MatchSearchFilter.TEAMS -> R.string.search_teams
    MatchSearchFilter.MATCHES -> R.string.title_matches
}

@Composable
private fun SearchSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun SearchResultRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Horizontal swipe over the match list: left for the next day, right for the previous.
 *
 * It deliberately does nothing until the finger has travelled [SWIPE_THRESHOLD_DP], so a
 * tap on a card or a small accidental move never changes the day. In a right-to-left
 * layout the direction is flipped, because "forward" is to the left for those readers.
 */
@Composable
private fun Modifier.daySwipe(
    selectedDayOffset: Int,
    onDaySelected: (Int) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD_DP.dp.toPx() }
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    return this.pointerInput(selectedDayOffset, rightToLeft) {
        var travel = 0f
        detectHorizontalDragGestures(
            onDragStart = { travel = 0f },
            onDragCancel = { travel = 0f },
            onDragEnd = {
                if (abs(travel) >= threshold) {
                    val step = if (travel < 0f) 1 else -1
                    val next = selectedDayOffset + if (rightToLeft) -step else step
                    if (next in MatchesViewModel.OLDEST_DAY_OFFSET..MatchesViewModel.NEWEST_DAY_OFFSET) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDaySelected(next)
                    }
                }
                travel = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
                travel += dragAmount
                change.consume()
            },
        )
    }
}

private const val SWIPE_THRESHOLD_DP = 64

@Composable
private fun RecentDayTabs(
    selectedDayOffset: Int,
    languageTag: String,
    onDaySelected: (Int) -> Unit,
) {
    val now = System.currentTimeMillis()
    val listState = rememberLazyListState()
    val offsets = ((MatchesViewModel.OLDEST_DAY_OFFSET..MatchesViewModel.NEWEST_DAY_OFFSET).toList() + selectedDayOffset)
        .distinct()
        .sorted()
    LaunchedEffect(selectedDayOffset) {
        listState.animateScrollToItem(offsets.indexOf(selectedDayOffset).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            count = offsets.size,
            key = { index -> offsets[index] },
        ) { index ->
            val offset = offsets[index]
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
                    .width(104.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onDaySelected(offset) }
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when (offset) {
                        0 -> stringResource(R.string.filter_today)
                        -1 -> stringResource(R.string.filter_yesterday)
                        1 -> stringResource(R.string.filter_tomorrow)
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

/**
 * Second filter row: scope (clubs / national teams) and gender.
 *
 * Both are first-class because the catalogue covers club football, national teams and
 * women's competitions, and a user must be able to see which of those a list contains.
 */
@Composable
private fun MatchScopeFilters(state: MatchesUiState, viewModel: MatchesViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MatchKindFilter.entries.forEach { kind ->
            FilterChip(
                selected = state.kind == kind,
                onClick = { viewModel.setKindFilter(kind) },
                label = {
                    Text(
                        stringResource(
                            when (kind) {
                                MatchKindFilter.ALL -> R.string.filter_all
                                MatchKindFilter.CLUBS -> R.string.filter_clubs
                                MatchKindFilter.NATIONAL -> R.string.filter_national
                            },
                        ),
                    )
                },
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        MatchGenderFilter.entries.forEach { gender ->
            FilterChip(
                selected = state.gender == gender,
                onClick = { viewModel.setGenderFilter(gender) },
                label = {
                    Text(
                        stringResource(
                            when (gender) {
                                MatchGenderFilter.ALL -> R.string.filter_all
                                MatchGenderFilter.MEN -> R.string.filter_men
                                MatchGenderFilter.WOMEN -> R.string.filter_women
                            },
                        ),
                    )
                },
            )
        }
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
