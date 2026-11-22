package com.footballai.engine

import com.footballai.engine.model.DataQuality
import com.footballai.engine.model.DataQualityLevel
import com.footballai.engine.model.LeagueBaseline
import com.footballai.engine.model.OutcomeTriple
import com.footballai.engine.model.PreMatchInput
import com.footballai.engine.model.TeamStrength
import com.footballai.engine.prediction.MatchPredictionEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The prices are information, not an answer sheet.
 *
 * The engine mixes them in by a bounded weight, leans on them harder when its own evidence
 * is thin, and keeps the whole score matrix consistent with whatever triple comes out.
 */
class MarketAlignmentTest {

    private val engine = MatchPredictionEngine()

    private fun quality(): DataQuality = DataQuality(
        level = DataQualityLevel.MEDIUM,
        completeness = 0.8,
        missing = emptyList(),
        historicalMatchesUsed = 300,
    )

    private fun input(market: OutcomeTriple?, matches: Int = 20): PreMatchInput = PreMatchInput(
        home = TeamStrength("H", 1.05, 1.0, 1505.0, matches),
        away = TeamStrength("A", 0.95, 1.0, 1495.0, matches),
        baseline = LeagueBaseline(1.45, 1.15, 380),
        quality = quality(),
        asOfEpochMs = 1_700_000_000_000L,
        market = market,
    )

    private val strongHomeMarket = OutcomeTriple(0.78, 0.14, 0.08)

    @Test
    fun `no prices means no alignment at all`() {
        val plain = engine.predictPreMatch(input(null))
        assertEquals(0.0, engine.marketAlignmentWeight(input(null)), 1e-12)
        assertNull(plain.preMarketProbabilities)
        assertTrue(plain.explanations.none { it.code.name == "MARKET_ALIGNED" })
    }

    @Test
    fun `prices move the headline towards them without replacing it`() {
        val plain = engine.predictPreMatch(input(null))
        val aligned = engine.predictPreMatch(input(strongHomeMarket))
        assertTrue(
            aligned.markets.homeWin > plain.markets.homeWin,
            "the market fancied the home side and the engine said otherwise",
        )
        assertTrue(
            aligned.markets.homeWin < strongHomeMarket.home,
            "the engine must keep its own weight, not copy the price",
        )
        assertEquals(
            1.0,
            aligned.markets.homeWin + aligned.markets.draw + aligned.markets.awayWin,
            1e-6,
            "the triple must still be a distribution",
        )
    }

    @Test
    fun `thin evidence leans on the market harder`() {
        val thin = engine.marketAlignmentWeight(input(strongHomeMarket, matches = 0))
        val thick = engine.marketAlignmentWeight(input(strongHomeMarket, matches = 20))
        assertTrue(thin > thick, "expected the thin sample to trust the prices more")
        assertEquals(0.35, thick, 1e-9)
        assertEquals(0.60, thin, 1e-9)
    }

    @Test
    fun `goal markets stay consistent with the aligned result`() {
        val aligned = engine.predictPreMatch(input(strongHomeMarket))
        for (line in aligned.markets.over.keys) {
            val over = aligned.markets.over.getValue(line)
            val under = aligned.markets.under.getValue(line)
            assertEquals(1.0, over + under, 1e-6, "over/under disagreed at line $line")
        }
        val scores = aligned.markets.topScores.take(3)
        assertTrue(scores.isNotEmpty())
        // The most likely score must belong to the side the headline now favours.
        val top = assertNotNull(aligned.markets.topScores.firstOrNull())
        assertTrue(top.homeGoals >= top.awayGoals, "the score list should follow the aligned 1X2")
        assertNotNull(aligned.preMarketProbabilities, "the unaligned triple must be kept for measurement")
    }
}
