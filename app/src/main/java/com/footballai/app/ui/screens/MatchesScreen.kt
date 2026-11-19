@file:OptIn(ExperimentalFoundationApi::class)

package com.footballai.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.CompetitionNameText
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.ScrollableRefreshContent
import com.footballai.app.ui.components.SwipeRefresh
import com.footballai.app.ui.components.SyncStatusStrip
import com.footballai.app.ui.components.TeamBadge
import com.footballai.app.ui.components.TeamNameLines
import com.footballai.app.ui.matchStatusLabel
import com.footballai.app.ui.components.RowDivider
import com.footballai.app.ui.components.SoftCard
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.theme.Radii
import com.footballai.app.ui.theme.Spacing
import com.footballai.app.ui.viewmodel.MatchEmptyReason
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
fun MatchesScreen(
    onMatchClick: (String) -> Unit,
    onCompetitionClick: (String) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val viewModel: MatchesViewModel = viewModel(
        factory = viewModelFactory { initializer { MatchesViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current
    // The day strip reads in the interface direction, so a later day sits on the left
    // in Persian and on the right in English.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var calendarOpen by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    // True while the pull gesture's own indicator is on screen: the app bar must not spin
    // as well, or one refresh looks like three.
    var pullActive by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
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
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !state.isSyncing && !state.autoSyncing,
                    ) {
                        if ((state.isSyncing || state.autoSyncing) && !pullActive) {
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
            QuickMatchFilters(
                state = state,
                onOpen = { filtersOpen = true },
                onFollowed = viewModel::toggleFollowedOnly,
                onLive = viewModel::toggleLiveOnly,
                languageTag = languageTag,
            )

            SyncStatusStrip(
                isSyncing = state.isSyncing || state.autoSyncing,
                isAuto = state.autoSyncing,
                progressDone = state.syncDone,
                progressTotal = state.syncTotal,
                lastSyncEpochMs = state.lastSyncEpochMs,
                nowEpochMs = state.nowEpochMs,
                added = state.justSynced,
                error = state.lastError,
                isOnline = state.isOnline,
                languageTag = languageTag,
                sourceName = state.sourceName,
            )

            SwipeRefresh(
                isRefreshing = state.isSyncing,
                onRefresh = viewModel::refresh,
                onPullStateChange = { pullActive = it },
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
                    // Changing the day used to swap the list in a single frame. The new
                    // day now slides in from the side its tab sits on, so the gesture and
                    // the result read as one movement.
                    AnimatedContent(
                        targetState = state.selectedDayOffset,
                        transitionSpec = {
                            val forwardStep = if (targetState > initialState) 1 else -1
                            val sign = if (rtl) -forwardStep else forwardStep
                            (
                                slideInHorizontally(animationSpec = tween(260)) { width -> sign * width / 3 } +
                                    fadeIn(animationSpec = tween(200))
                                ).togetherWith(
                                slideOutHorizontally(animationSpec = tween(220)) { width -> -sign * width / 6 } +
                                    fadeOut(animationSpec = tween(140)),
                            )
                        },
                        label = "day",
                    ) { _ ->
                        if (state.isLoading) {
                            LoadingState()
                        } else if (state.matches.isEmpty()) {
                            ScrollableRefreshContent {
                                val filtered = state.filters.activeCount > 0
                                EmptyState(
                                    title = stringResource(
                                        if (filtered) R.string.match_empty_filtered_title else R.string.match_empty_day_title,
                                    ),
                                    message = stringResource(when {
                                        state.emptyReason == MatchEmptyReason.FILTERED -> R.string.match_empty_filtered_message
                                        state.emptyReason == MatchEmptyReason.FILTERED_NO_DATA -> R.string.match_empty_filtered_no_data
                                        state.dailyAnalysisMode -> R.string.match_empty_daily_message
                                        else -> R.string.match_empty_day_message
                                    }),
                                    actionLabel = when {
                                        filtered -> stringResource(R.string.match_filters_clear)
                                        state.isSyncing || state.autoSyncing || state.switchingSource || !state.isOnline -> null
                                        state.dailyAnalysisMode -> stringResource(R.string.action_enable_global_coverage)
                                        else -> stringResource(R.string.action_sync)
                                    },
                                    onAction = when {
                                        filtered -> ({ viewModel.clearFilters() })
                                        state.isSyncing || state.autoSyncing || state.switchingSource || !state.isOnline -> null
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
                                onCompetitionClick = onCompetitionClick,
                            )
                        }
                    }
                }
            }
        }
    }

    if (filtersOpen) {
        MatchFiltersDialog(
            initial = state.filters,
            competitions = state.competitions,
            onApply = viewModel::applyFilters,
            onDismiss = { filtersOpen = false },
        )
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

/** The common choices stay visible; the full filter set is one tap away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickMatchFilters(
    state: MatchesUiState,
    onOpen: () -> Unit,
    onFollowed: () -> Unit,
    onLive: () -> Unit,
    languageTag: String,
) {
    val active = state.filters.activeCount
    val followedDescription = stringResource(R.string.match_filter_followed)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.page, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = active > 0,
            onClick = onOpen,
            colors = matchFilterChipColors(),
            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) },
            label = { Text(if (active > 0) stringResource(R.string.match_filters_count, Formatters.integer(active, languageTag))
                else stringResource(R.string.match_filters_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        FilterChip(
            modifier = Modifier.semantics { contentDescription = followedDescription },
            selected = state.followedOnly, onClick = onFollowed,
            colors = matchFilterChipColors(),
            leadingIcon = if (state.followedOnly) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null,
            label = { Text(stringResource(R.string.match_filter_followed_short), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        FilterChip(
            selected = state.liveOnly, onClick = onLive,
            colors = matchFilterChipColors(),
            leadingIcon = if (state.liveOnly) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null,
            label = { Text(stringResource(R.string.match_filter_live), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
    }
}

/** A favourite is intentionally also kept in its competition's complete match list. */
internal data class MatchSections(
    val favourites: List<MatchSummary>,
    val groups: List<List<MatchSummary>>,
)

internal fun matchSections(state: MatchesUiState): MatchSections {
    val ranks = state.competitions.mapIndexed { index, competition -> competition.id to index }.toMap()
    val groups = state.matches.groupBy { it.competitionId }.values.sortedWith(
        compareBy(
            { group -> ranks[group.first().competitionId] ?: Int.MAX_VALUE },
            { group -> group.minOf { it.kickoffEpochMs } },
        ),
    )
    return MatchSections(favourites = state.favourites.take(6), groups = groups)
}

@Composable
private fun GroupedMatchList(
    state: MatchesUiState,
    languageTag: String,
    onMatchClick: (String) -> Unit,
    onCompetitionClick: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val sections = matchSections(state)
    val favourites = sections.favourites
    val groups = sections.groups
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.page,
            end = Spacing.page,
            top = Spacing.sm,
            bottom = Spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (favourites.isNotEmpty()) {
            item(key = "favourites") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = Radii.cardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.favourite_teams_section),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    RowDivider()
                    favourites.forEachIndexed { index, match ->
                        RecentMatchRow(
                            match = match,
                            languageTag = languageTag,
                            onClick = { onMatchClick(match.id) },
                            showCompetition = true,
                        )
                        if (index < favourites.lastIndex) {
                            RowDivider()
                        }
                    }
                }
            }
        }
        groups.forEach { group ->
            val competId = group.first().competitionId
            val competition = state.competitions.firstOrNull { it.id == competId }
            // The tournament name pins to the top of the list for as long as its own
            // matches are on screen. Scrolling from one tournament to the next is then a
            // visible hand-over instead of a guess about where one league ended.
            stickyHeader(key = "competition-$competId") {
                CompetitionHeaderBar(
                    competitionName = group.first().competitionName,
                    competId = competId,
                    country = competition?.country,
                    matchCount = group.size,
                    languageTag = languageTag,
                    onClick = { onCompetitionClick(competId) },
                )
            }
            item(key = "matches-$competId") {
                CompetitionCard(
                    matches = group,
                    languageTag = languageTag,
                    onMatchClick = onMatchClick,
                )
            }
        }
    }
}

@Composable
private fun CompetitionHeaderBar(
    competitionName: String,
    competId: String?,
    country: String?,
    matchCount: Int,
    languageTag: String,
    onClick: () -> Unit = {},
) {
    val colors = FootballAiThemeExtra.colors
    // Opaque page behind the pinned bar, otherwise the rows scroll visibly under it.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = Spacing.sm, bottom = Spacing.xs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.small))
                .background(colors.cardStrong)
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A short accent rule reads as "new section" from a distance, the way a
            // header line does in a printed league table.
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                CompetitionNameText(
                    name = competitionName.ifBlank { stringResource(R.string.unknown_competition) },
                    competitionId = competId,
                    languageTag = languageTag,
                    primaryStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    primaryMaxLines = 1,
                    primaryColor = MaterialTheme.colorScheme.onSurface,
                )
                country?.takeIf { it.isNotBlank() }?.let { region ->
                    Text(
                        text = region,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.iconMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(
                    R.string.competition_matches_count,
                    Formatters.integer(matchCount, languageTag),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = colors.iconMuted,
            )
            Spacer(Modifier.width(Spacing.xs))
            // The bar is the way into the tournament's table. The chevron says so; without
            // it the header reads as a label and nobody taps a label.
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.action_open_table),
                tint = colors.iconMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun CompetitionCard(
    matches: List<MatchSummary>,
    languageTag: String,
    onMatchClick: (String) -> Unit,
) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        matches.forEachIndexed { index, match ->
            RecentMatchRow(
                match = match,
                languageTag = languageTag,
                onClick = { onMatchClick(match.id) },
            )
            if (index < matches.lastIndex) {
                RowDivider()
            }
        }
    }
}

@Composable
private fun RecentMatchRow(
    match: MatchSummary,
    languageTag: String,
    onClick: () -> Unit,
    showCompetition: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (showCompetition) {
                // Outside its league section a match needs to say which league it is.
                CompetitionNameText(
                    name = match.competitionName,
                    competitionId = match.competitionId,
                    languageTag = languageTag,
                    primaryStyle = MaterialTheme.typography.labelSmall,
                    secondaryStyle = MaterialTheme.typography.labelSmall,
                    primaryMaxLines = 1,
                    showLatin = false,
                )
                Spacer(Modifier.height(4.dp))
            }
            // fillMaxWidth, not weight: inside a Column a weight is a *height* weight, and
            // the team line collapsed to zero height and vanished.
            MatchTeam(match.home, languageTag, Modifier.fillMaxWidth())
        }
        MatchMoment(match, languageTag, Modifier.width(72.dp))
        MatchTeam(match.away, languageTag, Modifier.weight(1f))
    }
}

@Composable
private fun MatchTeam(team: TeamRef, languageTag: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        TeamBadge(team, size = 28)
        Spacer(Modifier.width(6.dp))
        TeamNameLines(
            team = team,
            languageTag = languageTag,
            modifier = Modifier.weight(1f, fill = false),
            primaryStyle = MaterialTheme.typography.bodyMedium,
            primaryMaxLines = 2,
            // Long published names may wrap instead of disappearing behind an ellipsis.
            secondaryMaxLines = 2,
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
                Formatters.scoreOrNull(match.homeGoals, match.awayGoals, languageTag) ?: "—"
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
