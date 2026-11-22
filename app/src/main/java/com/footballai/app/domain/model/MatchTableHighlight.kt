package com.footballai.app.domain.model

/** Meaning of a table row in the context of the currently open fixture, not league positions. */
enum class MatchTableMark { TEAM, WIN, LOSS, DRAW, LEADING, TRAILING, LEVEL }

internal object MatchTableHighlight {
    fun forMatch(homeId: String, awayId: String, status: MatchStatus, homeGoals: Int?, awayGoals: Int?): Map<String, MatchTableMark> {
        if (homeId.isBlank() || awayId.isBlank() || homeId == awayId) return emptyMap()
        if ((!status.isLive && !status.isFinished) || homeGoals == null || awayGoals == null || homeGoals < 0 || awayGoals < 0) {
            return mapOf(homeId to MatchTableMark.TEAM, awayId to MatchTableMark.TEAM)
        }
        val comparison = homeGoals.compareTo(awayGoals)
        val home = when {
            comparison == 0 -> if (status.isLive) MatchTableMark.LEVEL else MatchTableMark.DRAW
            comparison > 0 -> if (status.isLive) MatchTableMark.LEADING else MatchTableMark.WIN
            else -> if (status.isLive) MatchTableMark.TRAILING else MatchTableMark.LOSS
        }
        val away = when (home) {
            MatchTableMark.WIN -> MatchTableMark.LOSS
            MatchTableMark.LOSS -> MatchTableMark.WIN
            MatchTableMark.LEADING -> MatchTableMark.TRAILING
            MatchTableMark.TRAILING -> MatchTableMark.LEADING
            else -> home
        }
        return mapOf(homeId to home, awayId to away)
    }
}

/** Equal stored identifiers do not verify the provider actually returned that season. */
internal enum class TableSeasonComparison { UNKNOWN, DIFFERENT, SAME_UNVERIFIED }

internal fun compareTableSeason(matchSeason: Int?, tableSeason: Int?): TableSeasonComparison {
    if (matchSeason == null || tableSeason == null || matchSeason <= 0 || tableSeason <= 0) return TableSeasonComparison.UNKNOWN
    return if (matchSeason != tableSeason) TableSeasonComparison.DIFFERENT else TableSeasonComparison.SAME_UNVERIFIED
}
