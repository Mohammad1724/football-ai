package com.footballai.engine.eval

import com.footballai.engine.model.MatchOutcome
import kotlin.math.abs
import kotlin.math.ln

/** One evaluated prediction: three 1X2 probabilities and what actually happened. */
data class OutcomeSample(
    val homeWin: Double,
    val draw: Double,
    val awayWin: Double,
    val actual: MatchOutcome,
) {
    fun probabilityOf(outcome: MatchOutcome): Double = when (outcome) {
        MatchOutcome.HOME_WIN -> homeWin
        MatchOutcome.DRAW -> draw
        MatchOutcome.AWAY_WIN -> awayWin
    }

    fun normalised(): OutcomeSample {
        val total = homeWin + draw + awayWin
        if (total <= 0.0) return OutcomeSample(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0, actual)
        return OutcomeSample(homeWin / total, draw / total, awayWin / total, actual)
    }
}

data class BinarySample(val probability: Double, val actual: Boolean)

data class CalibrationBin(
    val lowerBound: Double,
    val upperBound: Double,
    val count: Int,
    val meanPredicted: Double,
    val observedFrequency: Double,
)

/** Aggregate metrics for one market; always reported with the sample size. */
data class MetricSummary(
    val count: Int,
    val brier: Double,
    val logLoss: Double,
    val accuracy: Double,
) {
    companion object {
        val EMPTY = MetricSummary(0, Double.NaN, Double.NaN, Double.NaN)
    }
}

/**
 * Proper scoring rules for probabilistic forecasts.
 *
 * Brier score and log loss are *strictly proper*: they cannot be improved by
 * reporting probabilities that do not reflect the real belief of the model, which
 * is why they are used here instead of "accuracy" alone.
 */
object Metrics {

    /** Multiclass Brier score for 1X2 predictions. Lower is better; 0 is perfect. */
    fun brier1x2(samples: List<OutcomeSample>): Double {
        if (samples.isEmpty()) return Double.NaN
        var total = 0.0
        for (sample in samples) {
            val s = sample.normalised()
            val expected = oneHot(sample.actual)
            total += (s.homeWin - expected[0]) * (s.homeWin - expected[0]) +
                (s.draw - expected[1]) * (s.draw - expected[1]) +
                (s.awayWin - expected[2]) * (s.awayWin - expected[2])
        }
        return total / samples.size
    }

    /** Mean negative log likelihood of the observed outcome. Lower is better. */
    fun logLoss1x2(samples: List<OutcomeSample>): Double {
        if (samples.isEmpty()) return Double.NaN
        var total = 0.0
        for (sample in samples) {
            val s = sample.normalised()
            val p = s.probabilityOf(sample.actual).coerceIn(1e-9, 1.0)
            total += -ln(p)
        }
        return total / samples.size
    }

    /** Share of matches where the highest-probability outcome actually happened. */
    fun accuracy1x2(samples: List<OutcomeSample>): Double {
        if (samples.isEmpty()) return Double.NaN
        var hits = 0
        for (sample in samples) {
            val s = sample.normalised()
            val best = when {
                s.homeWin >= s.draw && s.homeWin >= s.awayWin -> MatchOutcome.HOME_WIN
                s.draw >= s.awayWin -> MatchOutcome.DRAW
                else -> MatchOutcome.AWAY_WIN
            }
            if (best == sample.actual) hits++
        }
        return hits.toDouble() / samples.size
    }

    fun summary(samples: List<OutcomeSample>): MetricSummary {
        if (samples.isEmpty()) return MetricSummary.EMPTY
        return MetricSummary(
            count = samples.size,
            brier = brier1x2(samples),
            logLoss = logLoss1x2(samples),
            accuracy = accuracy1x2(samples),
        )
    }

    /** Brier score for a binary market such as over 2.5 goals or both teams to score. */
    fun brierBinary(samples: List<BinarySample>): Double {
        if (samples.isEmpty()) return Double.NaN
        var total = 0.0
        for (s in samples) {
            val actual = if (s.actual) 1.0 else 0.0
            val p = s.probability.coerceIn(0.0, 1.0)
            total += (p - actual) * (p - actual)
        }
        return total / samples.size
    }

