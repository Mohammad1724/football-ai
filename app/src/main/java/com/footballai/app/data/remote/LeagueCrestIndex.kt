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
        /**
         * Explicit mappings only; a league that is not listed here never uses roster
         * matching and falls back to the per-club search.
         *
         * A wrong slug cannot attach a wrong badge: the club still has to be found by name
         * inside the roster that slug returns, and only when exactly one club fits. The
         * slugs below are the ones documented by independent ESPN clients for those
         * competitions; leagues with no documented slug (the Scottish third and fourth
         * tiers, the non-league English divisions, the Finnish and Welsh top flights and
         * the lower Polish, Slovak and Slovenian tiers) stay out of this table on purpose.
         */
        val LEAGUES = mapOf(
            "E0" to "eng.1", "E1" to "eng.2", "E2" to "eng.3", "E3" to "eng.4",
            "D1" to "ger.1", "D2" to "ger.2", "SP1" to "esp.1", "SP2" to "esp.2",
            "I1" to "ita.1", "I2" to "ita.2", "F1" to "fra.1", "F2" to "fra.2",
            "N1" to "ned.1", "T1" to "tur.1", "AUT1" to "aut.1", "SC0" to "sco.1",
            "SC1" to "sco.2", "AUS1" to "aus.1", "USA1" to "usa.1", "NOR1" to "nor.1",
            // Added once the club matcher could read "KAA Gent" as "Gent": before that the
            // roster was fetched but almost nothing in these leagues was recognisable.
            "N2" to "ned.2", "B1" to "bel.1", "P1" to "por.1", "G1" to "gre.1",
            "SWZ1" to "sui.1", "DEN1" to "den.1", "SWE1" to "swe.1", "BRA1" to "bra.1",
            "ARG1" to "arg.1", "MEX1" to "mex.1", "JPN1" to "jpn.1", "POL1" to "pol.1",
            "CZE1" to "cze.1", "CRO1" to "cro.1", "ROU1" to "rou.1", "RUS1" to "rus.1",
            "UKR1" to "ukr.1", "CHN1" to "chn.1",
        )

        /**
         * A club found inside the published roster of its own league.
         *
         * The names tried for our club are the live name, the catalog alias and the
         * catalog's full name; each candidate is judged on both names ESPN publishes for
         * it. [ClubNameMatcher] decides identity — legal forms and founding years are not
         * part of a name, a reserve side never is the first team — and only a single
         * candidate is accepted, so an ambiguous name keeps its initials.
         */
        fun match(teams: List<LeagueCrestTeam>, name: String, alias: String? = null): String? {
            val wanted = listOfNotNull(name, alias, TeamNames.canonicalName(name))
                .filter { it.isNotBlank() }
                .distinct()
            if (wanted.isEmpty()) return null
            val found = ClubNameMatcher.pick(
                candidates = teams.distinctBy { it.id },
                publishedNames = { team ->
                    team.names.flatMap { published ->
                        listOfNotNull(published, TeamNames.canonicalName(published))
                    }
                },
                wanted = wanted,
            )
            return found?.crestUrl
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
