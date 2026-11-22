package com.footballai.app.data.remote

import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.provider.*
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.ProviderCapabilities
import com.footballai.app.domain.model.ProviderInfo
import com.footballai.app.domain.model.QuotaPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Unofficial read-only access to the publicly accessible Varzesh3 football feed. */
class Varzesh3Provider(
    private val client: ApiClient,
    private val clock: TimeProvider,
    private val baseUrl: String = "https://web-api.varzesh3.ir",
    private val kickoff: (String, String) -> Long? = ::persianKickoff,
) : FootballDataProvider {
    companion object {
        const val PROVIDER_ID = "varzesh3"
        val KEYS = listOf("6", "24")
        private const val MAX_PAGES = 40
        private val NAMES = mapOf("6" to "Iran Pro League", "24" to "Azadegan League")

        /** Dates and times on the league pages are in the Iranian civil calendar/time zone. */
        internal fun persianKickoff(date: String, time: String): Long? {
            return try {
            val parts = date.split('/').map { it.toInt() }
            val hours = time.split(':').map { it.toInt() }
            if (parts.size != 3 || hours.size != 2 || parts[0] !in 1200..1600 ||
                parts[1] !in 1..12 || parts[2] !in 1..31 || hours[0] !in 0..23 || hours[1] !in 0..59) return null
            Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"), ULocale("fa_IR@calendar=persian")).apply {
                isLenient = false
                clear()
                set(Calendar.YEAR, parts[0])
                set(Calendar.MONTH, parts[1] - 1)
                set(Calendar.DAY_OF_MONTH, parts[2])
                set(Calendar.HOUR_OF_DAY, hours[0])
                set(Calendar.MINUTE, hours[1])
            }.timeInMillis
            } catch (_: Exception) { null }
        }
    }

    override val info = ProviderInfo(
        id = PROVIDER_ID, displayName = "Varzesh3 (unofficial)", requiresApiKey = false,
        capabilities = ProviderCapabilities(liveScores = false, singleMatchRefresh = false,
            matchStatistics = false, events = false, lineups = false, injuries = false,
            odds = false, standings = true, historicalSeasons = 1),
        // Client-side limit, not a published upstream quota.
        quota = QuotaPolicy(requestsPerMinute = 30, requestsPerDay = 0),
        attribution = "Data: Varzesh3 (varzesh3.ir), unofficial interface; availability and upstream terms apply.",
    )
    override val defaultCompetitionKeys: List<String> = KEYS
    private val json = Json { ignoreUnknownKeys = true }
    private data class Season(val id: Int, val year: Int, val current: Boolean)
    private val seasonCache = mutableMapOf<String, Pair<Long, List<Season>>>()

    private suspend fun get(path: String): Outcome<String> = client.get(info.id, info.quota, "$baseUrl$path")

    override suspend fun competitions(): Outcome<List<RemoteCompetition>> {
        val competitions = mutableListOf<RemoteCompetition>()
        for (key in KEYS) {
            val available = when (val response = seasons(key)) {
                is Outcome.Failure -> return response
                is Outcome.Success -> response.value
            }
            val current = available.first { it.current }
            competitions += RemoteCompetition(key, NAMES.getValue(key), "Iran", null, current.year)
        }
        return Outcome.Success(competitions)
    }

    private suspend fun seasons(key: String): Outcome<List<Season>> {
        if (key !in KEYS) return Outcome.Failure(AppError(ErrorCode.NOT_FOUND))
        synchronized(seasonCache) {
            seasonCache[key]?.takeIf { clock.nowEpochMs() - it.first in 0..300_000L }
                ?.let { return Outcome.Success(it.second) }
        }
        val view = when (val response = get("/v2.0/football/leagues/$key")) {
            is Outcome.Failure -> return response
            is Outcome.Success -> response.value
        }
        val currentId = when (val parsed = parseOutcome {
            val root = json.parseToJsonElement(view).obj()
            val pattern = Regex("/football/leagues/$key/seasons/(\\d+)/matches(?:[?].*)?$")
            root["tabs"].arr().flatMap { tab -> (tab.obj()["_links"] as? JsonArray).orEmpty() }
                .mapNotNull { link -> pattern.find(link.obj().string("href").orEmpty())?.groupValues?.get(1)?.toIntOrNull() }
                .distinct().single()
        }) {
            is Outcome.Failure -> return parsed
            is Outcome.Success -> parsed.value
        }
        val result = when (val res = get("/v2.0/football/leagues/$key/archive")) {
            is Outcome.Failure -> res
            is Outcome.Success -> parseOutcome {
                val root = json.parseToJsonElement(res.value).obj()
                val seasons = root["seasons"].arr().mapNotNull { item ->
                    val row = item.obj()
                    val id = row.int("id") ?: return@mapNotNull null
                    val title = row.string("title") ?: return@mapNotNull null
                    val jalaliYear = Regex("1[34]\\d{2}").find(title)?.value?.toIntOrNull() ?: return@mapNotNull null
                    Season(id, jalaliYear + 621, id == currentId)
                }
                require(seasons.count { it.current } == 1) { "Current season not found in archive" }
                seasons
            }
        }
        if (result is Outcome.Success) synchronized(seasonCache) {
            seasonCache[key] = clock.nowEpochMs() to result.value
        }
        return result
    }

    override suspend fun matches(competitionKey: String, season: Int?, fromEpochMs: Long?, toEpochMs: Long?): Outcome<List<RemoteMatch>> {
        val available = when (val res = seasons(competitionKey)) {
            is Outcome.Failure -> return res
            is Outcome.Success -> res.value
        }
        val target = (if (season == null) available.firstOrNull { it.current } else available.firstOrNull { it.year == season })
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Season unavailable"))
        val result = mutableListOf<RemoteMatch>()
        val base = "/v2.0/football/leagues/$competitionKey/seasons/${target.id}"
        // The current view contains upcoming games. Results are fetched separately because
        // the first page is not a full season, and previous pages must be followed backwards.
        val initialPath = if (target.current) "$base/matches" else "$base/results?skip=0"
        when (val res = readPages(competitionKey, target.year, initialPath, if (target.current) "next" else "prev", fromEpochMs, toEpochMs)) {
            is Outcome.Failure -> return res
            is Outcome.Success -> result += res.value
        }
        if (target.current) {
            when (val res = readPages(competitionKey, target.year, "$base/results?skip=0", "prev", fromEpochMs, toEpochMs)) {
                is Outcome.Failure -> return res
                is Outcome.Success -> result += res.value
            }
        }
        return Outcome.Success(result.associateBy { it.providerKey }.values.map { match ->
            // Do not let a future-dated result become evidence or settle a prediction.
            if (match.status == MatchStatus.FINISHED && match.kickoffEpochMs > clock.nowEpochMs())
                match.copy(status = MatchStatus.UNKNOWN, scoreScope = "UNVERIFIED") else match
        }.filter {
            (fromEpochMs == null || it.kickoffEpochMs >= fromEpochMs) && (toEpochMs == null || it.kickoffEpochMs <= toEpochMs)
        }.sortedBy { it.kickoffEpochMs })
    }

    private suspend fun readPages(key: String, year: Int, firstPath: String, direction: String, from: Long?, to: Long?): Outcome<List<RemoteMatch>> {
        val result = mutableListOf<RemoteMatch>()
        val visited = mutableSetOf<String>()
        var path: String? = firstPath
        while (path != null) {
            if (visited.size >= MAX_PAGES || !visited.add(path)) {
                return Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = "Pagination limit or loop"))
            }
            val payload = when (val res = get(path)) {
                is Outcome.Failure -> return res
                is Outcome.Success -> res.value
            }
            val parsed = parseOutcome { parsePage(payload, key, year, kickoff) }
            if (parsed is Outcome.Failure) return parsed
            val page = (parsed as Outcome.Success).value
            result += page.matches
            // A continuation flag without a usable link is a partial response, not a
            // complete season. Reject it rather than silently losing rounds.
            val root = try { json.parseToJsonElement(payload).obj() } catch (e: Exception) {
                return Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, cause = e))
            }
            val flag = if (direction == "prev") "hasPrev" else "hasMore"
            if ((root[flag] as? JsonPrimitive)?.booleanOrNull == true && direction !in page.nextSkip) {
                return Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = "Missing pagination link"))
            }
            // Build the next URL locally: the server's link is data, not permission to
            // fetch arbitrary hosts. Only the advertised skip value is accepted.
            path = page.nextSkip[direction]?.let { skip ->
                val type = if (direction == "prev") "results" else "fixtures"
                val base = firstPath.substringBeforeLast('/').substringBefore('?')
                "$base/$type?skip=$skip"
            }
            if (direction == "prev" && from != null && page.matches.isNotEmpty() && page.matches.all { it.kickoffEpochMs < from }) path = null
            if (direction == "next" && to != null && page.matches.isNotEmpty() && page.matches.all { it.kickoffEpochMs > to }) path = null
        }
        return Outcome.Success(result)
    }

    override suspend fun standings(competitionKey: String, season: Int): Outcome<List<RemoteStandingRow>> {
        val all = when (val res = seasons(competitionKey)) {
            is Outcome.Failure -> return res
            is Outcome.Success -> res.value
        }
        if (all.none { it.current && it.year == season }) return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Only current standings available"))
        return when (val res = get("/v1.0/developer-tools/football/leagues/$competitionKey/standing")) {
            is Outcome.Failure -> res
            is Outcome.Success -> parseOutcome { Varzesh3StandingsParser.parse(res.value) }
        }
    }

    override suspend fun liveMatches(): Outcome<List<RemoteMatch>> = Outcome.Success(emptyList())
    override suspend fun match(matchKey: String): Outcome<RemoteMatch?> = Outcome.Success(null)
    override suspend fun matchStatistics(matchKey: String): Outcome<List<RemoteTeamStatistics>> = Outcome.Success(emptyList())
    override suspend fun matchEvents(matchKey: String): Outcome<List<RemoteEvent>> = Outcome.Success(emptyList())
    override suspend fun lineups(matchKey: String): Outcome<List<RemoteLineup>> = Outcome.Success(emptyList())

    private fun <T> parseOutcome(block: () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Outcome.Failure(AppError(ErrorCode.PARSE_ERROR, detail = error.message, cause = error)) }

    internal data class Page(val matches: List<RemoteMatch>, val nextSkip: Map<String, Int>)
    internal fun parsePage(payload: String, key: String, year: Int, convert: (String, String) -> Long?): Page {
        val root = json.parseToJsonElement(payload).obj()
        val pages = mutableMapOf<String, Int>()
        (root["_links"] as? JsonArray).orEmpty().forEach { link ->
            val item = link.obj()
            val rel = item.string("rel")
            val href = item.string("href").orEmpty()
            val expected = if (rel == "prev") "results" else if (rel == "next") "fixtures" else null
            if (expected != null && href.contains("/football/leagues/$key/seasons/") && href.contains("/$expected?")) {
                Regex("(?:[?&])skip=(\\d+)(?:&|$)").find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { pages[rel!!] = it }
            }
        }
        val rows = mutableListOf<RemoteMatch>()
        root["items"].arr().forEach { roundValue ->
            val round = roundValue.obj()
            val roundNumber = Regex("\\d+").find(round.string("round").orEmpty())?.value?.toIntOrNull()
            round["dates"].arr().forEach { dayValue ->
                val day = dayValue.obj()
                val date = day.string("date").orEmpty()
                day["matches"].arr().forEach { matchValue ->
                    val match = matchValue.obj()
                    val id = match.int("id")?.takeIf { it > 0 } ?: return@forEach
                    val timestamp = convert(date, match.string("time").orEmpty()) ?: return@forEach
                    fun team(field: String): RemoteTeam? {
                        val t = match[field] as? JsonObject ?: return null
                        // On paged fixture lists, id is often 0; the team link carries
                        // the stable identity used by standings and other match pages.
                        val linkedId = Regex("/team/(\\d+)(?:/|$)").find(t.string("link").orEmpty())
                            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
                        val idValue = linkedId ?: t.int("id")?.takeIf { it > 0 } ?: return null
                        val name = t.string("name")?.takeIf { it.isNotBlank() } ?: return null
                        return RemoteTeam(idValue.toString(), name, crestUrl = t.string("logo"), countryCode = "IR")
                    }
                    val home = team("host") ?: return@forEach
                    val away = team("guest") ?: return@forEach
                    val goals = match["goals"] as? JsonObject
                    val hg = goals?.int("host")?.takeIf { it >= 0 }
                    val ag = goals?.int("guest")?.takeIf { it >= 0 }
                    val status = when (match.int("status")) {
                        0, 1 -> MatchStatus.SCHEDULED
                        2 -> MatchStatus.LIVE
                        4 -> MatchStatus.POSTPONED
                        5 -> MatchStatus.CANCELLED
                        7 -> if (hg != null && ag != null) MatchStatus.FINISHED else MatchStatus.UNKNOWN
                        else -> MatchStatus.UNKNOWN
                    }
                    rows += RemoteMatch(id.toString(), key, NAMES.getValue(key), year, roundNumber, timestamp, status,
                        null, home, away, hg, ag, null, null, null, null)
                }
            }
        }
        return Page(rows, pages)
    }
}

private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException("Expected object")
private fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: throw IllegalArgumentException("Expected array")
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
