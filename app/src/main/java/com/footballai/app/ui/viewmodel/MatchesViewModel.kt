package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.Competition
import com.footballai.app.domain.model.MatchSummary
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Only recent football is shown here; future fixture filters deliberately do not exist. */
private data class MatchSelection(
    val dayOffset: Int = 0,
    val competitionId: String? = null,
    val followedOnly: Boolean = false,
    val liveOnly: Boolean = false,
)

data class MatchesUiState(
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val selectedDayOffset: Int = 0,
    val competitionId: String? = null,
    val followedOnly: Boolean = false,
    val liveOnly: Boolean = false,
    val matches: List<MatchSummary> = emptyList(),
    val competitions: List<Competition> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class MatchesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(MatchesUiState())
    val state: StateFlow<MatchesUiState> = _state.asStateFlow()

    private val selection = MutableStateFlow(MatchSelection())

    init {
        viewModelScope.launch {
            selection.flatMapLatest { selected ->
                val now = container.clock.nowEpochMs()
                val (from, to) = localDayWindow(now, selected.dayOffset)
                combine(
                    container.matchRepository.observeBetween(from, to),
                    container.matchRepository.observeCompetitions(),
                ) { matches, competitions ->
                    val followedIds = competitions.filter { it.isFollowed }.map { it.id }.toSet()
                    val visible = matches.filter { match ->
                        (selected.competitionId == null || match.competitionId == selected.competitionId) &&
                            (!selected.followedOnly || match.competitionId in followedIds) &&
                            (!selected.liveOnly || match.status.isLive)
                    }.sortedBy { it.kickoffEpochMs }
                    visible to competitions
                }
            }.collect { (matches, competitions) ->
                val selected = selection.value
                _state.update {
                    it.copy(
                        matches = matches,
                        competitions = competitions,
                        isLoading = false,
                        selectedDayOffset = selected.dayOffset,
                        competitionId = selected.competitionId,
                        followedOnly = selected.followedOnly,
                        liveOnly = selected.liveOnly,
                    )
                }
            }
        }
    }

    fun selectDay(dayOffset: Int) {
        if (dayOffset !in OLDEST_DAY_OFFSET..0) return
        selection.update { it.copy(dayOffset = dayOffset) }
    }

    fun setCompetition(competitionId: String?) {
        selection.update { it.copy(competitionId = competitionId) }
    }

    fun toggleFollowedOnly() {
        selection.update { it.copy(followedOnly = !it.followedOnly) }
    }

    fun toggleLiveOnly() {
        selection.update { it.copy(liveOnly = !it.liveOnly) }
    }

    fun refresh() {
        if (_state.value.isSyncing) return
        viewModelScope.launch {
            _state.update { it.copy(isSyncing = true) }
            try {
                val providerId = container.providerRegistry.activeProviderId() ?: return@launch
                // Include the whole of today and the two previous days. The UI still hides
                // tomorrow if a provider returns it as part of its normal response window.
                container.syncRepository.syncFixtures(
                    providerId = providerId,
                    daysBack = 3,
                    daysAhead = 1,
                )
            } finally {
                _state.update { it.copy(isSyncing = false) }
            }
        }
    }

    companion object {
        const val OLDEST_DAY_OFFSET = -2

        /** Calendar-day boundaries, including DST changes, in the device's time zone. */
        internal fun localDayWindow(
            nowEpochMs: Long,
            dayOffset: Int,
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): Pair<Long, Long> {
            val date = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate().plusDays(dayOffset.toLong())
            val start = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            return start to end
        }
    }
}
