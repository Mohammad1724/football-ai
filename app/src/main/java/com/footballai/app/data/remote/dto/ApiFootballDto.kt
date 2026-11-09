package com.footballai.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * API-Football (api-sports.io) payloads.
 *
 * Every field is nullable because the provider omits keys depending on the plan and
 * the competition; the mapper treats missing data as "unknown" rather than zero.
 */

@Serializable
internal data class AfEnvelope<T>(
    val results: Int = 0,
    val errors: JsonElement? = null,
    val response: List<T> = emptyList(),
)

@Serializable
internal data class AfFixtureResponse(
    val fixture: AfFixtureInfo? = null,
    val league: AfLeagueInfo? = null,
    val teams: AfTeamsInfo? = null,
    val goals: AfGoals? = null,
    val score: AfScore? = null,
)

@Serializable
internal data class AfFixtureInfo(
    val id: Long? = null,
    val date: String? = null,
    val status: AfStatus? = null,
    val venue: AfVenue? = null,
)

@Serializable
internal data class AfStatus(
    val long: String? = null,
    val short: String? = null,
    val elapsed: Int? = null,
)

@Serializable
internal data class AfVenue(
    val name: String? = null,
    val city: String? = null,
)

@Serializable
internal data class AfLeagueInfo(
    val id: Int? = null,
    val name: String? = null,
    val country: String? = null,
    val logo: String? = null,
    val season: Int? = null,
    val round: String? = null,
)

@Serializable
internal data class AfTeamsInfo(
    val home: AfTeam? = null,
    val away: AfTeam? = null,
)

@Serializable
internal data class AfTeam(
    val id: Long? = null,
    val name: String? = null,
    val logo: String? = null,
)

@Serializable
internal data class AfGoals(
    val home: Int? = null,
    val away: Int? = null,
)

@Serializable
internal data class AfScore(
    val halftime: AfGoals? = null,
    val fulltime: AfGoals? = null,
)

@Serializable
internal data class AfStatisticsResponse(
    val team: AfTeam? = null,
    val statistics: List<AfStatistic> = emptyList(),
)

@Serializable
internal data class AfStatistic(
    val type: String? = null,
    val value: JsonElement? = null,
)

@Serializable
internal data class AfEventResponse(
    val time: AfTime? = null,
    val team: AfTeam? = null,
    val player: AfPlayerRef? = null,
    val assist: AfPlayerRef? = null,
    val type: String? = null,
    val detail: String? = null,
    val comments: String? = null,
)

@Serializable
internal data class AfTime(
    val elapsed: Int? = null,
    val extra: Int? = null,
)

@Serializable
internal data class AfPlayerRef(
    val id: Long? = null,
    val name: String? = null,
)

@Serializable
internal data class AfLineupResponse(
    val team: AfTeam? = null,
    val formation: String? = null,
    @SerialName("startXI") val startXi: List<AfLineupSlot> = emptyList(),
    val substitutes: List<AfLineupSlot> = emptyList(),
)

@Serializable
internal data class AfLineupSlot(
    val player: AfLineupPlayer? = null,
)

@Serializable
internal data class AfLineupPlayer(
    val id: Long? = null,
    val name: String? = null,
    val number: Int? = null,
    val pos: String? = null,
)

@Serializable
internal data class AfStandingsResponse(
    val league: AfStandingsLeague? = null,
)

@Serializable
internal data class AfStandingsLeague(
    val id: Int? = null,
    val season: Int? = null,
    val standings: List<List<AfStandingRow>> = emptyList(),
)

@Serializable
internal data class AfStandingRow(
    val rank: Int? = null,
    val team: AfTeam? = null,
    val points: Int? = null,
    val goalsDiff: Int? = null,
    val group: String? = null,
    val form: String? = null,
    val all: AfStandingAll? = null,
)

@Serializable
internal data class AfStandingAll(
    val played: Int? = null,
    val win: Int? = null,
    val draw: Int? = null,
    val lose: Int? = null,
)

@Serializable
internal data class AfLeagueEntry(
    val league: AfLeagueInfo? = null,
    val country: AfCountry? = null,
    val seasons: List<AfSeason> = emptyList(),
)

@Serializable
internal data class AfCountry(
    val name: String? = null,
    val code: String? = null,
    val flag: String? = null,
)

@Serializable
internal data class AfSeason(
    val year: Int? = null,
    val current: Boolean? = null,
)
