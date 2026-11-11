package com.footballai.app.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.domain.model.PredictionWithMatch
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.ProbabilityBar
import com.footballai.app.ui.components.StatusChip
import com.footballai.app.ui.theme.FootballAiThemeExtra
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_predictions)) },
                actions = {
                    IconButton(onClick = { viewModel.resolve() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_resolve))
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
            if (state.items.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.empty_predictions_title),
                    message = stringResource(R.string.empty_predictions_message),
                )
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.prediction.id }) { item ->
                    PredictionHistoryCard(item = item, languageTag = languageTag, onClick = { onMatchClick(item.match.id) })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun PredictionHistoryCard(item: PredictionWithMatch, languageTag: String, onClick: () -> Unit) {
    val prediction = item.prediction
    val match = item.match
    val colors = FootballAiThemeExtra.colors
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${match.home.name} - ${match.away.name}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                val resolution = prediction.resolution
                when {
                    resolution != null -> StatusChip(
                        text = if (resolution.outcomeCorrect) {
                            stringResource(R.string.outcome_correct)
                        } else {
                            stringResource(R.string.outcome_wrong)
                        },
                        color = if (resolution.outcomeCorrect) colors.positive else colors.danger,
                    )
                    else -> StatusChip(
                        text = stringResource(R.string.outcome_pending),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = Formatters.kickoffDateTime(match.kickoffEpochMs, languageTag),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ProbabilityBar(
                homeWin = prediction.homeWin,
                draw = prediction.draw,
                awayWin = prediction.awayWin,
                languageTag = languageTag,
                showLegend = true,
            )
            Spacer(Modifier.height(6.dp))
            val resolution = prediction.resolution
            if (resolution != null) {
                Text(
                    text = stringResource(
                        R.string.prediction_resolved_body,
                        resolution.actualHomeGoals,
                        resolution.actualAwayGoals,
                        Formatters.decimal(resolution.brier, languageTag, 3),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.prediction_pending_body,
                        Formatters.kickoffDateTime(prediction.createdAtEpochMs, languageTag),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
