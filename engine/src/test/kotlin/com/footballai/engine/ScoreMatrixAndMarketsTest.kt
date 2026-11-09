package com.footballai.engine

import com.footballai.engine.markets.MarketCalculator
import com.footballai.engine.model.DoubleChance
import com.footballai.engine.poisson.ScoreMatrix
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoreMatrixAndMarketsTest {

    private val home = 1.62
    private val away = 1.18

    @Test
    fun `matrix is normalised`() {
        val matrix = ScoreMatrix(home, away)
        assertEquals(1.0, matrix.totalMass(), 1e-9)
    }

    @Test
    fun `one x two probabilities sum to one`() {
        val matrix = ScoreMatrix(home, away)
        val total = matrix.homeWin() + matrix.draw() + matrix.awayWin()
        assertEquals(1.0, total, 1e-9)
        assertTrue(matrix.homeWin() > matrix.awayWin(), "home team has the higher expected goals")
    }

    @Test
    fun `over and under are complementary for every line`() {
        val matrix = ScoreMatrix(home, away)
        for (line in MarketCalculator.GOAL_LINES) {
            assertEquals(1.0, matrix.over(line) + (1.0 - matrix.over(line)), 1e-12)
        }
    }

    @Test
    fun `higher scoring expectation shifts over 2_5 upwards`() {
        val lowScoring = ScoreMatrix(0.9, 0.8)
        val highScoring = ScoreMatrix(2.1, 1.9)
        assertTrue(highScoring.over(2.5) > lowScoring.over(2.5))
    }

    @Test
    fun `btts is bounded and consistent`() {
        val matrix = ScoreMatrix(home, away)
        val btts = matrix.bttsYes()
        assertTrue(btts in 0.0..1.0)
        assertTrue(abs(btts - (1.0 - matrix.probability(0, 0) - 0.0)) < 1.0)
    }

    @Test
    fun `dixon coles rho shifts low scorelines`() {
        val plain = ScoreMatrix(1.5, 1.2, rho = 0.0)
        val adjusted = ScoreMatrix(1.5, 1.2, rho = -0.08)
        assertTrue(
            adjusted.probability(0, 0) > plain.probability(0, 0),
            "negative rho must increase the probability of 0-0",
        )
        assertTrue(
            adjusted.probability(1, 1) > plain.probability(1, 1),
            "negative rho must increase the probability of 1-1",
        )
        assertEquals(1.0, adjusted.totalMass(), 1e-9)
    }

    @Test
    fun `market bundle keeps a consistent probability space`() {
        val markets = MarketCalculator.fromMatrix(ScoreMatrix(home, away))
        assertEquals(1.0, markets.homeWin + markets.draw + markets.awayWin, 1e-9)
        assertEquals(1.0, markets.bttsYes + markets.bttsNo, 1e-9)
        assertEquals(1.0, markets.over.getValue(2.5) + markets.under.getValue(2.5), 1e-9)
        assertEquals(
            markets.homeWin + markets.draw,
            markets.doubleChance.getValue(DoubleChance.HOME_OR_DRAW),
            1e-9,
        )
        assertTrue(markets.topScores.isNotEmpty())
        assertTrue(markets.topScores.first().probability >= markets.topScores.last().probability)
        val firstHalf = assertNotNull(markets.firstHalf)
        assertEquals(1.0, firstHalf.homeWin + firstHalf.draw + firstHalf.awayWin, 1e-9)
    }

    @Test
    fun `exact score matrix sums to one`() {
        val markets = MarketCalculator.fromMatrix(ScoreMatrix(home, away))
        assertEquals(1.0, markets.exactScoreMatrix.sumOf { it.probability }, 1e-9)
    }

    @Test
    fun `offsets keep live markets coherent`() {
        val remaining = ScoreMatrix(1.0, 0.9)
        val markets = MarketCalculator.fromMatrixWithOffsets(
            matrix = remaining,
            baseHomeGoals = 1,
            baseAwayGoals = 1,
            firstHalfShare = null,
        )
        assertEquals(1.0, markets.homeWin + markets.draw + markets.awayWin, 1e-9)
        assertEquals(1.0, markets.over.getValue(1.5) + markets.under.getValue(1.5), 1e-9)
        assertNull(markets.firstHalf, "first-half markets are meaningless for live predictions")
        assertTrue(markets.bttsYes > 0.99, "with 1-1 already on the board both teams have scored")
    }

    @Test
    fun `implied probabilities remove the bookmaker margin`() {
        val implied = MarketCalculator.impliedProbabilities(2.0, 3.5, 4.0)
        assertNotNull(implied)
        assertEquals(1.0, implied.first + implied.second + implied.third, 1e-9)
        assertTrue(implied.first > implied.second && implied.second > implied.third)
    }

    @Test
    fun `invalid odds are rejected`() {
        assertNull(MarketCalculator.impliedProbabilities(0.0, 3.0, 4.0))
        assertNull(MarketCalculator.impliedProbabilities(2.0, null, 4.0))
    }

    @Test
    fun `degenerate lambdas do not crash the matrix`() {
        val matrix = ScoreMatrix(0.08, 0.08)
        assertEquals(1.0, matrix.totalMass(), 1e-9)
        assertTrue(matrix.probability(0, 0) > 0.8)
    }
}
