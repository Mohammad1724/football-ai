package com.footballai.engine

import com.footballai.engine.eval.BacktestConfig
import com.footballai.engine.eval.Backtester
import com.footballai.engine.eval.HistoricalRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A league's Brier score only means something next to that league's own base rate.
 *
 * Leagues differ in how predictable they are: a league that is 70% home wins is easier to
 * score on than an even one. Judging a model by raw Brier across leagues hides that, which
 * is why the report carries a base rate per competition as well as an overall one.
 */
class PerCompetitionVerdictTest {

    private fun records(competitionId: String, seed: Long, teamCount: Int): List<HistoricalRecord> {
        val league = TestLeagueFactory.create(seed = seed, teamCount = teamCount, seasons = 5)
        return league.matches.mapIndexed { index, match ->
            HistoricalRecord(
                matchId = "$competitionId-$index",
                competitionId = competitionId,
                homeTeamId = match.homeTeamId,
                awayTeamId = match.awayTeamId,
                homeGoals = match.homeGoals,
                awayGoals = match.awayGoals,
                kickoffEpochMs = match.kickoffEpochMs,
            )
        }
    }

    @Test
    fun `every judged league is reported with its own base rate`() {
        val all = records("SYN-A", 5L, 8) + records("SYN-B", 6L, 10)
        val report = Backtester(BacktestConfig(refitEveryMatches = 120)).run(all)

        assertTrue(report.byCompetition.size >= 2, "both synthetic leagues should be evaluated")
        assertEquals(
            report.byCompetition.keys,
            report.byCompetitionBaseRate.keys,
            "a league without a base rate cannot be judged",
        )
        for ((id, baseRate) in report.byCompetitionBaseRate) {
            assertTrue(baseRate.isFinite(), "base rate for $id was not finite")
            // Log loss three-way guessing is ~1.1; a Brier above 0.75 would mean the rate
            // is not a distribution at all, which is the failure this asserts against.
            assertTrue(baseRate in 0.2..0.75, "base rate for $id looked wrong: $baseRate")
        }
    }

    @Test
    fun `a league the model learns is credited, not averaged away`() {
        val report = Backtester(BacktestConfig(refitEveryMatches = 120))
            .run(records("SYN-A", 5L, 8))
        val baseRate = report.byCompetitionBaseRate.getValue("SYN-A")
        assertTrue(report.model.brier < baseRate, "a learnable synthetic league must beat its base rate")
    }
}
