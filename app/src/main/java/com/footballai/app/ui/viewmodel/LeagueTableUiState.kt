package com.footballai.app.ui.viewmodel

import com.footballai.app.data.repository.MatchRepository
import com.footballai.app.domain.model.TableVenue
import com.footballai.app.ui.components.TableRow

/**
 * A table as a screen draws it, with where it came from.
 *
 * The table page, a competition's table tab and a club's table tab all draw the same thing, so
 * they read the same state: rows plus the two facts that keep it honest — whether it was
 * counted from stored results, and how many matches that count came from.
 */
data class LeagueTableUiState(
    val rows: List<TableRow> = emptyList(),
    val season: Int? = null,
    val isComputed: Boolean = false,
    val matches: Int = 0,
    val venue: TableVenue = TableVenue.ALL,
    /** Only a counted table can be split by venue; a published one arrives combined. */
    val canSplitByVenue: Boolean = false,
    /** A knockout tie has no positions, whatever rows exist. */
    val isKnockout: Boolean = false,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/** Reads one competition's table, already shaped for the screens that draw it. */
internal suspend fun MatchRepository.leagueTableUi(
    competitionId: String,
    venue: TableVenue = TableVenue.ALL,
): LeagueTableUiState {
    val view = tableFor(competitionId, venue) ?: return LeagueTableUiState(venue = venue)
    return LeagueTableUiState(
        rows = view.rows.map { row ->
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
                // A row borrowed from another source may have no page in this app.
                navigable = teamEntity(row.teamId) != null,
            )
        },
        season = view.season,
        isComputed = view.isComputed,
        matches = view.computedMatches,
        venue = venue,
        canSplitByVenue = view.isComputed,
        isKnockout = view.isKnockout,
    )
}
