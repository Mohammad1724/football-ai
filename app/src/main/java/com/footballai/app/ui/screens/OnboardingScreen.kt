package com.footballai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.data.settings.AppLanguage
import com.footballai.app.domain.model.ProviderInfo
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.components.InfoCard
import com.footballai.app.ui.errorLabel
import com.footballai.app.ui.viewmodel.FollowOption
import com.footballai.app.ui.viewmodel.OnboardingStep
import com.footballai.app.ui.viewmodel.OnboardingUiState
import com.footballai.app.ui.viewmodel.OnboardingViewModel

/**
 * First-run flow. It exists because a cold install is empty by definition: the app has
 * no fixtures (feeds are authenticated) and no training data (the model needs finished
 * matches). Without this the first screen would just show an error the user cannot act
 * on.
 *
 * Every step can be skipped — the flow explains the consequence instead of blocking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen() {
    val container = LocalAppContainer.current
    val viewModel: OnboardingViewModel = viewModel(
        factory = viewModelFactory { initializer { OnboardingViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    if (state.step != OnboardingStep.HISTORY && state.step != OnboardingStep.WELCOME) {
                        TextButton(onClick = { viewModel.finish() }) {
                            Text(stringResource(R.string.onboarding_skip))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            StepProgress(
                current = state.stepIndex,
                total = state.stepCount,
                label = stringResource(R.string.onboarding_step, state.stepIndex, state.stepCount),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(8.dp))
                when (state.step) {
                    OnboardingStep.WELCOME -> WelcomeStep(
                        language = state.language,
                        onLanguageChange = viewModel::setLanguage,
                    )
                    OnboardingStep.PROVIDER -> ProviderStep(
                        state = state,
                        onSelectProvider = viewModel::selectProvider,
                        onKeyChange = viewModel::updateKeyInput,
                        onSaveKey = viewModel::saveKeyAndVerify,
                        onSkip = viewModel::skipProviderStep,
                    )
                    OnboardingStep.COMPETITIONS -> CompetitionsStep(
                        state = state,
                        onToggleFollow = viewModel::toggleFollow,
                    )
                    OnboardingStep.HISTORY -> HistoryStep(
                        state = state,
                        onToggleKey = viewModel::toggleCsvKey,
                        onSeasonsChange = viewModel::setSeasons,
                        onImport = viewModel::importHistory,
                        onSkip = { viewModel.finish() },
                    )
                }
                state.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    InfoCard(
                        title = stringResource(R.string.error_request),
                        body = errorLabel(error),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            ActionBar(
                state = state,
                onBack = viewModel::back,
                onNext = {
                    when (state.step) {
                        OnboardingStep.WELCOME -> viewModel.goTo(OnboardingStep.PROVIDER)
                        OnboardingStep.PROVIDER -> viewModel.goTo(OnboardingStep.COMPETITIONS)
                        OnboardingStep.COMPETITIONS -> viewModel.continueFromCompetitions()
                        OnboardingStep.HISTORY -> viewModel.finish()
                    }
                },
            )
        }
    }
}

@Composable
private fun StepProgress(current: Int, total: Int, label: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(total) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (index < current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WelcomeStep(language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit) {
    Icon(
        imageVector = Icons.Default.SportsSoccer,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(40.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.ob_welcome_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.ob_welcome_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    BulletPoint(stringResource(R.string.ob_welcome_point_model))
    BulletPoint(stringResource(R.string.ob_welcome_point_offline))
    BulletPoint(stringResource(R.string.ob_welcome_point_honest))

    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.ob_language_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val options = listOf(
            AppLanguage.SYSTEM to stringResource(R.string.language_system),
            AppLanguage.PERSIAN to stringResource(R.string.language_persian),
            AppLanguage.ENGLISH to stringResource(R.string.language_english),
        )
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = language == value,
                onClick = { onLanguageChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.ob_language_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BulletPoint(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderStep(
    state: OnboardingUiState,
    onSelectProvider: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSaveKey: () -> Unit,
    onSkip: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Text(text = stringResource(R.string.ob_provider_title), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.ob_provider_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    state.providerOptions.forEach { info ->
        ProviderOptionCard(
            info = info,
            selected = info.id == state.selectedProviderId,
            onSelect = { onSelectProvider(info.id) },
        )
        Spacer(Modifier.height(8.dp))
    }

    val selected = state.providerOptions.firstOrNull { it.id == state.selectedProviderId }
    if (selected != null) {
        if (state.hasKey) {
            Text(
                text = stringResource(R.string.ob_key_stored),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = state.keyInput,
            onValueChange = onKeyChange,
            label = { Text(stringResource(R.string.ob_key_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(keyHelpRes(selected.id)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.ob_provider_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSaveKey, enabled = state.busy == null && state.keyInput.isNotBlank()) {
                Text(stringResource(R.string.ob_key_verify))
            }
            Spacer(Modifier.width(8.dp))
            if (state.busy == OnboardingViewModel.BUSY_VERIFY) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = {
                val url = signUpUrl(selected)
                if (url != null) uriHandler.openUri(url)
            },
        ) {
            Text(stringResource(R.string.ob_provider_get_key))
        }
    }

    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onSkip) { Text(stringResource(R.string.ob_provider_later)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderOptionCard(info: ProviderInfo, selected: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = info.displayName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.provider_capabilities,
                        yesNo(info.capabilities.liveScores),
                        yesNo(info.capabilities.matchStatistics),
                        yesNo(info.capabilities.lineups),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.provider_quota,
                        info.quota.requestsPerMinute,
                        if (info.quota.requestsPerDay <= 0) {
                            stringResource(R.string.unlimited)
                        } else {
                            info.quota.requestsPerDay.toString()
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CompetitionsStep(state: OnboardingUiState, onToggleFollow: (String) -> Unit) {
    Text(text = stringResource(R.string.ob_competitions_title), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.ob_competitions_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    if (state.followOptions.isEmpty()) {
        InfoCard(
            title = stringResource(R.string.ob_competitions_title),
            body = stringResource(R.string.ob_competitions_empty),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        return
    }

    state.followOptions.forEach { option ->
        FollowRow(
            option = option,
            checked = option.id in state.followedIds,
            onToggle = { onToggleFollow(option.id) },
        )
    }
}

@Composable
private fun FollowRow(option: FollowOption, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = option.name, style = MaterialTheme.typography.bodyLarge)
            option.country?.let { country ->
                Text(
                    text = country,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HistoryStep(
    state: OnboardingUiState,
    onToggleKey: (String) -> Unit,
    onSeasonsChange: (Int) -> Unit,
    onImport: () -> Unit,
    onSkip: () -> Unit,
) {
    Text(text = stringResource(R.string.ob_history_title), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.ob_history_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.csvCompetitions.forEach { competition ->
            FilterChip(
                selected = competition.key in state.selectedCsvKeys,
                onClick = { onToggleKey(competition.key) },
                label = { Text(competition.name) },
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    Text(text = stringResource(R.string.ob_history_seasons), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    val seasonOptions = listOf(1, 2, 3, 5)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        seasonOptions.forEachIndexed { index, seasons ->
            SegmentedButton(
                selected = state.seasons == seasons,
                onClick = { onSeasonsChange(seasons) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = seasonOptions.size),
            ) {
                Text(seasons.toString())
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.ob_history_estimate, state.estimatedRequests),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onImport,
            enabled = state.busy == null && state.selectedCsvKeys.isNotEmpty(),
        ) {
            Text(stringResource(R.string.ob_history_import))
        }
        Spacer(Modifier.width(8.dp))
        if (state.busy == OnboardingViewModel.BUSY_IMPORT) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.ob_history_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    state.importedMatches?.let { imported ->
        Spacer(Modifier.height(12.dp))
        InfoCard(
            title = stringResource(R.string.ob_history_title),
            body = stringResource(R.string.ob_history_result, imported, state.importedCompetitions),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        )
    }

    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onSkip) { Text(stringResource(R.string.ob_history_skip)) }
}

@Composable
private fun ActionBar(state: OnboardingUiState, onBack: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.step != OnboardingStep.WELCOME) {
            OutlinedButton(
                onClick = onBack,
                enabled = state.busy == null,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.onboarding_back))
            }
        }
        Button(
            onClick = onNext,
            enabled = !state.isFinishing && state.busy == null,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                stringResource(
                    if (state.step == OnboardingStep.HISTORY) {
                        R.string.onboarding_finish
                    } else {
                        R.string.onboarding_next
                    },
                ),
            )
        }
    }
}

@Composable
private fun yesNo(value: Boolean): String = stringResource(if (value) R.string.yes else R.string.no)

private fun keyHelpRes(providerId: String): Int = when (providerId) {
    "football-data-org" -> R.string.ob_key_help_football_data_org
    "api-football" -> R.string.ob_key_help_api_football
    else -> R.string.ob_provider_body
}

private fun signUpUrl(info: ProviderInfo): String? = when (info.id) {
    "football-data-org" -> "https://www.football-data.org/client/register"
    "api-football" -> "https://dashboard.api-football.com/register"
    else -> null
}
