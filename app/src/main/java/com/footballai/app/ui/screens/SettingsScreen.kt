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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.footballai.app.ui.errorLabel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.data.settings.AppLanguage
import com.footballai.app.data.settings.ThemeMode
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.InfoCard
import com.footballai.app.ui.components.SectionHeader
import com.footballai.app.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.title_settings)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                ToggleRow(title = stringResource(R.string.daily_mode_title),
                    subtitle = stringResource(R.string.daily_mode_hint),
                    checked = state.settings.dailyAnalysisEnabled, onCheckedChange = viewModel::setDailyAnalysis)
            }
            item { SectionHeader(title = stringResource(R.string.section_language)) }
            item {
                com.footballai.app.ui.components.LanguageSelector(
                    selected = state.settings.language,
                    onSelect = viewModel::setLanguage,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item { SectionHeader(title = stringResource(R.string.section_theme)) }
            item {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.settings.themeMode == mode,
                            onClick = { viewModel.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                            modifier = Modifier.weight(1f),
                            icon = {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .alpha(if (state.settings.themeMode == mode) 1f else 0f),
                                )
                            },
                        ) {
                            Text(
                                text = when (mode) {
                                    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                    ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            item { SectionHeader(title = stringResource(R.string.section_data_behaviour)) }
            if (!state.settings.dailyAnalysisEnabled) item {
                ToggleRow(
                    title = stringResource(R.string.setting_live_polling),
                    subtitle = stringResource(R.string.setting_live_polling_body),
                    checked = state.settings.livePollingEnabled,
                    onCheckedChange = viewModel::setLivePolling,
                )
            }
            item {
                ToggleRow(
                    title = stringResource(R.string.setting_background_sync),
                    subtitle = stringResource(R.string.setting_background_sync_body),
                    checked = state.settings.backgroundSyncEnabled,
                    onCheckedChange = viewModel::setBackgroundSync,
                )
            }

            item { SectionHeader(title = stringResource(R.string.section_storage)) }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(
                            R.string.storage_summary,
                            Formatters.integer(state.historicalMatches, languageTag),
                            Formatters.integer(state.storedPredictions, languageTag),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { confirmClear = true }) {
                        Text(stringResource(R.string.action_clear_data))
                    }
                }
            }

            item { SectionHeader(title = stringResource(R.string.section_about)) }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.about_version, state.appVersion),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.about_model, "Dixon-Coles + Elo"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.about_provider, state.providerName ?: stringResource(R.string.none)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = viewModel::restartOnboarding) {
                        Text(stringResource(R.string.action_run_setup))
                    }
                }
            }
            item {
                InfoCard(
                    title = stringResource(R.string.about_data_title),
                    body = stringResource(R.string.about_data_body),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            state.lastError?.let { error ->
                item { Text(text = errorLabel(error), color = MaterialTheme.colorScheme.error) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.action_clear_data)) },
            text = { Text(stringResource(R.string.clear_data_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllData()
                        confirmClear = false
                    },
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
