package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.core.TimeConstants
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.Competition
import com.footballai.app.domain.model.MatchSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [RANGE] is the default view: it is the window the user actually wants to see at a
 * glance (recent results plus everything coming up), so betting decisions do not
 * require tapping through several chips. The narrower filters stay available for
 * anyone who wants to zoom in.
 */
enum class MatchListFilter { RANGE, TODAY, TOMORROW, WEEK, FINISHED }

data class MatchesUiState(
    val isLoading: Boolean = true,
    val filter: MatchListFilter = MatchListFilter.RANGE,
    val competitionId: String? = null,
    val matches: List<MatchSummary> = emptyList(),
    val competitions: List<Competition> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class MatchesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(MatchesUiState())
    val state: StateFlow<MatchesUiState> = _state.asStateFlow()

    private val selection = MutableStateFlow(MatchListFilter.RANGE to null as String?)

    init {
        viewModelScope.launch {
            container.matchRepository.observeCompetitions().collect { competitions ->
                _state.update { it.copy(competitions = competitions) }
            }
        }
        viewModelScope.launch {
            selection.flatMapLatest { (filter, competitionId) ->
                val now = container.clock.nowEpochMs()
                val dayStart = now - (now % TimeConstants.DAY_MS)
                val (from, to) = when (filter) {
                    // Today (or up to 2 days back, for matches just finished) through the
                    // next 10 days — the window the user actually wants to scan before
                    // placing a bet, without having to flip through separate chips.
                    MatchListFilter.RANGE ->
                        dayStart - RANGE_DAYS_BACK * TimeConstants.DAY_MS to
                            dayStart + (RANGE_DAYS_AHEAD + 1) * TimeConstants.DAY_MS
                    MatchListFilter.TODAY -> dayStart to dayStart + TimeConstants.DAY_MS
                    MatchListFilter.TOMORROW -> dayStart + TimeConstants.DAY_MS to dayStart + 2 * TimeConstants.DAY_MS
                    MatchListFilter.WEEK -> dayStart to dayStart + 7 * TimeConstants.DAY_MS
                    MatchListFilter.FINISHED -> now - 14 * TimeConstants.DAY_MS to now
                }
                container.matchRepository.observeBetween(from, to).let { flow ->
                    if (competitionId == null) {
                        flow
                    } else {
                        kotlinx.coroutines.flow.flow {
                            flow.collect { list ->
                                emit(list.filter { it.competitionId == competitionId })
                            }
                        }
                    }
                }
            }.collect { matches ->
                _state.update {
                    it.copy(
                        matches = matches,
                        isLoading = false,
                        filter = selection.value.first,
                        competitionId = selection.value.second,
                    )
                }
            }
        }
    }

    fun setFilter(filter: MatchListFilter) {
        selection.value = filter to selection.value.second
    }

    fun setCompetition(competitionId: String?) {
        selection.value = selection.value.first to competitionId
    }

    fun refresh() {
        viewModelScope.launch {
            val providerId = container.providerRegistry.activeProviderId() ?: return@launch
            container.syncRepository.syncFixtures(
                providerId,
                daysBack = maxOf(7, RANGE_DAYS_BACK),
                daysAhead = maxOf(21, RANGE_DAYS_AHEAD),
            )
        }
    }

    companion object {
        /** How far back the default [MatchListFilter.RANGE] window reaches. */
        const val RANGE_DAYS_BACK = 2

        /** How far ahead the default [MatchListFilter.RANGE] window reaches. */
        const val RANGE_DAYS_AHEAD = 10
    }
}
