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
import com.footballai.app.domain.model.MatchStatisticPair
import com.footballai.app.domain.model.PredictionView
import com.footballai.app.domain.model.StatisticType
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.TeamRef
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
                        text = detail?.summary?.competitionName?.takeIf { it.isNotBlank() }
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
            // The download can only help when the archive itself is missing; an unknown
            // team name or a women's international would be unaffected by it.
            val missingArchive = state.lastError?.let { error ->
                error.code == com.footballai.app.core.ErrorCode.NO_NATIONAL_HISTORY && error.detail == null
            } == true
            when {
                selectedTab == 0 -> SwipeRefresh(
                    isRefreshing = state.isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AnalysisTab(
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
                        canPredict = state.lastError?.code != com.footballai.app.core.ErrorCode.INVALID_MATCH_STATE &&
                            !detail.summary.status.isFinished &&
                            (detail.summary.status.isLive || detail.summary.status == com.footballai.app.domain.model.MatchStatus.SCHEDULED),
                        onPredict = { viewModel.predict(forcePersist = true) },
                        needsNationalHistory = missingArchive,
                        isImportingHistory = state.isImportingHistory,
                        onImportNationalHistory = viewModel::importNationalHistory,
                        liveTrackingEnabled = state.liveTrackingEnabled,
                        isLiveTracking = state.isLiveTracking,
                        onToggleTracking = viewModel::setLiveTrackingEnabled,
                        languageTag = languageTag,
                    )
                }

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
                    team = summary.home,
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
                    team = summary.away,
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
    team: TeamRef,
    goals: Int?,
    languageTag: String,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
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
    liveTrackingEnabled: Boolean,
    isLiveTracking: Boolean,
    onToggleTracking: (Boolean) -> Unit,
    languageTag: String,
) {
    var showAdvanced by rememberSaveable(prediction?.id) { mutableStateOf(false) }
    var glossary by remember { mutableStateOf<PredictionGlossary?>(null) }
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

/** How clearly the model's top pick separates from the runner-up outcome. */
private enum class VerdictConfidence { HIGH, MEDIUM, LOW }
private enum class VerdictOutcome { HOME, DRAW, AWAY }

private data class MatchVerdict(
    val outcome: VerdictOutcome,
    val probability: Double,
    val confidence: VerdictConfidence,
)

private fun computeVerdict(prediction: PredictionView): MatchVerdict {
    val ranked = listOf(
        VerdictOutcome.HOME to prediction.homeWin,
        VerdictOutcome.DRAW to prediction.draw,
        VerdictOutcome.AWAY to prediction.awayWin,
    ).sortedByDescending { it.second }
    val top = ranked[0]
    val margin = top.second - ranked[1].second
    val confidence = when {
        margin >= 0.20 -> VerdictConfidence.HIGH
        margin >= 0.08 -> VerdictConfidence.MEDIUM
        else -> VerdictConfidence.LOW
    }
    return MatchVerdict(outcome = top.first, probability = top.second, confidence = confidence)
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
    val confidenceLabel = stringResource(
        when (verdict.confidence) {
            VerdictConfidence.HIGH -> R.string.confidence_high
            VerdictConfidence.MEDIUM -> R.string.confidence_medium
            VerdictConfidence.LOW -> R.string.confidence_low
        },
    )

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
            StatusChip(text = confidenceLabel, color = outcomeColor, filled = true)
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
    Spacer(Modifier.height(12.dp))
    SectionHeader(title = stringResource(R.string.evidence_title))
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConfidenceChip(level = confidence?.level)
            if (confidence != null && !confidence.uncertainty.isNaN()) {
                Text(
                    text = stringResource(
                        R.string.evidence_uncertainty,
                        Formatters.decimal(confidence.uncertainty, languageTag, 2),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.confidence_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (confidence != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.evidence_sample, confidence.effectiveSample),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = confidence.freshnessMinutes?.let {
                    stringResource(R.string.evidence_freshness, it)
                } ?: stringResource(R.string.evidence_freshness_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                stringResource(R.string.calibration_fitted, calibration.sampleCount)
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
                        Text(
                            text = NameLocalization.displayName(match.home.name, languageTag, match.home.shortName),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = Formatters.localizeDigits("${match.homeGoals ?: 0} - ${match.awayGoals ?: 0}", languageTag),
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
