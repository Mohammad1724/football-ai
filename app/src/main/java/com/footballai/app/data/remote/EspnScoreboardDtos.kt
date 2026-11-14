package com.footballai.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal view of ESPN's public scoreboard payload.
 *
 * Only the fields the app actually reads are declared and unknown keys are ignored,
 * so an upstream addition cannot break parsing. This is an undocumented endpoint, so
 * every accessor is defensive and a missing field is treated as "unknown", never as 0.
 */
@Serializable
data class EspnScoreboard(
    val events: List<EspnEvent> = emptyList(),
)

@Serializable
data class EspnEvent(
    val id: String? = null,
    val date: String? = null,
    val season: EspnSeason? = null,
    val competitions: List<EspnCompetition> = emptyList(),
)

@Serializable
data class EspnSeason(
    val year: Int? = null,
    val slug: String? = null,
)

@Serializable
data class EspnCompetition(
    val date: String? = null,
    val status: EspnStatus? = null,
    val venue: EspnVenue? = null,
    val competitors: List<EspnCompetitor> = emptyList(),
)

@Serializable
data class EspnStatus(
    val clock: Double? = null,
    val period: Int? = null,
    val displayClock: String? = null,
    val type: EspnStatusType? = null,
)

@Serializable
data class EspnStatusType(
    val name: String? = null,
    val state: String? = null,
    val completed: Boolean? = null,
    val detail: String? = null,
    val shortDetail: String? = null,
)

@Serializable
data class EspnVenue(
    val fullName: String? = null,
)

@Serializable
data class EspnCompetitor(
    val homeAway: String? = null,
    val score: String? = null,
    val winner: Boolean? = null,
    val team: EspnTeam? = null,
    val linescores: List<EspnLineScore> = emptyList(),
)

@Serializable
data class EspnLineScore(
    val value: Double? = null,
    @SerialName("displayValue") val displayValue: String? = null,
)

@Serializable
data class EspnTeam(
    val id: String? = null,
    val displayName: String? = null,
    val shortDisplayName: String? = null,
    val abbreviation: String? = null,
    val logo: String? = null,
    val location: String? = null,
)
