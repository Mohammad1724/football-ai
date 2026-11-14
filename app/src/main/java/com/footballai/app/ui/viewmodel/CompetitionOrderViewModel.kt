package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.catalog.CompetitionOrder
import com.footballai.app.domain.model.Competition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class CompetitionOrderUiState(
    val isLoading: Boolean = true,
    val competitions: List<Competition> = emptyList(),
    /** True once the user has arranged something, so "reset" is offered only then. */
    val hasCustomOrder: Boolean = false,
)

/**
 * Manual competition order.
 *
 * The app knows nothing about which league a user cares about, so it does not guess:
 * competitions are listed in the user's order, then alphabetically, and moving one up or
 * down is saved immediately — there is no "save" button to forget.
 */
class CompetitionOrderViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CompetitionOrderUiState())
    val state: StateFlow<CompetitionOrderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                container.matchRepository.observeCompetitions(),
                container.settingsRepository.settings,
            ) { competitions, settings ->
                CompetitionOrderUiState(
                    isLoading = false,
                    competitions = CompetitionOrder.sorted(competitions, settings.competitionOrder),
                    hasCustomOrder = settings.competitionOrder.isNotEmpty(),
                )
            }.collect { next -> _state.value = next }
        }
    }

    /**
     * Saves the order the user dropped the rows into. The whole list is written, so a
     * tournament the user never touched keeps its place instead of drifting.
     */
    fun applyOrder(ids: List<String>) {
        viewModelScope.launch { container.settingsRepository.setCompetitionOrder(ids) }
    }

    fun move(competitionId: String, up: Boolean) {
        viewModelScope.launch {
            val order = container.settingsRepository.current().competitionOrder
            val allIds = _state.value.competitions.map { it.id }
            val moved = CompetitionOrder.move(allIds, order, competitionId, up)
            if (moved != allIds) container.settingsRepository.setCompetitionOrder(moved)
        }
    }

    fun reset() {
        viewModelScope.launch { container.settingsRepository.setCompetitionOrder(emptyList()) }
    }
}
