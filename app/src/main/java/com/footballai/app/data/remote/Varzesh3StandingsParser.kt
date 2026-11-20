package com.footballai.app.data.remote

import com.footballai.app.data.provider.RemoteStandingRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Reads the ordered developer-tools table returned by Varzesh3. The endpoint contains
 * the current table only and has no
 * season identifier. It must never be used as a historical season's table.
 *
 * A malformed row fails the entire document so an incomplete table is not presented as
 * complete. Ranking is the published array order; this endpoint has no rank field.
 */
internal object Varzesh3StandingsParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): List<RemoteStandingRow> {
        val root = json.parseToJsonElement(payload) as? JsonObject
            ?: throw IllegalArgumentException("Expected a standings object")
        val teams = root["teams"] as? JsonArray
            ?: throw IllegalArgumentException("Missing standings teams")
        require(teams.isNotEmpty()) { "Empty standings" }
        val seen = mutableSetOf<String>()
        return teams.mapIndexed { index, element ->
            val row = element as? JsonObject ?: throw IllegalArgumentException("Invalid team row")
            val id = row.requiredInt("id")
            require(id > 0 && seen.add(id.toString())) { "Invalid or duplicate team id" }
            val name = (row["name"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Missing team name")
            val played = row.requiredInt("played")
            val wins = row.requiredInt("wins")
            val draws = row.requiredInt("draws")
            val losses = row.requiredInt("losses")
            val goalsFor = row.requiredInt("goalFor")
            val goalsAgainst = row.requiredInt("goalAgainst")
            val points = row.requiredInt("points")
            val difference = row.requiredInt("goalDifference")
            require(played >= 0 && wins >= 0 && draws >= 0 && losses >= 0 &&
                goalsFor >= 0 && goalsAgainst >= 0) { "Negative standings statistic" }
            RemoteStandingRow(
                rank = index + 1,
                teamKey = id.toString(),
                teamName = name,
                crestUrl = (row["logo"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                points = points,
                played = played,
                goalDifference = difference,
                form = null,
                wins = wins,
                draws = draws,
                losses = losses,
                goalsFor = goalsFor,
                goalsAgainst = goalsAgainst,
            )
        }
    }

    private fun JsonObject.requiredInt(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull
            ?: throw IllegalArgumentException("Missing or invalid $key")
}
