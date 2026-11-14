package com.footballai.engine.ratings

import com.footballai.engine.model.LeagueBaseline
import com.footballai.engine.model.TeamStrength
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * One finished international match.
 *
 * [importance] is the competition weight used by the Elo update (a friendly is
 * worth less than a World Cup knockout tie) and [neutralVenue] removes the home
 * bonus from the expectation, exactly as the World Football Elo Ratings do.
 */
data class NationalMatch(
    val homeTeamId: String,
    val awayTeamId: String,
    val homeGoals: Int,
    val awayGoals: Int,
    val kickoffEpochMs: Long,
    val importance: Double = 1.0,
    val neutralVenue: Boolean = false,
) {
    init {
        require(homeGoals >= 0 && awayGoals >= 0) { "goals must be non-negative" }
    }
}

data class NationalTeamConfig(
    /** Base K-factor; scaled by match importance and goal difference. */
    val kFactor: Double = 40.0,
    val homeAdvantageElo: Double = 50.0,
    val initialRating: Double = 1500.0,
    /** National squads change slowly; the regression half-life is longer than for clubs. */
    val regressionHalfLifeDays: Double = 730.0,
    /** Only matches younger than this contribute to the goal-based strength. */
    val windowDays: Int = 1800,
    /** Matches needed before a team's scoring rates are used instead of the mean. */
    val minMatches: Int = 5,
    /** Half-life of the exponential weight applied to goals scored/conceded. */
    val halfLifeDays: Double = 400.0,
    /** Goal-difference contribution is capped so a 9-0 does not dominate a rating. */
    val goalDifferenceCap: Double = 4.0,
)

/**
 * National-team strength estimation.
 *
 * This class exists so that national-team ratings are structurally separate from
 * club ratings: it is a different object with its own configuration, its own Elo
 * replay and its own goal-based priors. Nothing here reads or writes the club
 * [RatingBook], so a 1500 Elo in a domestic league and a 1500 Elo in international
 * football can never be compared, averaged or accidentally mixed.
 */
