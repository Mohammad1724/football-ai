package com.footballai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.domain.model.MarketType
import com.footballai.app.domain.model.MatchDetail
import com.footballai.app.domain.model.TeamLineup
import com.footballai.app.domain.model.MatchStatisticPair
import com.footballai.app.domain.model.PredictionView
import com.footballai.app.domain.model.StatisticType
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.localizedString
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.components.SoftBackButton
import com.footballai.app.ui.components.CompetitionNameText
import com.footballai.app.ui.components.ConfidenceChip
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.ErrorState
import com.footballai.app.ui.components.InfoCard
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.SwipeRefresh
import com.footballai.app.ui.components.MarketBarRow
import com.footballai.app.ui.components.ProbabilityBar
import com.footballai.app.ui.components.QualityBadge
import com.footballai.app.ui.components.ScoreChip
import com.footballai.app.ui.components.SectionHeader
import com.footballai.app.ui.components.StatComparisonRow
import com.footballai.app.ui.components.StatusChip
import com.footballai.app.ui.components.TeamBadge
import com.footballai.app.ui.components.TeamMonogram
import com.footballai.app.ui.components.TeamNameLines
import com.footballai.app.ui.errorLabel
import com.footballai.app.ui.evidenceGapLabel
import com.footballai.app.ui.explanationLabel
import com.footballai.app.ui.marketSelectionLabel
import com.footballai.app.ui.missingDataLabel
import com.footballai.app.ui.matchStatusLabel
import com.footballai.app.ui.qualityNoteLabel
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.viewmodel.MatchDetailViewModel
import kotlin.math.roundToInt

/**
 * Tab identities.
 *
 * The live tab is inserted between Analysis and Statistics when a match kicks off; keeping
 * the selection by name means the screen stays where the user left it, whatever the row
 * looks like afterwards.
 */
