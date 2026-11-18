package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One line of a league table, as the screen draws it. */
data class TableRow(
    val rank: Int,
    val teamId: String,
    val teamName: String,
    val crestUrl: String?,
    val played: Int,
    val goalDifference: Int,
    val points: Int,
    val form: String?,
)

data class CompetitionTableState(
    val competitionName: String = "",
    val season: Int? = null,
    val rows: List<TableRow> = emptyList(),
    val isLoading: Boolean = true,
    /** A table is missing, not broken: the sync has simply never stored one. */
    val isEmpty: Boolean = false,
    val error: String? = null,
)

/**
 * The table of one tournament.
 *
 * The fixtures screen groups matches under a pinned name; tapping that name lands here. The
 * table is read from what the sync already stored and, when nothing is stored, one refresh
 * is asked for — never a table from a different competition, and never a guessed season.
 */
class CompetitionTableViewModel(
    private val container: AppContainer,
    private val competitionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(CompetitionTableState())
    val state: StateFlow<CompetitionTableState> = _state.asStateFlow()

    init {
        load(refresh = true)
    }

    fun retry() = load(refresh = true)

    private fun load(refresh: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val competition = container.matchRepository.competitionEntity(competitionId)
                val table = container.matchRepository.leagueTable(competitionId)
                if (table == null && refresh && competition != null) {
                    // Nothing stored: ask for it once. A failure here is not an error the
                    // reader can do anything about — the screen still says "no table yet".
                    runCatching {
                        container.syncRepository.refreshStandings(
                            competition.providerId, listOf(competition.providerKey),
                        )
                    }
                }
                val shown = table ?: container.matchRepository.leagueTable(competitionId)
                _state.update { previous ->
                    previous.copy(
                        competitionName = competition?.name ?: previous.competitionName,
                        season = shown?.season,
                        rows = shown?.rows.orEmpty().map { row ->
                            TableRow(
                                rank = row.rank,
                                teamId = row.teamId,
                                teamName = row.teamName,
                                crestUrl = row.crestUrl,
                                played = row.played,
                                goalDifference = row.goalDifference,
                                points = row.points,
                                form = row.form,
                            )
                        },
                        isLoading = false,
                        isEmpty = shown == null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(isLoading = false, isEmpty = true, error = error.message)
                }
            }
        }
    }
}
