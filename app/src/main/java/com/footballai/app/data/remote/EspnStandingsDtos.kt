package com.footballai.app.data.remote

import kotlinx.serialization.Serializable

/**
 * The league table ESPN publishes at `/apis/v2/sports/soccer/{league}/standings`.
 *
 * The document nests the table two levels down (`children[0].standings.entries`), and a
 * stat is a named entry in a list rather than a field, so every value is read by name.
 * Everything is optional: a season that has not started yet answers with an empty table,
 * which is an honest "no table" rather than an error.
 */
@Serializable
data class EspnStandingsResponse(
    val children: List<EspnStandingsGroup> = emptyList(),
)

@Serializable
data class EspnStandingsGroup(
    val standings: EspnStandingsBlock? = null,
)

@Serializable
data class EspnStandingsBlock(
    val season: Int? = null,
    val entries: List<EspnStandingEntry> = emptyList(),
)

@Serializable
data class EspnStandingEntry(
    val team: EspnStandingTeam? = null,
    val stats: List<EspnStandingStat> = emptyList(),
)

@Serializable
data class EspnStandingTeam(
    val id: String? = null,
    val displayName: String? = null,
    val name: String? = null,
    val shortDisplayName: String? = null,
)

@Serializable
data class EspnStandingStat(
    val name: String = "",
    val value: Double? = null,
)
