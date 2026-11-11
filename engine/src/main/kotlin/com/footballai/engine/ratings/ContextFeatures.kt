package com.footballai.engine.ratings

import com.footballai.engine.model.ExplanationCode
import com.footballai.engine.model.LambdaAdjustment
import com.footballai.engine.model.LambdaAdjustmentLabel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Small, bounded adjustments derived only from results available before kickoff.
 * The same calculator is used in production and walk-forward backtests.
 *
 * These are deliberately regularised: recent five-game form must not overwhelm
 * the multi-season Dixon-Coles and Elo estimates. Neutral inputs produce no change.
 */
object ContextFeatures {
    private const val DAY_MS = 86_400_000.0
    private const val MIN_FORM_MATCHES = 3
    private const val MIN_VENUE_MATCHES = 3

    fun adjustments(
        history: List<RatingMatch>,
        homeTeamId: String,
        awayTeamId: String,
        asOfEpochMs: Long,
    ): List<LambdaAdjustment> {
        val available = history.filter { it.kickoffEpochMs < asOfEpochMs }
        if (available.isEmpty()) return emptyList()
        val result = mutableListOf<LambdaAdjustment>()

        val homeRecent = recentForm(available, homeTeamId, asOfEpochMs)
        val awayRecent = recentForm(available, awayTeamId, asOfEpochMs)
        if (homeRecent != null && awayRecent != null) {
            val difference = (homeRecent.rating - awayRecent.rating).coerceIn(-1.0, 1.0)
            val delta = difference * 0.08
            if (abs(delta) >= 0.005) {
                result += LambdaAdjustment(
                    LambdaAdjustmentLabel.RECENT_FORM,
                    homeFactor = 1.0 + delta,
                    awayFactor = 1.0 - delta,
                    reason = ExplanationCode.RECENT_FORM,
                )
            }
        }

        val homeVenue = venueForm(available, homeTeamId, atHome = true, asOfEpochMs)
        val awayVenue = venueForm(available, awayTeamId, atHome = false, asOfEpochMs)
        if (homeVenue != null && awayVenue != null) {
            val difference = (homeVenue - awayVenue).coerceIn(-2.0, 2.0)
            val delta = difference * 0.03
            if (abs(delta) >= 0.005) {
                result += LambdaAdjustment(
                    LambdaAdjustmentLabel.VENUE,
                    homeFactor = 1.0 + delta,
                    awayFactor = 1.0 - delta,
                    reason = ExplanationCode.VENUE_FORM,
                )
            }
        }

        val homeFatigue = fatigue(available, homeTeamId, asOfEpochMs)
        val awayFatigue = fatigue(available, awayTeamId, asOfEpochMs)
        val fatigueDelta = (awayFatigue - homeFatigue).coerceIn(-0.06, 0.06)
        if (abs(fatigueDelta) >= 0.005) {
            result += LambdaAdjustment(
                LambdaAdjustmentLabel.FIXTURE_CONGESTION,
                homeFactor = 1.0 + fatigueDelta,
                awayFactor = 1.0 - fatigueDelta,
                reason = ExplanationCode.FIXTURE_CONGESTION,
            )
        }
        return result
    }

    private data class Form(val rating: Double)

    private fun recentForm(
        history: List<RatingMatch>,
        teamId: String,
        asOfEpochMs: Long,
    ): Form? {
        val matches = history.filter { it.homeTeamId == teamId || it.awayTeamId == teamId }
            .sortedByDescending { it.kickoffEpochMs }
            .take(5)
        if (matches.size < MIN_FORM_MATCHES) return null
        var weightSum = 0.0
        var points = 0.0
        var goalDifference = 0.0
        for (match in matches) {
            val home = match.homeTeamId == teamId
            val scored = if (home) match.homeGoals else match.awayGoals
            val conceded = if (home) match.awayGoals else match.homeGoals
            val ageDays = ((asOfEpochMs - match.kickoffEpochMs) / DAY_MS).coerceAtLeast(0.0)
            val weight = EloRatingSystem.timeDecayWeight(ageDays, 45.0)
            weightSum += weight
            points += weight * when {
                scored > conceded -> 3.0
                scored == conceded -> 1.0
                else -> 0.0
            }
            goalDifference += weight * (scored - conceded)
        }
        if (weightSum <= 0.0) return null
        val pointsRating = ((points / weightSum - 1.35) / 1.65).coerceIn(-1.0, 1.0)
        val goalsRating = ((goalDifference / weightSum) / 2.5).coerceIn(-1.0, 1.0)
        val shrinkage = matches.size / (matches.size + 2.0)
        return Form((pointsRating * 0.45 + goalsRating * 0.55) * shrinkage)
    }

    /** Goal difference above the team's own overall baseline, at the relevant venue. */
    private fun venueForm(
        history: List<RatingMatch>,
        teamId: String,
        atHome: Boolean,
        asOfEpochMs: Long,
    ): Double? {
        val teamMatches = history.filter { it.homeTeamId == teamId || it.awayTeamId == teamId }
        val venueMatches = teamMatches.filter { (it.homeTeamId == teamId) == atHome }
            .sortedByDescending { it.kickoffEpochMs }
            .take(8)
        if (venueMatches.size < MIN_VENUE_MATCHES || teamMatches.size < 5) return null
        val overall = teamMatches.map { match ->
            goalDifference(match, teamId)
        }.average()
        val venue = venueMatches.map { match ->
            val ageDays = ((asOfEpochMs - match.kickoffEpochMs) / DAY_MS).coerceAtLeast(0.0)
            val weight = EloRatingSystem.timeDecayWeight(ageDays, 90.0)
            goalDifference(match, teamId) to weight
        }
        val weightSum = venue.sumOf { it.second }
        if (weightSum <= 0.0) return null
        val venueAverage = venue.sumOf { it.first * it.second } / weightSum
        val shrinkage = venueMatches.size / (venueMatches.size + 4.0)
        return (venueAverage - overall) * shrinkage
    }

    private fun fatigue(history: List<RatingMatch>, teamId: String, asOfEpochMs: Long): Double {
        val matches = history.filter { it.homeTeamId == teamId || it.awayTeamId == teamId }
        if (matches.isEmpty()) return 0.0
        val latest = matches.maxOf { it.kickoffEpochMs }
        val restDays = max(0L, ((asOfEpochMs - latest) / DAY_MS).roundToLong()).toInt()
        val congested = matches.count { asOfEpochMs - it.kickoffEpochMs in 0..(14 * DAY_MS).toLong() }
        val shortRest = max(0, 4 - restDays) * 0.02
        val congestion = max(0, congested - 4) * 0.015
        return (shortRest + congestion).coerceAtMost(0.12)
    }

    private fun goalDifference(match: RatingMatch, teamId: String): Int =
        if (match.homeTeamId == teamId) match.homeGoals - match.awayGoals else match.awayGoals - match.homeGoals
}
