package com.footballai.engine

import com.footballai.engine.model.DataQuality
import com.footballai.engine.model.DataQualityLevel
import com.footballai.engine.model.LeagueBaseline
import com.footballai.engine.model.PreMatchInput
import com.footballai.engine.model.TeamStrength
import com.footballai.engine.prediction.MatchPredictionEngine
import com.footballai.engine.ratings.DixonColesFitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two changes that exist only to make thin evidence behave sanely.
 *
 * Neither of them makes a well-covered league sharper; both stop a league with two
 * rounds played from producing extreme numbers out of nothing.
 */
class ThinEvidenceTest {

    private val engine = MatchPredictionEngine()

    private fun quality(): DataQuality = DataQuality(
        level = DataQualityLevel.MEDIUM,
        completeness = 0.6,
        missing = emptyList(),
        historicalMatchesUsed = 60,
    )

    private fun input(
        homeMatches: Int,
        awayMatches: Int,
        homeElo: Double = 1620.0,
        awayElo: Double = 1380.0,
    ) = PreMatchInput(
        home = TeamStrength("H", 1.0, 1.0, homeElo, homeMatches),
        away = TeamStrength("A", 1.0, 1.0, awayElo, awayMatches),
        baseline = LeagueBaseline(1.5, 1.15, 120),
        quality = quality(),
        asOfEpochMs = 1_700_000_000_000L,
    )

    @Test
    fun `a strength is only believed as far as the matches behind it`() {
        // A team that scored twice the league average: with two matches we keep a fifth of
        // that deviation, with a full season we keep almost all of it.
        val thin = DixonColesFitter.shrinkTowardsLeagueMean(2.0, 2, 8.0)
        val thick = DixonColesFitter.shrinkTowardsLeagueMean(2.0, 40, 8.0)
        assertTrue(thin > 1.0, "shrinkage must never invert a team, got $thin")
        assertTrue(thin < thick, "two matches must be believed less than forty")
        assertEquals(1.8333, thick, 1e-3)
        assertEquals(1.0, DixonColesFitter.shrinkTowardsLeagueMean(2.0, 0, 8.0), 1e-9)
    }

    @Test
    fun `shrinkage keeps a below average team below average`() {
        val thin = DixonColesFitter.shrinkTowardsLeagueMean(0.5, 3, 8.0)
        assertTrue(thin in 0.5..1.0, "expected a value between the fit and the mean, got $thin")
    }

    @Test
    fun `the elo tilt reaches further when the fit has seen almost nothing`() {
        val thin = engine.effectiveEloBlendWeight(input(homeMatches = 0, awayMatches = 0))
        val half = engine.effectiveEloBlendWeight(input(homeMatches = 10, awayMatches = 10))
        val thick = engine.effectiveEloBlendWeight(input(homeMatches = 20, awayMatches = 20))
        assertEquals(0.40, thin, 1e-9)
        assertEquals(0.15, thick, 1e-9)
        assertTrue(thin > half && half > thick, "the weight must fall as evidence grows")
    }

    @Test
    fun `a clear elo gap separates the teams when the league fit is empty`() {
        // Both teams are league-average by the fit; only Elo knows they are not.
        val withHistory = engine.predictPreMatch(input(homeMatches = 25, awayMatches = 25))
        val withoutHistory = engine.predictPreMatch(input(homeMatches = 0, awayMatches = 0))
        assertTrue(
            withoutHistory.markets.homeWin > withHistory.markets.homeWin,
            "a 240-point Elo gap should count for more, not less, when nothing else is known",
        )
        assertTrue(withoutHistory.markets.homeWin < 0.95, "and must never become a certainty")
    }
}
