package com.footballai.engine.calibration

import com.footballai.engine.model.MatchOutcome
import kotlin.math.ln
import kotlin.math.pow

/**
 * One resolved forecast: the three 1X2 probabilities and the outcome that happened.
 * Samples must be passed in chronological order; the calibrator relies on that to
 * build an honest train/holdout split.
 */
data class CalibrationSample(
    val homeWin: Double,
    val draw: Double,
    val awayWin: Double,
    val actual: MatchOutcome,
) {
    init {
        require(homeWin.isFinite() && draw.isFinite() && awayWin.isFinite()) { "probabilities must be finite" }
    }

    val checksum: Double get() = homeWin + draw + awayWin

    fun probabilityOf(outcome: MatchOutcome): Double = when (outcome) {
        MatchOutcome.HOME_WIN -> homeWin
        MatchOutcome.DRAW -> draw
        MatchOutcome.AWAY_WIN -> awayWin
    }

    /** Renormalises to a valid distribution; a zero triple becomes the uniform prior. */
    fun normalised(): CalibrationSample {
        val total = checksum
        if (total <= 0.0) return CalibrationSample(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0, actual)
        return CalibrationSample(homeWin / total, draw / total, awayWin / total, actual)
    }
}

/** A monotone (non-decreasing) mapping built by weighted pool-adjacent-violators. */
data class IsotonicPoint(val predicted: Double, val observed: Double, val count: Int)

enum class CalibrationMethod {
    /** No correction is applied because the evidence is not sufficient. */
    NONE,

    /** Multiclass temperature scaling: p_i^(1/T), renormalised. */
    TEMPERATURE,

    /** Per-outcome isotonic regression, renormalised to a distribution. */
    ISOTONIC,
}

/**
 * A fitted calibration. It is a plain value object so it can be cached, logged and
 * stored without carrying any mutable state.
 */
data class CalibrationModel(
    val method: CalibrationMethod = CalibrationMethod.NONE,
    val temperature: Double = 1.0,
    val curves: Map<MatchOutcome, List<IsotonicPoint>> = emptyMap(),
    val sampleCount: Int = 0,
    val fittedAtEpochMs: Long = 0L,
    /** In-sample log loss before and after the correction (diagnostics only). */
    val trainLogLossBefore: Double = Double.NaN,
    val trainLogLossAfter: Double = Double.NaN,
    /** Out-of-sample log loss on the withheld (most recent) slice, if any. */
    val holdoutCount: Int = 0,
    val holdoutLogLossBefore: Double = Double.NaN,
    val holdoutLogLossAfter: Double = Double.NaN,
    /** Holdout one-vs-rest expected calibration error of the adopted model. */
    val holdoutExpectedCalibrationError: Double = Double.NaN,
    /** Reasons why no correction was adopted; empty when [method] is not NONE. */
    val reasons: List<CalibrationSkipReason> = emptyList(),
) {
    val isApplied: Boolean get() = method != CalibrationMethod.NONE

    /** Relative out-of-sample improvement in log loss; NaN when not measurable. */
    val holdoutImprovement: Double
        get() {
            if (!isApplied || holdoutCount == 0) return Double.NaN
            if (holdoutLogLossBefore.isNaN() || holdoutLogLossAfter.isNaN()) return Double.NaN
            return holdoutLogLossBefore - holdoutLogLossAfter
        }

    companion object {
        val NONE = CalibrationModel()
    }
}

enum class CalibrationSkipReason {
    NOT_ENOUGH_SAMPLES,
    NOT_ENOUGH_HOLDOUT_SAMPLES,
    NO_OUT_OF_SAMPLE_IMPROVEMENT,
    DEGENERATE_DISTRIBUTION,
    FITTING_FAILED,
}

