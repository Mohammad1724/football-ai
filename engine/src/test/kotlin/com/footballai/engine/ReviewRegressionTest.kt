package com.footballai.engine

import com.footballai.engine.eval.BacktestConfig
import com.footballai.engine.eval.Backtester
import com.footballai.engine.eval.HistoricalRecord
import com.footballai.engine.model.*
import com.footballai.engine.poisson.ScoreMatrix
import com.footballai.engine.prediction.EngineConfig
import com.footballai.engine.prediction.MatchPredictionEngine
import com.footballai.engine.quality.DataQualityAssessor
import com.footballai.engine.quality.QualityInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ReviewRegressionTest {
    private fun input() = PreMatchInput(
        TeamStrength.neutral("H"), TeamStrength.neutral("A"),
        LeagueBaseline(1.5, 1.1, 400),
        quality = DataQualityAssessor.assess(QualityInputs()), asOfEpochMs = 1L,
    )

    @Test fun `zero horizon has no future goals and a certain current score`() {
        val result = MatchPredictionEngine().predictLive(LiveInput(input(), 90, 2, 1))
        assertEquals(0.0, result.expectedHomeGoals)
        assertEquals(0.0, result.expectedAwayGoals)
        assertEquals(1.0, result.markets.homeWin, 1e-12)
        assertEquals(1.0, result.live!!.noMoreGoals, 1e-12)
        assertEquals(0.0, result.live!!.nextGoalHome)
        assertEquals(0.0, result.live!!.nextGoalAway)
    }

    @Test fun `zero poisson matrix is a point mass`() {
        val matrix = ScoreMatrix(0.0, 0.0)
        assertEquals(1.0, matrix.probability(0, 0))
        assertEquals(1.0, matrix.totalMass())
        assertEquals(0.0, matrix.over(0.5))
    }

    @Test fun `total shots use total shot baseline not shots on target`() {
        val engine = MatchPredictionEngine(EngineConfig(averageShotsPerTeam = 12.0))
        val neutral = engine.predictLive(LiveInput(input(), 45, 0, 0))
        val normalShots = engine.predictLive(LiveInput(input(), 45, 0, 0, homeShots = 6, awayShots = 6))
        assertEquals(neutral.expectedHomeGoals, normalShots.expectedHomeGoals, 1e-12)
        assertEquals(neutral.expectedAwayGoals, normalShots.expectedAwayGoals, 1e-12)
    }

    @Test fun `market comparison uses the exact same subset`() {
        val records = TestLeagueFactory.create(seed = 21L, teamCount = 8, seasons = 6, matchesPerPairPerSeason = 1).matches.mapIndexed { i, m ->
            HistoricalRecord("m$i", "SYN", m.homeTeamId, m.awayTeamId,
                m.homeGoals, m.awayGoals, m.kickoffEpochMs,
                if (i % 2 == 0) 2.0 else null, if (i % 2 == 0) 3.0 else null, if (i % 2 == 0) 4.0 else null)
        }
        val report = Backtester().run(records)
        assertTrue(report.market!!.count < report.model.count)
        assertEquals(report.market!!.count, report.modelOnMarketSubset!!.count)
        assertEquals(report.modelOnMarketSubset!!.brier < report.market!!.brier, report.beatsMarket)
    }

    @Test fun `zero refit interval is rejected rather than looping forever`() {
        assertFailsWith<IllegalArgumentException> { Backtester(BacktestConfig(refitEveryMatches = 0)) }
    }

    @Test fun `renaming simultaneous matches does not change aggregate predictions`() {
        val records = TestLeagueFactory.create(seed = 21L, teamCount = 8, seasons = 6, matchesPerPairPerSeason = 1).matches.mapIndexed { i, m ->
            HistoricalRecord("m%04d".format(i), "SYN", m.homeTeamId, m.awayTeamId,
                m.homeGoals, m.awayGoals, (i / 4).toLong() * 604800000L)
        }
        val config = BacktestConfig(refitEveryMatches = 7)
        val first = Backtester(config).run(records)
        val renamed = Backtester(config).run(records.mapIndexed { i, r -> r.copy(matchId = "m%04d".format(records.size - i)) })
        assertTrue(first.matchesEvaluated > 0)
        assertEquals(first.model.brier, renamed.model.brier, 1e-8)
    }
    @Test fun `changing a simultaneous result cannot affect its peers predictions`() {
        val rows = TestLeagueFactory.create(teamCount = 8, seasons = 2, matchesPerPairPerSeason = 1).matches
            .mapIndexed { i, m -> HistoricalRecord("m$i", "SYN", m.homeTeamId, m.awayTeamId,
                m.homeGoals, m.awayGoals, m.kickoffEpochMs) }
        val time = rows.last().kickoffEpochMs + 604800000L
        val group = listOf(
            HistoricalRecord("a", "SYN", "T01", "T02", 0, 0, time),
            HistoricalRecord("b", "SYN", "T03", "T04", 1, 0, time),
        )
        val tester = Backtester(BacktestConfig(refitEveryMatches = 1))
        val before = tester.evaluateCompetition("SYN", rows + group).last()
        val after = tester.evaluateCompetition("SYN", rows + listOf(group[0].copy(homeGoals = 9), group[1])).last()
        assertEquals(before.homeWin, after.homeWin, 1e-12)
        assertEquals(before.draw, after.draw, 1e-12)
        assertEquals(before.awayWin, after.awayWin, 1e-12)
    }

}
