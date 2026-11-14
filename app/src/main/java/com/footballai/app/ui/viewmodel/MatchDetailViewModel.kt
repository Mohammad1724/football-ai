package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.core.AppError
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeConstants
import com.footballai.app.data.local.mapper.parseStatus
import com.footballai.app.data.repository.PredictionPolicy
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.MatchDetail
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.PredictionKind
import com.footballai.app.domain.model.PredictionView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MatchDetailUiState(
    val isLoading: Boolean = true,
    val detail: MatchDetail? = null,
    val prediction: PredictionView? = null,
    val isPredicting: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLiveTracking: Boolean = false,
    val liveTrackingEnabled: Boolean = true,
    val headToHead: List<MatchSummary> = emptyList(),
    val lastError: AppError? = null,
    /**
     * A failed background refresh, kept apart from [lastError]: the screen still has
     * usable stored data, so this is a note, not the "nothing found" state.
     */
    val refreshWarning: AppError? = null,
    /** False when the source cannot re-read a single fixture, so live polling is pointless. */
    val liveTrackingSupported: Boolean = true,
    /** True while the international archive is being downloaded and stored. */
    val isImportingHistory: Boolean = false,
)

class MatchDetailViewModel(
    private val container: AppContainer,
    private val matchId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(MatchDetailUiState())
    val state: StateFlow<MatchDetailUiState> = _state.asStateFlow()

    private var freshnessJob: Job? = null
    private var screenVisible = false
    private var trackingJob: Job? = null
    private var lastLivePredictionAt: Long = 0L
    private var lastDetailsSyncAt: Long = 0L

    init {
        load()
    }

    fun load(refreshFromNetwork: Boolean = true) {
        viewModelScope.launch {
            guarded {
                val detail = container.matchRepository.matchDetail(matchId)
                _state.update { it.copy(detail = detail, isLoading = false) }
                if (detail != null) {
                    val headToHead = container.matchRepository.headToHead(
                        matchId = matchId,
                        homeTeamId = detail.summary.home.id,
                        awayTeamId = detail.summary.away.id,
                    )
                    _state.update { it.copy(headToHead = headToHead) }
                }
                if (refreshFromNetwork) {
                    val entity = container.matchRepository.matchEntity(matchId)
                    if (entity != null) {
                        val refreshed = if (syncFresh()) {
                            container.matchRepository.matchDetail(matchId)
                        } else {
                            null
                        }
                        if (refreshed != null) _state.update { it.copy(detail = refreshed) }
                    }
                }
                // A refresh that failed used to end here, leaving the analysis empty even
                // though the stored data was enough to predict. It is computed either way.
                predict()
                maybeStartTracking()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            guarded {
                _state.update { it.copy(isRefreshing = true) }
                val refreshed = if (syncFresh()) container.matchRepository.matchDetail(matchId) else null
                _state.update { it.copy(detail = refreshed ?: it.detail, isRefreshing = false) }
                predict()
                maybeStartTracking()
            }
        }
    }

    fun predict(forcePersist: Boolean = false) {
        if (_state.value.isPredicting) return
        _state.update { it.copy(isPredicting = true) }
        viewModelScope.launch {
            guarded {
                val entity = container.matchRepository.matchEntity(matchId)
                val isLive = entity != null && parseStatus(entity.status).isLive
                val now = container.clock.nowEpochMs()
                // Live screens poll every few minutes; only one stored snapshot per 15 minutes
                // keeps the local history readable and the database small.
                val persist = !isLive || PredictionPolicy.shouldStoreLive(now, lastLivePredictionAt, forcePersist)
                when (val result = container.predictionRepository.predict(matchId, persist = persist)) {
                    is Outcome.Failure -> _state.update {
                        it.copy(isPredicting = false, prediction = null, lastError = result.error)
                    }
                    is Outcome.Success -> {
                        if (isLive && persist) lastLivePredictionAt = now
                        _state.update { it.copy(isPredicting = false, prediction = result.value, lastError = null) }
                    }
                }
            }
        }
    }

    /**
     * Runs a unit of screen work and converts any unexpected exception into an error
     * state. Without this, an exception inside a screen coroutine reaches the default
     * Android handler and the process is killed — the user sees the app close with no
     * message and no way to report what happened.
     */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.update {
                it.copy(
                    isLoading = false,
                    isPredicting = false,
                    isRefreshing = false,
                    lastError = AppError(
                        code = com.footballai.app.core.ErrorCode.UNKNOWN,
                        detail = "${error::class.java.simpleName}: ${error.message}",
                        cause = error,
                    ),
                )
            }
        }
    }

    /**
     * Downloads the international results archive, then retries the prediction.
     *
     * This is the one action that can turn "not enough history" into a real forecast for
     * a national-team fixture, so it lives next to the error it fixes rather than in a
     * settings screen the user has no reason to visit.
     */
    fun importNationalHistory() {
        if (_state.value.isImportingHistory) return
        _state.update { it.copy(isImportingHistory = true, lastError = null) }
        viewModelScope.launch {
            guarded {
                when (val result = container.nationalHistoryRepository.importArchive()) {
                    is Outcome.Failure -> _state.update {
                        it.copy(isImportingHistory = false, lastError = result.error)
                    }
                    is Outcome.Success -> {
                        _state.update { it.copy(isImportingHistory = false) }
                        predict()
                    }
                }
            }
        }
    }

    fun setLiveTrackingEnabled(enabled: Boolean) {
        _state.update { it.copy(liveTrackingEnabled = enabled) }
        if (!enabled) {
            stopTracking()
        } else {
            viewModelScope.launch { maybeStartTracking() }
        }
    }

    fun onScreenVisible() {
        screenVisible = true
        if (freshnessJob?.isActive != true) freshnessJob = viewModelScope.launch {
            while (isActive) {
                val match = container.matchRepository.matchEntity(matchId)
                // A source that cannot refresh one fixture will never make the live row
                // fresher, so calling it stale every ten seconds would be a permanent
                // error the user can do nothing about.
                if (match != null && parseStatus(match.status).isLive &&
                    container.syncRepository.supportsMatchRefresh(match.providerId) &&
                    container.clock.nowEpochMs() - match.lastSyncedAt > PredictionPolicy.LIVE_TTL_MS) {
                    _state.update { it.copy(prediction = null, lastError = AppError(com.footballai.app.core.ErrorCode.STALE_DATA)) }
                }
                delay(10_000L)
            }
        }
        viewModelScope.launch { maybeStartTracking() }
    }

    fun onScreenHidden() {
        screenVisible = false
        freshnessJob?.cancel()
        freshnessJob = null
        stopTracking()
    }

    fun dismissError() = _state.update { it.copy(lastError = null) }

    private suspend fun maybeStartTracking() {
        val settings = container.settingsRepository.current()
        val entity = container.matchRepository.matchEntity(matchId) ?: return
        val isLive = parseStatus(entity.status).isLive
        val enabled = screenVisible && _state.value.liveTrackingEnabled && settings.livePollingEnabled &&
            isLive && container.syncRepository.supportsMatchRefresh(entity.providerId)
        if (!enabled) {
            stopTracking()
            return
        }
        if (trackingJob?.isActive == true) return
        trackingJob = viewModelScope.launch {
            _state.update { it.copy(isLiveTracking = true) }
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val current = container.matchRepository.matchEntity(matchId) ?: break
                if (!parseStatus(current.status).isLive) break
                if (!syncFresh()) continue
                val detail = container.matchRepository.matchDetail(matchId)
                _state.update { it.copy(detail = detail) }
                predict()
            }
            _state.update { it.copy(isLiveTracking = false) }
        }
    }

    private suspend fun syncFresh(): Boolean {
        val entity = container.matchRepository.matchEntity(matchId)
        val supported = entity != null && container.syncRepository.supportsMatchRefresh(entity.providerId)
        _state.update { it.copy(liveTrackingSupported = supported) }
        // A source that publishes whole matchdays cannot re-read one fixture. There is
        // nothing to refresh and nothing went wrong: the stored row is the truth until
        // the next matchday sync replaces it.
        if (!supported) {
            _state.update { it.copy(refreshWarning = null) }
            return false
        }
        when (val result = container.syncRepository.refreshMatch(matchId)) {
            is Outcome.Failure -> {
                // Keep the detail and the prediction: the user can still read the
                // analysis of what is stored, and the strip says the refresh failed.
                _state.update { it.copy(refreshWarning = result.error, isLoading = false) }
                return false
            }
            is Outcome.Success -> _state.update { it.copy(refreshWarning = null, lastError = null) }
        }
        val now = container.clock.nowEpochMs()
        if (now - lastDetailsSyncAt >= DETAILS_INTERVAL_MS) {
            // Missing/old statistics are excluded by PredictionRepository. A details
            // failure must not prevent a fresh score-only prediction.
            container.syncRepository.syncMatchDetails(matchId, force = true)
            lastDetailsSyncAt = now
        }
        return true
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        _state.update { it.copy(isLiveTracking = false) }
    }

    override fun onCleared() {
        super.onCleared()
        stopTracking()
    }

    companion object {
        /** One minute; quota guard still enforces provider limits. */
        const val POLL_INTERVAL_MS = TimeConstants.MINUTE_MS
        private const val DETAILS_INTERVAL_MS = 20 * TimeConstants.MINUTE_MS
    }
}

/** Convenience: the kind of prediction that should be shown for a given status. */
fun PredictionView.isLivePrediction(): Boolean = kind == PredictionKind.LIVE
