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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
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
import com.footballai.app.data.repository.DailyAnalysisPolicy
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.confidenceLevelLabel
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
import com.footballai.app.ui.glass.AuroraBackground
import com.footballai.app.ui.glass.GlassCard
import com.footballai.app.ui.glass.GlassFillHeader
import com.footballai.app.ui.glass.GlassChip
import com.footballai.app.ui.glass.GlassProbabilityBar
import com.footballai.app.ui.theme.FootballAiTheme
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.theme.GlassGold
import com.footballai.app.ui.theme.GlassGoldBright
import com.footballai.app.ui.theme.GlassInkMuted
import com.footballai.app.ui.theme.Radii
import com.footballai.app.ui.theme.Spacing
import com.footballai.app.ui.viewmodel.MatchEmptyReason
import com.footballai.app.ui.viewmodel.MatchesUiState
import com.footballai.app.ui.viewmodel.MatchSearchFilter
import com.footballai.app.ui.viewmodel.MatchesViewModel
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * The home screen is the first surface of the liquid-glass redesign: a fixed aurora
 * behind glass cards, in a forced dark scheme (the material only exists on a dark
 * ground). The rest of the app keeps the user's theme; this screen and the match
 * page opt into the glass world locally.
 */
@Composable
fun MatchesScreen(
    onMatchClick: (String) -> Unit,
    onCompetitionClick: (String) -> Unit = {},
) {
    val languageTag = LocalLanguageTag.current
    FootballAiTheme(darkTheme = true, languageTag = languageTag) {
        Box(modifier = Modifier.fillMaxSize()) {
            AuroraBackground(modifier = Modifier.fillMaxSize())
            MatchesFeed(onMatchClick = onMatchClick, onCompetitionClick = onCompetitionClick)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MatchesFeed(
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
        containerColor = Color.Transparent,
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
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = GlassGoldBright,
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.action_sync),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                glass = true,
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
                    color = if (selected) GlassGoldBright else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(if (selected) 40.dp else 0.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(GlassGold),
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
    // Today's top picks lead the feed, so on the Today tab they leave the league
    // groups: a match is shown once, in the row that says why it matters, not
    // again a screenful below under its league. A group the picks empty out is
    // dropped entirely, header and all, and the counts on the headers that stay
    // count only the matches actually listed under them. Other days have no
    // top-picks row, so their groups pass through untouched.
    val picks = if (state.selectedDayOffset == 0) topPicks(state.matches) else emptyList()
    val groups = if (picks.isEmpty()) {
        sections.groups
    } else {
        val pickIds = picks.mapTo(HashSet()) { it.id }
        sections.groups
            .map { group -> group.filter { match -> match.id !in pickIds } }
            .filter { group -> group.isNotEmpty() }
    }
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
        // The day's strongest stored forecasts lead the feed — but only on the Today
        // tab, and only forecasts the app's own pick rule calls clear (see topPicks).
        if (picks.isNotEmpty()) {
            item(key = "top-picks") {
                Column {
                    Text(
                        text = stringResource(R.string.top_picks_today),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassGold,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(picks, key = { it.id }) { match ->
                            TopPickCard(
                                match = match,
                                languageTag = languageTag,
                                onClick = { onMatchClick(match.id) },
                            )
                        }
                    }
                }
            }
        }
        if (favourites.isNotEmpty()) {
            item(key = "favourites") {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
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
    // The pinned bar is glass in finish but opaque in fill: it is a sticky header,
    // so match rows constantly scroll beneath it, and through the translucent fill
    // their names and badges stayed legible inside the bar — two layers of text in
    // one card. GlassFillHeader hides whatever passes below — every stop is
    // full-alpha — but as an aurora-tinted gradient rather than a flat ink, so
    // with the specular and the bright edge the bar reads as a darker pane of
    // the same glass, not a solid navy slab.
    //
    // The card alone is not the whole item, though: the item's own padding above
    // and below the card was left unpainted, and through those transparent strips
    // the first match row still showed as a faint band attached to the pinned
    // header. The whole item is therefore painted with the same opaque fill
    // first — background before padding, so the paint covers the padding too —
    // and no part of the pinned block can act as a window onto what scrolls under.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassFillHeader)
            .padding(top = Spacing.sm, bottom = Spacing.xs),
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            onClick = onClick,
            fill = GlassFillHeader,
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                    .background(GlassGold),
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
}

@Composable
private fun CompetitionCard(
    matches: List<MatchSummary>,
    languageTag: String,
    onMatchClick: (String) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
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

/**
 * Today's strongest stored forecasts, strongest first, at most three.
 *
 * A match qualifies only when a forecast was already stored for it (the feed never
 * computes one), the fixture has not kicked off, and the app's descriptive pick rule
 * ([DailyAnalysisPolicy.pick]) calls the outcome clear rather than close or
 * insufficient — the same honesty rule the predictions tab uses. A floor of 40% on
 * the leading probability keeps a three-way coin flip out of a "most confident" row.
 */
internal fun topPicks(matches: List<MatchSummary>): List<MatchSummary> =
    matches.asSequence()
        .filter { it.status == MatchStatus.SCHEDULED && it.prediction != null }
        .mapNotNull { match ->
            val prediction = match.prediction ?: return@mapNotNull null
            val pick = DailyAnalysisPolicy.pick(
                prediction.homeWin, prediction.draw, prediction.awayWin, prediction.qualityLevel,
            )
            val top = maxOf(prediction.homeWin, prediction.draw, prediction.awayWin)
            if (pick in TOP_PICK_OUTCOMES && top >= TOP_PICK_MIN_PROBABILITY) match to top else null
        }
        .sortedByDescending { it.second }
        .map { it.first }
        .take(3)
        .toList()

private val TOP_PICK_OUTCOMES = setOf("HOME", "DRAW", "AWAY")
private const val TOP_PICK_MIN_PROBABILITY = 0.40

/** The pick sentence for a stored forecast, or null when the pick rule stays silent. */
@Composable
private fun storedPickLabel(match: MatchSummary, languageTag: String): String? {
    val prediction = match.prediction ?: return null
    return when (DailyAnalysisPolicy.pick(
        prediction.homeWin, prediction.draw, prediction.awayWin, prediction.qualityLevel,
    )) {
        "HOME" -> stringResource(
            R.string.daily_pick_team,
            NameLocalization.displayName(match.home.name, languageTag, match.home.shortName),
        )
        "AWAY" -> stringResource(
            R.string.daily_pick_team,
            NameLocalization.displayName(match.away.name, languageTag, match.away.shortName),
        )
        "DRAW" -> stringResource(R.string.daily_pick_draw)
        else -> null
    }
}

/** One of the day's strongest forecasts, as a wide hero glass card. */
@Composable
private fun TopPickCard(
    match: MatchSummary,
    languageTag: String,
    onClick: () -> Unit,
) {
    val prediction = match.prediction ?: return
    val pickLabel = storedPickLabel(match, languageTag) ?: return
    val topProbability = maxOf(prediction.homeWin, prediction.draw, prediction.awayWin)
    GlassCard(
        modifier = Modifier.width(272.dp),
        hero = true,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompetitionNameText(
                    name = match.competitionName,
                    competitionId = match.competitionId,
                    languageTag = languageTag,
                    modifier = Modifier.weight(1f),
                    primaryStyle = MaterialTheme.typography.labelSmall,
                    primaryMaxLines = 1,
                    showLatin = false,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = Formatters.timeOnly(match.kickoffEpochMs, languageTag),
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassInkMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = pickLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = Formatters.percent(topProbability, languageTag),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = GlassGoldBright,
                )
                prediction.confidenceLevel
                    ?.takeIf { it == "HIGH" || it == "MEDIUM" || it == "LOW" }
                    ?.let { level ->
                        Spacer(Modifier.width(10.dp))
                        GlassChip(text = confidenceLevelLabel(level))
                    }
            }
            Spacer(Modifier.height(10.dp))
            GlassProbabilityBar(
                homeWin = prediction.homeWin,
                draw = prediction.draw,
                awayWin = prediction.awayWin,
                languageTag = languageTag,
            )
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
    // The stored forecast rides under the fixture line when one exists and the
    // pick rule calls it clear. Nothing is computed here and nothing is invented:
    // no stored forecast, no strip. A finished match keeps its score instead —
    // the forecast for it lives on the predictions tab, with its evaluation.
    val pickLabel = if (match.status == MatchStatus.SCHEDULED) {
        storedPickLabel(match, languageTag)
    } else {
        null
    }
    val prediction = match.prediction
    val hasStrip = pickLabel != null && prediction != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Without a strip the row is a single fixture line, and the full
            // vertical padding left it floating in mostly empty glass, as tall
            // as a predicted row. The compact row hugs its line; rows with a
            // strip keep the original padding exactly.
            .padding(horizontal = 14.dp, vertical = if (hasStrip) 13.dp else 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
        if (pickLabel != null && prediction != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pickLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassGoldBright,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                prediction.confidenceLevel
                    ?.takeIf { it == "HIGH" || it == "MEDIUM" || it == "LOW" }
                    ?.let { level -> GlassChip(text = confidenceLevelLabel(level)) }
            }
            Spacer(Modifier.height(6.dp))
            GlassProbabilityBar(
                homeWin = prediction.homeWin,
                draw = prediction.draw,
                awayWin = prediction.awayWin,
                languageTag = languageTag,
            )
        }
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
            // The primary name may wrap: two lines of Persian still say which team
            // this is, where an ellipsis would hide it.
            primaryMaxLines = 2,
            // The Latin sub-name may not. Left to wrap, a long one ("Istanbul
            // Basaksehir") broke onto a second line at whatever width the column
            // happened to leave it, at an uneven offset from the one-line names
            // around it — the card's whole name block looked staggered. One line
            // with an ellipsis keeps both sides of the fixture the same height
            // and the same shape; the full name is one tap away on the match page.
            secondaryMaxLines = 1,
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
