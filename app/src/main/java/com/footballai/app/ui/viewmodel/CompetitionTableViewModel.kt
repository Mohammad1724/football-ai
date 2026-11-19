package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.data.local.entity.StandingEntity
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.LeagueStats
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
    /** Null when the source published no such column; the row prints a dash. */
    val wins: Int? = null,
    val draws: Int? = null,
    val losses: Int? = null,
    val goalsFor: Int? = null,
    val goalsAgainst: Int? = null,
    val form: String?,
    /** A row whose club is stored here can be opened; a borrowed row may have no page. */
    val navigable: Boolean = false,
)

data class CompetitionTableState(
    val competitionName: String = "",
    /** The league's own home/draw/away figures, when the app holds results for it. */
    val leagueStats: LeagueStats? = null,
    val season: Int? = null,
    val rows: List<TableRow> = emptyList(),
    val isLoading: Boolean = true,
    /** A table is missing, not broken: the sync has simply never stored one. */
    val isEmpty: Boolean = false,
    /** True when the rows were counted from stored results, not read from a published table. */
    val isComputed: Boolean = false,
    /** How many finished matches a computed table was counted from. */
    val computedMatches: Int = 0,
    /** A knockout tie has no positions: the screen says that instead of asking for a table. */
    val isKnockout: Boolean = false,
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
                var table = container.matchRepository.leagueTable(competitionId)
                if (table == null && refresh && competition != null) {
                    // Nothing stored: ask for it once — from the competition's own source
                    // when it publishes a table, otherwise the same league's table from the
                    // one source that does.
                    runCatching { container.syncRepository.refreshTableFor(competitionId) }
                    table = container.matchRepository.leagueTable(competitionId)
                }
                // Still nothing published, but the app holds results for this league: a table
                // counted from those is better than an apology, as long as it is labelled as
                // what it is and says how many matches it was counted from.
                val computed = if (table == null) {
                    container.matchRepository.computedTable(competitionId)
                } else {
                    null
                }
                // The rows the screen draws: the published table when there is one, otherwise
                // the counted one. (A `LeagueTable` is not a list, so `table ?: computed` would
                // be an `Any` and nothing below it would compile.)
                val shown: List<StandingEntity> = table?.rows ?: computed?.rows.orEmpty()
                // Counted over the same season the table is drawn for, so the two agree.
                val stats = container.matchRepository.leagueStats(
                    competitionId, table?.season ?: computed?.season,
                )
                _state.update { previous ->
                    previous.copy(
                        competitionName = competition?.name ?: previous.competitionName,
                        season = table?.season ?: computed?.season,
                        rows = shown.map { row ->
                            TableRow(
                                rank = row.rank,
                                teamId = row.teamId,
                                teamName = row.teamName,
                                crestUrl = row.crestUrl,
                                played = row.played,
                                goalDifference = row.goalDifference,
                                points = row.points,
                                wins = row.wins,
                                draws = row.draws,
                                losses = row.losses,
                                goalsFor = row.goalsFor,
                                goalsAgainst = row.goalsAgainst,
                                form = row.form,
                                navigable = container.matchRepository.teamEntity(row.teamId) != null,
                            )
                        },
                        isLoading = false,
                        isEmpty = shown.isEmpty(),
                        isComputed = computed != null,
                        computedMatches = computed?.matches ?: 0,
                        isKnockout = competition?.competitionClass
                            ?.contains("LEAGUE", ignoreCase = true) == false,
                        leagueStats = stats,
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