/**
 * On-device probability calibration for the 1X2 market.
 *
 * Design constraints that matter more than the technique:
 *
 * 1. **Out-of-sample or nothing.** Samples arrive chronologically. The most recent
 *    slice is withheld, the correction is fitted on the older slice only, and it is
 *    adopted only when it improves log loss on the withheld slice by more than
 *    [Config.minHoldoutImprovement]. Otherwise the raw model is returned unchanged
 *    and the reason is recorded.
 * 2. **No tiny samples.** Below [Config.minSamples] resolved predictions nothing is
 *    fitted at all: a temperature or an isotonic curve estimated from 50 matches is
 *    noise that makes the reported numbers *worse* while looking sophisticated.
 * 3. **Always a distribution.** Every output is renormalised, so
 *    P(home) + P(draw) + P(away) = 1 within [PROBABILITY_TOLERANCE].
 * 4. **Bounded.** Temperature is limited to [Config.minTemperature, maxTemperature]
 *    and isotonic outputs are clamped away from exactly 0 and 1, so a single weird
 *    match cannot produce a 99.9% claim.
 */
object Calibrator {

    /**
     * Documented numerical tolerance for the probability sum. The UI may round for
     * display, but a stored prediction must satisfy the invariant to this precision.
     */
    const val PROBABILITY_TOLERANCE = 1e-9

    data class Config(
        val minSamples: Int = 400,
        val minHoldoutSamples: Int = 150,
        val holdoutFraction: Double = 0.25,
        /** Minimum out-of-sample log-loss gain required before a correction is used. */
        val minHoldoutImprovement: Double = 0.002,
        val minTemperature: Double = 0.5,
        val maxTemperature: Double = 3.0,
        val temperatureSteps: Int = 60,
        /** Minimum observations per isotonic point: keeps the curve from memorising noise. */
        val minIsotonicBinCount: Int = 40,
        val maxIsotonicBins: Int = 12,
        val isotonicMinProbability: Double = 0.01,
        val isotonicMaxProbability: Double = 0.99,
    )

    fun fit(samples: List<CalibrationSample>, config: Config = Config()): CalibrationModel {
        val usable = samples.filter {
            it.homeWin.isFinite() && it.draw.isFinite() && it.awayWin.isFinite() && it.checksum > 0.0
        }.map { it.normalised() }
        if (usable.size < config.minSamples) {
            return CalibrationModel(
                method = CalibrationMethod.NONE,
                sampleCount = usable.size,
                reasons = listOf(CalibrationSkipReason.NOT_ENOUGH_SAMPLES),
            )
        }

        val holdoutSize = (usable.size * config.holdoutFraction).toInt()
        val trainSize = usable.size - holdoutSize
        val train = usable.subList(0, trainSize)
        val holdout = usable.subList(trainSize, usable.size)
        if (holdout.size < config.minHoldoutSamples) {
            return CalibrationModel(
                method = CalibrationMethod.NONE,
                sampleCount = usable.size,
                reasons = listOf(CalibrationSkipReason.NOT_ENOUGH_HOLDOUT_SAMPLES),
            )
        }

        val temperature = fitTemperature(train, config)
        val curves = fitIsotonicCurves(train, config)

        val candidates = listOf(
            CalibrationModel(method = CalibrationMethod.NONE, sampleCount = usable.size),
            CalibrationModel(
                method = CalibrationMethod.TEMPERATURE,
                temperature = temperature,
                sampleCount = usable.size,
            ),
            CalibrationModel(
                method = CalibrationMethod.ISOTONIC,
                temperature = temperature,
                curves = curves,
                sampleCount = usable.size,
            ),
        ).filter { candidate ->
            // A candidate must be well defined on the holdout slice.
            holdout.all { sample ->
                val calibrated = apply(candidate, sample.homeWin, sample.draw, sample.awayWin)
                isValid(calibrated)
            }
        }

        val scored = candidates.map { candidate -> candidate to logLoss(holdout, candidate) }
        val baselineLoss = scored.firstOrNull { it.first.method == CalibrationMethod.NONE }?.second
            ?: return CalibrationModel(
                method = CalibrationMethod.NONE,
                sampleCount = usable.size,
                reasons = listOf(CalibrationSkipReason.DEGENERATE_DISTRIBUTION),
            )
        val best = scored
            .filter { it.first.method != CalibrationMethod.NONE }
            .minByOrNull { it.second }

        if (best == null || best.second.isNaN() || baselineLoss.isNaN() ||
            baselineLoss - best.second < config.minHoldoutImprovement
        ) {
            return CalibrationModel(
                method = CalibrationMethod.NONE,
                sampleCount = usable.size,
                trainLogLossBefore = logLoss(train, CalibrationModel.NONE),
                trainLogLossAfter = logLoss(train, CalibrationModel.NONE),
                holdoutCount = holdout.size,
                holdoutLogLossBefore = baselineLoss,
                holdoutLogLossAfter = baselineLoss,
                holdoutExpectedCalibrationError = expectedCalibrationError(holdout, CalibrationModel.NONE),
                reasons = listOf(CalibrationSkipReason.NO_OUT_OF_SAMPLE_IMPROVEMENT),
            )
        }

        val chosen = best.first.copy(
            sampleCount = usable.size,
            trainLogLossBefore = logLoss(train, CalibrationModel.NONE),
            trainLogLossAfter = logLoss(train, best.first),
            holdoutCount = holdout.size,
            holdoutLogLossBefore = baselineLoss,
            holdoutLogLossAfter = best.second,
            holdoutExpectedCalibrationError = expectedCalibrationError(holdout, best.first),
        )
        return chosen
    }

