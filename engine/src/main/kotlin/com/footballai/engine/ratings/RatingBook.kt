package com.footballai.engine.ratings

import com.footballai.engine.model.LeagueBaseline
import com.footballai.engine.model.TeamStrength

data class RatingBookConfig(
    /** Half-life of the exponential time decay applied to historical matches, in days. */
    val halfLifeDays: Double = 240.0,
    /** Only matches younger than this are used when fitting. */
    val windowDays: Int = 1200,
    val elo: EloConfig = EloConfig(),
    val dixonColes: DixonColesConfig = DixonColesConfig(),
    /** Minimum number of usable matches before a Dixon-Coles fit is attempted. */
    val minMatchesForFit: Int = 40,
)

/**
 * Holds historical results and turns them into team strengths.
 *
 * The book is the single source of ratings for both the application and the
 * back-tester, so offline predictions and historical evaluation share exactly
 * the same code path. Matches are only ever used when their kickoff is at or
 * before the requested point in time, which prevents data leakage.
 */
class RatingBook(private val config: RatingBookConfig = RatingBookConfig()) {

    private data class CachedFit(val version: Int, val asOfBucket: Long, val fit: DixonColesFit?)

    private val history = HashMap<String, MutableList<RatingMatch>>()
    private val elo = EloRatingSystem(config.elo)
    private val fitCache = HashMap<String, CachedFit>()
    private var version = 0
    private var eloVersion = -1
    private var eloAsOfBucket = Long.MIN_VALUE
    private var totalMatches = 0

    fun add(competitionId: String, match: RatingMatch) {
        history.getOrPut(competitionId) { mutableListOf() }.add(match)
        version++
        totalMatches++
    }

    fun addAll(competitionId: String, matches: List<RatingMatch>) {
        val bucket = history.getOrPut(competitionId) { mutableListOf() }
        bucket.addAll(matches)
        version++
        totalMatches += matches.size
    }

    fun clear() {
        history.clear()
        fitCache.clear()
        version++
        totalMatches = 0
        eloVersion = -1
    }

    fun competitionIds(): Set<String> = history.keys.toSet()

    fun matchCount(competitionId: String): Int = history[competitionId]?.size ?: 0

    val totalMatchCount: Int get() = totalMatches

    /** Rated teams ordered by rating, used by the model-performance and team screens. */
    fun rankedTeams(competitionId: String, asOfEpochMs: Long, limit: Int = 0): List<TeamStrength> {
        ensureElo(asOfEpochMs)
        val teams = history[competitionId]?.flatMap { listOf(it.homeTeamId, it.awayTeamId) }?.distinct().orEmpty()
        val fit = fit(competitionId, asOfEpochMs)
        val list = teams.map { teamId ->
            TeamStrength(
                teamId = teamId,
                attack = fit?.attack?.get(teamId) ?: 1.0,
                defence = fit?.defence?.get(teamId) ?: 1.0,
                elo = elo.rating(teamId, asOfEpochMs),
                matchesUsed = fit?.matchesByTeam?.get(teamId) ?: 0,
            )
        }.sortedByDescending { it.elo }
        return if (limit > 0) list.take(limit) else list
    }

    fun strengths(
        competitionId: String,
        homeTeamId: String,
        awayTeamId: String,
        asOfEpochMs: Long,
    ): Pair<TeamStrength, TeamStrength> {
        ensureElo(asOfEpochMs)
        val fit = fit(competitionId, asOfEpochMs)
        val home = TeamStrength(
            teamId = homeTeamId,
            attack = fit?.attack?.get(homeTeamId) ?: 1.0,
            defence = fit?.defence?.get(homeTeamId) ?: 1.0,
            elo = elo.rating(homeTeamId, asOfEpochMs),
            matchesUsed = fit?.matchesByTeam?.get(homeTeamId) ?: 0,
        )
        val away = TeamStrength(
            teamId = awayTeamId,
            attack = fit?.attack?.get(awayTeamId) ?: 1.0,
            defence = fit?.defence?.get(awayTeamId) ?: 1.0,
            elo = elo.rating(awayTeamId, asOfEpochMs),
            matchesUsed = fit?.matchesByTeam?.get(awayTeamId) ?: 0,
        )
        return Pair(home, away)
    }

