package com.footballai.app

import com.footballai.app.domain.LineupEvidence
import com.footballai.app.domain.LineupImpact
import com.footballai.engine.model.ExplanationCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lineup must never become a guess dressed as a measurement.
 *
 * These tests pin the guards: too few recorded goals means silence, a name list that does
 * not line up means silence, and however bad the news is the attack never drops by more
 * than six percent.
 */
class LineupImpactTest {

    private val starters = listOf(
        "Alisson", "Alexander-Arnold", "Van Dijk", "Konate", "Robertson",
        "Mac Allister", "Szoboszlai", "Salah", "Gakpo", "Diaz", "Nunez",
    )

    private fun goals(vararg pairs: Pair<String, Int>) = pairs.toMap()

    @Test
    fun `too few recorded goals is not an opinion`() {
        val thin = goals("Salah" to 4, "Diaz" to 3, "Nunez" to 2)
        assertNull(LineupImpact.assess(thin, starters), "eight goals cannot measure a season")
    }

    @Test
    fun `a full strength eleven is not adjusted`() {
        val all = goals("Salah" to 12, "Diaz" to 8, "Nunez" to 6, "Gakpo" to 3)
        val evidence = assertNotNull(LineupImpact.assess(all, starters))
        assertEquals(0.0, evidence.missingShare, 1e-9)
        assertEquals(1.0, LineupImpact.attackFactor(evidence), 1e-9)
        assertNull(
            LineupImpact.adjustment(evidence, evidence),
            "nothing moved, so no adjustment should be reported",
        )
    }

    @Test
    fun `missing scorers lower the attack and the cap holds`() {
        val withSalah = goals("Salah" to 18, "Diaz" to 6, "Nunez" to 4)
        val salahOut = starters.filterNot { it == "Salah" }
        val evidence = assertNotNull(LineupImpact.assess(withSalah, salahOut))
        assertEquals(18, evidence.goalsMissing)
        val factor = LineupImpact.attackFactor(evidence)
        assertTrue(factor < 1.0 && factor >= 1.0 - LineupImpact.MAX_ATTACK_REDUCTION - 1e-9)

        val nobodyScoring = goals("Salah" to 18, "Diaz" to 10, "Nunez" to 8)
        // Almost everyone who scores is out; one remains, so this is not the naming guard.
        val bareXi = listOf("Nunez", "Alisson", "Van Dijk")
        val extreme = assertNotNull(LineupImpact.assess(nobodyScoring, bareXi))
        assertTrue(
            LineupImpact.attackFactor(extreme) >= 1.0 - LineupImpact.MAX_ATTACK_REDUCTION - 1e-9,
            "the reduction is capped, never proportional to a bad day of data",
        )
    }

    @Test
    fun `names that do not line up produce silence, not eleven absences`() {
        // Lineups spell players "M. Salah" while events say "Salah"; nothing matches.
        val mismatched = starters.map { "M. $it" }
        val all = goals("Salah" to 12, "Diaz" to 8, "Nunez" to 6)
        assertNull(
            LineupImpact.assess(all, mismatched),
            "an unmatched list is a naming problem, not a team news story",
        )
    }

    @Test
    fun `only the affected side is reduced`() {
        val home = LineupEvidence(observedGoals = 40, goalsMissing = 24, missingShare = 0.6, scorersInXi = 2)
        val adjustment = assertNotNull(LineupImpact.adjustment(home, null))
        assertTrue(adjustment.homeFactor < 1.0, "the home attack should be reduced")
        assertEquals(1.0, adjustment.awayFactor, 1e-9)
        assertEquals(ExplanationCode.LINEUP_ABSENCE_HOME, adjustment.reason)
    }
}
