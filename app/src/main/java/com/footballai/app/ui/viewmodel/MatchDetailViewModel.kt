package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.core.AppError
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeConstants
import com.footballai.app.data.local.mapper.parseStatus
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
)

class MatchDetailViewModel(
    private val container: AppContainer,
    private val matchId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(MatchDetailUiState())
    val state: StateFlow<MatchDetailUiState> = _state.asStateFlow()

    private var trackingJob: Job? = null
    private var lastLivePredictionAt: Long = 0L
    private var lastDetailsSyncAt: Long = 0L

    init {
        load()
    }

    fun load(refreshFromNetwork: Boolean = true) {
        viewModelScope.launch {
            val detail = container.matchRepository.matchDetail(matchId)
            _state.update { it.copy(detail = detail, isLoading = detail == null) }
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
                    container.syncRepository.refreshMatch(matchId)
                    container.syncRepository.syncMatchDetails(matchId)
                    lastDetailsSyncAt = container.clock.nowEpochMs()
                    val refreshed = container.matchRepository.matchDetail(matchId)
                    _state.update { it.copy(detail = refreshed ?: it.detail) }
                }
            }
            predict()
            maybeStartTracking()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            container.syncRepository.refreshMatch(matchId)
            container.syncRepository.syncMatchDetails(matchId, force = true)
            val detail = container.matchRepository.matchDetail(matchId)
            _state.update { it.copy(detail = detail, isRefreshing = false) }
            predict()
            maybeStartTracking()
        }
    }

    fun predict(forcePersist: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(isPredicting = true, lastError = null) }
            val entity = container.matchRepository.matchEntity(matchId)
            val isLive = entity != null && parseStatus(entity.status).isLive
            val now = container.clock.nowEpochMs()
            // Live screens poll every few minutes; only one stored snapshot per 15 minutes
            // keeps the local history readable and the database small.
            val persist = forcePersist || !isLive || now - lastLivePredictionAt > LIVE_PERSIST_INTERVAL_MS
            when (val result = container.predictionRepository.predict(matchId, persist = persist)) {
                is Outcome.Failure -> _state.update { it.copy(isPredicting = false, lastError = result.error) }
                is Outcome.Success -> {
                    if (isLive) lastLivePredictionAt = now
                    _state.update { it.copy(isPredicting = false, prediction = result.value) }
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
        viewModelScope.launch { maybeStartTracking() }
    }

    fun onScreenHidden() = stopTracking()

    fun dismissError() = _state.update { it.copy(lastError = null) }

    private suspend fun maybeStartTracking() {
        val settings = container.settingsRepository.current()
        val entity = container.matchRepository.matchEntity(matchId) ?: return
        val isLive = parseStatus(entity.status).isLive
        val enabled = _state.value.liveTrackingEnabled && settings.livePollingEnabled && isLive
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
                container.syncRepository.refreshMatch(matchId)
                val now = container.clock.nowEpochMs()
                if (now - lastDetailsSyncAt > DETAILS_INTERVAL_MS) {
                    container.syncRepository.syncMatchDetails(matchId, force = true)
                    lastDetailsSyncAt = now
                }
                val detail = container.matchRepository.matchDetail(matchId)
                _state.update { it.copy(detail = detail) }
                predict()
            }
            _state.update { it.copy(isLiveTracking = false) }
        }
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
        /** Three minutes balances freshness against the free-plan request budget. */
        const val POLL_INTERVAL_MS = 3 * TimeConstants.MINUTE_MS
        private const val DETAILS_INTERVAL_MS = 20 * TimeConstants.MINUTE_MS
        private const val LIVE_PERSIST_INTERVAL_MS = 15 * TimeConstants.MINUTE_MS
    }
}

/** Convenience: the kind of prediction that should be shown for a given status. */
fun PredictionView.isLivePrediction(): Boolean = kind == PredictionKind.LIVE