    /** Applies a fitted model and returns a normalised triple. */
    fun apply(model: CalibrationModel, homeWin: Double, draw: Double, awayWin: Double): Triple<Double, Double, Double> {
        val base = normalise(homeWin, draw, awayWin)
            ?: return Triple(1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0)
        if (!model.isApplied) return base
        val adjusted = when (model.method) {
            CalibrationMethod.NONE -> base
            CalibrationMethod.TEMPERATURE -> applyTemperature(base, model.temperature)
            CalibrationMethod.ISOTONIC -> {
                val curved = Triple(
                    applyCurve(model.curves[MatchOutcome.HOME_WIN], base.first),
                    applyCurve(model.curves[MatchOutcome.DRAW], base.second),
                    applyCurve(model.curves[MatchOutcome.AWAY_WIN], base.third),
                )
                normalise(curved.first, curved.second, curved.third) ?: base
            }
        }
        return normalise(adjusted.first, adjusted.second, adjusted.third) ?: base
    }

    /** Validates and renormalises a triple, or returns null when it cannot be used. */
    fun normalise(homeWin: Double, draw: Double, awayWin: Double): Triple<Double, Double, Double>? {
        if (!homeWin.isFinite() || !draw.isFinite() || !awayWin.isFinite()) return null
        if (homeWin < 0.0 || draw < 0.0 || awayWin < 0.0) return null
        val total = homeWin + draw + awayWin
        if (total <= 0.0) return null
        return Triple(homeWin / total, draw / total, awayWin / total)
    }

    /** True when a triple is a usable distribution within [PROBABILITY_TOLERANCE]. */
    fun isValid(triple: Triple<Double, Double, Double>): Boolean {
        val (h, d, a) = triple
        if (!h.isFinite() || !d.isFinite() || !a.isFinite()) return false
        if (h < 0.0 || d < 0.0 || a < 0.0) return false
        return kotlin.math.abs(h + d + a - 1.0) <= PROBABILITY_TOLERANCE
    }

    // ---------------------------------------------------------------- internals

    private fun applyTemperature(
        base: Triple<Double, Double, Double>,
        temperature: Double,
    ): Triple<Double, Double, Double> {
        val t = temperature.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
        if (t == 1.0) return base
        val power = 1.0 / t
        val h = base.first.coerceAtLeast(MIN_PROBABILITY).pow(power)
        val d = base.second.coerceAtLeast(MIN_PROBABILITY).pow(power)
        val a = base.third.coerceAtLeast(MIN_PROBABILITY).pow(power)
        val total = h + d + a
        if (total <= 0.0) return base
        return Triple(h / total, d / total, a / total)
    }

