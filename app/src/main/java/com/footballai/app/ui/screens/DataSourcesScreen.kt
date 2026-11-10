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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.data.repository.HistoricalSourceOption
import com.footballai.app.data.repository.SyncSummary
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.SectionHeader
import com.footballai.app.ui.components.StatusChip
import com.footballai.app.ui.errorLabel
import com.footballai.app.ui.viewmodel.DataSourcesViewModel
import com.footballai.app.ui.viewmodel.ProviderRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSourcesScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: DataSourcesViewModel = viewModel(
        factory = viewModelFactory { initializer { DataSourcesViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current
    var keyDialogProvider by remember { mutableStateOf<ProviderRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.title_data_sources)) },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = stringResource(R.string.history_upgrade_notice),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    SectionHeader(
                        title = stringResource(R.string.section_providers),
                        subtitle = stringResource(R.string.section_providers_subtitle),
                    )
                }
                items(state.providers) { provider ->
                    ProviderCard(
                        provider = provider,
                        languageTag = languageTag,
                        onSaveKey = { keyDialogProvider = provider },
                        onClearKey = { viewModel.clearKey(provider.info.id) },
                        onSelect = { viewModel.selectProvider(provider.info.id) },
                    )
                }

                state.lastError?.let { error ->
                    item {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            StatusChip(text = errorLabel(error), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                state.lastSummary?.let { summary ->
                    item { SummaryCard(summary = summary, languageTag = languageTag) }
                }

                item {
                    SectionHeader(title = stringResource(R.string.section_sync_actions))
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(
                            onClick = { viewModel.syncCompetitions() },
                            label = { Text(stringResource(R.string.action_sync_competitions)) },
                        )
                        AssistChip(
                            onClick = { viewModel.syncFixtures() },
                            label = { Text(stringResource(R.string.action_sync_fixtures)) },
                        )
                        AssistChip(
                            onClick = { viewModel.syncLive() },
                            label = { Text(stringResource(R.string.action_sync_live)) },
                        )
                    }
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.section_history_import),
                        subtitle = stringResource(R.string.section_history_import_subtitle),
                    )
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.csvCompetitions.forEach { competition ->
                            FilterChip(
                                selected = state.selectedCsvKeys.contains(competition.key),
                                onClick = { viewModel.toggleCsvKey(competition.key) },
                                label = { Text("${competition.name} · ${competition.country}") },
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(1, 3, 5).forEach { seasons ->
                            FilterChip(
                                selected = state.historicalSeasons == seasons,
                                onClick = { viewModel.setHistoricalSeasons(seasons) },
                                label = {
                                    Text(stringResource(R.string.seasons_count, Formatters.integer(seasons, languageTag)))
                                },
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.estimated_requests,
                                Formatters.integer(state.estimatedRequests, languageTag),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AssistChip(
                            onClick = { viewModel.importCsvHistory() },
                            enabled = state.busyAction == null,
                            label = { Text(stringResource(R.string.action_import_csv_history)) },
                            leadingIcon = {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                        )
                        AssistChip(
                            onClick = { viewModel.importProviderHistory() },
                            enabled = state.busyAction == null,
                            label = { Text(stringResource(R.string.action_import_provider_history)) },
                        )
                    }
                }

                item {
                    SectionHeader(
                        title = stringResource(R.string.section_stored_history),
                    )
                }
                if (state.historicalSources.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.no_history_stored),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    items(state.historicalSources) { source ->
                        HistorySourceCard(
                            source = source,
                            languageTag = languageTag,
                            onDelete = { viewModel.clearHistoricalData(source.source) },
                        )
                    }
                }

                item { SectionHeader(title = stringResource(R.string.section_competitions)) }
                if (state.competitions.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.no_competitions),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    items(state.competitions) { competition ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = competition.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = competition.country ?: competition.providerId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = competition.isFollowed,
                                onCheckedChange = { viewModel.toggleFollowedCompetition(competition) },
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    keyDialogProvider?.let { provider ->
        var apiKey by remember(provider.info.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { keyDialogProvider = null },
            title = { Text(provider.info.displayName) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.api_key_dialog_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.api_key_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveKey(provider.info.id, apiKey)
                        keyDialogProvider = null
                    },
                    enabled = apiKey.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { keyDialogProvider = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderRow,
    languageTag: String,
    onSaveKey: () -> Unit,
    onClearKey: () -> Unit,
    onSelect: () -> Unit,
) {
    val info = provider.info
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = info.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (provider.isActive) {
                    StatusChip(text = stringResource(R.string.provider_active), color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.provider_capabilities,
                    booleanLabel(info.capabilities.liveScores),
                    booleanLabel(info.capabilities.matchStatistics),
                    booleanLabel(info.capabilities.lineups),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.provider_quota,
                    info.quota.requestsPerMinute,
                    if (info.quota.requestsPerDay == 0) stringResource(R.string.unlimited) else info.quota.requestsPerDay.toString(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            provider.quota?.let { quota ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (quota.dailyLimit <= 0) {
                        stringResource(
                            R.string.quota_used_only,
                            Formatters.integer(quota.usedToday, languageTag),
                        )
                    } else {
                        stringResource(
                            R.string.quota_usage,
                            Formatters.integer(quota.usedToday, languageTag),
                            Formatters.integer(quota.dailyLimit, languageTag),
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (info.requiresApiKey) {
                    if (provider.hasKey) {
                        AssistChip(onClick = onClearKey, label = { Text(stringResource(R.string.action_remove_key)) })
                    } else {
                        AssistChip(onClick = onSaveKey, label = { Text(stringResource(R.string.action_add_key)) })
                    }
                }
                if (!provider.isActive && (!info.requiresApiKey || provider.hasKey)) {
                    AssistChip(onClick = onSelect, label = { Text(stringResource(R.string.action_use_provider)) })
                }
            }
        }
    }
}

@Composable
private fun booleanLabel(value: Boolean): String =
    stringResource(if (value) R.string.yes else R.string.no)

@Composable
private fun SummaryCard(summary: SyncSummary, languageTag: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = stringResource(R.string.sync_summary_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(
                    R.string.sync_summary_body,
                    Formatters.integer(summary.fixtures, languageTag),
                    Formatters.integer(summary.historical, languageTag),
                    Formatters.integer(summary.live, languageTag),
                    Formatters.integer(summary.requestsEstimated, languageTag),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (summary.errors.isNotEmpty()) {
                Text(
                    text = summary.errors.take(3).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HistorySourceCard(
    source: HistoricalSourceOption,
    languageTag: String,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = source.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(
                    R.string.history_source_body,
                    Formatters.integer(source.matches, languageTag),
                    Formatters.integer(source.competitions.size, languageTag),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            source.competitions.take(4).forEach { competition ->
                Text(
                    text = "• ${competition.name} (${Formatters.integer(competition.matches, languageTag)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete_history)) }
        }
    }
}
