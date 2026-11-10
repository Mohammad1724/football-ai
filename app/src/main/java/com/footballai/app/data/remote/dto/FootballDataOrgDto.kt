package com.footballai.app.data.remote.dto

import kotlinx.serialization.Serializable

/** football-data.org v4 payloads. */

@Serializable
internal data class FdoCompetitionsResponse(
    val competitions: List<FdoCompetition> = emptyList(),
)

@Serializable
internal data class FdoCompetition(
    val id: Int? = null,
    val code: String? = null,
    val name: String? = null,
    val area: FdoArea? = null,
    val emblem: String? = null,
    val currentSeason: FdoSeason? = null,
    val numberOfAvailableSeasons: Int? = null,
)

@Serializable
internal data class FdoArea(
    val id: Int? = null,
    val name: String? = null,
)

@Serializable
internal data class FdoSeason(
    val id: Int? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val currentMatchday: Int? = null,
)

@Serializable
internal data class FdoMatchesResponse(
    val matches: List<FdoMatch> = emptyList(),
    val resultSet: FdoResultSet? = null,
)

@Serializable
internal data class FdoResultSet(
    val count: Int? = null,
    val competitions: String? = null,
)

@Serializable
internal data class FdoMatch(
    val id: Long? = null,
    val utcDate: String? = null,
    val status: String? = null,
    val matchday: Int? = null,
    val venue: String? = null,
    val competition: FdoCompetition? = null,
    val homeTeam: FdoTeam? = null,
    val awayTeam: FdoTeam? = null,
    val score: FdoScore? = null,
)

@Serializable
internal data class FdoTeam(
    val id: Long? = null,
    val name: String? = null,
    val shortName: String? = null,
    val tla: String? = null,
    val crest: String? = null,
)

@Serializable
internal data class FdoScore(
    val winner: String? = null,
    val duration: String? = null,
    val fullTime: FdoGoals? = null,
    val halfTime: FdoGoals? = null,
)

@Serializable
internal data class FdoGoals(
    val home: Int? = null,
    val away: Int? = null,
)

@Serializable
internal data class FdoStandingsResponse(
    val standings: List<FdoStandingGroup> = emptyList(),
)

@Serializable
internal data class FdoStandingGroup(
    val stage: String? = null,
    val type: String? = null,
    val group: String? = null,
    val table: List<FdoStandingRow> = emptyList(),
)

@Serializable
internal data class FdoStandingRow(
    val position: Int? = null,
    val team: FdoTeam? = null,
    val playedGames: Int? = null,
    val form: String? = null,
    val points: Int? = null,
    val goalDifference: Int? = null,
)
