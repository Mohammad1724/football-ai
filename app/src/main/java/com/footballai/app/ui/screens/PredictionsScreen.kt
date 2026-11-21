@file:OptIn(ExperimentalFoundationApi::class)

package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.domain.model.PredictionWithMatch
import com.footballai.app.domain.model.EvaluationBlock
import com.footballai.app.domain.model.evaluationBlock
import com.footballai.app.data.repository.PredictionRepository
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.localizedString
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.InfoCard
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.ScrollableRefreshContent
import com.footballai.app.ui.components.SwipeRefresh
import com.footballai.app.ui.components.SyncStatusStrip
import com.footballai.app.ui.components.ProbabilityBar
import com.footballai.app.ui.components.StatusChip
import com.footballai.app.ui.components.TeamBadge
import com.footballai.app.ui.components.TeamNameLines
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.viewmodel.PredictionListFilter
import com.footballai.app.ui.viewmodel.PredictionGroup
import com.footballai.app.ui.viewmodel.PredictionRecordStatus
import com.footballai.app.ui.viewmodel.predictionRecordStatus
import com.footballai.app.ui.viewmodel.isLatePreMatch
import com.footballai.app.ui.viewmodel.includes
import com.footballai.app.ui.viewmodel.PredictionsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionsScreen(onMatchClick: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: PredictionsViewModel = viewModel(
        factory = viewModelFactory { initializer { PredictionsViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current
    // While the pull gesture shows its own indicator the app bar stays a plain icon: one
    // refresh should not light up two spinners.
    var pullActive by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.title_predictions)) },
                actions = {
                    // The same action as the pull gesture: sync, then grade what finished.
                    // Pressing refresh and having nothing happen was the confusing part.
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !state.refreshing && !state.resolving,
                    ) {
                        if ((state.refreshing || state.resolving) && !pullActive) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SyncStatusStrip(
                isSyncing = state.refreshing,
                isAuto = state.autoSyncing,
                progressDone = state.syncDone,
                progressTotal = state.syncTotal,
                lastSyncEpochMs = state.lastSyncEpochMs,
                nowEpochMs = state.nowEpochMs,
                added = state.lastSyncedAdded.takeIf { it > 0 },
                error = state.lastError,
                isOnline = state.isOnline,
                languageTag = languageTag,
            )
            if (state.isLoading) {
                LoadingState()
                return@Column
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(text = stringResource(R.string.prediction_history_loaded,
                    Formatters.integer(state.loadedCount, languageTag), Formatters.integer(state.storedCount, languageTag)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                if (state.loadedLimit < state.storedCount) {
                    TextButton(onClick = viewModel::loadMore, enabled = !state.loadingMore) {
                        Text(stringResource(if (state.loadingMore) R.string.prediction_loading_more else R.string.prediction_load_more))
                    }
                }
            }
            PredictionFilterRow(state.filter, state.upcomingCount, state.liveCount, state.waitingCount,
                state.resolvedCount, viewModel::setFilter)
            SwipeRefresh(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                onPullStateChange = { pullActive = it },
                enabled = state.isOnline,
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 36.dp)) {
                    state.dailyReport?.let { report ->
                        item(key = "report") { CollapsibleDailyReport(report, languageTag) }
                    }
                    item(key = "scope") {
                        Text(stringResource(R.string.prediction_history_scope),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                    if (state.items.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(title = stringResource(R.string.prediction_filter_empty_title),
                                message = stringResource(R.string.prediction_filter_empty_message))
                        }
                    }
                    items(state.items, key = { it.matchId }) { group ->
                        PredictionGroupCard(group, state.filter, state.nowEpochMs, languageTag,
                            onOpenMatch = { onMatchClick(group.matchId) })
                    }
                    if (state.loadedLimit < state.storedCount) {
                        item(key = "more") {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = viewModel::loadMore, enabled = !state.loadingMore) {
                                    Text(stringResource(if (state.loadingMore) R.string.prediction_loading_more else R.string.prediction_load_more))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleDailyReport(report: com.footballai.app.data.repository.DailyAnalysisReport, languageTag: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.prediction_report_hide else R.string.prediction_report_show))
        }
        if (expanded) DailyReportCard(report, languageTag)
    }
}

/** What the daily analysis run did, in numbers a non-specialist can read. */
@Composable
private fun DailyReportCard(
    report: com.footballai.app.data.repository.DailyAnalysisReport,
    languageTag: String,
) {
    InfoCard(
        title = stringResource(R.string.daily_report_title),
        body = stringResource(
            R.string.daily_report_body,
            Formatters.integer(report.coveredLeagues, languageTag),
            Formatters.integer(report.candidates, languageTag),
            Formatters.integer(report.predicted + report.alreadySaved, languageTag),
            Formatters.integer(report.insufficientHistory, languageTag),
            Formatters.integer(report.failed, languageTag),
            Formatters.kickoffDateTime(report.attemptedAt, languageTag),
        ),
    )
}

@Composable
private fun PredictionFilterRow(
    selected: PredictionListFilter, upcoming: Int, live: Int, waiting: Int, resolved: Int,
    onSelect: (PredictionListFilter) -> Unit,
) {
    val languageTag = LocalLanguageTag.current
    val filters = listOf(
        Triple(PredictionListFilter.UPCOMING, R.string.predictions_upcoming_filter, upcoming),
        Triple(PredictionListFilter.LIVE, R.string.prediction_live_filter, live),
        Triple(PredictionListFilter.WAITING, R.string.prediction_waiting_filter, waiting),
        Triple(PredictionListFilter.RESOLVED, R.string.predictions_resolved_filter, resolved),
    )
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(filters) { (filter, label, count) ->
            FilterChip(selected = selected == filter, onClick = { onSelect(filter) },
                label = { Text(stringResource(label, Formatters.integer(count, languageTag))) })
        }
        item { FilterChip(selected = selected == PredictionListFilter.ALL,
            onClick = { onSelect(PredictionListFilter.ALL) }, label = { Text(stringResource(R.string.search_all)) }) }
    }
}

@Composable
private fun PredictionGroupCard(
    group: PredictionGroup, filter: PredictionListFilter, now: Long, languageTag: String, onOpenMatch: () -> Unit,
) {
    var expanded by rememberSaveable(group.matchId, filter) { mutableStateOf(false) }
    var selectedId by rememberSaveable(group.matchId, filter) { mutableStateOf<String?>(null) }
    val selected = group.versions.firstOrNull { it.prediction.id == selectedId }
        ?: group.versions.firstOrNull { filter.includes(it, now) } ?: group.latest
    val match = selected.match
    val prediction = selected.prediction
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(NameLocalization.competitionNamePair(match.competitionName, match.competitionId, languageTag).first,
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                StatusChip(text = recordStatusLabel(selected, now), color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.selection_home), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PredictionTeamLine(match.home, languageTag)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.selection_away), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PredictionTeamLine(match.away, languageTag)
                }
            }
            Text(stringResource(R.string.prediction_kickoff, Formatters.kickoffDateTime(match.kickoffEpochMs, languageTag)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            if (prediction.resolution == null && match.status.hasScore && match.homeGoals != null && match.awayGoals != null) {
                ScoreBySide(match.homeGoals, match.awayGoals, languageTag, final = false)
            }
            Text(recordKindLabel(selected), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            if (match.status.isFinished && prediction.resolution == null) {
                Text(evaluationReason(selected), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (prediction.resolution != null) {
                EvaluationSummary(selected, languageTag)
            } else {
                com.footballai.app.ui.components.PreMatchVerdict(prediction, match.home, match.away, languageTag, showNote = false)
            }
            Spacer(Modifier.height(4.dp))
            ProbabilityBar(homeWin = prediction.homeWin, draw = prediction.draw, awayWin = prediction.awayWin,
                languageTag = languageTag)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (expanded) R.string.prediction_hide_versions else R.string.prediction_show_versions,
                        Formatters.integer(group.versions.size, languageTag)))
                }
                TextButton(onClick = onOpenMatch) { Text(stringResource(R.string.prediction_open_match_short)) }
            }
            if (expanded) {
                Text(stringResource(R.string.prediction_versions_scope), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                group.versions.forEach { version ->
                    TextButton(onClick = { selectedId = version.prediction.id }) {
                        val row = stringResource(R.string.prediction_version_row,
                            recordKindLabel(version), Formatters.kickoffDateTime(version.prediction.createdAtEpochMs, languageTag),
                            recordStatusLabel(version, now))
                        Text(if (version.prediction.id == prediction.id) stringResource(R.string.prediction_selected_version, row) else row)
                    }
                }
                Text(stringResource(R.string.prediction_record_time, Formatters.kickoffDateTime(prediction.createdAtEpochMs, languageTag)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.prediction_source, match.id.substringBefore(':')),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.prediction_model_version, prediction.modelVersion),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (match.status.isFinished && prediction.resolution == null) {
                    Text(evaluationReason(selected), style = MaterialTheme.typography.bodySmall)
                }
                prediction.resolution?.let { result ->
                    Text(stringResource(R.string.prediction_brier_only, Formatters.decimal(result.brier, languageTag, 3)),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.prediction_open_match_latest), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EvaluationSummary(item: PredictionWithMatch, languageTag: String) {
    val prediction = item.prediction
    val match = item.match
    val result = prediction.resolution ?: return
    val evaluation = evaluationDisplay(prediction.homeWin, prediction.draw, prediction.awayWin, result)
    val leading = MatchAnalysisPresentation.leadingOutcome(prediction.homeWin, prediction.draw, prediction.awayWin)
    val colors = FootballAiThemeExtra.colors
    val statusColor = when (evaluation) {
        EvaluationDisplay.CORRECT -> colors.positive
        EvaluationDisplay.INCORRECT -> colors.danger
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = stringResource(when (evaluation) {
        EvaluationDisplay.CORRECT -> R.string.prediction_evaluation_correct
        EvaluationDisplay.INCORRECT -> R.string.prediction_evaluation_wrong
        EvaluationDisplay.UNCLEAR -> R.string.prediction_evaluation_tied
        EvaluationDisplay.UNAVAILABLE -> R.string.prediction_evaluation_unavailable
    })
    val predictionText = when (leading) {
        VerdictOutcome.HOME -> stringResource(R.string.prediction_evaluation_pick_team,
            NameLocalization.displayName(match.home.name, languageTag, match.home.shortName))
        VerdictOutcome.AWAY -> stringResource(R.string.prediction_evaluation_pick_team,
            NameLocalization.displayName(match.away.name, languageTag, match.away.shortName))
        VerdictOutcome.DRAW -> stringResource(R.string.prediction_evaluation_pick_draw)
        null -> stringResource(if (MatchAnalysisPresentation.validProbabilities(prediction.homeWin, prediction.draw, prediction.awayWin))
            R.string.prediction_evaluation_pick_tied else R.string.prediction_evaluation_pick_unavailable)
    }
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(statusText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = statusColor)
            Text(predictionText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            Text(stringResource(R.string.prediction_evaluated_score_title), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EvaluatedTeamScore(match.home, result.actualHomeGoals, R.string.selection_home, languageTag, Modifier.weight(1f))
                EvaluatedTeamScore(match.away, result.actualAwayGoals, R.string.selection_away, languageTag, Modifier.weight(1f))
            }
            if (scoreChangedSinceEvaluation(result, match.homeGoals, match.awayGoals)) {
                Text(stringResource(R.string.prediction_score_changed), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                ScoreBySide(match.homeGoals!!, match.awayGoals!!, languageTag, final = false)
            }
        }
    }
}

@Composable
private fun EvaluatedTeamScore(team: com.footballai.app.domain.model.TeamRef, goals: Int,
    sideLabel: Int, languageTag: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(stringResource(sideLabel), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TeamNameLines(team = team, languageTag = languageTag, primaryStyle = MaterialTheme.typography.labelMedium,
            primaryMaxLines = 2, secondaryMaxLines = 2)
        Text(stringResource(R.string.prediction_evaluated_goals, Formatters.integer(goals, languageTag)),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ScoreBySide(home: Int, away: Int, languageTag: String, final: Boolean) {
    Text(stringResource(if (final) R.string.prediction_final_score_by_side else R.string.prediction_score_by_side,
        Formatters.integer(home, languageTag), Formatters.integer(away, languageTag)),
        style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun evaluationReason(item: PredictionWithMatch): String {
    val match = item.match
    val prediction = item.prediction
    val reason = evaluationBlock(match.status, item.scoreScope, prediction.modelVersion, PredictionRepository.MODEL_VERSION,
        if ((item.storedKind ?: prediction.kind.name) == "PRE_MATCH") com.footballai.app.domain.model.PredictionKind.PRE_MATCH
        else com.footballai.app.domain.model.PredictionKind.LIVE,
        prediction.createdAtEpochMs, match.kickoffEpochMs, match.homeGoals, match.awayGoals)
    return stringResource(when (reason) {
        EvaluationBlock.MATCH_NOT_FINISHED -> R.string.prediction_reason_not_finished
        EvaluationBlock.UNVERIFIED_REGULATION -> R.string.prediction_reason_scope
        EvaluationBlock.OLD_MODEL -> R.string.prediction_reason_old_model
        EvaluationBlock.LATE_PRE_MATCH -> R.string.prediction_reason_late
        EvaluationBlock.MISSING_SCORE -> R.string.prediction_reason_missing_score
        EvaluationBlock.NONE -> R.string.prediction_reason_pending
    })
}

@Composable
private fun recordStatusLabel(item: PredictionWithMatch, now: Long): String = stringResource(when (predictionRecordStatus(item, now)) {
    PredictionRecordStatus.UPCOMING -> R.string.prediction_status_upcoming
    PredictionRecordStatus.LIVE -> R.string.prediction_status_live
    PredictionRecordStatus.RESOLVED -> R.string.prediction_status_evaluated
    PredictionRecordStatus.WAITING -> when (item.match.status) {
        com.footballai.app.domain.model.MatchStatus.FINISHED -> R.string.prediction_status_finished_ungraded
        com.footballai.app.domain.model.MatchStatus.POSTPONED -> R.string.prediction_status_postponed
        com.footballai.app.domain.model.MatchStatus.CANCELLED -> R.string.prediction_status_cancelled
        com.footballai.app.domain.model.MatchStatus.SUSPENDED -> R.string.prediction_status_suspended
        else -> R.string.prediction_status_unconfirmed
    }
})

@Composable
private fun recordKindLabel(item: PredictionWithMatch): String = stringResource(when {
    item.storedKind != null && item.storedKind !in setOf("PRE_MATCH", "LIVE") -> R.string.prediction_record_unknown
    item.prediction.kind == com.footballai.app.domain.model.PredictionKind.LIVE -> R.string.prediction_record_live
    isLatePreMatch(item) -> R.string.prediction_record_late
    else -> R.string.prediction_record_prematch
})

/** One team inside a prediction card: badge, Persian name, published name. */
@Composable
private fun PredictionTeamLine(team: com.footballai.app.domain.model.TeamRef, languageTag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamBadge(team, size = 22)
        Spacer(Modifier.size(8.dp))
        TeamNameLines(
            team = team,
            languageTag = languageTag,
            primaryStyle = MaterialTheme.typography.titleSmall,
            primaryMaxLines = 2, secondaryMaxLines = 2,
        )
    }
}