class NationalTeamRatingBook(
    private val config: NationalTeamConfig = NationalTeamConfig(),
) {
    private val matches = ArrayList<NationalMatch>()

    fun add(match: NationalMatch) {
        matches.add(match)
    }

    fun addAll(list: List<NationalMatch>) {
        matches.addAll(list)
    }

    fun clear() = matches.clear()

    val matchCount: Int get() = matches.size

    fun teamIds(): Set<String> =
        matches.flatMap { listOf(it.homeTeamId, it.awayTeamId) }.toSet()

    /**
     * Builds both teams' strengths and the competition baseline using every match
     * whose kickoff is at or before [asOfEpochMs]. Later matches are invisible, which
     * is what makes this usable for a walk-forward evaluation.
     */
    fun prepare(
        homeTeamId: String,
        awayTeamId: String,
        asOfEpochMs: Long,
        neutralVenue: Boolean = false,
    ): NationalPrepared {
        val available = matches.filter { it.kickoffEpochMs <= asOfEpochMs }
        val ratings = replayElo(available)
        val homeSample = available.count { it.homeTeamId == homeTeamId || it.awayTeamId == homeTeamId }
        val awaySample = available.count { it.homeTeamId == awayTeamId || it.awayTeamId == awayTeamId }

        val meanHome = weightedMeanGoals(available, asOfEpochMs, home = true)
        val meanAway = weightedMeanGoals(available, asOfEpochMs, home = false)
        val baseline = if (available.isEmpty()) {
            FALLBACK_BASELINE
        } else {
            val homeRate = meanHome ?: FALLBACK_BASELINE.averageHomeGoals
            val awayRate = meanAway ?: FALLBACK_BASELINE.averageAwayGoals
            LeagueBaseline(
                averageHomeGoals = homeRate.coerceIn(0.2, 5.0),
                averageAwayGoals = awayRate.coerceIn(0.2, 5.0),
                sampleSize = available.size,
            )
        }

        val homeStrength = strength(
            teamId = homeTeamId,
            available = available,
            asOfEpochMs = asOfEpochMs,
            elo = ratings[homeTeamId] ?: config.initialRating,
            sample = homeSample,
            baseline = baseline,
        )
        val awayStrength = strength(
            teamId = awayTeamId,
            available = available,
            asOfEpochMs = asOfEpochMs,
            elo = ratings[awayTeamId] ?: config.initialRating,
            sample = awaySample,
            baseline = baseline,
        )

        return NationalPrepared(
            home = homeStrength,
            away = awayStrength,
            baseline = baseline,
            neutralVenue = neutralVenue,
            enoughEvidence = available.size >= config.minMatches &&
                homeSample >= config.minMatches &&
                awaySample >= config.minMatches,
        )
    }

    /** Elo rating of a national team at a point in time, replayed from the match list. */
    fun rating(teamId: String, asOfEpochMs: Long): Double =
        replayElo(matches.filter { it.kickoffEpochMs <= asOfEpochMs })[teamId] ?: config.initialRating

    /** Days since the team's previous international, or null when it has not played. */
    fun daysSinceLastMatch(teamId: String, asOfEpochMs: Long): Int? {
        val last = matches.filter { it.kickoffEpochMs <= asOfEpochMs }
            .filter { it.homeTeamId == teamId || it.awayTeamId == teamId }
            .maxOfOrNull { it.kickoffEpochMs } ?: return null
        return ((asOfEpochMs - last) / DAY_MS).toInt().coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ internals

    private fun replayElo(available: List<NationalMatch>): Map<String, Double> {
        val ratings = HashMap<String, Double>()
        val ordered = available.sortedWith(compareBy({ it.kickoffEpochMs }, { it.homeTeamId }))
        for (match in ordered) {
            val home = ratings.getOrPut(match.homeTeamId) { config.initialRating }
            val away = ratings.getOrPut(match.awayTeamId) { config.initialRating }
            val homeBonus = if (match.neutralVenue) 0.0 else config.homeAdvantageElo
            val expectedHome = 1.0 / (1.0 + 10.0.pow(-(home + homeBonus - away) / 400.0))
            val actualHome = when {
                match.homeGoals > match.awayGoals -> 1.0
                match.homeGoals == match.awayGoals -> 0.5
                else -> 0.0
            }
            val goalDifference = abs(match.homeGoals - match.awayGoals)
            val marginMultiplier = when {
                goalDifference <= 1 -> 1.0
                goalDifference == 2 -> 1.5
                else -> (11.0 + goalDifference.coerceAtMost(config.goalDifferenceCap.toInt())) / 8.0
            }
            val importance = match.importance.coerceIn(0.2, 2.0)
            val delta = config.kFactor * importance * marginMultiplier * (actualHome - expectedHome)
            ratings[match.homeTeamId] = home + delta
            ratings[match.awayTeamId] = away - delta
        }
        return ratings
    }

    private fun strength(
        teamId: String,
        available: List<NationalMatch>,
        asOfEpochMs: Long,
        elo: Double,
        sample: Int,
        baseline: LeagueBaseline,
    ): TeamStrength {
        if (sample < config.minMatches) {
            return TeamStrength(
                teamId = teamId,
                attack = 1.0,
                defence = 1.0,
                elo = elo,
                matchesUsed = sample,
            )
        }
        var weightSum = 0.0
        var scored = 0.0
        var conceded = 0.0
        for (match in available) {
            val isHome = match.homeTeamId == teamId
            if (!isHome && match.awayTeamId != teamId) continue
            val ageDays = ((asOfEpochMs - match.kickoffEpochMs) / DAY_MS).coerceAtLeast(0.0)
            val weight = 0.5.pow(ageDays / config.halfLifeDays)
            weightSum += weight
            scored += weight * if (isHome) match.homeGoals else match.awayGoals
            conceded += weight * if (isHome) match.awayGoals else match.homeGoals
        }
        if (weightSum <= 0.0) {
            return TeamStrength(teamId, 1.0, 1.0, elo, sample)
        }
        val matchesEquivalent = weightSum
        val scoredRate = scored / matchesEquivalent
        val concededRate = conceded / matchesEquivalent
        val opponentAverage = (baseline.averageTotalGoals / 2.0).coerceAtLeast(0.2)

        // Shrink toward the international mean so five matches cannot produce an
        // extreme multiplier. The shrinkage strength is expressed in matches.
        val shrinkage = matchesEquivalent / (matchesEquivalent + SHRINKAGE_MATCHES)
        val rawAttack = (scoredRate / opponentAverage).coerceIn(0.3, 3.0)
        val rawDefence = (concededRate / opponentAverage).coerceIn(0.3, 3.0)
        val attack = (1.0 + (rawAttack - 1.0) * shrinkage).coerceIn(0.35, 2.5)
        val defence = (1.0 + (rawDefence - 1.0) * shrinkage).coerceIn(0.35, 2.5)
        return TeamStrength(
            teamId = teamId,
            attack = attack,
            defence = defence,
            elo = elo,
            matchesUsed = sample,
        )
    }

    private fun weightedMeanGoals(
        available: List<NationalMatch>,
        asOfEpochMs: Long,
        home: Boolean,
    ): Double? {
        if (available.isEmpty()) return null
        val windowMs = config.windowDays.toLong() * DAY_MS
        var weightSum = 0.0
        var goals = 0.0
        for (match in available) {
            if (asOfEpochMs - match.kickoffEpochMs > windowMs) continue
            val ageDays = ((asOfEpochMs - match.kickoffEpochMs) / DAY_MS).coerceAtLeast(0.0)
            val weight = 0.5.pow(ageDays / config.halfLifeDays)
            weightSum += weight
            goals += weight * if (home) match.homeGoals else match.awayGoals
        }
        if (weightSum <= 0.0) return null
        return goals / weightSum
    }

    companion object {
        private const val DAY_MS = 86_400_000.0
        private const val SHRINKAGE_MATCHES = 6.0

        /** Used only when no international result has been imported at all. */
        val FALLBACK_BASELINE = LeagueBaseline(
            averageHomeGoals = 1.30,
            averageAwayGoals = 1.05,
            sampleSize = 0,
        )
    }
}

data class NationalPrepared(
    val home: TeamStrength,
    val away: TeamStrength,
    val baseline: LeagueBaseline,
    val neutralVenue: Boolean,
    val enoughEvidence: Boolean,
) {
    /** Baseline with home advantage removed, as used on neutral grounds. */
    fun adjustedBaseline(): LeagueBaseline = if (!neutralVenue) baseline else {
        val mean = baseline.averageTotalGoals / 2.0
        LeagueBaseline(mean, mean, baseline.sampleSize)
    }

    val sampleSize: Int get() = minOf(home.matchesUsed, away.matchesUsed)
}
