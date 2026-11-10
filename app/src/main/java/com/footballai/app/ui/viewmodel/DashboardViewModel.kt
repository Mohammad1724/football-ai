package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.core.AppError
import com.footballai.app.core.AppErrorException
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeConstants
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
    val dailyMode: Boolean = true,
    val dailyReport: com.footballai.app.data.repository.DailyAnalysisReport? = null,
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
    /** Progress of the running fixture sync: competitions finished and total. */
    val syncDone: Int = 0,
    val syncTotal: Int = 0,
    /** Per-competition failures from the last sync, e.g. "API-Football/39: REQUEST_FAILED". */
    val syncWarnings: List<String> = emptyList(),
    /** Keyless source that supplied the fixtures when the configured provider returned none. */
    val fallbackProviderName: String? = null,
    val fallbackProviderId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var syncJob: Job? = null
    private var autoSyncAttempted = false

    private val ticker = flow {
        while (true) {
            emit(container.clock.nowEpochMs())
            delay(TICK_MS)
        }
    }

    init {
        viewModelScope.launch {
            container.dailyAnalysisRepository.reports.collect { report -> _state.update { it.copy(dailyReport = report) } }
        }
        observeMatches()
        observeProvider()
        refreshCounts()
        autoSyncIfEmpty()
    }

    /**
     * Keeps the provider label in step with the stored credentials: adding a key in the
     * data screen must not leave the dashboard claiming that nothing is configured.
     */
    private fun observeProvider() {
        viewModelScope.launch {
            container.settingsRepository.settings.collect {
                val provider = container.providerRegistry.activeProvider()
                _state.update { state -> state.copy(providerName = provider?.info?.displayName, dailyMode = it.dailyAnalysisEnabled) }
            }
        }
    }

    fun refresh() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _state.update {
                it.copy(isSyncing = true, isLoading = false, lastError = null, syncDone = 0, syncTotal = 0)
            }
            try {
                if (container.settingsRepository.current().dailyAnalysisEnabled) {
                    val report = container.dailyAnalysisRepository.run { done, total ->
                        _state.update { it.copy(syncDone = done, syncTotal = total) }
                    }
                    _state.update { it.copy(dailyReport = report, syncWarnings = report.errors,
                        fallbackProviderName = null, fallbackProviderId = null) }
                    return@launch
                }
                val providerId = container.providerRegistry.activeProviderId()
                if (providerId == null) {
                    _state.update { it.copy(lastError = AppError(ErrorCode.MISSING_API_KEY)) }
                    return@launch
                }
                val result = container.syncRepository.syncFixtures(
                    providerId = providerId,
                    onProgress = { done, total ->
                        _state.update { it.copy(syncDone = done, syncTotal = total) }
                    },
                )
                when (result) {
                    is Outcome.Failure -> _state.update { it.copy(lastError = result.error) }
                    is Outcome.Success -> {
                        container.predictionRepository.resolveFinishedPredictions()
                        _state.update {
                            it.copy(
                                lastError = null,
                                syncWarnings = result.value.errors,
                                fallbackProviderName = result.value.fallbackProviderId
                                    ?.let { id -> container.providerRegistry.info(id)?.displayName },
                                fallbackProviderId = result.value.fallbackProviderId,
                            )
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: AppErrorException) {
                // Quota exhaustion and similar typed failures must not leave the screen
                // spinning forever.
                _state.update { it.copy(lastError = error.error) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(lastError = AppError(ErrorCode.UNKNOWN, detail = error.message))
                }
            } finally {
                _state.update { it.copy(isSyncing = false) }
                refreshCounts()
            }
        }
    }

    /** Makes the keyless source the active provider so later syncs skip the failing one. */
    fun useFallbackProvider() {
        val providerId = _state.value.fallbackProviderId ?: return
        viewModelScope.launch {
            container.settingsRepository.setActiveProvider(providerId)
            _state.update { it.copy(fallbackProviderName = null, fallbackProviderId = null) }
            refresh()
        }
    }

    /** Stops a long fixture sync (free providers space requests a few seconds apart). */
    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        _state.update {
            it.copy(isSyncing = false, syncDone = 0, syncTotal = 0)
        }
    }

    fun dismissError() = _state.update { it.copy(lastError = null) }

    private fun observeMatches() {
        viewModelScope.launch {
            combine(ticker, container.settingsRepository.settings) { now, settings -> now to settings.dailyAnalysisEnabled }
                .flatMapLatest { (now, daily) ->
                    val (dayStart, dayEnd) = container.matchRepository.dayWindow(now)
                    combine(
                        container.matchRepository.observeLive(),
                        container.matchRepository.observeBetween(dayStart, dayEnd),
                        container.matchRepository.observeBetween(now, now + 7 * TimeConstants.DAY_MS),
                    ) { live, today, upcoming ->
                        fun visible(list: List<MatchSummary>) = if (daily) list.filter { it.id.startsWith("football-data-co-uk:") } else list
                        val shownLive = if (daily) emptyList() else live
                        val liveIds = shownLive.map { it.id }.toSet()
                        val shownToday = visible(today).filterNot { it.id in liveIds }
                        val todayIds = shownToday.map { it.id }.toSet()
                        Triple(shownLive, shownToday, visible(upcoming).filter {
                            it.id !in liveIds && it.id !in todayIds && it.status == com.footballai.app.domain.model.MatchStatus.SCHEDULED
                        }.take(12))
                    }
                }.collect { (live, today, upcoming) ->
                    _state.update { it.copy(liveMatches = live, todayMatches = today, upcomingMatches = upcoming,
                        isLoading = false, hasAnyData = live.isNotEmpty() || today.isNotEmpty() || upcoming.isNotEmpty()) }
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
        if (autoSyncAttempted) return
        autoSyncAttempted = true
        viewModelScope.launch {
            if (container.settingsRepository.current().dailyAnalysisEnabled) {
                if (container.dailyAnalysisRepository.needsRefresh()) refresh()
                return@launch
            }
            val providerId = container.providerRegistry.activeProviderId() ?: return@launch
            val (fixtures, _, _) = container.matchRepository.countsTriple()
            if (fixtures == 0) refresh()
        }
    }

    companion object {
        private const val TICK_MS = 60_000L
    }
}
