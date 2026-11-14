package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.core.AppError
import com.footballai.app.core.Outcome
import com.footballai.app.data.sync.SyncTrigger
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.Competition
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.PredictionWithMatch
import com.footballai.app.domain.model.TeamProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ----------------------------------------------------------------------- Teams

data class TeamUiState(
    val isLoading: Boolean = true,
    val profile: TeamProfile? = null,
    val lastError: AppError? = null,
)

class TeamViewModel(
    private val container: AppContainer,
    private val teamId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(TeamUiState())
    val state: StateFlow<TeamUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val profile = container.matchRepository.teamProfile(teamId)
            _state.update { TeamUiState(isLoading = false, profile = profile) }
        }
    }
}

// ----------------------------------------------------------------- Competitions

data class CompetitionUiState(
    val isLoading: Boolean = true,
    val competition: Competition? = null,
    val fixtures: List<MatchSummary> = emptyList(),
    val lastError: AppError? = null,
)

class CompetitionViewModel(
    private val container: AppContainer,
    private val competitionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(CompetitionUiState())
    val state: StateFlow<CompetitionUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val competition = container.matchRepository.competition(competitionId)
            val fixtures = container.matchRepository.fixturesForCompetition(competitionId, 40)
            _state.update {
                CompetitionUiState(isLoading = false, competition = competition, fixtures = fixtures)
            }
        }
    }
}

// ------------------------------------------------------------------ Predictions

enum class PredictionListFilter { UPCOMING, RESOLVED, ALL }

data class PredictionsUiState(
    val isLoading: Boolean = true,
    val items: List<PredictionWithMatch> = emptyList(),
    val filter: PredictionListFilter = PredictionListFilter.UPCOMING,
    val upcomingCount: Int = 0,
    val resolvedCount: Int = 0,
    val resolving: Boolean = false,
    val resolvedNow: Int = 0,
    /** True while the screen's refresh gesture is running a full sync. */
    val refreshing: Boolean = false,
    /** Fixtures the last refresh received; 0 is a valid answer, not a failure. */
    val lastSyncedAdded: Int = 0,
    val lastError: com.footballai.app.core.AppError? = null,
)

class PredictionsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(PredictionsUiState())
    val state: StateFlow<PredictionsUiState> = _state.asStateFlow()
    private var latestItems: List<PredictionWithMatch> = emptyList()

    init {
        viewModelScope.launch {
            combine(container.predictionRepository.observeRecent(500), container.settingsRepository.settings) { items, settings ->
                if (settings.dailyAnalysisEnabled) items.filter {
                    it.match.id.startsWith("football-data-co-uk:") && it.prediction.kind == com.footballai.app.domain.model.PredictionKind.PRE_MATCH &&
                        it.prediction.modelVersion == com.footballai.app.data.repository.PredictionRepository.MODEL_VERSION
                } else items
            }.catch { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                // A stored row that cannot be read must not close the whole tab.
                _state.update { it.copy(isLoading = false) }
            }.collect { items ->
                latestItems = items
                _state.update { state ->
                    state.copy(
                        items = filterItems(items, state.filter),
                        upcomingCount = items.count { it.prediction.resolution == null },
                        resolvedCount = items.count { it.prediction.resolution != null },
                        isLoading = false,
                    )
                }
            }
        }
        resolve()
    }

    fun setFilter(filter: PredictionListFilter) {
        _state.update { it.copy(filter = filter, items = filterItems(latestItems, filter)) }
    }

    fun resolve() {
        viewModelScope.launch {
            _state.update { it.copy(resolving = true) }
            val resolved = container.predictionRepository.resolveFinishedPredictions()
            _state.update { it.copy(resolving = false, resolvedNow = resolved) }
        }
    }

    /**
     * The screen's refresh control.
     *
     * Resolving finished predictions only updates rows that already exist; a user pressing
     * refresh on this screen is also asking for new fixtures and new odds to appear, so the
     * gesture does both, in that order: sync first, then grade what has finished.
     */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, lastError = null) }
            try {
                when (val result = container.autoSyncCoordinator.syncNow(SyncTrigger.MANUAL)) {
                    is Outcome.Failure -> _state.update { it.copy(lastError = result.error) }
                    is Outcome.Success -> {
                        val resolved = container.predictionRepository.resolveFinishedPredictions()
                        _state.update { it.copy(resolvedNow = resolved, lastSyncedAdded = result.value.added) }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(lastError = com.footballai.app.core.AppError(com.footballai.app.core.ErrorCode.UNKNOWN)) }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    companion object {
        internal fun filterItems(
            items: List<PredictionWithMatch>,
            filter: PredictionListFilter,
        ): List<PredictionWithMatch> = when (filter) {
            PredictionListFilter.UPCOMING -> items.filter { it.prediction.resolution == null }
                .sortedBy { it.match.kickoffEpochMs }
            PredictionListFilter.RESOLVED -> items.filter { it.prediction.resolution != null }
                .sortedByDescending { it.match.kickoffEpochMs }
            PredictionListFilter.ALL -> items.sortedByDescending { it.match.kickoffEpochMs }
        }
    }
}

// ------------------------------------------------------------------ Performance

data class PerformanceUiState(
    val isLoading: Boolean = true,
    val isRunning: Boolean = false,
    val sources: List<com.footballai.app.data.repository.HistoricalSourceOption> = emptyList(),
    val selectedSource: String? = null,
    val report: com.footballai.app.domain.model.PerformanceReport? = null,
    val storedStats: com.footballai.app.data.repository.StoredPredictionStats =
        com.footballai.app.data.repository.StoredPredictionStats.EMPTY,
    /** Out-of-sample calibration study for the class selected below. */
    val studyClass: String = "CLUB_LEAGUE",
    val study: com.footballai.app.data.repository.CalibrationStudyResult? = null,
    val studyLoading: Boolean = false,
    /** True while the stored-prediction statistics are being recomputed. */
    val statsRefreshing: Boolean = false,
    val fittedSamples: Int = 0,
    val lastError: AppError? = null,
)

/** Competition classes with a separately fitted and separately measured calibration. */
val CALIBRATION_CLASSES = listOf(
    "CLUB_LEAGUE",
    "CLUB_KNOCKOUT",
    "INTERNATIONAL_TOURNAMENT",
    "WOMEN_CLUB_LEAGUE",
    "WOMEN_INTERNATIONAL",
)

class PerformanceViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(PerformanceUiState())
    val state: StateFlow<PerformanceUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val sources = container.performanceRepository.historicalSources()
            val storedStats = container.performanceRepository.storedPredictionStats()
            val selected = _state.value.selectedSource
                ?: sources.firstOrNull { it.source == "football-data-co-uk" }?.source
                ?: sources.firstOrNull()?.source
            _state.update {
                it.copy(
                    isLoading = false,
                    sources = sources,
                    selectedSource = selected,
                    storedStats = storedStats,
                )
            }
            loadStudy(_state.value.studyClass)
            if (selected != null && _state.value.report == null) {
                runBacktest(selected)
            }
        }
    }

    fun selectSource(source: String) {
        _state.update { it.copy(selectedSource = source, report = null) }
        runBacktest(source)
    }

    fun runBacktest(source: String? = _state.value.selectedSource) {
        if (source == null) return
        viewModelScope.launch {
            _state.update { it.copy(isRunning = true, lastError = null) }
            when (val result = container.performanceRepository.runBacktest(source)) {
                is Outcome.Failure -> _state.update { it.copy(isRunning = false, lastError = result.error) }
                is Outcome.Success -> _state.update { it.copy(isRunning = false, report = result.value) }
            }
        }
    }

    fun selectStudyClass(competitionClass: String) {
        _state.update { it.copy(studyClass = competitionClass) }
        loadStudy(competitionClass)
    }

    /** Refreshes the calibration study and the sample count actually used on this device. */
    fun loadStudy(competitionClass: String = _state.value.studyClass) {
        viewModelScope.launch {
            _state.update { it.copy(studyLoading = true) }
            val study = container.performanceRepository.calibrationStudy(competitionClass)
            val fitted = container.performanceRepository.calibrationSampleCount(competitionClass)
            _state.update { it.copy(studyLoading = false, study = study, fittedSamples = fitted) }
        }
    }

    fun refreshStoredStats() {
        if (_state.value.statsRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(statsRefreshing = true) }
            try {
                val stats = container.performanceRepository.refreshStoredStats()
                _state.update { it.copy(storedStats = stats) }
            } finally {
                _state.update { it.copy(statsRefreshing = false) }
            }
        }
    }
}
