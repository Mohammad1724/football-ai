package com.footballai.engine

import com.footballai.engine.ratings.DixonColesConfig
import com.footballai.engine.ratings.DixonColesFitter
import com.footballai.engine.ratings.EloConfig
import com.footballai.engine.ratings.EloRatingSystem
import com.footballai.engine.ratings.RatingBook
import com.footballai.engine.ratings.RatingBookConfig
import com.footballai.engine.ratings.RatingMatch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingsTest {

    @Test
    fun `dixon coles recovers the ordering of team strengths`() {
        val league = TestLeagueFactory.create(seed = 7L, teamCount = 8, seasons = 5)
        val fit = DixonColesFitter.fit(league.matches, DixonColesConfig(maxIterations = 500))
        assertNotNull(fit)
        assertTrue(fit.matchesUsed >= 200, "the fit should use the whole synthetic history")

        val teams = league.attack.keys.sorted()
        val trueLogAttack = teams.map { kotlin.math.ln(league.attack.getValue(it)) }
        val fittedLogAttack = teams.map { kotlin.math.ln(fit.attack.getValue(it)) }
        val correlation = TestLeagueFactory.correlation(trueLogAttack, fittedLogAttack)
        assertTrue(correlation > 0.7, "attack correlation was $correlation")

        val meanAttack = fit.attack.values.average()
        assertEquals(1.0, meanAttack, 0.15, "attack parameters must be centred around one")
        assertTrue(fit.rho in -0.2..0.2)
        assertTrue(fit.logLikelihood.isFinite())
    }

    @Test
    fun `dixon coles returns null when there is not enough data`() {
        val league = TestLeagueFactory.create(seed = 3L, teamCount = 4, seasons = 1)
        val fit = DixonColesFitter.fit(league.matches.take(10), DixonColesConfig(minMatches = 30))
        assertNull(fit)
    }

    @Test
    fun `stronger teams get higher expected goals`() {
        val league = TestLeagueFactory.create(seed = 11L, teamCount = 10, seasons = 6)
        val fit = assertNotNull(
            DixonColesFitter.fit(league.matches, DixonColesConfig(maxIterations = 500)),
        )
        val best = league.attack.maxByOrNull { it.value }!!.key
        val worst = league.attack.minByOrNull { it.value }!!.key
        val (strongHome, _) = fit.expectedGoals(best, worst)
        val (weakHome, _) = fit.expectedGoals(worst, best)
        assertTrue(strongHome > weakHome, "the strongest attack must score more")
    }

    @Test
    fun `elo updates are symmetric and zero sum`() {
        val elo = EloRatingSystem()
        elo.update("A", "B", 2, 0, 1_600_000_000_000L)
        val ratingA = elo.storedRating("A")
        val ratingB = elo.storedRating("B")
        assertTrue(ratingA > 1500.0)
        assertTrue(ratingB < 1500.0)
        assertEquals(3000.0, ratingA + ratingB, 1e-6)
    }

    @Test
    fun `elo draw moves the ratings only slightly`() {
        val elo = EloRatingSystem(EloConfig(homeAdvantageElo = 0.0))
        elo.update("A", "B", 1, 1, 1_600_000_000_000L)
        assertEquals(1500.0, elo.storedRating("A"), 1e-9)
        assertEquals(1500.0, elo.storedRating("B"), 1e-9)
    }

    @Test
    fun `elo expectation is monotone in the rating difference`() {
        assertTrue(EloRatingSystem.expectedScore(100.0) > EloRatingSystem.expectedScore(0.0))
        assertTrue(EloRatingSystem.expectedScore(0.0) > EloRatingSystem.expectedScore(-100.0))
        assertEquals(0.5, EloRatingSystem.expectedScore(0.0), 1e-9)
    }

    @Test
    fun `elo ratings decay toward the mean over time`() {
        val elo = EloRatingSystem(EloConfig(regressionHalfLifeDays = 300.0))
        val kickoff = 1_600_000_000_000L
        elo.update("A", "B", 4, 0, kickoff)
        val fresh = elo.rating("A", kickoff)
        val later = elo.rating("A", kickoff + 365L * 86_400_000L)
        assertTrue(abs(later - 1500.0) < abs(fresh - 1500.0))
    }

    @Test
    fun `rating book ignores matches played after the prediction timestamp`() {
        val kickoff = 1_600_000_000_000L
        val book = RatingBook(RatingBookConfig(minMatchesForFit = 2))
        book.add("L1", RatingMatch("A", "B", 5, 0, kickoff))
        book.add("L1", RatingMatch("A", "B", 5, 0, kickoff + 7 * 86_400_000L))

        val before = book.strengths("L1", "A", "B", kickoff - 1)
        assertEquals(1.0, before.first.attack, 1e-9)
        assertEquals(0, before.first.matchesUsed)
        assertEquals(EloConfig().initialRating, before.first.elo, 1e-9)

        val after = book.strengths("L1", "A", "B", kickoff + 8 * 86_400_000L)
        assertTrue(after.first.elo > before.first.elo)
        assertTrue(book.matchCount("L1") == 2)
    }

    @Test
    fun `rating book baseline reflects historical averages`() {
        val kickoff = 1_600_000_000_000L
        val book = RatingBook()
        repeat(10) { index ->
            book.add("L1", RatingMatch("A", "B", 2, 1, kickoff + index * 86_400_000L))
        }
        val baseline = book.baseline("L1", kickoff + 10L * 86_400_000L)
        assertEquals(2.0, baseline.averageHomeGoals, 0.001)
        assertEquals(1.0, baseline.averageAwayGoals, 0.001)
        assertEquals(10, baseline.sampleSize)
    }

    @Test
    fun `rating book falls back when there is no history`() {
        val book = RatingBook()
        val baseline = book.baseline("missing", 1_600_000_000_000L)
        assertEquals(0, baseline.sampleSize)
        assertNull(book.fit("missing", 1_600_000_000_000L))
    }
}
