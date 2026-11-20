package com.footballai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.LeagueStats
import com.footballai.app.domain.model.TableVenue
import com.footballai.app.ui.components.TableRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CompetitionTableState(
    val competitionName: String = "",
    /** The league's own home/draw/away figures, when the app holds results for it. */
    val leagueStats: LeagueStats? = null,
    /** Rows, season and provenance: the same shape the other two table screens read. */
    val table: LeagueTableUiState = LeagueTableUiState(),
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val rows: List<TableRow> get() = table.rows
    val season: Int? get() = table.season
    val isEmpty: Boolean get() = table.isEmpty
    val isComputed: Boolean get() = table.isComputed
    val computedMatches: Int get() = table.matches
    val isKnockout: Boolean get() = table.isKnockout
    val venue: TableVenue get() = table.venue
    val canSplitByVenue: Boolean get() = table.canSplitByVenue
}

/**
 * The table of one tournament.
 *
 * The fixtures screen groups matches under a pinned name; tapping that name lands here. The
 * table is read from what the sync already stored and, when nothing is stored, one refresh
 * is asked for — never a table from a different competition, and never a guessed season.
 */
internal fun shouldRefreshCompetitionTable(refresh: Boolean, force: Boolean, table: LeagueTableUiState): Boolean =
    refresh && (force || table.isEmpty || table.isComputed)

class CompetitionTableViewModel(
    private val container: AppContainer,
    private val competitionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(CompetitionTableState())
    val state: StateFlow<CompetitionTableState> = _state.asStateFlow()

    init {
        load(refresh = true)
    }

    fun retry() = load(refresh = true, force = true)

    /**
     * Switches the table between all matches, home matches and away matches.
     *
     * Only offered for a counted table: a published table arrives combined, and splitting one
     * would mean splitting a number the league itself never split. Nothing is downloaded here —
     * the rows are counted again from what is already stored.
     */
    fun setVenue(venue: TableVenue) {
        if (venue == _state.value.table.venue) return
        _state.update { it.copy(table = it.table.copy(venue = venue)) }
        load(refresh = false)
    }

    private fun load(refresh: Boolean, force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val competition = container.matchRepository.competitionEntity(competitionId)
                var table = container.matchRepository.leagueTableUi(competitionId, _state.value.table.venue)
                if (shouldRefreshCompetitionTable(refresh, force, table) && competition != null) {
                    // Nothing published: ask once — from the competition's own source when it
                    // publishes a table, otherwise the same league's table from the one source
                    // that does. Asking is throttled inside the sync, so opening the page again
                    // does not spend a request every time.
                    try {
                        container.syncRepository.refreshTableFor(competitionId, force = force)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Keep the locally stored table when the source cannot be reached.
                    }
                    table = container.matchRepository.leagueTableUi(competitionId, _state.value.table.venue)
                }
                // A published table is the league's own answer; when there is none, a table
                // counted from the results in this app is better than an apology, as long as
                // it is labelled as what it is and says how many matches it counted.
                val stats = container.matchRepository.leagueStats(competitionId, table.season)
                _state.update { previous ->
                    previous.copy(
                        competitionName = competition?.name ?: previous.competitionName,
                        isLoading = false,
                        table = table,
                        leagueStats = stats,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = error.message)
                }
            }
        }
    }
}
