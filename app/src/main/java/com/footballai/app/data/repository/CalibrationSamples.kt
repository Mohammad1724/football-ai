package com.footballai.app.data.repository

import com.footballai.app.data.local.Serialized
import com.footballai.app.data.local.entity.PredictionEntity
import com.footballai.app.domain.model.CalibrationView
import com.footballai.engine.calibration.CalibrationSample
import com.footballai.engine.markets.MarketCalculator
import com.footballai.engine.model.MatchOutcome
import kotlin.math.abs

/** Calibration is fitted to the same raw model distribution it will later transform. */
internal fun rawCalibrationProbabilities(
    published: Triple<Double, Double, Double>,
    metadataKnown: Boolean,
    calibration: CalibrationView?,
    hasMarketAlignment: Boolean,
): Triple<Double, Double, Double>? {
    val raw = when {
        calibration != null -> Triple(calibration.rawHomeWin, calibration.rawDraw, calibration.rawAwayWin)
        // Legacy snapshots with neither calibration nor a market mixture published the
        // raw output unchanged. An unknown record or blended output cannot be inverted.
        metadataKnown && !hasMarketAlignment -> published
        else -> return null
    }
    val values = listOf(raw.first, raw.second, raw.third)
    if (values.any { !it.isFinite() || it !in 0.0..1.0 } || abs(values.sum() - 1.0) > 1e-6) return null
    return raw
}

internal fun PredictionEntity.toRawCalibrationSample(): CalibrationSample? {
    val home = actualHomeGoals ?: return null
    val away = actualAwayGoals ?: return null
    if (home < 0 || away < 0) return null
    val (confidence, calibration) = Serialized.decodeConfidence(confidenceJson)
    val raw = rawCalibrationProbabilities(
        published = Triple(homeWin, draw, awayWin),
        metadataKnown = confidence != null,
        calibration = calibration,
        hasMarketAlignment = MarketCalculator.impliedProbabilities(oddsHome, oddsDraw, oddsAway) != null,
    ) ?: return null
    return CalibrationSample(raw.first, raw.second, raw.third, when {
        home > away -> MatchOutcome.HOME_WIN
        home == away -> MatchOutcome.DRAW
        else -> MatchOutcome.AWAY_WIN
    })
}