    private fun applyCurve(curve: List<IsotonicPoint>?, probability: Double): Double {
        if (curve.isNullOrEmpty()) return probability
        val x = probability.coerceIn(0.0, 1.0)
        if (x <= curve.first().predicted) return curve.first().observed
        if (x >= curve.last().predicted) return curve.last().observed
        for (i in 0 until curve.size - 1) {
            val left = curve[i]
            val right = curve[i + 1]
            if (x >= left.predicted && x <= right.predicted) {
                val span = right.predicted - left.predicted
                if (span <= 1e-12) return left.observed
                val fraction = (x - left.predicted) / span
                return left.observed + fraction * (right.observed - left.observed)
            }
        }
        return curve.last().observed
    }

    private fun fitTemperature(samples: List<CalibrationSample>, config: Config): Double {
        if (samples.isEmpty()) return 1.0
        var bestTemperature = 1.0
        var bestLoss = Double.MAX_VALUE
        val steps = config.temperatureSteps.coerceAtLeast(2)
        for (step in 0..steps) {
            val t = config.minTemperature + (config.maxTemperature - config.minTemperature) * step / steps
            val loss = logLoss(samples, CalibrationModel(method = CalibrationMethod.TEMPERATURE, temperature = t))
            if (loss < bestLoss - 1e-12) {
                bestLoss = loss
                bestTemperature = t
            }
        }
        return bestTemperature.coerceIn(config.minTemperature, config.maxTemperature)
    }

    private fun fitIsotonicCurves(
        samples: List<CalibrationSample>,
        config: Config,
    ): Map<MatchOutcome, List<IsotonicPoint>> {
        val map = LinkedHashMap<MatchOutcome, List<IsotonicPoint>>()
        for (outcome in MatchOutcome.entries) {
            val curve = fitIsotonicCurve(
                samples = samples.map { sample -> sample.probabilityOf(outcome) to (sample.actual == outcome) },
                config = config,
            )
            if (curve.isNotEmpty()) map[outcome] = curve
        }
        return map
    }

    /**
     * Weighted isotonic regression with a minimum bin size.
     *
     * The observations are first aggregated into quantile bins (so a single match
     * cannot create its own step), then smoothed with pool-adjacent-violators so the
     * mapping is non-decreasing - the property that makes a calibration curve
     * interpretable.
     */
    internal fun fitIsotonicCurve(
        samples: List<Pair<Double, Boolean>>,
        config: Config,
    ): List<IsotonicPoint> {
        if (samples.size < config.minIsotonicBinCount) return emptyList()
        val sorted = samples.sortedBy { it.first }
        val binCount = (sorted.size / config.minIsotonicBinCount)
            .coerceIn(1, config.maxIsotonicBins)
        if (binCount < 2) return emptyList()

        val binSize = sorted.size.toDouble() / binCount
        val bins = ArrayList<IsotonicPoint>(binCount)
        for (i in 0 until binCount) {
            val from = (i * binSize).toInt()
            val to = (((i + 1) * binSize).toInt()).coerceAtMost(sorted.size)
            if (to <= from) continue
            val slice = sorted.subList(from, to)
            val meanPredicted = slice.sumOf { it.first } / slice.size
            val observed = slice.count { it.second }.toDouble() / slice.size
            bins.add(
                IsotonicPoint(
                    predicted = meanPredicted,
                    observed = observed.coerceIn(config.isotonicMinProbability, config.isotonicMaxProbability),
                    count = slice.size,
                ),
            )
        }
        if (bins.isEmpty()) return emptyList()

        // Weighted PAVA: merge adjacent blocks while the observed rate decreases.
        val blocks = ArrayList<MutableBlock>()
        for (bin in bins) {
            blocks.add(MutableBlock(mutableListOf(bin)))
            while (blocks.size >= 2) {
                val last = blocks[blocks.size - 1]
                val previous = blocks[blocks.size - 2]
                if (previous.mean() <= last.mean() + 1e-12) break
                previous.merge(last)
                blocks.removeAt(blocks.size - 1)
            }
        }

        val result = ArrayList<IsotonicPoint>(blocks.size)
        for (block in blocks) {
            val count = block.points.sumOf { it.count }
            if (count <= 0) continue
            val predicted = block.points.sumOf { it.predicted * it.count } / count
            val observed = block.points.sumOf { it.observed * it.count } / count
            result.add(
                IsotonicPoint(
                    predicted = predicted,
                    observed = observed.coerceIn(config.isotonicMinProbability, config.isotonicMaxProbability),
                    count = count,
                ),
            )
        }
        // Ensure strictly increasing x for the interpolator.
        return result.sortedBy { it.predicted }
    }

