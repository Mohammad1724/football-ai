package com.footballai.app.data.repository

import com.footballai.app.data.local.entity.HistoricalMatchEntity
import com.footballai.app.data.local.entity.TeamEntity
import java.util.Locale

/** Exact, league-scoped identities only. No substring, fuzzy or transliteration guesses. */
object HistoryBridge {
    val leagues = mapOf("PL" to "E0", "ELC" to "E1", "BL1" to "D1", "SA" to "I1",
        "PD" to "SP1", "FL1" to "F1", "DED" to "N1", "PPL" to "P1", "CL" to "UCL")

    /**
     * Competitions a fixture provider publishes but supplies no usable history
     * for, bridged to the CSV mirror archive like [leagues] bridges
     * football-data.org. Keyed by `providerId:providerKey`.
     */
    val mirrorLeagues = mapOf(
        "api-football:2" to "UCL",
        "espn-open:uefa.champions" to "UCL",
    )

    /**
     * Extra names a mirror history row may answer to, per CSV competition
     * code: a row is findable under its own published name and under each of
     * these equivalents. Explicit pairs only, verified against the fixture
     * providers' published names (ESPN's live scoreboard/standings, the
     * other providers' house styles) — the same discipline as the rest of
     * this bridge, never a similarity guess. An equivalent that collides
     * with another club's name is rejected by the uniqueness checks in
     * [map], exactly like a duplicated published name.
     */
    val nameAliases: Map<String, Map<String, List<String>>> = mapOf(
        "UCL" to listOf(
            "Man City" to "Manchester City",
            "Dortmund" to "Borussia Dortmund",
            "PSV Eindhoven" to "PSV",
            "Bayern München" to "Bayern Munich",
            "Bayern München" to "Bayern",
            "Inter" to "Internazionale",
            "PSG" to "Paris Saint-Germain",
            "PSG" to "Paris Saint Germain",
            "Man United" to "Manchester United",
            "Fenerbahçe" to "Fenerbahce",
            "Beşiktaş" to "Besiktas",
            "Bodø/Glimt" to "Bodo/Glimt",
            "LASK" to "LASK Linz",
            "Feyenoord" to "Feyenoord Rotterdam",
            "Frankfurt" to "Eintracht Frankfurt",
            "Leverkusen" to "Bayer Leverkusen",
            "Milan" to "AC Milan",
            "Roma" to "AS Roma",
            "Tottenham" to "Tottenham Hotspur",
            "Newcastle" to "Newcastle United",
            "Atlético Madrid" to "Atletico Madrid",
            "Atlético Madrid" to "Atlético de Madrid",
            "FC København" to "FC Copenhagen",
            "FK Crvena Zvezda" to "Red Star Belgrade",
            "Viktoria Plzeň" to "Viktoria Plzen",
            "Union St.Gilloise" to "Union Saint-Gilloise",
            "Qarabağ FK" to "Qarabag",
            "Viking" to "Viking FK",
            "Wolfsburg" to "VfL Wolfsburg",
            "Salzburg" to "FC Salzburg",
        ).groupBy({ key(it.first) }, { key(it.second) })
            .mapValues { it.value.distinct() },
    )

    private fun key(name: String) = name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    fun map(
        rows: List<HistoricalMatchEntity>,
        teams: List<TeamEntity>,
        aliases: Map<String, List<String>> = emptyMap(),
    ): List<HistoricalMatchEntity> {
        val targets = teams.flatMap { t -> listOfNotNull(t.name, t.shortName).map { key(it) to t.id } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }
        val names = rows.flatMap { listOf(it.homeTeamId to it.homeTeamName, it.awayTeamId to it.awayTeamName) }
            .flatMap { (id, name) ->
                val base = key(name)
                (listOf(base) + aliases[base].orEmpty()).map { it to id }
            }
            .distinct().groupBy({ it.first }, { it.second })
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
