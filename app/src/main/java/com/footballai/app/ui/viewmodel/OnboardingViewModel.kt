package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.core.Seasons
import com.footballai.app.data.provider.CsvCompetition
import com.footballai.app.data.repository.SyncSummary
import com.footballai.app.data.settings.AppLanguage
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.ProviderInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Ordered steps of the first-run flow. */
enum class OnboardingStep { WELCOME, PROVIDER, COMPETITIONS, HISTORY }

/** A competition the user can follow during onboarding. */
data class FollowOption(
    val id: String,
    val name: String,
    val country: String?,
    val providerKey: String,
)

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val providerOptions: List<ProviderInfo> = emptyList(),
    val selectedProviderId: String = "",
    val keyInput: String = "",
    val hasKey: Boolean = false,
    val verifiedProviderId: String? = null,
    val followOptions: List<FollowOption> = emptyList(),
    val followedIds: Set<String> = emptySet(),
    val csvCompetitions: List<CsvCompetition> = emptyList(),
    val selectedCsvKeys: Set<String> = DEFAULT_CSV_SELECTION,
    val seasons: Int = 3,
    val busy: String? = null,
    val error: AppError? = null,
    val importedMatches: Int? = null,
    val importedCompetitions: Int = 0,
    val isFinishing: Boolean = false,
) {
    val stepIndex: Int get() = step.ordinal + 1
    val stepCount: Int = 4
    val estimatedRequests: Int get() = selectedCsvKeys.size * seasons
}

/**
 * Drives the first-run flow: language, a fixture provider with its key, the
 * competitions to follow, and the historical CSV import that makes the model usable.
 *
 * Every step is skippable: the app never blocks the user behind a credential, it only
 * explains what will be missing (no fixtures without a provider, no trustworthy
 * ratings without history).
 */
class OnboardingViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val settings = container.settingsRepository.current()
        val providers = container.providerRegistry.allInfos.filter { it.requiresApiKey }
        val competitions = container.database.competitionDao().allOnce()
        val options = competitions.map { entity ->
            FollowOption(
                id = entity.id,
                name = entity.name,
                country = entity.country,
                providerKey = entity.providerKey,
            )
        }
        val followed = competitions.filter { it.isFollowed }.map { it.id }.toSet()
        _state.update {
            it.copy(
                language = settings.language,
                providerOptions = providers,
                selectedProviderId = settings.activeProviderId.ifBlank { providers.firstOrNull()?.id.orEmpty() },
                hasKey = providers.any { info -> container.providerRegistry.hasCredentials(info.id) },
                selectedCsvKeys = DEFAULT_CSV_SELECTION,
                csvCompetitions = container.providerRegistry.historical.availableCompetitions(),
                followOptions = options,
                followedIds = followed,
            )
        }
    }

    fun setLanguage(language: AppLanguage) {
        _state.update { it.copy(language = language) }
        viewModelScope.launch { container.settingsRepository.setLanguage(language) }
    }

    fun selectProvider(providerId: String) {
        _state.update { it.copy(selectedProviderId = providerId, error = null) }
    }

    fun updateKeyInput(value: String) {
        _state.update { it.copy(keyInput = value, error = null) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun goTo(step: OnboardingStep) {
        _state.update { it.copy(step = step, error = null) }
    }

    fun back() {
        val previous = when (_state.value.step) {
            OnboardingStep.WELCOME -> OnboardingStep.WELCOME
            OnboardingStep.PROVIDER -> OnboardingStep.WELCOME
            OnboardingStep.COMPETITIONS -> OnboardingStep.PROVIDER
            OnboardingStep.HISTORY -> if (_state.value.followOptions.isEmpty()) {
                OnboardingStep.PROVIDER
            } else {
                OnboardingStep.COMPETITIONS
            }
        }
        _state.update { it.copy(step = previous, error = null) }
    }

    /**
     * Stores the key locally and immediately proves it works by listing competitions.
     * A stored-but-unverified key is useless to the user, so the flow does not advance
     * until the provider answers.
     */
    fun saveKeyAndVerify() {
        val providerId = _state.value.selectedProviderId
        val key = _state.value.keyInput.trim()
        if (providerId.isBlank()) return
        if (key.isBlank()) {
            _state.update { it.copy(error = AppError(ErrorCode.MISSING_API_KEY)) }
            return
        }
        container.providerRegistry.saveCredentials(providerId, key)
        _state.update { it.copy(hasKey = true, busy = BUSY_VERIFY, error = null, verifiedProviderId = null) }

        viewModelScope.launch {
            if (container.settingsRepository.current().activeProviderId.isBlank()) {
                container.settingsRepository.setActiveProvider(providerId)
            }
            when (val result = container.syncRepository.syncCompetitions(providerId)) {
                is Outcome.Failure -> _state.update {
                    it.copy(busy = null, error = result.error)
                }
                is Outcome.Success -> {
                    val competitions = container.database.competitionDao().allOnce().filter { it.providerId == providerId }
                    container.settingsRepository.setActiveProvider(providerId)
                    _state.update {
                        it.copy(
                            busy = null,
                            verifiedProviderId = providerId,
                            keyInput = "",
                            followOptions = competitions.map { entity ->
                                FollowOption(entity.id, entity.name, entity.country, entity.providerKey)
                            },
                            followedIds = competitions.filter { c -> c.isFollowed }.map { c -> c.id }.toSet(),
                            step = if (competitions.isEmpty()) {
                                OnboardingStep.HISTORY
                            } else {
                                OnboardingStep.COMPETITIONS
                            },
                        )
                    }
                }
            }
        }
    }

    fun skipProviderStep() {
        _state.update { it.copy(step = OnboardingStep.HISTORY, error = null) }
    }

    fun toggleFollow(competitionId: String) {
        _state.update {
            val updated = if (competitionId in it.followedIds) {
                it.followedIds - competitionId
            } else {
                it.followedIds + competitionId
            }
            it.copy(followedIds = updated)
        }
    }

    fun continueFromCompetitions() {
        _state.update { it.copy(step = OnboardingStep.HISTORY, error = null) }
    }

    fun toggleCsvKey(key: String) {
        _state.update {
            val updated = if (key in it.selectedCsvKeys) it.selectedCsvKeys - key else it.selectedCsvKeys + key
            it.copy(selectedCsvKeys = updated)
        }
    }

    fun setSeasons(count: Int) {
        _state.update { it.copy(seasons = count.coerceIn(1, 10)) }
    }

    fun importHistory() {
        if (_state.value.selectedCsvKeys.isEmpty()) {
            _state.update { it.copy(error = AppError(ErrorCode.EMPTY_RESPONSE, detail = "No competition selected")) }
            return
        }
        _state.update { it.copy(busy = BUSY_IMPORT, error = null) }
        viewModelScope.launch {
            val summary: Outcome<SyncSummary> = container.syncRepository.importCsvHistory(
                competitionKeys = _state.value.selectedCsvKeys.toList(),
                seasons = Seasons.recentStartYears(container.clock.nowEpochMs(), _state.value.seasons),
            )
            when (summary) {
                is Outcome.Failure -> _state.update { it.copy(busy = null, error = summary.error) }
                is Outcome.Success -> _state.update {
                    it.copy(
                        busy = null,
                        importedMatches = summary.value.historical,
                        importedCompetitions = _state.value.selectedCsvKeys.size,
                    )
                }
            }
            if (summary is Outcome.Success) {
                container.settingsRepository.setLastHistoricalImport(container.clock.nowEpochMs())
            }
        }
    }

    /** Persists the choices and flips the app out of the onboarding gate. */
    fun finish() {
        if (_state.value.isFinishing) return
        _state.update { it.copy(isFinishing = true) }
        viewModelScope.launch {
            val followedKeys = _state.value.followOptions
                .filter { it.id in _state.value.followedIds }
                .map { it.providerKey }
                .toSet()
            if (followedKeys.isNotEmpty()) {
                container.settingsRepository.setFollowedCompetitions(followedKeys)
            }
            for (option in _state.value.followOptions) {
                val shouldFollow = option.id in _state.value.followedIds
                container.database.competitionDao().setFollowed(option.id, shouldFollow)
            }
            container.settingsRepository.setOnboarded()
        }
    }

    companion object {
        const val BUSY_VERIFY = "verify_key"
        const val BUSY_IMPORT = "import_history"
    }
}
