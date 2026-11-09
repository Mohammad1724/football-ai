package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.ErrorState
import com.footballai.app.ui.components.InfoCard
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.MatchCard
import com.footballai.app.ui.components.SectionHeader
import com.footballai.app.ui.components.StatusChip
import com.footballai.app.ui.errorLabel
import com.footballai.app.ui.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onMatchClick: (String) -> Unit,
    onOpenDataSources: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMatches: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: DashboardViewModel = viewModel(
        factory = viewModelFactory { initializer { DashboardViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = Formatters.dateOnly(System.currentTimeMillis(), languageTag),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            imageVector = if (state.isSyncing) Icons.Default.CloudSync else Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_sync),
                        )
                    }
                    IconButton(onClick = onOpenDataSources) {
                        Icon(
                            imageVector = Icons.Outlined.Insights,
                            contentDescription = stringResource(R.string.title_data_sources),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.title_settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading) {
                LoadingState()
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    SyncCard(
                        providerName = state.providerName,
                        lastSyncEpochMs = state.sync.lastSuccessfulSyncEpochMs,
                        fixtures = state.sync.fixturesCount,
                        historical = state.sync.historicalCount,
                        predictions = state.sync.predictionsCount,
                        isSyncing = state.isSyncing,
                        onSync = { viewModel.refresh() },
                        onConfigure = onOpenDataSources,
                    )
                }

                state.lastError?.let { error ->
                    item {
                        ErrorState(
                            message = errorLabel(error),
                            onRetry = { viewModel.refresh() },
                        )
                    }
                }

                if (state.liveMatches.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.section_live),
                            subtitle = stringResource(R.string.section_live_subtitle),
                        )
                    }
                    items(state.liveMatches, key = { it.id }) { match ->
                        MatchCard(match = match, languageTag = languageTag, onClick = { onMatchClick(match.id) })
                    }
                }

                if (state.todayMatches.isNotEmpty()) {
                    item { SectionHeader(title = stringResource(R.string.section_today)) }
                    items(state.todayMatches, key = { it.id }) { match ->
                        MatchCard(match = match, languageTag = languageTag, onClick = { onMatchClick(match.id) })
                    }
                }

                if (state.upcomingMatches.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.section_upcoming),
                            actionLabel = stringResource(R.string.action_see_all),
                            onAction = onOpenMatches,
                        )
                    }
                    items(state.upcomingMatches, key = { it.id }) { match ->
                        MatchCard(match = match, languageTag = languageTag, onClick = { onMatchClick(match.id) })
                    }
                }

                if (!state.hasAnyData && state.lastError == null) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.empty_matches_title),
                            message = stringResource(R.string.empty_matches_message),
                            actionLabel = stringResource(R.string.action_configure_data),
                            onAction = onOpenDataSources,
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(12.dp))
                    InfoCard(
                        title = stringResource(R.string.disclaimer_title),
                        body = stringResource(R.string.disclaimer_body),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SyncCard(
    providerName: String?,
    lastSyncEpochMs: Long?,
    fixtures: Int,
    historical: Int,
    predictions: Int,
    isSyncing: Boolean,
    onSync: () -> Unit,
    onConfigure: () -> Unit,
) {
    val languageTag = LocalLanguageTag.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = providerName ?: stringResource(R.string.no_provider_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (isSyncing) {
                    StatusChip(
                        text = stringResource(R.string.sync_in_progress),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(onClick = onConfigure) {
                        Text(text = stringResource(R.string.action_manage))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (providerName == null) {
                Text(
                    text = stringResource(R.string.no_provider_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StatBlock(stringResource(R.string.stat_fixtures), Formatters.integer(fixtures, languageTag))
                    StatBlock(stringResource(R.string.stat_history), Formatters.integer(historical, languageTag))
                    StatBlock(stringResource(R.string.stat_predictions), Formatters.integer(predictions, languageTag))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.last_sync,
                        lastSyncEpochMs?.let { Formatters.kickoffDateTime(it, languageTag) }
                            ?: stringResource(R.string.never),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onSync) {
                    Text(text = stringResource(R.string.action_sync_now))
                }
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
