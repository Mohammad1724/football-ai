package com.footballai.app.domain

import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.engine.model.ExplanationCode
import com.footballai.engine.model.LambdaAdjustment
import com.footballai.engine.model.LambdaAdjustmentLabel
import kotlin.math.min

/**
 * What a confirmed lineup says about the goals a team will miss.
 *
 * The app has no player ratings and no source publishes them, so this deliberately does
 * not pretend to know how good a player is. It uses the one thing it does record: the
 * goals attributed to named players in matches whose events were synced. If half of the
 * goals this club has scored came from players who are not in today's starting eleven,
 * the attack is reduced — and never by more than [MAX_ATTACK_REDUCTION].
 *
 * Three guards keep it honest:
 *  - fewer than [MIN_OBSERVED_GOALS] recorded goals means no opinion at all;
 *  - if *none* of the club's scorers is in the XI, the two name lists probably disagree
 *    about spelling rather than eleven players being absent, so it stays silent;
 *  - a player who left the club looks like an absence until his goals leave the window,
 *    which is why the window is bounded and the effect is small.
 */
data class LineupEvidence(
    val observedGoals: Int,
    val goalsMissing: Int,
    /** Share of the recorded goals scored by players not in today's XI, 0..1. */
    val missingShare: Double,
    /** How many known scorers are starting; used as the naming guard. */
    val scorersInXi: Int,
)

object LineupImpact {

    const val MIN_OBSERVED_GOALS = 12

    /** The adjustment is capped here, whatever the numbers say. */
    const val MAX_ATTACK_REDUCTION = 0.06

    /** Missing share at which the full reduction applies. Half a squad's goals is plenty. */
    private const val SHARE_FOR_FULL_REDUCTION = 0.5

    /** Below this the adjustment is not worth telling the user about. */
    private const val MIN_VISIBLE_REDUCTION = 0.005

    fun assess(goalsByPlayer: Map<String, Int>, starters: Collection<String>): LineupEvidence? {
        val total = goalsByPlayer.values.filter { it > 0 }.sum()
        if (total < MIN_OBSERVED_GOALS) return null
        val xi = starters.map { NameLocalization.normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (xi.isEmpty()) return null
        // Sources spell the same player differently in lineups ("M. Salah") and in events
        // ("Mohamed Salah"), so a surname is accepted as the same person. Inside one team
        // list that is unambiguous; it is only ever compared within one club.
        val surnames = xi.mapNotNull { it.substringAfterLast(' ', "").takeIf { s -> s.length >= 4 } }
            .toSet()
        var missing = 0
        var present = 0
        for ((name, goals) in goalsByPlayer) {
            if (goals <= 0) continue
            val key = NameLocalization.normalize(name)
            if (key.isEmpty()) continue
            val surname = key.substringAfterLast(' ', "")
            val isStarting = key in xi || (surname.length >= 4 && surname in surnames)
            if (isStarting) present++ else missing += goals
        }
        if (present == 0) return null
        val share = missing.toDouble() / total
        return LineupEvidence(
            observedGoals = total,
            goalsMissing = missing,
            missingShare = share.coerceIn(0.0, 1.0),
            scorersInXi = present,
        )
    }

    /** Multiplier applied to a team's expected goals for this fixture. */
    fun attackFactor(evidence: LineupEvidence): Double =
        1.0 - MAX_ATTACK_REDUCTION * min(1.0, evidence.missingShare / SHARE_FOR_FULL_REDUCTION)

    /**
     * The bounded adjustment for one fixture, or null when the lineups say nothing usable
     * or nothing material. Only the team whose scoring is actually affected is moved.
     */
    fun adjustment(home: LineupEvidence?, away: LineupEvidence?): LambdaAdjustment? {
        val homeFactor = home?.let { attackFactor(it) } ?: 1.0
        val awayFactor = away?.let { attackFactor(it) } ?: 1.0
        val homeMoved = 1.0 - homeFactor >= MIN_VISIBLE_REDUCTION
        val awayMoved = 1.0 - awayFactor >= MIN_VISIBLE_REDUCTION
        if (!homeMoved && !awayMoved) return null
        return LambdaAdjustment(
            label = LambdaAdjustmentLabel.CONFIRMED_LINEUPS,
            homeFactor = homeFactor,
            awayFactor = awayFactor,
            // Explanations are attached per adjustment, so the code names the side that is
            // short of goals; when both are, the home side is reported (the away factor is
            // still applied and is visible in the card's own numbers).
            reason = if (homeMoved) {
                ExplanationCode.LINEUP_ABSENCE_HOME
            } else {
                ExplanationCode.LINEUP_ABSENCE_AWAY
            },
        )
    }

    /** Persian-facing sentence for the evidence list: what was observed and what is missing. */
    fun describe(evidence: LineupEvidence): String =
        "${evidence.goalsMissing} از ${evidence.observedGoals} گل"
}
