package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.core.Formatters
import com.footballai.app.data.settings.AppLanguage
import com.footballai.app.data.settings.ThemeMode
import com.footballai.app.data.remote.CrestImages
import com.footballai.app.core.CrashLog
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.localizedString
import com.footballai.app.ui.theme.Spacing
import com.footballai.app.ui.components.InfoCard
import com.footballai.app.ui.components.NavigationRow
import com.footballai.app.ui.components.SectionHeader
import com.footballai.app.ui.components.StatusChip
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.components.SoftCard
import com.footballai.app.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onOpenDataSources: () -> Unit, onOpenCompetitionOrder: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { SettingsViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var crashReport by remember(context) { mutableStateOf(CrashLog.last(context)) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            // Keeps the last row off the floating pill bar.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                ToggleRow(title = stringResource(R.string.daily_mode_title),
                    subtitle = stringResource(R.string.daily_mode_hint),
                    checked = state.settings.dailyAnalysisEnabled, onCheckedChange = viewModel::setDailyAnalysis)
            }
            item { SectionHeader(title = stringResource(R.string.section_data_sync)) }
            item {
                SyncSettingsCard(
                    providerName = state.providerName,
                    lastSyncEpochMs = state.settings.lastSyncEpochMs,
                    fixtures = state.fixturesCount,
                    historical = state.historicalMatches,
                    predictions = state.storedPredictions,
                    isSyncing = state.isSyncing,
                    progressDone = state.syncDone,
                    progressTotal = state.syncTotal,
                    onSync = viewModel::syncNow,
                    onCancel = viewModel::cancelSync,
                    onManage = onOpenDataSources,
                )
            }

            item { SectionHeader(title = stringResource(R.string.section_sources)) }
            item {
                NavigationRow(
                    title = stringResource(R.string.title_data_sources),
                    subtitle = stringResource(R.string.data_sources_hint),
                    onClick = onOpenDataSources,
                )
            }
            item {
                NavigationRow(
                    title = stringResource(R.string.title_competition_order),
                    subtitle = stringResource(R.string.competition_order_hint_short),
                    onClick = onOpenCompetitionOrder,
                )
            }

            item { SectionHeader(title = stringResource(R.string.section_favourite_teams)) }
            if (state.settings.favouriteTeams.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.favourite_teams_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else {
                items(state.settings.favouriteTeams.sorted(), key = { it }) { name ->
                    FollowedTeamRow(name = name, onRemove = { viewModel.unfollowTeam(name) })
                }
            }

            item { SectionHeader(title = stringResource(R.string.section_favourite_players)) }
            if (state.settings.favouritePlayers.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.favourite_players_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else {
                items(state.followedPlayers, key = { it.entry }) { player ->
                    FollowedPlayerRow(
                        name = player.name,
                        club = player.teamName?.let { teamName ->
                            com.footballai.app.domain.catalog.NameLocalization.displayName(
                                teamName,
                                languageTag,
                                player.teamShortName,
                            )
                        },
                        onRemove = { viewModel.unfollowPlayer(player.entry) },
                    )
                }
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
                // Same pill row as the language choice: three theme names do not need a
                // framed segmented control with an outline box around them.
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.settings.themeMode == mode,
                            onClick = { viewModel.setTheme(mode) },
                            label = {
                                Text(
                                    text = when (mode) {
                                        ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                        ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                        ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                    },
                                    maxLines = 1,
                                )
                            },
                        )
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
                    title = stringResource(R.string.setting_auto_refresh),
                    subtitle = stringResource(R.string.setting_auto_refresh_body),
                    checked = state.settings.autoRefreshOnOpen,
                    onCheckedChange = viewModel::setAutoRefreshOnOpen,
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
                        text = localizedString(R.string.about_version, state.appVersion.ifBlank { "—" }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.about_model, "Dixon-Coles + Elo + form/context"),
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
            // A crash closes the app before anything can be shown, so the record of the
            // last one is surfaced here on the next launch — the only way to report a
            // defect from a phone that has no developer tools attached.
            item { SectionHeader(title = stringResource(R.string.section_diagnostics)) }
            crashReport?.let { report ->
                item {
                    InfoCard(
                        title = stringResource(R.string.crash_report_title),
                        body = stringResource(R.string.crash_report_body),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    )
                }
                item {
                    Text(
                        text = report,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item {
                    TextButton(
                        onClick = {
                            CrashLog.clear(context)
                            crashReport = null
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text(stringResource(R.string.action_clear_crash_report)) }
                }
            } ?: item {
                Text(
                    text = stringResource(R.string.crash_report_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // A blind report — "the badges are missing again" — has three possible causes,
            // and the phone is the only place that can tell them apart. This asks for one
            // real crest and prints what came back, so the next report carries a number
            // instead of a guess: 200 means the picture arrives and the fault is elsewhere,
            // 403 means the phone was turned away, 404 means the address is wrong.
            item {
                val scope = rememberCoroutineScope()
                var probe by remember { mutableStateOf<String?>(null) }
                var checking by remember { mutableStateOf(false) }
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(
                        enabled = !checking,
                        onClick = {
                            checking = true
                            scope.launch {
                                // The address answer first, then what the stored data
                                // actually holds: the two together say whether the phone
                                // cannot reach the picture or the picture has no address.
                                val network = CrestImages.probe(CrestImages.probeAddress)
                                val data = runCatching {
                                    container.matchRepository.crestDiagnostics()
                                }.getOrElse { error -> "teams: unreadable (${error::class.java.simpleName})" }
                                probe = network + "\n" + data
                                checking = false
                            }
                        },
                    ) { Text(stringResource(R.string.action_check_crest)) }
                    probe?.let { answer ->
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    CrestImages.lastFailure?.let { failure ->
                        Text(
                            text = failure,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
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
    SoftCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** A followed team, with the one action that matters: stop following it. */
@Composable
private fun FollowedTeamRow(name: String, onRemove: () -> Unit) {
    val persian = NameLocalization.persianTeamName(name)
    SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.page, vertical = 3.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = persian ?: name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                if (persian != null && persian != name) {
                    Text(
                        text = "\u200E" + name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_unfollow_team),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
    }

/**
 * What the app has stored, where it came from, and the two actions that change it.
 *
 * This was the top card of the home screen. The home screen is gone because it duplicated the
 * match list; a control panel like this belongs with the other controls.
 */
@Composable
private fun SyncSettingsCard(
    providerName: String?,
    lastSyncEpochMs: Long?,
    fixtures: Int,
    historical: Int,
    predictions: Int,
    isSyncing: Boolean,
    progressDone: Int,
    progressTotal: Int,
    onSync: () -> Unit,
    onCancel: () -> Unit,
    onManage: () -> Unit,
) {
    val languageTag = LocalLanguageTag.current
    SoftCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = 3.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = providerName ?: stringResource(R.string.no_provider_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(
                            R.string.last_sync,
                            lastSyncEpochMs?.let { Formatters.kickoffDateTime(it, languageTag) }
                                ?: stringResource(R.string.never),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isSyncing) {
                    StatusChip(
                        text = stringResource(R.string.sync_in_progress),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (providerName == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.no_provider_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = listOf(
                        Formatters.integer(fixtures, languageTag) + " " + stringResource(R.string.stat_fixtures),
                        Formatters.integer(historical, languageTag) + " " + stringResource(R.string.stat_history),
                        Formatters.integer(predictions, languageTag) + " " + stringResource(R.string.stat_predictions),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isSyncing) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (progressTotal > 0) {
                        stringResource(
                            R.string.sync_progress,
                            Formatters.integer(progressDone, languageTag),
                            Formatters.integer(progressTotal, languageTag),
                        )
                    } else {
                        stringResource(R.string.sync_in_progress)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isSyncing) {
                    SyncPillButton(
                        label = stringResource(R.string.action_cancel),
                        filled = false,
                        onClick = onCancel,
                    )
                } else {
                    SyncPillButton(
                        label = stringResource(R.string.action_sync_now),
                        filled = true,
                        onClick = onSync,
                    )
                    SyncPillButton(
                        label = stringResource(R.string.action_manage),
                        filled = false,
                        onClick = onManage,
                    )
                }
            }
        }
    }
}

/** A small action shaped like a pill, so the two buttons never differ in height. */
@Composable
private fun SyncPillButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val colors = FootballAiThemeExtra.colors
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (filled) MaterialTheme.colorScheme.primary else colors.cardStrong,
        contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * One followed player: the name, with the club underneath when the team table still knows it.
 *
 * The club is what tells two players with the same name apart — a list of names alone gave
 * two identical rows and no way to know which star to remove. A club that is no longer in
 * the table simply stays blank instead of being guessed.
 */
@Composable
private fun FollowedPlayerRow(name: String, club: String?, onRemove: () -> Unit) {
    SoftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.page, vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!club.isNullOrBlank()) {
                    Text(
                        text = club,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_unfollow_player),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
