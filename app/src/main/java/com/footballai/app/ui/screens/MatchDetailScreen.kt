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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.remember
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
import com.footballai.app.domain.model.MatchStatisticPair
import com.footballai.app.domain.model.PredictionView
import com.footballai.app.domain.model.StatisticType
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.ErrorState
import com.footballai.app.ui.components.InfoCard
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.MarketBarRow
import com.footballai.app.ui.components.ProbabilityBar
import com.footballai.app.ui.components.QualityBadge
import com.footballai.app.ui.components.ScoreChip
import com.footballai.app.ui.components.SectionHeader
import com.footballai.app.ui.components.StatComparisonRow
import com.footballai.app.ui.components.StatusChip
import com.footballai.app.ui.components.TeamMonogram
import com.footballai.app.ui.errorLabel
import com.footballai.app.ui.explanationLabel
import com.footballai.app.ui.marketSelectionLabel
import com.footballai.app.ui.missingDataLabel
import com.footballai.app.ui.matchStatusLabel
import com.footballai.app.ui.qualityNoteLabel
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.viewmodel.MatchDetailViewModel
import kotlin.math.roundToInt

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
    var selectedTab by remember { mutableIntStateOf(0) }

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
    val tabs = buildList {
        add(stringResource(R.string.tab_analysis))
        if (detail?.summary?.status?.isLive == true) add(stringResource(R.string.tab_live))
        add(stringResource(R.string.tab_statistics))
        add(stringResource(R.string.tab_head_to_head))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    Text(
                        text = detail?.summary?.let { "${it.home.name} - ${it.away.name}" }
                            ?: stringResource(R.string.title_match),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_sync))
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

            MatchHeader(detail = detail, languageTag = languageTag, onTeamClick = onTeamClick)

            TabRow(selectedTabIndex = selectedTab.coerceAtMost(tabs.lastIndex)) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }

            val hasLiveTab = detail.summary.status.isLive
            when {
                selectedTab == 0 -> AnalysisTab(
                    prediction = state.prediction,
                    isPredicting = state.isPredicting,
                    error = state.lastError?.let { errorLabel(it) },
                    isLiveMatch = detail.summary.status.isLive,
                    onPredict = { viewModel.predict(forcePersist = true) },
                    liveTrackingEnabled = state.liveTrackingEnabled,
                    isLiveTracking = state.isLiveTracking,
                    onToggleTracking = viewModel::setLiveTrackingEnabled,
                    languageTag = languageTag,
                )

                hasLiveTab && selectedTab == 1 -> LiveTab(
                    prediction = state.prediction,
                    detail = detail,
                    languageTag = languageTag,
                )

                selectedTab == if (hasLiveTab) 2 else 1 -> StatisticsTab(
                    statistics = detail.statistics,
                    languageTag = languageTag,
                )

                else -> HeadToHeadTab(
                    matches = state.headToHead,
                    languageTag = languageTag,
                )
            }
        }
    }
}

@Composable
private fun MatchHeader(detail: MatchDetail, languageTag: String, onTeamClick: (String) -> Unit) {
    val summary = detail.summary
    val colors = FootballAiThemeExtra.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = summary.competitionName.ifBlank { stringResource(R.string.unknown_competition) },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    name = summary.home.name,
                    id = summary.home.id,
                    goals = summary.homeGoals.takeIf { summary.status.hasScore },
                    languageTag = languageTag,
                    onClick = onTeamClick,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (summary.status.hasScore) {
                        Text(
                            text = Formatters.localizeDigits(
                                "${summary.homeGoals ?: 0} - ${summary.awayGoals ?: 0}",
                                languageTag,
                            ),
                            style = MaterialTheme.typography.headlineMedium,
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
                    name = summary.away.name,
                    id = summary.away.id,
                    goals = summary.awayGoals.takeIf { summary.status.hasScore },
                    languageTag = languageTag,
                    onClick = onTeamClick,
                    modifier = Modifier.weight(1f),
                    alignEnd = true,
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
    name: String,
    id: String,
    goals: Int?,
    languageTag: String,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
) {
    Column(
        modifier = modifier.clickable { onClick(id) },
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        TeamMonogram(name, size = 40)
        Spacer(Modifier.height(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
        if (goals != null) {
            Text(
                text = Formatters.integer(goals, languageTag),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AnalysisTab(
    prediction: PredictionView?,
    isPredicting: Boolean,
    error: String?,
    isLiveMatch: Boolean,
    onPredict: () -> Unit,
    liveTrackingEnabled: Boolean,
    isLiveTracking: Boolean,
    onToggleTracking: (Boolean) -> Unit,
    languageTag: String,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (isLiveMatch) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = FootballAiThemeExtra.colors.live,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                if (isLiveTracking) R.string.live_tracking_active else R.string.live_tracking_idle,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Switch(checked = liveTrackingEnabled, onCheckedChange = onToggleTracking)
                }
            }
        }

        if (error != null) {
            item { ErrorState(message = error, onRetry = onPredict) }
        }

        if (isPredicting && prediction == null) {
            item { LoadingState() }
        }

        if (prediction == null) {
            item {
                EmptyState(
                    title = stringResource(R.string.no_prediction_title),
                    message = stringResource(R.string.no_prediction_message),
                    actionLabel = stringResource(R.string.action_analyze),
                    onAction = onPredict,
                )
            }
            return@LazyColumn
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
                    QualityBadge(
                        level = prediction.quality.level,
                        completeness = prediction.quality.completeness,
                        languageTag = languageTag,
                    )
                }
                Spacer(Modifier.height(10.dp))
                ProbabilityBar(
                    homeWin = prediction.homeWin,
                    draw = prediction.draw,
                    awayWin = prediction.awayWin,
                    languageTag = languageTag,
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
        item {
            Spacer(Modifier.height(8.dp))
            InfoCard(
                title = stringResource(R.string.disclaimer_title),
                body = stringResource(R.string.disclaimer_body),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        item {
            TextButton(
                onClick = onPredict,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.action_recalculate))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
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
private fun LiveTab(prediction: PredictionView?, detail: MatchDetail, languageTag: String) {
    val live = prediction?.live
    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                        text = Formatters.localizeDigits("${detail.summary.homeGoals ?: 0} - ${detail.summary.awayGoals ?: 0}", languageTag),
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
                        text = stringResource(R.string.live_remaining_minutes, live.remainingMinutes),
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
private fun StatisticsTab(statistics: List<MatchStatisticPair>, languageTag: String) {
    if (statistics.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.empty_statistics_title),
            message = stringResource(R.string.empty_statistics_message),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
    matches: List<com.footballai.app.domain.model.MatchSummary>,
    languageTag: String,
) {
    if (matches.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.empty_h2h_title),
            message = stringResource(R.string.empty_h2h_message),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(matches) { match ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                        Text(text = match.home.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = Formatters.localizeDigits("${match.homeGoals ?: 0} - ${match.awayGoals ?: 0}", languageTag),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(text = match.away.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
