package com.footballai.engine.ratings

import com.footballai.engine.model.ExplanationCode
import com.footballai.engine.model.LambdaAdjustment
import com.footballai.engine.model.LambdaAdjustmentLabel
import kotlin.math.abs

/**
 * Configuration for the injuries/suspensions adjustment.
 *
 * A team's "missing share" for a fixture is the share of its season minutes
 * (up to that fixture) belonging to players listed unavailable for it —
 * injured, suspended or otherwise out. [strength] converts the difference
 * between the two teams' missing shares into a symmetric expected-goals tilt.
 * A strength of zero disables the feature entirely.
 *
 * The production strength was fitted on 2021-22…2023-24 and validated on the
 * untouched 2024-25 + 2025-26 seasons (big-5, FotMob availability lists):
 * validation log loss 0.9843 → 0.9837 and accuracy 53.11% → 53.20% versus the
 * same model without the adjustment. See docs on the tuning harness.
 */
data class AvailabilityImpactConfig(
    val strength: Double = 0.3,
    /** Hard cap on the tilt, in line with the other bounded adjustments. */
    val maxTilt: Double = 0.10,
    /** Below this the adjustment is not worth applying or explaining. */
    val minDelta: Double = 0.005,
) {
    init {
        require(strength >= 0.0)
        require(maxTilt in 0.0..0.25)
        require(minDelta >= 0.0)
    }
}

/**
 * Injuries/suspensions adjustment from pre-match availability.
 *
 * The same calculator is used in production and walk-forward backtests. It
 * knows nothing about individual players: a missing squad player who never
 * plays costs nothing, a missing ever-present costs his full minutes share,
 * and the difference between the two teams is all that moves the forecast.
 * Neutral inputs (equal shares, or no data for either side) produce no
 * adjustment.
 */
object AvailabilityImpact {

    fun adjustment(
        homeMissingShare: Double?,
        awayMissingShare: Double?,
        config: AvailabilityImpactConfig = AvailabilityImpactConfig(),
    ): LambdaAdjustment? {
        if (config.strength <= 0.0) return null
        val home = homeMissingShare ?: return null
        val away = awayMissingShare ?: return null
        require(home in 0.0..1.0 && away in 0.0..1.0) { "missing shares must be within [0, 1]" }
        // Positive delta favours the home team: it grows when the *away* side
        // is missing more of its minutes than the home side.
        val delta = (config.strength * (away - home)).coerceIn(-config.maxTilt, config.maxTilt)
        if (abs(delta) < config.minDelta) return null
        return LambdaAdjustment(
            LambdaAdjustmentLabel.INJURIES,
            homeFactor = 1.0 + delta,
            awayFactor = 1.0 - delta,
            reason = ExplanationCode.INJURY_ABSENCE,
        )
    }
}
