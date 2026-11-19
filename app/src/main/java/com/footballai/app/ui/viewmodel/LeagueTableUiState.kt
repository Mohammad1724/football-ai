package com.footballai.app.ui.viewmodel

import com.footballai.app.data.repository.MatchRepository
import com.footballai.app.di.AppContainer
import com.footballai.app.domain.model.TableVenue
import com.footballai.app.ui.components.TableRow
import kotlinx.coroutines.CancellationException

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

    /**
     * Whether asking the source could give a better table than this one.
     *
     * A published table beats a table counted from a handful of results, and nothing beats
     * nothing — but a home or away view can only ever be counted, so asking for it would be
     * asking for a split the source does not publish.
     */
    val wouldImproveWithFetch: Boolean
        get() = !isKnockout && venue == TableVenue.ALL && (rows.isEmpty() || isComputed)
}

/** Reads one competition's table, already shaped for the screens that draw it. */
internal suspend fun MatchRepository.leagueTableUi(
    competitionId: String,
    venue: TableVenue = TableVenue.ALL,
): LeagueTableUiState {
    val view = tableFor(competitionId, venue) ?: return LeagueTableUiState(
        venue = venue,
        isKnockout = competitionEntity(competitionId)?.competitionClass?.contains("LEAGUE", ignoreCase = true) == false,
    )
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

/**
 * The competition's table as a screen should draw it, asking the source when the device's own
 * copy is missing or only a count.
 *
 * Every table surface used to read whatever happened to be stored: a club page whose league
 * had never been fetched drew a two-row "table" counted from the one result on the device,
 * which reads as the app being wrong about the league. The request is throttled by the sync
 * layer (a league without a published table is not asked twice a day), so calling this on
 * every page open costs one stored read after the first.
 */
internal suspend fun AppContainer.leagueTableUi(
    competitionId: String,
    venue: TableVenue = TableVenue.ALL,
): LeagueTableUiState {
    val stored = matchRepository.leagueTableUi(competitionId, venue)
    if (!stored.wouldImproveWithFetch) return stored
    try {
        syncRepository.refreshTableFor(competitionId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return stored
    }
    // Another page may have completed the same request while this call was throttled.
    return matchRepository.leagueTableUi(competitionId, venue)
}