private const val TAB_ANALYSIS = "analysis"
private const val TAB_LIVE = "live"
private const val TAB_STATISTICS = "statistics"
private const val TAB_HEAD_TO_HEAD = "head_to_head"
private const val TAB_LINEUPS = "lineups"

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MatchDetailScreen(
    matchId: String,
    onBack: () -> Unit,
    onTeamClick: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: MatchDetailViewModel = viewModel(
        key = matchId,
        factory = viewModelFactory { initializer { MatchDetailViewModel(container, matchId) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current
    // The chosen tab is remembered by identity, not by index: a live match adds a tab
    // in the middle of the row, and an index kept across that change would silently
    // point at its neighbour.
    var selectedTabKey by rememberSaveable { mutableStateOf(TAB_ANALYSIS) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onScreenVisible()
                Lifecycle.Event.ON_STOP -> viewModel.onScreenHidden()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.onScreenVisible()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenHidden()
        }
    }

    val detail = state.detail
    // The lineups tab is appended last and only when the source says it has (or gave) them,
    // so the indices of the existing tabs never move.
    val hasLineupTab = detail != null && (detail.summary.hasLineups || detail.lineups.isNotEmpty())
    val hasLiveTabNow = detail?.summary?.status?.isLive == true
    val tabKeys = buildList {
        add(TAB_ANALYSIS)
        if (hasLiveTabNow) add(TAB_LIVE)
        add(TAB_STATISTICS)
        add(TAB_HEAD_TO_HEAD)
        if (hasLineupTab) add(TAB_LINEUPS)
    }
    val tabs = tabKeys.map { key ->
        stringResource(
            when (key) {
                TAB_LIVE -> R.string.tab_live
                TAB_STATISTICS -> R.string.tab_statistics
                TAB_HEAD_TO_HEAD -> R.string.tab_head_to_head
                TAB_LINEUPS -> R.string.tab_lineups
                else -> R.string.tab_analysis
            },
        )
    }
    // A tab that disappears (a live match ends) falls back to the first tab instead of
    // landing on whichever tab inherited its index.
    val selectedTab = tabKeys.indexOf(selectedTabKey).takeIf { it >= 0 } ?: 0

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
                    val summary = detail?.summary
                    val competitionName = summary?.competitionName?.takeIf { it.isNotBlank() }
                    Text(
                        // The Persian name is what the bar shows; the published name is in
                        // the header card below, where there is room for both.
                        text = competitionName?.let {
                            NameLocalization.competitionNamePair(it, summary?.competitionId, languageTag).first
                        } ?: stringResource(R.string.title_match),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.isRefreshing) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_sync),
                            tint = if (state.isRefreshing) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading && detail == null) {
                LoadingState()
                return@Column
            }
            if (detail == null) {
                EmptyState(
                    title = stringResource(R.string.empty_match_title),
                    message = stringResource(R.string.empty_match_message),
                )
                return@Column
            }

            // The match card used to sit above the tab row, so it never moved: on a phone
            // it kept roughly half the screen for the whole visit. It is now the first item
            // of each tab's list, which scrolls it away while the tabs stay in reach.
            val matchHeader: @Composable () -> Unit = {
                MatchHeader(
                    detail = detail,
                    languageTag = languageTag,
                    onTeamClick = onTeamClick,
                    favouriteTeams = state.favouriteTeams,
                    onToggleFavourite = viewModel::toggleFavourite,
                )
            }

            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTabKey = tabKeys[index] },
                        text = { Text(title, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }

            val hasLiveTab = hasLiveTabNow
            // The download can only help when the archive itself is missing; an unknown
            // team name or a women's international would be unaffected by it.
            val missingArchive = state.lastError?.let { error ->
                error.code == com.footballai.app.core.ErrorCode.NO_NATIONAL_HISTORY && error.detail == null
            } == true
            // Switching tabs is a movement, not a jump: the outgoing tab leaves the
            // way the new one arrives, so the eye can follow which direction it went.
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { width -> direction * width / 8 } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> -direction * width / 8 } + fadeOut())
                },
                label = "match-tab",
                modifier = Modifier.fillMaxSize(),
            ) { tab ->
                when {
                    tab == 0 -> SwipeRefresh(
                        isRefreshing = state.isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        AnalysisTab(
                            header = matchHeader,
                            prediction = state.prediction,
                            isPredicting = state.isPredicting,
                            error = state.lastError?.let { errorLabel(it) },
                            isLiveMatch = detail.summary.status.isLive,
                            isFinished = detail.summary.status.isFinished,
                            homeName = NameLocalization.displayName(
                                detail.summary.home.name,
                                languageTag,
                                detail.summary.home.shortName,
                            ),
                            awayName = NameLocalization.displayName(
                                detail.summary.away.name,
                                languageTag,
                                detail.summary.away.shortName,
                            ),
                            // A forecast the app is not allowed to compute (a live game in
                            // daily mode, a finished one) must not come with a retry button
                            // that is known to fail.
                            canPredict = (state.lastError?.code ?: state.predictionWarning?.code) !=
                                com.footballai.app.core.ErrorCode.INVALID_MATCH_STATE &&
                                !detail.summary.status.isFinished &&
                                (detail.summary.status.isLive || detail.summary.status == com.footballai.app.domain.model.MatchStatus.SCHEDULED),
                            onPredict = { viewModel.predict(forcePersist = true) },
                            needsNationalHistory = missingArchive,
                            isImportingHistory = state.isImportingHistory,
                            onImportNationalHistory = viewModel::importNationalHistory,
                            liveTrackingSupported = state.liveTrackingSupported,
                            refreshWarning = state.refreshWarning?.let { errorLabel(it) },
                            onRetryRefresh = viewModel::refresh,
                            predictionWarning = state.predictionWarning?.let { errorLabel(it) },
                            languageTag = languageTag,
                        )
                    }

                    hasLiveTab && tab == 1 -> LiveTab(
                        header = matchHeader,
                        prediction = state.prediction,
                        detail = detail,
                        languageTag = languageTag,
                    )

                    tab == if (hasLiveTab) 2 else 1 -> StatisticsTab(
                        header = matchHeader,
                        statistics = detail.statistics,
                        languageTag = languageTag,
                    )

                    tab == if (hasLiveTab) 3 else 2 -> HeadToHeadTab(
                        header = matchHeader,
                        matches = state.headToHead,
                        languageTag = languageTag,
                    )

                    tab == if (hasLiveTab) 4 else 3 -> LineupsTab(
                        header = matchHeader,
                        lineups = detail.lineups,
                        home = detail.summary.home,
                        away = detail.summary.away,
                        favouritePlayerKeys = state.favouritePlayerKeys,
                        onTogglePlayer = viewModel::toggleFavouritePlayer,
                        languageTag = languageTag,
                    )

                    else -> HeadToHeadTab(
                        header = matchHeader,
                        matches = state.headToHead,
                        languageTag = languageTag,
                    )
                }
                }
        }
    }
}