    fun baseline(competitionId: String, asOfEpochMs: Long): LeagueBaseline {
        val windowed = windowedMatches(competitionId, asOfEpochMs)
        if (windowed.isEmpty()) return LeagueBaseline.FALLBACK
        var weightSum = 0.0
        var homeGoals = 0.0
        var awayGoals = 0.0
        for (match in windowed) {
            weightSum += match.weight
            homeGoals += match.weight * match.homeGoals
            awayGoals += match.weight * match.awayGoals
        }
        if (weightSum <= 0.0) return LeagueBaseline.FALLBACK
        return LeagueBaseline(
            averageHomeGoals = (homeGoals / weightSum).coerceIn(0.2, 5.0),
            averageAwayGoals = (awayGoals / weightSum).coerceIn(0.2, 5.0),
            sampleSize = windowed.size,
        )
    }

    fun fit(competitionId: String, asOfEpochMs: Long): DixonColesFit? {
        val bucket = (asOfEpochMs / CACHE_BUCKET_MS) * CACHE_BUCKET_MS
        val cached = fitCache[competitionId]
        if (cached != null && cached.version == version && cached.asOfBucket == bucket) return cached.fit

        val windowed = windowedMatches(competitionId, asOfEpochMs)
        val result = if (windowed.size >= config.minMatchesForFit) {
            DixonColesFitter.fit(windowed, config.dixonColes)
        } else {
            null
        }
        fitCache[competitionId] = CachedFit(version, bucket, result)
        return result
    }

    fun eloRating(teamId: String, asOfEpochMs: Long): Double {
        ensureElo(asOfEpochMs)
        return elo.rating(teamId, asOfEpochMs)
    }

    private fun windowedMatches(competitionId: String, asOfEpochMs: Long): List<RatingMatch> {
        val all = history[competitionId] ?: return emptyList()
        val windowMs = config.windowDays.toLong() * 86_400_000L
        val result = ArrayList<RatingMatch>(all.size)
        for (match in all) {
            if (match.kickoffEpochMs > asOfEpochMs) continue
            val ageMs = asOfEpochMs - match.kickoffEpochMs
            if (ageMs > windowMs) continue
            val ageDays = ageMs / 86_400_000.0
            val decay = EloRatingSystem.timeDecayWeight(ageDays, config.halfLifeDays)
            result.add(match.copy(weight = match.weight * decay))
        }
        return result
    }

    /** Replays every known match chronologically so Elo ratings reflect the requested instant. */
    private fun ensureElo(asOfEpochMs: Long) {
        val bucket = (asOfEpochMs / CACHE_BUCKET_MS) * CACHE_BUCKET_MS
        if (eloVersion == version && eloAsOfBucket == bucket) return
        val fresh = EloRatingSystem(config.elo)
        val ordered = history.entries
            .flatMap { entry -> entry.value.map { entry.key to it } }
            .filter { it.second.kickoffEpochMs <= asOfEpochMs }
            .sortedWith(compareBy({ it.second.kickoffEpochMs }, { it.second.homeTeamId }))
        for ((_, match) in ordered) {
            fresh.update(
                homeTeamId = match.homeTeamId,
                awayTeamId = match.awayTeamId,
                homeGoals = match.homeGoals,
                awayGoals = match.awayGoals,
                kickoffEpochMs = match.kickoffEpochMs,
                weight = 1.0,
            )
        }
        elo.replaceWith(fresh)
        eloVersion = version
        eloAsOfBucket = bucket
    }

    companion object {
        private const val CACHE_BUCKET_MS = 6 * 3_600_000L
    }
}
