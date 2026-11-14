package com.footballai.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.footballai.app.data.repository.CalibrationStudyResult
import com.footballai.app.ui.competitionClassLabel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.domain.model.CompetitionPerformance
import com.footballai.app.domain.model.PerformanceReport
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.CalibrationChart
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.ErrorState
import com.footballai.app.ui.components.InfoCard
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.SectionHeader
import com.footballai.app.ui.errorLabel
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.viewmodel.CALIBRATION_CLASSES
import com.footballai.app.ui.viewmodel.PerformanceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen() {
    val container = LocalAppContainer.current
    val viewModel: PerformanceViewModel = viewModel(
        factory = viewModelFactory { initializer { PerformanceViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_performance)) },
                actions = {
                    IconButton(onClick = { viewModel.runBacktest() }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.action_run_backtest))
                    }
                    IconButton(onClick = { viewModel.refreshStoredStats() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.section_stored_predictions),
                        subtitle = stringResource(R.string.section_stored_predictions_subtitle),
                    )
                }
                item {
                    StoredStatsCard(
                        resolved = state.storedStats.resolved,
                        correct = state.storedStats.correct,
                        brier = state.storedStats.brier,
                        logLoss = state.storedStats.logLoss,
                        accuracy = state.storedStats.accuracy,
                        languageTag = languageTag,
                    )
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.calibration_study_title),
                        subtitle = stringResource(R.string.calibration_study_hint),
                    )
                }
                item {
                    CalibrationStudyCard(
                        studyClass = state.studyClass,
                        study = state.study,
                        fittedSamples = state.fittedSamples,
                        loading = state.studyLoading,
                        languageTag = languageTag,
                        onSelectClass = viewModel::selectStudyClass,
                    )
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.section_backtest),
                        subtitle = stringResource(R.string.section_backtest_subtitle),
                    )
                }
                if (state.sources.isEmpty()) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.empty_backtest_title),
                            message = stringResource(R.string.empty_backtest_message),
                        )
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.sources.forEach { source ->
                                FilterChip(
                                    selected = state.selectedSource == source.source,
                                    onClick = { viewModel.selectSource(source.source) },
                                    label = {
                                        Text(
                                            "${source.displayName} (${Formatters.integer(source.matches, languageTag)})",
                                        )
                                    },
                                )
                            }
                        }
                    }
                    item {
                        if (state.isRunning) {
                            LoadingState()
                        } else {
                            state.lastError?.let { error ->
                                ErrorState(message = errorLabel(error), onRetry = { viewModel.runBacktest() })
                            }
                        }
                    }
                    state.report?.let { report ->
                        item { BacktestSummary(report = report, languageTag = languageTag) }
                        if (report.baselineMatchesEvaluated > 0) {
                            item {
                                InfoCard(
                                    title = stringResource(R.string.context_comparison_title),
                                    body = stringResource(
                                        R.string.context_comparison_body,
                                        Formatters.decimal(report.baselineBrier, languageTag, 4),
                                        Formatters.decimal(report.brier, languageTag, 4),
                                        Formatters.decimal(report.baselineLogLoss, languageTag, 3),
                                        Formatters.decimal(report.logLoss, languageTag, 3),
                                        Formatters.percent(report.baselineAccuracy, languageTag),
                                        Formatters.percent(report.accuracy, languageTag),
                                    ),
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            }
                        }
                        item {
                            SectionHeader(title = stringResource(R.string.section_calibration))
                        }
                        item {
                            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                CalibrationChart(points = report.calibration)
                                Text(
                                    text = stringResource(
                                        R.string.calibration_hint,
                                        Formatters.decimal(report.expectedCalibrationError, languageTag, 3),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item { SectionHeader(title = stringResource(R.string.section_by_competition)) }
                        items(report.byCompetition.values.toList()) { competition ->
                            CompetitionPerformanceRow(competition = competition, languageTag = languageTag)
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(12.dp))
                    InfoCard(
                        title = stringResource(R.string.honesty_title),
                        body = stringResource(R.string.honesty_body),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * Shows the measured, out-of-sample effect of calibration for one competition class,
 * plus the sample count actually used by the on-device fitter. A class with too few
 * resolved forecasts says so instead of showing a number.
 */
@Composable
private fun CalibrationStudyCard(
    studyClass: String,
    study: CalibrationStudyResult?,
    fittedSamples: Int,
    loading: Boolean,
    languageTag: String,
    onSelectClass: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CALIBRATION_CLASSES.forEach { option ->
                FilterChip(
                    selected = option == studyClass,
                    onClick = { onSelectClass(option) },
                    label = { Text(competitionClassLabel(option)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                when {
                    loading -> Text(
                        text = "…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    study == null || study.sampleCount == 0 || study.note == "NOT_ENOUGH_SAMPLES" -> Text(
                        text = stringResource(R.string.calibration_study_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        Text(
                            text = stringResource(R.string.calibration_fitted, study.sampleCount),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.calibration_gain, Formatters.decimal(study.logLossGain, languageTag, 4)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.calibration_holdout,
                                Formatters.decimal(study.rawLogLoss, languageTag, 4),
                                Formatters.decimal(study.calibratedLogLoss, languageTag, 4),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!study.adopted) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.calibration_not_adopted),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.calibration_local_samples, fittedSamples),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StoredStatsCard(
    resolved: Int,
    correct: Int,
    brier: Double,
    logLoss: Double,
    accuracy: Double,
    languageTag: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (resolved == 0) {
                Text(
                    text = stringResource(R.string.no_resolved_predictions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MetricTile(stringResource(R.string.metric_resolved), Formatters.integer(resolved, languageTag))
                    MetricTile(stringResource(R.string.metric_accuracy), Formatters.percent(accuracy, languageTag))
                    MetricTile(stringResource(R.string.metric_brier), Formatters.decimal(brier, languageTag, 3))
                    MetricTile(stringResource(R.string.metric_log_loss), Formatters.decimal(logLoss, languageTag, 3))
                }
            }
        }
    }
}

@Composable
private fun BacktestSummary(report: PerformanceReport, languageTag: String) {
    val colors = FootballAiThemeExtra.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(
                    R.string.backtest_period,
                    Formatters.dateOnly(report.periodStartEpochMs, languageTag),
                    Formatters.dateOnly(report.periodEndEpochMs, languageTag),
                    Formatters.integer(report.matchesEvaluated, languageTag),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricTile(stringResource(R.string.metric_brier), Formatters.decimal(report.brier, languageTag, 4))
                MetricTile(stringResource(R.string.metric_base_rate), Formatters.decimal(report.baseRateBrier, languageTag, 4))
                MetricTile(stringResource(R.string.metric_accuracy), Formatters.percent(report.accuracy, languageTag))
                MetricTile(stringResource(R.string.metric_log_loss), Formatters.decimal(report.logLoss, languageTag, 3))
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricTile(stringResource(R.string.metric_market_brier), report.marketBrier?.let { Formatters.decimal(it, languageTag, 4) } ?: "—")
                MetricTile(stringResource(R.string.metric_over25), Formatters.decimal(report.over25Brier, languageTag, 4))
                MetricTile(stringResource(R.string.metric_btts), Formatters.decimal(report.bttsBrier, languageTag, 4))
                MetricTile(stringResource(R.string.metric_ece), Formatters.decimal(report.expectedCalibrationError, languageTag, 3))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.market_paired_comparison,
                    report.pairedModelBrier?.let { Formatters.decimal(it, languageTag, 4) } ?: "—",
                    Formatters.integer(report.marketSampleCount, languageTag)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = if (report.brier < report.baseRateBrier) {
                    stringResource(R.string.backtest_beats_base_rate)
                } else {
                    stringResource(R.string.backtest_loses_base_rate)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (report.brier < report.baseRateBrier) colors.positive else colors.warning,
            )
        }
    }
}

@Composable
private fun CompetitionPerformanceRow(competition: CompetitionPerformance, languageTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = competition.competitionName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                text = stringResource(
                    R.string.competition_matches,
                    Formatters.integer(competition.matches, languageTag),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = Formatters.decimal(competition.brier, languageTag, 4),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String) {
    Column {
        Text(text = value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