    private class MutableBlock(val points: MutableList<IsotonicPoint>) {
        fun mean(): Double {
            val count = points.sumOf { it.count }
            if (count <= 0) return 0.0
            return points.sumOf { it.observed * it.count } / count
        }

        fun merge(other: MutableBlock) {
            points.addAll(other.points)
        }
    }

    fun logLoss(samples: List<CalibrationSample>, model: CalibrationModel): Double {
        if (samples.isEmpty()) return Double.NaN
        var total = 0.0
        for (sample in samples) {
            val raw = apply(model, sample.homeWin, sample.draw, sample.awayWin)
            val (home, draw, away) = normalise(raw.first, raw.second, raw.third) ?: return Double.NaN
            val p = when (sample.actual) {
                MatchOutcome.HOME_WIN -> home
                MatchOutcome.DRAW -> draw
                MatchOutcome.AWAY_WIN -> away
            }
            total += -ln(p.coerceIn(1e-9, 1.0))
        }
        return total / samples.size
    }

    /** Multiclass Brier score of a list of samples under a calibration model. */
    fun brier(samples: List<CalibrationSample>, model: CalibrationModel): Double {
        if (samples.isEmpty()) return Double.NaN
        var total = 0.0
        for (sample in samples) {
            val raw = apply(model, sample.homeWin, sample.draw, sample.awayWin)
            val (h, d, a) = normalise(raw.first, raw.second, raw.third) ?: return Double.NaN
            val targets = when (sample.actual) {
                MatchOutcome.HOME_WIN -> Triple(1.0, 0.0, 0.0)
                MatchOutcome.DRAW -> Triple(0.0, 1.0, 0.0)
                MatchOutcome.AWAY_WIN -> Triple(0.0, 0.0, 1.0)
            }
            total += (h - targets.first).let { it * it } +
                (d - targets.second).let { it * it } +
                (a - targets.third).let { it * it }
        }
        return total / samples.size
    }

    /**
     * One-vs-rest expected calibration error over all three outcomes.
     *
     * The per-outcome reliability curves are pooled with their own sample counts,
     * which is a stricter measure than looking only at the home-win column.
     */
    fun expectedCalibrationError(
        samples: List<CalibrationSample>,
        model: CalibrationModel,
        bins: Int = 10,
    ): Double {
        if (samples.isEmpty() || bins <= 0) return Double.NaN
        var pooled = 0.0
        var observations = 0
        for (outcome in MatchOutcome.entries) {
            val counts = IntArray(bins)
            val sumPredicted = DoubleArray(bins)
            val sumActual = DoubleArray(bins)
            for (sample in samples) {
                val raw = apply(model, sample.homeWin, sample.draw, sample.awayWin)
                val p = when (outcome) {
                    MatchOutcome.HOME_WIN -> raw.first
                    MatchOutcome.DRAW -> raw.second
                    MatchOutcome.AWAY_WIN -> raw.third
                }.coerceIn(0.0, 1.0)
                val index = (p * bins).toInt().coerceIn(0, bins - 1)
                counts[index]++
                sumPredicted[index] += p
                sumActual[index] += if (sample.actual == outcome) 1.0 else 0.0
            }
            for (i in 0 until bins) {
                if (counts[i] == 0) continue
                val meanPredicted = sumPredicted[i] / counts[i]
                val observed = sumActual[i] / counts[i]
                pooled += counts[i] * kotlin.math.abs(meanPredicted - observed)
                observations += counts[i]
            }
        }
        return if (observations == 0) Double.NaN else pooled / observations
    }

    private const val MIN_PROBABILITY = 1e-6
    private const val MIN_TEMPERATURE = 0.2
    private const val MAX_TEMPERATURE = 5.0
}
