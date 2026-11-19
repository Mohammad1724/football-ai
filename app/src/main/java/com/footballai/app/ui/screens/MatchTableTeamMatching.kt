package com.footballai.app.ui.screens

import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.MatchTableHighlight
import com.footballai.app.domain.model.MatchTableMark
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.components.TableRow

/**
 * Table rows may be borrowed from another publisher and have different ids. Prefer an exact
 * id, otherwise use only an exact normalized name or an explicit unambiguous club alias.
 * Require a one-to-one match: never guess between similar clubs, reserve sides or duplicates.
 * This changes presentation only, not persistent team identities or table navigation.
 */
internal object MatchTableTeamMatching {
    fun marks(match: MatchSummary, rows: List<TableRow>): Map<String, MatchTableMark> {
        val fixtureMarks = MatchTableHighlight.forMatch(match.home.id, match.away.id, match.status, match.homeGoals, match.awayGoals)
        if (fixtureMarks.size != 2) return emptyMap()
        val teams = listOf(match.home, match.away)
        val uniqueRowIds = rows.groupingBy { it.teamId }.eachCount().filterValues { it == 1 }.keys
        val candidates = rows.filter { it.teamId.isNotBlank() && it.teamId in uniqueRowIds }
        val matched = mutableMapOf<String, String>()
        for (team in teams) {
            candidates.singleOrNull { it.teamId == team.id }?.let { matched[team.id] = it.teamId }
        }
        val remaining = teams.filter { it.id !in matched }
        val choices = remaining.associate { team ->
            team.id to candidates.filter { it.teamId !in matched.values && equivalent(team, it) }
        }
        for (team in remaining) {
            val row = choices[team.id]?.singleOrNull() ?: continue
            // Two fixture teams may not claim the same row even if each has one candidate.
            if (choices.values.count { options -> options.any { it.teamId == row.teamId } } != 1) continue
            matched[team.id] = row.teamId
        }
        return matched.mapNotNull { (fixtureId, rowId) -> fixtureMarks[fixtureId]?.let { rowId to it } }.toMap()
    }

    private fun equivalent(team: TeamRef, row: TableRow): Boolean {
        val teamName = NameLocalization.normalize(team.name)
        val rowName = NameLocalization.normalize(row.teamName)
        if (teamName.isEmpty() || rowName.isEmpty()) return false
        if (teamName == rowName) return true
        val teamKey = NameLocalization.curatedClubIdentity(team.name) ?: return false
        return teamKey == NameLocalization.curatedClubIdentity(row.teamName)
    }
}
