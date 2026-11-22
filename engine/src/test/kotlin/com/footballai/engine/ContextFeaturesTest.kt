package com.footballai.engine

import com.footballai.engine.model.LambdaAdjustmentLabel
import com.footballai.engine.ratings.ContextFeatures
import com.footballai.engine.ratings.RatingMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextFeaturesTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun match(
        home: String,
        away: String,
        homeGoals: Int,
        awayGoals: Int,
        daysAgo: Long,
    ) = RatingMatch(home, away, homeGoals, awayGoals, now - daysAgo * day)

    @Test
    fun `strong recent home form increases only bounded home expectation`() {
        val history = (1L..5L).flatMap { ago ->
            listOf(
                match("H", "X$ago", 3, 0, ago + 2),
                match("Y$ago", "A", 2, 0, ago + 2),
            )
        }
        val adjustments = ContextFeatures.adjustments(history, "H", "A", now)
        val form = adjustments.single { it.label == LambdaAdjustmentLabel.RECENT_FORM }
        assertTrue(form.homeFactor > 1.0)
        assertTrue(form.awayFactor < 1.0)
        assertTrue(form.homeFactor <= 1.08)
        assertTrue(form.awayFactor >= 0.92)
    }

    @Test
    fun `future results never affect contextual features`() {
        val before = (1L..5L).flatMap { ago ->
            listOf(match("H", "X$ago", 1, 1, ago + 10), match("Y$ago", "A", 1, 1, ago + 10))
        }
        val expected = ContextFeatures.adjustments(before, "H", "A", now)
        val withFuture = before + listOf(match("H", "A", 8, 0, -2))
        assertEquals(expected, ContextFeatures.adjustments(withFuture, "H", "A", now))
    }

    @Test
    fun `short rest penalises the congested team`() {
        val history = (1L..5L).flatMap { ago ->
            listOf(
                match("H", "X$ago", 1, 1, if (ago == 1L) 1 else ago * 3),
                match("Y$ago", "A", 1, 1, ago * 3 + 3),
            )
        }
        val congestion = ContextFeatures.adjustments(history, "H", "A", now)
            .single { it.label == LambdaAdjustmentLabel.FIXTURE_CONGESTION }
        assertTrue(congestion.homeFactor < 1.0)
        assertTrue(congestion.awayFactor > 1.0)
    }

    @Test
    fun `insufficient team samples produce neutral adjustments`() {
        val history = listOf(match("H", "A", 2, 1, 3))
        assertTrue(ContextFeatures.adjustments(history, "H", "A", now).isEmpty())
    }
}