@Composable
private fun MatchHeader(
    detail: MatchDetail,
    languageTag: String,
    onTeamClick: (String) -> Unit,
    favouriteTeams: Set<String>,
    onToggleFavourite: (String, Boolean) -> Unit,
) {
    val summary = detail.summary
    val colors = FootballAiThemeExtra.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompetitionNameText(
                    name = summary.competitionName.ifBlank { stringResource(R.string.unknown_competition) },
                    competitionId = summary.competitionId,
                    languageTag = languageTag,
                    modifier = Modifier.weight(1f),
                    primaryStyle = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(
                    text = if (summary.status.isLive && summary.minute != null) {
                        "${matchStatusLabel(summary.status)} ${Formatters.elapsedLabel(summary.minute, languageTag)}"
                    } else {
                        matchStatusLabel(summary.status)
                    },
                    color = if (summary.status.isLive) colors.live else MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TeamColumn(
                    team = summary.home,
                    goals = summary.homeGoals.takeIf { summary.status.hasScore },
                    languageTag = languageTag,
                    onClick = onTeamClick,
                    modifier = Modifier.weight(1f),
                    isFavourite = favouriteTeams.contains(NameLocalization.favouriteKey(summary.home.name, summary.home.shortName)),
                    onToggleFavourite = { onToggleFavourite(summary.home.name, it) },
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (summary.status.hasScore) {
                        Text(
                            // A score the source has not published yet is a dash, never 0-0.
                            text = Formatters.scoreOrNull(summary.homeGoals, summary.awayGoals, languageTag) ?: "—",
                            // The score is the one number the eye goes looking for: it gets the
                            // largest size on the screen and fixed-width digits so 2–1 and 0–0
                            // sit on the same axis.
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontFeatureSettings = "tnum",
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Text(
                            text = Formatters.timeOnly(summary.kickoffEpochMs, languageTag),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = Formatters.dateOnly(summary.kickoffEpochMs, languageTag),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TeamColumn(
                    team = summary.away,
                    goals = summary.awayGoals.takeIf { summary.status.hasScore },
                    languageTag = languageTag,
                    onClick = onTeamClick,
                    modifier = Modifier.weight(1f),
                    alignEnd = true,
                    isFavourite = favouriteTeams.contains(NameLocalization.favouriteKey(summary.away.name, summary.away.shortName)),
                    onToggleFavourite = { onToggleFavourite(summary.away.name, it) },
                )
            }
            if (!detail.venue.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = detail.venue,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TeamColumn(
    team: TeamRef,
    goals: Int?,
    languageTag: String,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    isFavourite: Boolean = false,
    onToggleFavourite: (Boolean) -> Unit = {},
) {
    Column(
        modifier = modifier.clickable { onClick(team.id) },
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        TeamBadge(team, size = 40)
        Spacer(Modifier.height(6.dp))
        TeamNameLines(
            team = team,
            languageTag = languageTag,
            primaryStyle = MaterialTheme.typography.titleSmall,
            primaryMaxLines = 2,
        )
        // Follow a team from where the user meets it: on its own match.
        IconButton(
            onClick = { onToggleFavourite(!isFavourite) },
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = if (isFavourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = stringResource(
                    if (isFavourite) R.string.action_unfollow_team else R.string.action_follow_team,
                ),
                tint = if (isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (goals != null) {
            Text(
                text = Formatters.integer(goals, languageTag),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class PredictionGlossary { EXPECTED_GOALS, DATA_COVERAGE }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AnalysisTab(
    header: @Composable () -> Unit,
    prediction: PredictionView?,
    isPredicting: Boolean,
    error: String?,
    isLiveMatch: Boolean,
    isFinished: Boolean,
    homeName: String,
    awayName: String,
    canPredict: Boolean,
    onPredict: () -> Unit,
    needsNationalHistory: Boolean,
    isImportingHistory: Boolean,
    onImportNationalHistory: () -> Unit,
    liveTrackingSupported: Boolean,
    refreshWarning: String?,
    onRetryRefresh: () -> Unit,
    predictionWarning: String?,
    languageTag: String,
) {
    var showAdvanced by rememberSaveable(prediction?.id) { mutableStateOf(false) }
    var glossary by remember { mutableStateOf<PredictionGlossary?>(null) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { header() }
        if (refreshWarning != null) {
            // A failed refresh is not an empty screen. The stored analysis below stays.
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        text = stringResource(R.string.refresh_failed_keep_stored),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = refreshWarning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetryRefresh) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }
        if (isLiveMatch) {
            // The switch that used to sit here said "live tracking is off" and did not say
            // what it tracked. The app now does the useful thing by itself and says so;
            // a control that has to be explained twice is not a control.
            item {
                Text(
                    text = stringResource(
                        if (liveTrackingSupported) R.string.live_auto_refresh_note
                        else R.string.live_tracking_unsupported_source,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        if (predictionWarning != null && prediction != null) {
            // The forecast on screen is the last one the app could compute. Saying so is
            // better than pretending it is current, and far better than deleting it.
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        text = stringResource(R.string.prediction_not_updated),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = predictionWarning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // A finished game with no stored forecast is an expected empty state, not
        // a retryable calculation error. Never offer an action known to be forbidden.
        if (prediction == null && isFinished) {
            item { EmptyState(
                title = stringResource(R.string.finished_no_prediction_title),
                message = stringResource(R.string.finished_no_prediction_message),
            ) }
            return@LazyColumn
        }
        if (error != null) {
            item {
                if (canPredict) ErrorState(message = error, onRetry = onPredict)
                else EmptyState(title = stringResource(R.string.analysis_unavailable), message = error)
            }
            if (needsNationalHistory) {
                item {
                    NationalHistoryCard(
                        isImporting = isImportingHistory,
                        onImport = onImportNationalHistory,
                    )
                }
            }
            if (prediction == null) return@LazyColumn
        }
        if (prediction == null) {
            item {
                if (isPredicting) LoadingState()
                else EmptyState(
                    title = stringResource(if (canPredict) R.string.no_prediction_title else R.string.analysis_unavailable),
                    message = stringResource(if (canPredict) R.string.no_prediction_message else R.string.error_match_state),
                    actionLabel = if (canPredict) stringResource(R.string.action_analyze) else null,
                    onAction = if (canPredict) onPredict else null,
                )
            }
            return@LazyColumn
        }

        item {
            PredictionVerdictCard(
                prediction = prediction,
                homeTeamName = homeName,
                awayTeamName = awayName,
                languageTag = languageTag,
            )
        }

        item {
            WhyThisNumberCard(
                prediction = prediction,
                homeTeamName = homeName,
                awayTeamName = awayName,
                languageTag = languageTag,
            )
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.prediction_result),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Box(modifier = Modifier.clickable { glossary = PredictionGlossary.DATA_COVERAGE }) {
                        QualityBadge(
                            level = prediction.quality.level,
                            completeness = prediction.quality.completeness,
                            languageTag = languageTag,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                ProbabilityBar(
                    homeWin = prediction.homeWin,
                    draw = prediction.draw,
                    awayWin = prediction.awayWin,
                    languageTag = languageTag,
                    homeLabel = homeName,
                    awayLabel = awayName,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ExpectedGoals(label = stringResource(R.string.expected_home_goals), value = prediction.expectedHomeGoals, languageTag = languageTag)
                    ExpectedGoals(label = stringResource(R.string.expected_away_goals), value = prediction.expectedAwayGoals, languageTag = languageTag)
                    ExpectedGoals(label = stringResource(R.string.expected_total_goals), value = prediction.expectedHomeGoals + prediction.expectedAwayGoals, languageTag = languageTag)
                }
                TextButton(onClick = { glossary = PredictionGlossary.EXPECTED_GOALS }) {
                    Text(stringResource(R.string.expected_goals_help))
                }
                val liveSnapshot = prediction.live
                if (liveSnapshot != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.live_remaining_goals,
                            Formatters.decimal(liveSnapshot.remainingHomeExpectedGoals, languageTag),
                            Formatters.decimal(liveSnapshot.remainingAwayExpectedGoals, languageTag),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            EvidenceSection(prediction = prediction, languageTag = languageTag)
        }

        if (prediction.topScores.isNotEmpty()) {
            item {
                SectionHeader(title = stringResource(R.string.section_likely_scores))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    prediction.topScores.take(4).forEachIndexed { index, score ->
                        ScoreChip(score = score, languageTag = languageTag, highlight = index == 0)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(stringResource(if (showAdvanced) R.string.hide_advanced_analysis else R.string.show_advanced_analysis))
                }
            }
        }

        if (showAdvanced) {
        item { MarketSection(prediction = prediction, languageTag = languageTag) }

        item {
            SectionHeader(
                title = stringResource(R.string.section_explanation),
                subtitle = stringResource(R.string.section_explanation_subtitle),
            )
        }
        items(prediction.explanations.take(6)) { explanation ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = explanationLabel(explanation.code),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (prediction.quality.missing.isNotEmpty() || prediction.quality.notes.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader(title = stringResource(R.string.section_data_quality))
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.missing_data_title),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        prediction.quality.missing.take(6).forEach { item ->
                            StatusChip(text = missingDataLabel(item), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    prediction.quality.notes.take(4).forEach { note ->
                        Text(
                            text = "• " + qualityNoteLabel(note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            InfoCard(
                title = stringResource(R.string.model_info_title),
                body = stringResource(
                    R.string.model_info_body,
                    prediction.modelName,
                    Formatters.integer(prediction.fittedMatches, languageTag),
                    Formatters.decimal(prediction.rho, languageTag, 3),
                    Formatters.kickoffDateTime(prediction.createdAtEpochMs, languageTag),
                ),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        }
        item {
            Spacer(Modifier.height(8.dp))
            InfoCard(
                title = stringResource(R.string.disclaimer_title),
                body = stringResource(R.string.disclaimer_body),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        if (!isFinished) {
            item {
                TextButton(
                    onClick = onPredict,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.action_recalculate))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    glossary?.let { selected ->
        AlertDialog(
            onDismissRequest = { glossary = null },
            confirmButton = {
                TextButton(onClick = { glossary = null }) { Text(stringResource(R.string.action_close)) }
            },
            title = {
                Text(stringResource(if (selected == PredictionGlossary.EXPECTED_GOALS) R.string.expected_goals_help_title else R.string.section_data_quality))
            },
            text = {
                Text(stringResource(if (selected == PredictionGlossary.EXPECTED_GOALS) R.string.expected_goals_help_body else R.string.data_coverage_help_body))
            },
        )
    }
}

/** The model's top pick, how far ahead it is, and the evidence standing behind it. */
private enum class VerdictOutcome { HOME, DRAW, AWAY }

private data class MatchVerdict(
    val outcome: VerdictOutcome,
    val probability: Double,
    /**
     * How far the top outcome is ahead of the runner-up.
     *
     * This is a description of the distribution, not evidence: a 12-point gap from a model
     * with no history is worth less than a 4-point gap from a calibrated one. It is shown
     * as a number and never turned into a confidence label.
     */
    val separation: Double,
)

private fun computeVerdict(prediction: PredictionView): MatchVerdict {
    val ranked = listOf(
        VerdictOutcome.HOME to prediction.homeWin,
        VerdictOutcome.DRAW to prediction.draw,
        VerdictOutcome.AWAY to prediction.awayWin,
    ).sortedByDescending { it.second }
    val top = ranked[0]
    return MatchVerdict(
        outcome = top.first,
        probability = top.second,
        separation = (top.second - ranked[1].second).coerceAtLeast(0.0),
    )
}

/** Headline model pick above the complete 1X2 probability breakdown. */
@Composable
private fun PredictionVerdictCard(
    prediction: PredictionView,
    homeTeamName: String,
    awayTeamName: String,
    languageTag: String,
) {
    val colors = FootballAiThemeExtra.colors
    val verdict = remember(prediction) { computeVerdict(prediction) }
    val outcomeColor = when (verdict.outcome) {
        VerdictOutcome.HOME -> colors.homeSeries
        VerdictOutcome.DRAW -> colors.drawSeries
        VerdictOutcome.AWAY -> colors.awaySeries
    }
    val outcomeLabel = when (verdict.outcome) {
        VerdictOutcome.HOME -> stringResource(R.string.verdict_home_win, homeTeamName)
        VerdictOutcome.DRAW -> stringResource(R.string.verdict_draw)
        VerdictOutcome.AWAY -> stringResource(R.string.verdict_away_win, awayTeamName)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = outcomeColor.copy(alpha = 0.12f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.prediction_verdict_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = outcomeLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = outcomeColor,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Formatters.percent(verdict.probability, languageTag),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = outcomeColor,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The chip reports the evidence recorded with the forecast, never the
                // margin: a wide gap from a model with no history is not strong evidence.
                ConfidenceChip(level = prediction.confidence?.level)
                Text(
                    text = stringResource(
                        R.string.verdict_separation,
                        Formatters.percent(verdict.separation, languageTag),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.verdict_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Plain-language answer to "why this number?".
 *
 * The card above says 75%. This one says where the 75% came from in the fewest possible
 * words: how many goals each side is expected to score, the two strongest reasons the
 * model moved away from an even game, and one line about what it cannot know.
 */
@Composable
private fun WhyThisNumberCard(
    prediction: PredictionView,
    homeTeamName: String,
    awayTeamName: String,
    languageTag: String,
) {
    val reasons = remember(prediction) { plainReasons(prediction) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.why_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Bullet(
            text = stringResource(
                R.string.why_expected_goals,
                Formatters.decimal(prediction.expectedHomeGoals, languageTag, 1),
                Formatters.decimal(prediction.expectedAwayGoals, languageTag, 1),
            ),
        )
        reasons.take(2).forEach { reason ->
            Bullet(
                text = when (reason) {
                    PlainReason.MARKET -> stringResource(R.string.why_market)
                    PlainReason.HOME_ADVANTAGE -> stringResource(R.string.why_home_advantage)
                    PlainReason.ELO_HOME -> stringResource(R.string.why_elo_home, homeTeamName)
                    PlainReason.ELO_AWAY -> stringResource(R.string.why_elo_away, awayTeamName)
                    PlainReason.FORM -> stringResource(R.string.why_form)
                    PlainReason.VENUE -> stringResource(R.string.why_venue)
                    PlainReason.CONGESTION -> stringResource(R.string.why_congestion)
                    PlainReason.HOME_ATTACK -> stringResource(R.string.why_home_attack)
                    PlainReason.AWAY_ATTACK -> stringResource(R.string.why_away_attack)
                    PlainReason.HOME_DEFENCE -> stringResource(R.string.why_home_defence)
                    PlainReason.AWAY_DEFENCE -> stringResource(R.string.why_away_defence)
                    PlainReason.LINEUP_HOME -> stringResource(R.string.why_lineup_home)
                    PlainReason.LINEUP_AWAY -> stringResource(R.string.why_lineup_away)
                    PlainReason.CALIBRATION -> stringResource(R.string.why_calibration)
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.why_limits),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = "• ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = text, style = MaterialTheme.typography.bodySmall)
    }
}

/** The engine's reasons, in the order a reader cares about them. */
private enum class PlainReason {
    MARKET, HOME_ADVANTAGE, ELO_HOME, ELO_AWAY, FORM, VENUE, CONGESTION,
    HOME_ATTACK, AWAY_ATTACK, HOME_DEFENCE, AWAY_DEFENCE,
    LINEUP_HOME, LINEUP_AWAY, CALIBRATION,
}

private fun plainReasons(prediction: PredictionView): List<PlainReason> {
    val mapped = prediction.explanations.mapNotNull { explanation ->
        when (explanation.code) {
            "MARKET_ALIGNED" -> PlainReason.MARKET
            "HOME_ADVANTAGE" -> PlainReason.HOME_ADVANTAGE
            "ELO_EDGE_HOME" -> PlainReason.ELO_HOME
            "ELO_EDGE_AWAY" -> PlainReason.ELO_AWAY
            "RECENT_FORM" -> PlainReason.FORM
            "VENUE_FORM" -> PlainReason.VENUE
            "FIXTURE_CONGESTION" -> PlainReason.CONGESTION
            "STRONG_HOME_ATTACK", "WEAK_AWAY_ATTACK" -> PlainReason.HOME_ATTACK
            "STRONG_AWAY_ATTACK", "WEAK_HOME_ATTACK" -> PlainReason.AWAY_ATTACK
            "SOLID_HOME_DEFENCE", "LEAKY_AWAY_DEFENCE" -> PlainReason.HOME_DEFENCE
            "SOLID_AWAY_DEFENCE", "LEAKY_HOME_DEFENCE" -> PlainReason.AWAY_DEFENCE
            "LINEUP_ABSENCE_HOME" -> PlainReason.LINEUP_HOME
            "LINEUP_ABSENCE_AWAY" -> PlainReason.LINEUP_AWAY
            "CALIBRATION_ADJUSTMENT" -> PlainReason.CALIBRATION
            else -> null
        }
    }
    return mapped.distinct()
}

/**
 * Evidence, not a second probability.
 *
 * Shows the recorded confidence level, the calibration that was actually applied and
 * every machine-readable gap, so "no data" is visible instead of silently becoming a
 * confident-looking number. Nothing here is recomputed on the UI side.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EvidenceSection(prediction: PredictionView, languageTag: String) {
    val confidence = prediction.confidence
    val calibration = prediction.calibration
    // The chip and two short lines answer the question the heading asks. Everything that
    // explains the method itself — gaps, calibration, the 90-minute rule — waits behind
    // one button: a reader who wants it asks for it, and a reader who does not is no
    // longer scrolling through five paragraphs to reach the rest of the page.
    var detailsOpen by rememberSaveable(prediction.id) { mutableStateOf(false) }
    Spacer(Modifier.height(12.dp))
    SectionHeader(title = stringResource(R.string.evidence_title))
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        ConfidenceChip(level = confidence?.level)
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.confidence_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (confidence != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = localizedString(R.string.evidence_sample, confidence.effectiveSample),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = confidence.freshnessMinutes?.let {
                    localizedString(R.string.evidence_freshness, it)
                } ?: stringResource(R.string.evidence_freshness_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { detailsOpen = !detailsOpen }) {
            Text(
                stringResource(
                    if (detailsOpen) R.string.evidence_details_hide else R.string.evidence_details_show,
                ),
            )
        }
        if (!detailsOpen) return@Column
        if (confidence != null) {
            if (confidence.gaps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.evidence_gaps),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    confidence.gaps.take(8).forEach { gap ->
                        StatusChip(text = evidenceGapLabel(gap), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.calibration_title),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (calibration == null || calibration.method == "NONE") {
                stringResource(R.string.calibration_none)
            } else {
                localizedString(R.string.calibration_fitted, calibration.sampleCount)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (calibration != null && calibration.method != "NONE") {
            Text(
                text = stringResource(R.string.calibration_method, calibration.method),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!calibration.logLossBefore.isNaN() && !calibration.logLossAfter.isNaN()) {
                Text(
                    text = stringResource(
                        R.string.calibration_holdout,
                        Formatters.decimal(calibration.logLossBefore, languageTag, 3),
                        Formatters.decimal(calibration.logLossAfter, languageTag, 3),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.calibration_ninety_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MarketSection(prediction: PredictionView, languageTag: String) {
    val groups = prediction.markets.groupBy { it.type }
    Column {
        groups.forEach { (type, rows) ->
            if (type == MarketType.ONE_X_TWO) return@forEach
            if (type == MarketType.EXACT_SCORE) return@forEach
            SectionHeader(title = groupTitle(type))
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                rows.sortedByDescending { it.probability }.take(6).forEach { row ->
                    MarketBarRow(
                        label = marketSelectionLabel(type, row.selectionKey, row.line),
                        probability = row.probability,
                        languageTag = languageTag,
                        highlight = row.probability >= 0.5,
                    )
                }
            }
        }
    }
}

@Composable
private fun groupTitle(type: MarketType): String = when (type) {
    MarketType.ONE_X_TWO -> stringResource(R.string.market_1x2)
    MarketType.DOUBLE_CHANCE -> stringResource(R.string.market_double_chance)
    MarketType.OVER_UNDER -> stringResource(R.string.market_over_under)
    MarketType.BOTH_TEAMS_TO_SCORE -> stringResource(R.string.market_btts)
    MarketType.EXACT_SCORE -> stringResource(R.string.market_exact_score)
    MarketType.FIRST_HALF_RESULT -> stringResource(R.string.market_first_half_result)
    MarketType.FIRST_HALF_OVER -> stringResource(R.string.market_first_half_over)
    MarketType.NEXT_GOAL -> stringResource(R.string.market_next_goal)
}

@Composable
private fun ExpectedGoals(label: String, value: Double, languageTag: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = Formatters.decimal(value, languageTag, 2),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveTab(
    header: @Composable () -> Unit,
    prediction: PredictionView?,
    detail: MatchDetail,
    languageTag: String,
) {
    val live = prediction?.live
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { header() }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.live_current_score),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = Formatters.scoreOrNull(
                            detail.summary.homeGoals,
                            detail.summary.awayGoals,
                            languageTag,
                        ) ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.live_minute),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = detail.summary.minute?.let { Formatters.elapsedLabel(it, languageTag) } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (live == null) {
            item {
                EmptyState(
                    title = stringResource(R.string.no_live_prediction_title),
                    message = stringResource(R.string.no_live_prediction_message),
                )
            }
        } else {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = localizedString(R.string.live_remaining_minutes, live.remainingMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ProbabilityBar(
                        homeWin = prediction?.homeWin ?: 0.0,
                        draw = prediction?.draw ?: 0.0,
                        awayWin = prediction?.awayWin ?: 0.0,
                        languageTag = languageTag,
                    )
                    Spacer(Modifier.height(12.dp))
                    SectionHeader(title = stringResource(R.string.section_next_goal))
                    MarketBarRow(stringResource(R.string.selection_next_goal_home), live.nextGoalHome, languageTag)
                    MarketBarRow(stringResource(R.string.selection_next_goal_away), live.nextGoalAway, languageTag)
                    MarketBarRow(stringResource(R.string.selection_next_goal_none), live.noMoreGoals, languageTag)
                    Spacer(Modifier.height(8.dp))
                    SectionHeader(title = stringResource(R.string.section_remaining_goals))
                    live.remainingOver.forEach { (line, probability) ->
                        MarketBarRow(
                            label = stringResource(R.string.remaining_over_line, Formatters.decimal(line, languageTag, 1)),
                            probability = probability,
                            languageTag = languageTag,
                        )
                    }
                }
            }
            item {
                InfoCard(
                    title = stringResource(R.string.live_method_title),
                    body = stringResource(R.string.live_method_body),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StatisticsTab(
    header: @Composable () -> Unit,
    statistics: List<MatchStatisticPair>,
    languageTag: String,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { header() }
        if (statistics.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.empty_statistics_title),
                    message = stringResource(R.string.empty_statistics_message),
                )
            }
            return@LazyColumn
        }
        items(statistics) { pair ->
            StatComparisonRow(
                label = statisticLabel(pair.type),
                homeValue = pair.home,
                awayValue = pair.away,
                languageTag = languageTag,
                isPercentage = pair.type == StatisticType.POSSESSION,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun statisticLabel(type: StatisticType): String = when (type) {
    StatisticType.POSSESSION -> stringResource(R.string.stat_possession)
    StatisticType.SHOTS -> stringResource(R.string.stat_shots)
    StatisticType.SHOTS_ON_TARGET -> stringResource(R.string.stat_shots_on_target)
    StatisticType.CORNERS -> stringResource(R.string.stat_corners)
    StatisticType.YELLOW_CARDS -> stringResource(R.string.stat_yellow_cards)
    StatisticType.RED_CARDS -> stringResource(R.string.stat_red_cards)
    StatisticType.EXPECTED_GOALS -> stringResource(R.string.stat_expected_goals)
    StatisticType.FOULS -> stringResource(R.string.stat_fouls)
    StatisticType.OFFSIDES -> stringResource(R.string.stat_offsides)
    StatisticType.SAVES -> stringResource(R.string.stat_saves)
}

@Composable
private fun HeadToHeadTab(
    header: @Composable () -> Unit,
    matches: List<com.footballai.app.domain.model.MatchSummary>,
    languageTag: String,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { header() }
        if (matches.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.empty_h2h_title),
                    message = stringResource(R.string.empty_h2h_message),
                )
            }
            return@LazyColumn
        }
        items(matches) { match ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = Formatters.dateOnly(match.kickoffEpochMs, languageTag),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = NameLocalization.displayName(match.home.name, languageTag, match.home.shortName),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = Formatters.scoreOrNull(match.homeGoals, match.awayGoals, languageTag) ?: "—",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = NameLocalization.displayName(match.away.name, languageTag, match.away.shortName),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * The one action that can make a national-team forecast possible.
 *
 * A club league has its results in the CSV archive; national teams do not, and no feed
 * in this app publishes them. The archive used here is CC0, updated continuously and
 * about the size of a photo, so downloading it is a reasonable one-tap request — and the
 * card says what it is instead of pretending the button does something vague.
 */
@Composable
private fun NationalHistoryCard(
    isImporting: Boolean,
    onImport: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.national_history_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.national_history_body),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            if (isImporting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.national_history_running),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            } else {
                Button(onClick = onImport) {
                    Text(stringResource(R.string.national_history_action))
                }
            }
        }
    }
}

/**
 * The two squads, with a star on every player.
 *
 * Following a player is the one action this screen adds to the data the source gave: the
 * star is keyed by the club plus the normalised name, so the same player stays followed
 * when another source spells him differently, and two players who share a name under
 * different clubs stay two people.
 */
@Composable
private fun LineupsTab(
    header: @Composable () -> Unit,
    lineups: List<TeamLineup>,
    home: TeamRef,
    away: TeamRef,
    favouritePlayerKeys: Set<String>,
    onTogglePlayer: (String, String, Boolean) -> Unit,
    languageTag: String,
) {
    if (lineups.isEmpty()) {
        // Honest empty state: the source said lineups exist, this one has not arrived yet.
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { header() }
            item {
                Text(
                    text = stringResource(R.string.note_lineups_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { header() }
        // Home first, then away: the order the match was read in.
        lineups.sortedBy { lineup ->
            when (lineup.teamId) {
                home.id -> 0
                away.id -> 1
                else -> 2
            }
        }.forEach { lineup ->
            val team = if (lineup.teamId == away.id) away else home
            val teamName = NameLocalization.displayName(team.name, languageTag, team.shortName)
            item {
                LineupTeamHeader(
                    teamName = teamName,
                    formation = lineup.formation,
                    starters = lineup.players.count { it.isStarter },
                )
            }
            val starters = lineup.players.filter { it.isStarter }
            val bench = lineup.players.filterNot { it.isStarter }
            if (starters.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.lineup_starters),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
                    )
                }
                items(starters, key = { lineup.teamId + "|s|" + it.name }) { player ->
                    LineupPlayerRow(
                        teamId = lineup.teamId,
                        name = player.name,
                        number = player.number,
                        position = player.position,
                        isFavourite = favouritePlayerKeys.contains(
                            NameLocalization.playerFavouriteKey(lineup.teamId, player.name),
                        ),
                        onTogglePlayer = onTogglePlayer,
                    )
                }
            }
            if (bench.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.lineup_bench),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                    )
                }
                items(bench, key = { lineup.teamId + "|b|" + it.name }) { player ->
                    LineupPlayerRow(
                        teamId = lineup.teamId,
                        name = player.name,
                        number = player.number,
                        position = player.position,
                        isFavourite = favouritePlayerKeys.contains(
                            NameLocalization.playerFavouriteKey(lineup.teamId, player.name),
                        ),
                        onTogglePlayer = onTogglePlayer,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LineupTeamHeader(teamName: String, formation: String?, starters: Int) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Text(text = teamName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            text = listOfNotNull(
                formation?.takeIf { it.isNotBlank() },
                if (starters > 0) Formatters.integer(starters, LocalLanguageTag.current) + " " + stringResource(R.string.lineup_starters) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LineupPlayerRow(
    teamId: String,
    name: String,
    number: Int?,
    position: String?,
    isFavourite: Boolean,
    onTogglePlayer: (String, String, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number?.toString() ?: "–",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            position?.takeIf { it.isNotBlank() }?.let { position ->
                Text(
                    text = position,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = { onTogglePlayer(teamId, name, !isFavourite) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = if (isFavourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = stringResource(
                    if (isFavourite) R.string.action_unfollow_player else R.string.action_follow_player,
                ),
                tint = if (isFavourite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