    fun logLossBinary(samples: List<BinarySample>): Double {
        if (samples.isEmpty()) return Double.NaN
        var total = 0.0
        for (s in samples) {
            val p = (if (s.actual) s.probability else 1.0 - s.probability).coerceIn(1e-9, 1.0)
            total += -ln(p)
        }
        return total / samples.size
    }

    fun accuracyBinary(samples: List<BinarySample>): Double {
        if (samples.isEmpty()) return Double.NaN
        var hits = 0
        for (s in samples) {
            val predicted = s.probability >= 0.5
            if (predicted == s.actual) hits++
        }
        return hits.toDouble() / samples.size
    }

    fun binarySummary(samples: List<BinarySample>): MetricSummary {
        if (samples.isEmpty()) return MetricSummary.EMPTY
        return MetricSummary(
            count = samples.size,
            brier = brierBinary(samples),
            logLoss = logLossBinary(samples),
            accuracy = accuracyBinary(samples),
        )
    }

    /**
     * Reliability curve: groups forecasts into equal-width bins and compares the
     * mean predicted probability with the observed frequency. A well calibrated
     * model follows the diagonal.
     */
    fun calibration(samples: List<BinarySample>, bins: Int = 10): List<CalibrationBin> {
        require(bins > 0) { "bins must be positive" }
        val bucketCounts = IntArray(bins)
        val bucketSumPredicted = DoubleArray(bins)
        val bucketSumActual = DoubleArray(bins)
        for (sample in samples) {
            val p = sample.probability.coerceIn(0.0, 1.0)
            val rawIndex = (p * bins).toInt()
            val index = rawIndex.coerceIn(0, bins - 1)
            bucketCounts[index]++
            bucketSumPredicted[index] += p
            bucketSumActual[index] += if (sample.actual) 1.0 else 0.0
        }
        val result = ArrayList<CalibrationBin>(bins)
        for (i in 0 until bins) {
            val count = bucketCounts[i]
            result.add(
                CalibrationBin(
                    lowerBound = i.toDouble() / bins,
                    upperBound = (i + 1).toDouble() / bins,
                    count = count,
                    meanPredicted = if (count > 0) bucketSumPredicted[i] / count else Double.NaN,
                    observedFrequency = if (count > 0) bucketSumActual[i] / count else Double.NaN,
                ),
            )
        }
        return result
    }

    /** Expected calibration error, weighted by the number of forecasts per bin. */
    fun expectedCalibrationError(bins: List<CalibrationBin>): Double {
        val total = bins.sumOf { it.count }
        if (total == 0) return Double.NaN
        var error = 0.0
        for (bin in bins) {
            if (bin.count == 0 || bin.meanPredicted.isNaN()) continue
            error += (bin.count.toDouble() / total) * abs(bin.meanPredicted - bin.observedFrequency)
        }
        return error
    }

    /**
     * Reference Brier score obtained by always predicting the observed base rates of
     * the sample. A model that cannot beat this reference is not adding information.
     */
    fun baseRateBrier(samples: List<OutcomeSample>): Double {
        if (samples.isEmpty()) return Double.NaN
        var home = 0
        var draw = 0
        var away = 0
        for (s in samples) {
            when (s.actual) {
                MatchOutcome.HOME_WIN -> home++
                MatchOutcome.DRAW -> draw++
                MatchOutcome.AWAY_WIN -> away++
            }
        }
        val n = samples.size.toDouble()
        val ph = home / n
        val pd = draw / n
        val pa = away / n
        var total = 0.0
        for (s in samples) {
            val expected = oneHot(s.actual)
            total += (ph - expected[0]) * (ph - expected[0]) +
                (pd - expected[1]) * (pd - expected[1]) +
                (pa - expected[2]) * (pa - expected[2])
        }
        return total / samples.size
    }

    private fun oneHot(outcome: MatchOutcome): DoubleArray = when (outcome) {
        MatchOutcome.HOME_WIN -> doubleArrayOf(1.0, 0.0, 0.0)
        MatchOutcome.DRAW -> doubleArrayOf(0.0, 1.0, 0.0)
        MatchOutcome.AWAY_WIN -> doubleArrayOf(0.0, 0.0, 1.0)
    }
}
