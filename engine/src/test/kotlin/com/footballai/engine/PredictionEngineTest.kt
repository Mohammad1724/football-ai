package com.footballai.engine

import com.footballai.engine.model.DataQuality
import com.footballai.engine.model.DataQualityLevel
import com.footballai.engine.model.LeagueBaseline
import com.footballai.engine.model.LiveInput
import com.footballai.engine.model.MatchOutcome
import com.footballai.engine.model.MissingDataItem
import com.footballai.engine.model.PreMatchInput
import com.footballai.engine.model.TeamStrength
import com.footballai.engine.prediction.EngineConfig
import com.footballai.engine.prediction.MatchPredictionEngine
import com.footballai.engine.quality.DataQualityAssessor
import com.footballai.engine.quality.QualityInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PredictionEngineTest {

    private val engine = MatchPredictionEngine()

    private fun quality(): DataQuality = DataQuality(
        level = DataQualityLevel.MEDIUM,
        completeness = 0.6,
        missing = listOf(MissingDataItem.LINEUPS),
        historicalMatchesUsed = 200,
    )

    private fun input(
        homeAttack: Double = 1.2,
        awayAttack: Double = 1.0,
        homeDefence: Double = 0.95,
        awayDefence: Double = 1.05,
        homeElo: Double = 1520.0,
        awayElo: Double = 1480.0,
    ) = PreMatchInput(
        home = TeamStrength("H", homeAttack, homeDefence, homeElo, 25),
        away = TeamStrength("A", awayAttack, awayDefence, awayElo, 25),
        baseline = LeagueBaseline(1.5, 1.15, 380),
        quality = quality(),
        asOfEpochMs = 1_700_000_000_000L,
    )

    @Test
    fun `pre match probabilities behave like a distribution`() {
        val result = engine.predictPreMatch(input())
        val markets = result.markets
        assertEquals(1.0, markets.homeWin + markets.draw + markets.awayWin, 1e-9)
        assertEquals(1.0, markets.over.getValue(2.5) + markets.under.getValue(2.5), 1e-9)
        assertTrue(result.expectedHomeGoals > 0.0 && result.expectedAwayGoals > 0.0)
        assertTrue(result.mostLikelyScores.isNotEmpty())
    }

    @Test
    fun `stronger home team is favoured`() {
        val strongHome = engine.predictPreMatch(input(homeAttack = 1.6, homeElo = 1620.0))
        val weakHome = engine.predictPreMatch(input(homeAttack = 0.7, homeElo = 1400.0))
        assertTrue(strongHome.markets.homeWin > weakHome.markets.homeWin)
        assertEquals(MatchOutcome.HOME_WIN, strongHome.mostLikelyOutcome())
    }

    @Test
    fun `expected goals follow the multiplied strengths`() {
        val result = engine.predictPreMatch(
            input(homeAttack = 1.3, awayAttack = 1.1, homeDefence = 0.9, awayDefence = 1.1, homeElo = 1500.0, awayElo = 1500.0),
        )
        assertEquals(1.5 * 1.3 * 1.1, result.expectedHomeGoals, 1e-6)
        assertEquals(1.15 * 1.1 * 0.9, result.expectedAwayGoals, 1e-6)
    }

    @Test
    fun `dixon coles rho is applied and reported`() {
        val result = engine.predictPreMatch(input().copy(dixonColesRho = -0.06))
        assertTrue(result.model.dixonColesEnabled)
        assertEquals(-0.06, result.model.rho, 1e-9)
    }

    @Test
    fun `explanations are ordered by importance and never empty`() {
        val result = engine.predictPreMatch(input())
        assertTrue(result.explanations.isNotEmpty())
        val weights = result.explanations.map { it.weight }
        assertEquals(weights.sortedDescending(), weights)
    }

    @Test
    fun `live prediction at the whistle is decided by the current score`() {
        val pre = input()
        val live = engine.predictLive(
            LiveInput(preMatch = pre, minute = 89, homeGoals = 2, awayGoals = 0),
        )
        assertTrue(live.markets.homeWin > 0.95, "a two-goal lead in minute 89 must be decisive")
        assertEquals(1.0, live.markets.homeWin + live.markets.draw + live.markets.awayWin, 1e-9)
        val context = assertNotNull(live.live)
        assertEquals(89, context.minute)
        assertEquals(1, context.remainingMinutes)
    }

    @Test
    fun `a one all draw late in the game keeps the draw likely`() {
        val live = engine.predictLive(
            LiveInput(preMatch = input(), minute = 70, homeGoals = 1, awayGoals = 1),
        )
        assertTrue(live.markets.draw > live.markets.homeWin)
        assertTrue(live.markets.draw > live.markets.awayWin)
        assertTrue(live.markets.bttsYes > 0.99)
    }

    @Test
    fun `remaining goal expectation shrinks with elapsed time`() {
        val early = engine.predictLive(LiveInput(preMatch = input(), minute = 10, homeGoals = 0, awayGoals = 0))
        val late = engine.predictLive(LiveInput(preMatch = input(), minute = 80, homeGoals = 0, awayGoals = 0))
        val earlyRemaining = assertNotNull(early.live).remainingHomeExpectedGoals
        val lateRemaining = assertNotNull(late.live).remainingHomeExpectedGoals
        assertTrue(earlyRemaining > lateRemaining)
    }

    @Test
    fun `red cards shift the live probabilities`() {
        val base = engine.predictLive(LiveInput(preMatch = input(), minute = 55, homeGoals = 0, awayGoals = 0))
        val homeDown = engine.predictLive(
            LiveInput(preMatch = input(), minute = 55, homeGoals = 0, awayGoals = 0, homeRedCards = 1),
        )
        assertTrue(homeDown.markets.homeWin < base.markets.homeWin)
        assertTrue(homeDown.markets.awayWin > base.markets.awayWin)
    }

    @Test
    fun `live markets stay normalised with incomplete statistics`() {
        val live = engine.predictLive(
            LiveInput(
                preMatch = input(),
                minute = 63,
                homeGoals = 1,
                awayGoals = 0,
                homeShots = null,
                awayShots = null,
            ),
        )
        assertEquals(1.0, live.markets.homeWin + live.markets.draw + live.markets.awayWin, 1e-9)
        assertTrue(live.quality.notes.isNotEmpty())
    }

    @Test
    fun `invalid minute values are clamped`() {
        val live = engine.predictLive(LiveInput(preMatch = input(), minute = 130, homeGoals = 1, awayGoals = 1))
        val context = assertNotNull(live.live)
        assertEquals(90, context.minute)
        assertEquals(0, context.remainingMinutes)
        assertTrue(live.markets.draw > 0.85, "with no time left the drawn scoreline dominates")
        assertTrue(live.markets.homeWin + live.markets.awayWin < 0.15)
    }

    @Test
    fun `zero history still produces a coherent prediction`() {
        val engineWithTinySample = MatchPredictionEngine(EngineConfig(eloBlendWeight = 0.0))
        val result = engineWithTinySample.predictPreMatch(
            PreMatchInput(
                home = TeamStrength.neutral("H"),
                away = TeamStrength.neutral("A"),
                baseline = LeagueBaseline.FALLBACK,
                quality = DataQualityAssessor.assess(QualityInputs()),
                asOfEpochMs = 0L,
            ),
        )
        assertEquals(1.0, result.markets.homeWin + result.markets.draw + result.markets.awayWin, 1e-9)
        assertEquals(DataQualityLevel.INSUFFICIENT, result.quality.level)
    }
}
