package com.footballai.app.data.repository

import com.footballai.app.core.TimeProvider
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.local.entity.PredictionEntity
import com.footballai.engine.calibration.CalibrationModel
import com.footballai.engine.calibration.CalibrationSample
import com.footballai.engine.calibration.Calibrator
import com.footballai.engine.model.MatchOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fits the on-device 1X2 calibration from the predictions the app itself stored and
 * later resolved.
 *
 * Only resolved *pre-match* predictions of the current model version are used, in
 * chronological order, and only when the match has a verified regulation-time score.
 * Live snapshots are excluded: they answer a different question and must never be
 * mixed into the calibration sample.
 *
 * Nothing is fitted below [Calibrator.Config.minSamples]; the engine then returns the
 * raw probabilities and the UI states that no calibration is applied.
 */
class CalibrationRepository(
    private val db: FootballDatabase,
    private val clock: TimeProvider,
    private val config: Calibrator.Config = Calibrator.Config(),
) {
    private data class CacheKey(val competitionClass: String, val modelVersion: String)

    private data class Cached(val sampleCount: Int, val model: CalibrationModel)

    private val cache = HashMap<CacheKey, Cached>()
    private val mutex = Mutex()

    /** Maximum number of resolved forecasts used; older ones are outside the window. */
    private val sampleLimit = 3000

    suspend fun modelFor(competitionClass: String, modelVersion: String): CalibrationModel = mutex.withLock {
        val samples = withContext(Dispatchers.IO) {
            db.predictionDao()
                .resolvedForClass(modelVersion, competitionClass, sampleLimit)
                .mapNotNull { it.toCalibrationSample() }
        }
        val key = CacheKey(competitionClass, modelVersion)
        val cached = cache[key]
        if (cached != null && cached.sampleCount == samples.size) return cached.model
        val model = if (samples.size < config.minSamples) {
            CalibrationModel.NONE.copy(sampleCount = samples.size)
        } else {
            withContext(Dispatchers.Default) {
                Calibrator.fit(samples, config).copy(fittedAtEpochMs = clock.nowEpochMs())
            }
        }
        cache[key] = Cached(samples.size, model)
        return model
    }

    /** Resolved count per competition class, for the performance screen and diagnostics. */
    suspend fun resolvedCount(competitionClass: String, modelVersion: String): Int = withContext(Dispatchers.IO) {
        db.predictionDao().resolvedCountForClass(modelVersion, competitionClass)
    }

    /** Drops the cached fit; called when predictions are resolved or data is cleared. */
    suspend fun invalidate() = mutex.withLock { cache.clear() }

    private fun PredictionEntity.toCalibrationSample(): CalibrationSample? {
        val home = actualHomeGoals ?: return null
        val away = actualAwayGoals ?: return null
        val outcome = when {
            home > away -> MatchOutcome.HOME_WIN
            home == away -> MatchOutcome.DRAW
            else -> MatchOutcome.AWAY_WIN
        }
        if (!homeWin.isFinite() || !draw.isFinite() || !awayWin.isFinite()) return null
        if (homeWin + draw + awayWin <= 0.0) return null
        return CalibrationSample(homeWin, draw, awayWin, outcome)
    }
}
