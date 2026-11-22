package com.footballai.app.data.repository

import com.footballai.app.data.local.entity.HistoricalMatchEntity
import com.footballai.app.data.local.entity.TeamEntity
import java.util.Locale

/** Exact, league-scoped identities only. No substring, fuzzy or transliteration guesses. */
object HistoryBridge {
    val leagues = mapOf("PL" to "E0", "ELC" to "E1", "BL1" to "D1", "SA" to "I1",
        "PD" to "SP1", "FL1" to "F1", "DED" to "N1", "PPL" to "P1")
    private fun key(name: String) = name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    fun map(rows: List<HistoricalMatchEntity>, teams: List<TeamEntity>): List<HistoricalMatchEntity> {
        val targets = teams.flatMap { t -> listOfNotNull(t.name, t.shortName).map { key(it) to t.id } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }
        val names = rows.flatMap { listOf(it.homeTeamId to it.homeTeamName, it.awayTeamId to it.awayTeamName) }
            .distinct().groupBy({ key(it.second) }, { it.first })
        val mapping = names.filterValues { it.distinct().size == 1 }.mapNotNull { (name, ids) ->
            targets[name]?.singleOrNull()?.let { ids.first() to it }
        }.toMap()
        val unique = mapping.filterValues { target -> mapping.values.count { it == target } == 1 }
        return rows.mapNotNull { row ->
            val home = unique[row.homeTeamId] ?: return@mapNotNull null
            val away = unique[row.awayTeamId] ?: return@mapNotNull null
            row.copy(homeTeamId = home, awayTeamId = away)
        }
    }
}
