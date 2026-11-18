package com.footballai.app.data.remote

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.domain.catalog.TeamNames
import com.footballai.app.domain.model.QuotaPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** Bulk artwork recovery using ESPN's published teams for the same domestic league.
 * No result, match, or model identity is changed. An unsupported league falls back to
 * the regular per-team search. The mapping intentionally excludes youth competitions.
 */
class LeagueCrestIndex(private val client: ApiClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private data class CacheEntry(val createdAt: Long, val teams: List<LeagueCrestTeam>)

    suspend fun league(slug: String): Outcome<List<LeagueCrestTeam>> {
        val cached = cache[slug]
        if (cached != null && System.currentTimeMillis() - cached.createdAt in 0 until CACHE_MS) {
            return Outcome.Success(cached.teams)
        }
        val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/teams"
        return when (val result = client.get("espn-open", QuotaPolicy(150, 3000), url, query = mapOf("limit" to "100"))) {
            is Outcome.Failure -> result
            is Outcome.Success -> {
                val teams = parse(result.value, slug)
                if (teams == null) Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = "Invalid league teams"))
                else {
                    cache[slug] = CacheEntry(System.currentTimeMillis(), teams)
                    Outcome.Success(teams)
                }
            }
        }
    }

    internal fun parse(payload: String, expectedSlug: String): List<LeagueCrestTeam>? {
        val doc = runCatching { json.decodeFromString(LeagueDocument.serializer(), payload) }.getOrNull() ?: return null
        val league = doc.sports.flatMap { it.leagues }.singleOrNull { it.slug == expectedSlug } ?: return null
        if (league.teams.isEmpty()) return null
        return league.teams.mapNotNull { row ->
            val team = row.team ?: return@mapNotNull null
            val id = team.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val names = listOfNotNull(team.name, team.displayName).filter { it.isNotBlank() }.distinct()
            val badge = (team.logos.firstOrNull { logo -> logo.rel.any { it.equals("default", true) } }
                ?: team.logos.firstOrNull())?.href
                ?.takeIf { it.startsWith("https://a.espncdn.com/") }
            if (names.isEmpty()) null else LeagueCrestTeam(id, names, badge)
        }
    }

    companion object {
        private const val CACHE_MS = 12L * 60L * 60L * 1000L
        /** Explicit mappings only; leagues not listed here do not use cross-source matching. */
        val LEAGUES = mapOf(
            "E0" to "eng.1", "E1" to "eng.2", "E2" to "eng.3", "E3" to "eng.4",
            "D1" to "ger.1", "D2" to "ger.2", "SP1" to "esp.1", "SP2" to "esp.2",
            "I1" to "ita.1", "I2" to "ita.2", "F1" to "fra.1", "F2" to "fra.2",
            "N1" to "ned.1", "T1" to "tur.1", "AUT1" to "aut.1", "SC0" to "sco.1",
            "SC1" to "sco.2", "AUS1" to "aus.1", "USA1" to "usa.1", "NOR1" to "nor.1",
        )
        private val legalWords = setOf("fc", "cf", "sc", "afc", "1")
        private fun key(name: String): String = TeamNames.normalizeNameKey(name)
            .split(' ').filter { it.isNotBlank() && it !in legalWords }.joinToString(" ")

        /** Exact normalized names or explicit catalog aliases, unique within this league.
         * Reserve, age and gender suffixes are not discarded; one-letter initials do not
         * constitute enough evidence to link unrelated clubs.
         */
        fun match(teams: List<LeagueCrestTeam>, name: String, alias: String? = null): String? {
            val inputs = listOfNotNull(name, alias, TeamNames.canonicalName(name)).map(::key)
                .filter { it.length >= 3 }.toSet()
            if (inputs.isEmpty()) return null
            val candidates = teams.filter { team ->
                team.names.any { published ->
                    val choices = listOfNotNull(published, TeamNames.canonicalName(published)).map(::key)
                    choices.any { it in inputs }
                }
            }.distinctBy { it.id }
            return candidates.singleOrNull()?.crestUrl
        }
    }
}

data class LeagueCrestTeam(val id: String, val names: List<String>, val crestUrl: String?)

@Serializable private data class LeagueDocument(val sports: List<LeagueSport> = emptyList())
@Serializable private data class LeagueSport(val leagues: List<LeagueData> = emptyList())
@Serializable private data class LeagueData(val slug: String? = null, val teams: List<LeagueRow> = emptyList())
@Serializable private data class LeagueRow(val team: LeagueTeam? = null)
@Serializable private data class LeagueTeam(
    val id: String? = null,
    val name: String? = null,
    val displayName: String? = null,
    val logos: List<LeagueLogo> = emptyList(),
)
@Serializable private data class LeagueLogo(val href: String? = null, val rel: List<String> = emptyList())
