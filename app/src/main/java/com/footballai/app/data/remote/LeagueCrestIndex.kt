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
    /**
     * Slugs that answered "no such league". Half the lower divisions are like this — the
     * Ukrainian, Polish, Czech and Croatian first tiers, both English sixth tiers — and a
     * failure is not cached above, so without this every club in such a lane asked again.
     */
    private val missing = ConcurrentHashMap<String, Long>()
    private data class CacheEntry(val createdAt: Long, val teams: List<LeagueCrestTeam>)

    suspend fun league(slug: String): Outcome<List<LeagueCrestTeam>> {
        val cached = cache[slug]
        if (cached != null && System.currentTimeMillis() - cached.createdAt in 0 until CACHE_MS) {
            return Outcome.Success(cached.teams)
        }
        val missingAt = missing[slug]
        if (missingAt != null && System.currentTimeMillis() - missingAt in 0 until CACHE_MS) {
            return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "no document for $slug"))
        }
        val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/teams"
        return when (val result = client.get("espn-open", QuotaPolicy(150, 3000), url, query = mapOf("limit" to "100"))) {
            is Outcome.Failure -> {
                // "This league is not published" is a fact worth remembering; a quota or
                // network failure is not, and is retried on the next club.
                if (result.error.code == ErrorCode.NOT_FOUND || result.error.code == ErrorCode.PARSE_ERROR) {
                    missing[slug] = System.currentTimeMillis()
                }
                result
            }
            is Outcome.Success -> {
                val teams = parse(result.value, slug)
                if (teams == null) {
                    missing[slug] = System.currentTimeMillis()
                    Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = "Invalid league teams"))
                }
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
         * slugs below were each confirmed by asking the endpoint for that competition and
         * reading back the roster; a league whose endpoint answers 404 or an empty document
         * stays out, and its clubs are found by search only.
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
            "ARG1" to "arg.1", "MEX1" to "mex.1", "JPN1" to "jpn.1", "ROU1" to "rou.1",
            "RUS1" to "rus.1", "CHN1" to "chn.1",
            // Verified by asking the endpoint directly: it answers for these and not for
            // the Ukrainian, Polish, Czech and Croatian first tiers (`ukr.1`, `pol.1`,
            // `cze.1`, `cro.1` return 404 or an empty document), so those four keys stay
            // out of the table and rely on the search path.
            "SC2" to "sco.3", "SC3" to "sco.4", "EC" to "eng.5", "WAL1" to "wal.1",
            "CYP1" to "cyp.1", "BRA2" to "bra.2", "ARG2" to "arg.2", "MEX2" to "mex.2",
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
        fun match(teams: List<LeagueCrestTeam>, name: String, alias: String? = null): String? =
            lookup(teams, name, alias).crestUrl

        /**
         * What the roster says about one club: its published badge, and whether the club was
         * recognised at all.
         *
         * The difference matters to the caller: a club that is in the roster but carries no
         * published badge cannot be found by searching the same source again, so the search
         * can be skipped instead of spending the request budget on an answer that is already
         * known to be empty.
         */
        fun lookup(teams: List<LeagueCrestTeam>, name: String, alias: String? = null): RosterLookup {
            val wanted = listOfNotNull(name, alias, TeamNames.canonicalName(name))
                .filter { it.isNotBlank() }
                .distinct()
            if (wanted.isEmpty()) return RosterLookup(null, matched = false)
            val found = ClubNameMatcher.pick(
                candidates = teams.distinctBy { it.id },
                publishedNames = { team ->
                    team.names.flatMap { published ->
                        listOfNotNull(published, TeamNames.canonicalName(published))
                    }
                },
                wanted = wanted,
            ) ?: return RosterLookup(null, matched = false)
            return RosterLookup(found.crestUrl, matched = true)
        }
    }
}

data class LeagueCrestTeam(val id: String, val names: List<String>, val crestUrl: String?)

/** A roster answer: the badge, and whether the club itself was recognised in that roster. */
data class RosterLookup(val crestUrl: String?, val matched: Boolean)

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
