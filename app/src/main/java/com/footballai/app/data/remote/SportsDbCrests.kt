package com.footballai.app.data.remote

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.domain.catalog.TeamNames
import com.footballai.app.domain.model.QuotaPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Artwork-only fallback. 123 is TheSportsDB's documented shared free API key. */
class SportsDbCrests(private val client: ApiClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var lastRequestNanos: Long? = null

    suspend fun find(name: String, gender: String): Outcome<String?> = lock.withLock {
        // A token bucket alone allows a burst; space requests below the free tier's
        // published limit of 30/minute even at the beginning of a session.
        lastRequestNanos?.let { previous ->
            val elapsedMs = (System.nanoTime() - previous) / 1_000_000
            if (elapsedMs < 2_100) delay(2_100 - elapsedMs)
        }
        lastRequestNanos = System.nanoTime()
        when (val response = client.get(
            "sportsdb-crest", QuotaPolicy(requestsPerMinute = 20, requestsPerDay = 1000),
            "https://www.thesportsdb.com/api/v1/json/123/searchteams.php",
            query = mapOf("t" to name),
        )) {
            is Outcome.Failure -> response
            is Outcome.Success -> {
                val decoded = runCatching {
                    val root = json.parseToJsonElement(response.value).jsonObject
                    require("teams" in root)
                    json.decodeFromJsonElement(SportsDbResponse.serializer(), root)
                }.getOrNull()
                if (decoded == null) Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = "Invalid TheSportsDB response"))
                else Outcome.Success(match(decoded, name, gender))
            }
        }
    }

    internal fun parse(payload: String, name: String, gender: String): String? =
        runCatching { match(json.decodeFromString(SportsDbResponse.serializer(), payload), name, gender) }.getOrNull()

    /**
     * The club this answer is about.
     *
     * The endpoint is a search: asking for `Billericay` answers `Billericay Town`, and the
     * old rule — the published name has to be spelled exactly like the feed's — threw all of
     * that away, which is why the lower English, German, Polish and Czech divisions stayed
     * on initials even though this source carries their badges. Identity is decided by
     * [ClubNameMatcher], the same rules the other crest source uses: legal forms and
     * founding years are not a name, a reserve or women's side is a different team, and one
     * word only matches the head of a longer name.
     *
     * `Colwyn` is still not `Colwyn Bay Wanderers`: a name is compared word by word, never as
     * a substring, and when the answer holds several clubs that could be meant, the exact
     * spelling wins and otherwise nothing is stored.
     */
    private fun match(response: SportsDbResponse, name: String, gender: String): String? {
        val wanted = TeamNames.normalizeNameKey(name)
        val expectedGender = when (gender) { "MEN" -> "Male"; "WOMEN" -> "Female"; else -> return null }
        if (wanted.isBlank()) return null
        val related = response.teams.orEmpty().filter { team ->
            team.strSport.equals("Soccer", ignoreCase = true) &&
                team.strGender.equals(expectedGender, ignoreCase = true) &&
                // A row that names a reserve side is that side, even when another spelling
                // in the same row looks like the first team.
                namesOf(team).none { published -> ClubNameMatcher.addsReserveMarker(published, name) } &&
                namesOf(team).any { published -> ClubNameMatcher.matches(published, name) }
        }
        val chosen = related
            .filter { team -> namesOf(team).any { TeamNames.normalizeNameKey(it) == wanted } }
            .singleOrNull()
            ?: related.singleOrNull()
            ?: return null
        val badge = chosen.strBadge?.trim() ?: return null
        return badge.takeIf { it.startsWith("https://r2.thesportsdb.com/") || it.startsWith("https://www.thesportsdb.com/") }
    }

    private fun namesOf(team: SportsDbTeam): List<String> =
        (listOfNotNull(team.strTeam) + team.strTeamAlternate.orEmpty().split(','))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}

@Serializable
private data class SportsDbResponse(val teams: List<SportsDbTeam>? = null)

@Serializable
private data class SportsDbTeam(
    val strTeam: String? = null,
    val strTeamAlternate: String? = null,
    val strSport: String? = null,
    val strGender: String? = null,
    val strBadge: String? = null,
)
