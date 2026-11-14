package com.footballai.engine.eval

import com.footballai.engine.calibration.CalibrationModel
import com.footballai.engine.calibration.CalibrationSample
import com.footballai.engine.calibration.Calibrator

/**
 * One expanding-window block of the calibration study.
 *
 * [trainSamples] forecasts were available when the correction was fitted, and the
 * very next [evaluatedMatches] forecasts are scored with it. The correction never
 * sees the matches it is judged on.
 */
data class CalibrationFoldReport(
    val fold: Int,
    val trainSamples: Int,
    val evaluatedMatches: Int,
    val method: String,
    val rawLogLoss: Double,
    val calibratedLogLoss: Double,
    val rawBrier: Double,
    val calibratedBrier: Double,
    val rawCalibrationError: Double,
    val calibratedCalibrationError: Double,
) {
    val logLossGain: Double get() = rawLogLoss - calibratedLogLoss
    val brierGain: Double get() = rawBrier - calibratedBrier
    val calibrationErrorGain: Double get() = rawCalibrationError - calibratedCalibrationError
}

data class CalibrationStudyReport(
    val folds: List<CalibrationFoldReport>,
    val evaluatedMatches: Int,
    val rawLogLoss: Double,
    val calibratedLogLoss: Double,
    val rawBrier: Double,
    val calibratedBrier: Double,
    val rawCalibrationError: Double,
    val calibratedCalibrationError: Double,
    val adoptedFolds: Int,
    val skippedReason: String? = null,
) {
    val logLossGain: Double get() = rawLogLoss - calibratedLogLoss
    val brierGain: Double get() = rawBrier - calibratedBrier
    val improves: Boolean get() = folds.isNotEmpty() && logLossGain > 0.0

    companion object {
        val EMPTY = CalibrationStudyReport(
            folds = emptyList(),
            evaluatedMatches = 0,
            rawLogLoss = Double.NaN,
            calibratedLogLoss = Double.NaN,
            rawBrier = Double.NaN,
            calibratedBrier = Double.NaN,
            rawCalibrationError = Double.NaN,
            calibratedCalibrationError = Double.NaN,
            adoptedFolds = 0,
        )
    }
}

/**
 * Expanding-window study of the calibration layer.
 *
 * It answers one question with out-of-sample evidence: does applying the on-device
 * calibration make the stored probabilities better (lower log loss, lower calibration
 * error) on matches it has never seen? If the answer is no, the calibrator itself
 * refuses to adopt a correction, and this study reports that instead of pretending
 * otherwise.
 */
object CalibrationStudy {

    data class Config(
        val foldCount: Int = 5,
        val calibrator: Calibrator.Config = Calibrator.Config(),
        /** Forecasts kept as the first training block before evaluation starts. */
        val minimumWarmupSamples: Int = 600,
        val evaluationBins: Int = 10,
    )

    fun run(
        orderedSamples: List<CalibrationSample>,
        config: Config = Config(),
    ): CalibrationStudyReport {
        val usable = orderedSamples.filter {
            it.homeWin.isFinite() && it.draw.isFinite() && it.awayWin.isFinite() && it.checksum > 0.0
        }.map { it.normalised() }
        val warmup = maxOf(config.minimumWarmupSamples, config.calibrator.minSamples)
        if (usable.size < warmup + config.foldCount) {
            return CalibrationStudyReport.EMPTY.copy(
                skippedReason = "NOT_ENOUGH_SAMPLES",
                evaluatedMatches = usable.size,
            )
        }
        val evaluationSize = usable.size - warmup
        val foldSize = evaluationSize / config.foldCount
        if (foldSize < 1) {
            return CalibrationStudyReport.EMPTY.copy(skippedReason = "NOT_ENOUGH_SAMPLES")
        }

        val folds = ArrayList<CalibrationFoldReport>()
        var index = warmup
        var fold = 0
        while (index < usable.size && fold < config.foldCount) {
            val end = minOf(index + foldSize, usable.size)
            val train = usable.subList(0, index)
            val evaluation = usable.subList(index, end)
            if (evaluation.isEmpty()) break
            val model = Calibrator.fit(train, config.calibrator)
            folds.add(
                CalibrationFoldReport(
                    fold = fold,
                    trainSamples = train.size,
                    evaluatedMatches = evaluation.size,
                    method = model.method.name,
                    rawLogLoss = Calibrator.logLoss(evaluation, CalibrationModel.NONE),
                    calibratedLogLoss = Calibrator.logLoss(evaluation, model),
                    rawBrier = Calibrator.brier(evaluation, CalibrationModel.NONE),
                    calibratedBrier = Calibrator.brier(evaluation, model),
                    rawCalibrationError = Calibrator.expectedCalibrationError(
                        evaluation, CalibrationModel.NONE, config.evaluationBins,
                    ),
                    calibratedCalibrationError = Calibrator.expectedCalibrationError(
                        evaluation, model, config.evaluationBins,
                    ),
                ),
            )
            index = end
            fold++
        }
        if (folds.isEmpty()) {
            return CalibrationStudyReport.EMPTY.copy(skippedReason = "NO_FOLDS")
        }

        val evaluated = folds.sumOf { it.evaluatedMatches }.toDouble()
        fun weighted(selector: (CalibrationFoldReport) -> Double): Double {
            var sum = 0.0
            var weight = 0.0
            for (report in folds) {
                val value = selector(report)
                if (value.isNaN()) continue
                sum += value * report.evaluatedMatches
                weight += report.evaluatedMatches
            }
            return if (weight <= 0.0) Double.NaN else sum / weight
        }

        return CalibrationStudyReport(
            folds = folds,
            evaluatedMatches = evaluated.toInt(),
            rawLogLoss = weighted { it.rawLogLoss },
            calibratedLogLoss = weighted { it.calibratedLogLoss },
            rawBrier = weighted { it.rawBrier },
            calibratedBrier = weighted { it.calibratedBrier },
            rawCalibrationError = weighted { it.rawCalibrationError },
            calibratedCalibrationError = weighted { it.calibratedCalibrationError },
            adoptedFolds = folds.count { it.method != "NONE" },
        )
    }

    /** Convenience adapter so a backtest can be studied without re-running it. */
    fun fromBacktestSamples(samples: List<BacktestSample>, config: Config = Config()): CalibrationStudyReport =
        run(
            orderedSamples = samples.sortedBy { it.kickoffEpochMs }.map {
                CalibrationSample(it.homeWin, it.draw, it.awayWin, it.actual)
            },
            config = config,
        )
}
