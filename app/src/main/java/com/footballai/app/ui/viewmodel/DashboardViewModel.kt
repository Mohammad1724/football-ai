package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.core.AppError
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeConstants
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val liveMatches: List<MatchSummary> = emptyList(),
    val todayMatches: List<MatchSummary> = emptyList(),
    val upcomingMatches: List<MatchSummary> = emptyList(),
    val sync: SyncStatus = SyncStatus(
        lastSuccessfulSyncEpochMs = null,
        lastAttemptEpochMs = null,
        isSyncing = false,
        lastError = null,
        fixturesCount = 0,
        historicalCount = 0,
        predictionsCount = 0,
    ),
    val providerName: String? = null,
    val historicalMatches: Int = 0,
    val lastError: AppError? = null,
    val hasAnyData: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private val ticker = flow {
        while (true) {
            emit(container.clock.nowEpochMs())
            delay(TICK_MS)
        }
    }

    init {
        observeMatches()
        refreshCounts()
        autoSyncIfEmpty()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isSyncing = true, lastError = null) }
            val providerId = container.providerRegistry.activeProviderId()
            if (providerId == null) {
                _state.update { it.copy(isSyncing = false, isLoading = false) }
                return@launch
            }
            when (val result = container.syncRepository.syncFixtures(providerId)) {
                is Outcome.Failure -> _state.update { it.copy(isSyncing = false, lastError = result.error) }
                is Outcome.Success -> {
                    container.predictionRepository.resolveFinishedPredictions()
                    _state.update { it.copy(isSyncing = false, lastError = null) }
                }
            }
            refreshCounts()
        }
    }

    fun dismissError() = _state.update { it.copy(lastError = null) }

    private fun observeMatches() {
        viewModelScope.launch {
            ticker.flatMapLatest { now ->
                val (dayStart, dayEnd) = container.matchRepository.dayWindow(now)
                combine(
                    container.matchRepository.observeLive(),
                    container.matchRepository.observeBetween(now - 6 * TimeConstants.HOUR_MS, dayEnd),
                    container.matchRepository.observeUpcoming(12),
                ) { live, today, upcoming ->
                    // A live match must not be listed twice.
                    val liveIds = live.map { it.id }.toSet()
                    Triple(
                        live,
                        today.filterNot { liveIds.contains(it.id) && it.status.isFinished },
                        upcoming.filterNot { liveIds.contains(it.id) },
                    )
                }
            }.collect { (live, today, upcoming) ->
                _state.update {
                    it.copy(
                        liveMatches = live,
                        todayMatches = today,
                        upcomingMatches = upcoming,
                        isLoading = false,
                        hasAnyData = live.isNotEmpty() || today.isNotEmpty() || upcoming.isNotEmpty(),
                    )
                }
            }
        }
    }

    private fun refreshCounts() {
        viewModelScope.launch {
            val provider = container.providerRegistry.activeProvider()
            val settings = container.settingsRepository.current()
            val (fixtures, historical, predictions) = container.matchRepository.countsTriple()
            _state.update {
                it.copy(
                    providerName = provider?.info?.displayName,
                    historicalMatches = historical,
                    sync = it.sync.copy(
                        lastSuccessfulSyncEpochMs = settings.lastSyncEpochMs,
                        fixturesCount = fixtures,
                        historicalCount = historical,
                        predictionsCount = predictions,
                        isSyncing = it.isSyncing,
                    ),
                )
            }
        }
    }

    private fun autoSyncIfEmpty() {
        viewModelScope.launch {
            val providerId = container.providerRegistry.activeProviderId() ?: return@launch
            val (fixtures, _, _) = container.matchRepository.countsTriple()
            if (fixtures == 0) refresh()
        }
    }

    companion object {
        private const val TICK_MS = 60_000L
    }
}
