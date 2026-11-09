package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.data.provider.CsvCompetition
import com.footballai.app.data.remote.QuotaState
import com.footballai.app.data.repository.HistoricalSourceOption
import com.footballai.app.data.repository.SyncSummary
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.Competition
import com.footballai.app.domain.model.ProviderInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneOffset
import java.time.ZonedDateTime

data class ProviderRow(
    val info: ProviderInfo,
    val hasKey: Boolean,
    val isActive: Boolean,
    val quota: QuotaState?,
)

data class DataSourcesUiState(
    val isLoading: Boolean = true,
    val providers: List<ProviderRow> = emptyList(),
    val competitions: List<Competition> = emptyList(),
    val csvCompetitions: List<CsvCompetition> = emptyList(),
    val selectedCsvKeys: Set<String> = DEFAULT_CSV_SELECTION,
    val historicalSeasons: Int = 3,
    val historicalSources: List<HistoricalSourceOption> = emptyList(),
    val busyAction: String? = null,
    val lastSummary: SyncSummary? = null,
    val lastError: AppError? = null,
    val messageKey: String? = null,
    val estimatedRequests: Int = 0,
)

val DEFAULT_CSV_SELECTION: Set<String> = setOf("E0", "SP1", "D1", "I1", "F1")

class DataSourcesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DataSourcesUiState())
    val state: StateFlow<DataSourcesUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            container.matchRepository.observeCompetitions().collect { competitions ->
                _state.update { it.copy(competitions = competitions) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val activeId = container.providerRegistry.activeProviderId()
            val rows = container.providerRegistry.allInfos.map { info ->
                ProviderRow(
                    info = info,
                    hasKey = container.providerRegistry.hasCredentials(info.id),
                    isActive = info.id == activeId,
                    quota = if (info.requiresApiKey) {
                        container.quotaGuardProvider.state(info.id, info.quota)
                    } else {
                        null
                    },
                )
            }
            val sources = container.performanceRepository.historicalSources()
            val csv = container.providerRegistry.historical.availableCompetitions()
            _state.update {
                it.copy(
                    isLoading = false,
                    providers = rows,
                    historicalSources = sources,
                    csvCompetitions = csv,
                    estimatedRequests = it.selectedCsvKeys.size * it.historicalSeasons,
                )
            }
        }
    }

    fun saveKey(providerId: String, apiKey: String) {
        if (apiKey.isBlank()) return
        container.providerRegistry.saveCredentials(providerId, apiKey)
        viewModelScope.launch {
            if (container.settingsRepository.current().activeProviderId.isBlank()) {
                container.settingsRepository.setActiveProvider(providerId)
            }
            _state.update { it.copy(messageKey = "message_key_saved") }
            refresh()
            syncCompetitions()
        }
    }

    fun clearKey(providerId: String) {
        container.providerRegistry.clearCredentials(providerId)
        viewModelScope.launch {
            _state.update { it.copy(messageKey = "message_key_removed") }
            refresh()
        }
    }

    fun selectProvider(providerId: String) {
        viewModelScope.launch {
            container.settingsRepository.setActiveProvider(providerId)
            refresh()
        }
    }

    fun syncCompetitions() = runAction("competitions") {
        val providerId = container.providerRegistry.activeProviderId()
        if (providerId == null) {
            null
        } else {
            when (val result = container.syncRepository.syncCompetitions(providerId)) {
                is Outcome.Failure -> result
                is Outcome.Success -> Outcome.Success(SyncSummary(competitions = result.value, requestsEstimated = 1))
            }
        }
    }

    fun syncFixtures() = runAction("fixtures") {
        val providerId = container.providerRegistry.activeProviderId()
        if (providerId == null) null else container.syncRepository.syncFixtures(providerId)
    }

    fun syncLive() = runAction("live") {
        val providerId = container.providerRegistry.activeProviderId()
        if (providerId == null) null else container.syncRepository.syncLive(providerId)
    }

    fun importProviderHistory() = runAction("providerHistory") {
        val providerId = container.providerRegistry.activeProviderId()
        if (providerId == null) {
            null
        } else {
            val keys = container.database.competitionDao().followed().map { it.providerKey }
            if (keys.isEmpty()) {
                Outcome.Failure(
                    AppError(ErrorCode.EMPTY_RESPONSE, detail = "Follow at least one competition first"),
                )
            } else {
                container.syncRepository.importProviderHistory(
                    providerId = providerId,
                    competitionKeys = keys,
                    seasonsBack = _state.value.historicalSeasons,
                )
            }
        }
    }

    fun importCsvHistory() = runAction("csvHistory") {
        container.syncRepository.importCsvHistory(
            competitionKeys = _state.value.selectedCsvKeys.toList(),
            seasons = recentSeasonStartYears(_state.value.historicalSeasons),
        )
    }

    fun toggleCsvKey(key: String) {
        _state.update {
            val updated = if (it.selectedCsvKeys.contains(key)) {
                it.selectedCsvKeys - key
            } else {
                it.selectedCsvKeys + key
            }
            it.copy(selectedCsvKeys = updated, estimatedRequests = updated.size * it.historicalSeasons)
        }
    }

    fun setHistoricalSeasons(seasons: Int) {
        val clamped = seasons.coerceIn(1, 10)
        _state.update {
            it.copy(historicalSeasons = clamped, estimatedRequests = it.selectedCsvKeys.size * clamped)
        }
    }

    fun toggleFollowedCompetition(competition: Competition) {
        viewModelScope.launch {
            val entity = container.database.competitionDao().byId(competition.id) ?: return@launch
            val newValue = !entity.isFollowed
            container.database.competitionDao().setFollowed(competition.id, newValue)
            val settings = container.settingsRepository.current()
            val followed = settings.followedCompetitionKeys
            container.settingsRepository.setFollowedCompetitions(
                if (newValue) followed + entity.providerKey else followed - entity.providerKey,
            )
            refresh()
        }
    }

    fun clearHistoricalData(source: String) {
        viewModelScope.launch {
            container.database.historicalDao().deleteForSource(source)
            container.ratingsRepository.invalidate()
            refresh()
        }
    }

    fun dismissMessage() = _state.update { it.copy(messageKey = null, lastError = null, lastSummary = null) }

    private fun recentSeasonStartYears(count: Int): List<Int> {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val currentStart = if (now.monthValue >= 7) now.year else now.year - 1
        return (0 until count).map { currentStart - it }
    }

    private fun runAction(action: String, block: suspend () -> Outcome<SyncSummary>?) {
        viewModelScope.launch {
            _state.update { it.copy(busyAction = action, lastError = null) }
            try {
                when (val result = block()) {
                    null -> _state.update { it.copy(busyAction = null) }
                    is Outcome.Failure -> _state.update { it.copy(busyAction = null, lastError = result.error) }
                    is Outcome.Success -> _state.update {
                        it.copy(busyAction = null, lastSummary = result.value)
                    }
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(busyAction = null, lastError = AppError(ErrorCode.UNKNOWN, detail = error.message))
                }
            }
            refresh()
        }
    }
}
