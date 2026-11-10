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
import kotlinx.coroutines.flow.map
import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


data class MatchesUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val lastError: AppError? = null,
    val filter: MatchListFilter = MatchListFilter.TODAY,
    val competitionId: String? = null,
    val matches: List<MatchSummary> = emptyList(),
    val competitions: List<Competition> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class MatchesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(MatchesUiState())
    val state: StateFlow<MatchesUiState> = _state.asStateFlow()

    private val refreshVersion = MutableStateFlow(0)
    private val selection = MutableStateFlow(MatchListFilter.TODAY to null as String?)

    init {
        viewModelScope.launch {
            container.matchRepository.observeCompetitions().collect { competitions ->
                _state.update { it.copy(competitions = competitions) }
            }
        }
        viewModelScope.launch {
            combine(selection, refreshVersion) { selected, _ -> selected }
                .flatMapLatest { (filter, competitionId) ->
                    val (from, to) = MatchListRules.range(filter, container.clock.nowEpochMs())
                    container.matchRepository.observeBetween(from, to).map { matches ->
                        Triple(filter, competitionId, MatchListRules.select(matches, filter, competitionId))
                    }
                }.collect { (filter, competitionId, matches) ->
                    _state.update { it.copy(matches = matches, isLoading = false,
                        filter = filter, competitionId = competitionId) }
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
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true, lastError = null) }
        viewModelScope.launch {
            try {
                val providerId = container.providerRegistry.activeProviderId()
                val result = if (providerId == null) {
                    Outcome.Failure(AppError(ErrorCode.NOT_FOUND))
                } else container.syncRepository.syncFixtures(providerId)
                val error = when (result) {
                    is Outcome.Failure -> result.error
                    is Outcome.Success -> if (result.value.errors.isEmpty()) null
                        else AppError(runCatching { ErrorCode.valueOf(result.value.errors.first().substringAfterLast(": ")) }.getOrDefault(ErrorCode.REQUEST_FAILED))
                }
                _state.update { it.copy(lastError = error) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(lastError = AppError(ErrorCode.UNKNOWN, cause = error)) }
            } finally {
                refreshVersion.value += 1
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }
}
