package com.footballai.app.data.repository

import com.footballai.app.core.AppError
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeConstants
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.local.Serialized
import com.footballai.app.data.local.entity.PredictionEntity
import com.footballai.app.data.local.entity.PredictionMarketEntity
import com.footballai.app.data.local.mapper.parseStatus
import com.footballai.app.domain.model.ExplanationView
import com.footballai.app.domain.model.MarketRow
import com.footballai.app.domain.model.MarketType
import com.footballai.app.domain.model.MatchOdds
import com.footballai.app.domain.model.PredictionKind
import com.footballai.app.domain.model.PredictionResolution
import com.footballai.app.domain.model.PredictionView
import com.footballai.app.domain.model.PredictionWithMatch
import com.footballai.app.domain.model.QualityLevel
import com.footballai.app.domain.model.QualityView
import com.footballai.app.domain.model.ScoreLine
import com.footballai.engine.markets.MarketCalculator
import com.footballai.engine.model.DataQualityLevel
import com.footballai.engine.model.LiveInput
import com.footballai.engine.model.MatchOutcome
import com.footballai.engine.model.PreMatchInput
import com.footballai.engine.model.PredictionResult
import com.footballai.engine.prediction.MatchPredictionEngine
import com.footballai.engine.quality.DataQualityAssessor
import com.footballai.engine.quality.QualityInputs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.footballai.app.domain.model.MatchStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.ln

/**
 * Owns the prediction lifecycle: build inputs from local data, run the engine,
 * store the result with its timestamp, and later resolve it against the final score.
 *
 * No network access happens here, so predictions work fully offline.
 */
class PredictionRepository(
    private val db: FootballDatabase,
    private val matchRepository: MatchRepository,
    private val ratingsRepository: RatingsRepository,
    private val engine: MatchPredictionEngine,
    private val clock: TimeProvider,
    private val settingsRepository: com.footballai.app.data.settings.SettingsRepository,
) {

    suspend fun predict(matchId: String, persist: Boolean = true): Outcome<PredictionView> = withContext(Dispatchers.Default) {
        predictInternal(matchId, persist)
    }

    private suspend fun predictInternal(matchId: String, persist: Boolean): Outcome<PredictionView> {
        val match = db.matchDao().byId(matchId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown match $matchId"))
        val homeTeam = db.teamDao().byId(match.homeTeamId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown team ${match.homeTeamId}"))
        val awayTeam = db.teamDao().byId(match.awayTeamId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND, detail = "Unknown team ${match.awayTeamId}"))

        val now = clock.nowEpochMs()
        val status = parseStatus(match.status)
        val isLive = status.isLive
        val dailyMode = settingsRepository.current().dailyAnalysisEnabled
        if (dailyMode && isLive) return Outcome.Failure(AppError(ErrorCode.INVALID_MATCH_STATE))
        if (status.isFinished) {
            return latestView(matchId)?.let { Outcome.Success(it) }
                ?: Outcome.Failure(AppError(ErrorCode.INVALID_MATCH_STATE))
        }
        PredictionPolicy.error(match, now)?.let { return Outcome.Failure(AppError(it)) }
        val liveMinute = PredictionPolicy.minute(match)
        val source = match.providerId

        val strengths = ratingsRepository.strengths(
            source = source,
            competitionId = match.competitionId,
            homeTeamId = match.homeTeamId,
            awayTeamId = match.awayTeamId,
            asOfEpochMs = now,
        )
        val baseline = ratingsRepository.baseline(source, match.competitionId, now)
        val fit = ratingsRepository.fit(source, match.competitionId, now)
        val historyCount = ratingsRepository.matchCount(source, match.competitionId)
        if (!DailyAnalysisPolicy.hasEnoughHistory(historyCount, strengths.first.matchesUsed, strengths.second.matchesUsed)) {
            return Outcome.Failure(AppError(ErrorCode.INSUFFICIENT_HISTORY))
        }
        val statistics = db.statisticsDao().byMatch(matchId)
            ?.takeIf { !isLive || now - it.updatedAt in 0..LIVE_TTL_MS }

        val quality = DataQualityAssessor.assess(
            QualityInputs(
                competitionHistoricalMatches = historyCount,
                homeMatchesUsed = strengths.first.matchesUsed,
                awayMatchesUsed = strengths.second.matchesUsed,
                hasLineups = match.hasLineups,
                hasInjuries = false,
                hasOdds = match.oddsHome != null && match.oddsDraw != null && match.oddsAway != null,
                hasLiveStatistics = statistics != null,
                hasExpectedGoalsData = statistics?.homeExpectedGoals != null,
                hasStandings = false,
                leagueBaselineIsFallback = baseline.sampleSize == 0,
                isLive = isLive,
            ),
        )

        if (quality.level == com.footballai.engine.model.DataQualityLevel.INSUFFICIENT) {
            return Outcome.Failure(AppError(ErrorCode.INSUFFICIENT_HISTORY))
        }
        val preMatchInput = PreMatchInput(
            home = strengths.first,
            away = strengths.second,
            baseline = baseline,
            adjustments = emptyList(),
            eloBlendWeight = ELO_BLEND_WEIGHT,
            dixonColesRho = fit?.rho,
            quality = quality,
            asOfEpochMs = now,
            modelVersion = MODEL_VERSION,
            fittedMatches = fit?.matchesUsed ?: 0,
        )

        val result: PredictionResult = if (isLive) {
            engine.predictLive(
                LiveInput(
                    preMatch = preMatchInput,
                    minute = requireNotNull(liveMinute),
                    homeGoals = match.homeGoals ?: 0,
                    awayGoals = match.awayGoals ?: 0,
                    homeRedCards = statistics?.homeRedCards ?: 0,
                    awayRedCards = statistics?.awayRedCards ?: 0,
                    homeShots = statistics?.homeShots,
                    awayShots = statistics?.awayShots,
                    homeShotsOnTarget = statistics?.homeShotsOnTarget,
                    awayShotsOnTarget = statistics?.awayShotsOnTarget,
                ),
            )
        } else {
            engine.predictPreMatch(preMatchInput)
        }

        val kind = if (isLive) PredictionKind.LIVE else PredictionKind.PRE_MATCH
        val view = result.toView(
            matchId = matchId,
            kind = kind,
            createdAtEpochMs = now,
            odds = if (match.oddsHome != null && match.oddsDraw != null && match.oddsAway != null) {
                MatchOdds(match.oddsHome, match.oddsDraw, match.oddsAway)
            } else {
                null
            },
        )
        // Fitting may be slow. Do not publish an input snapshot that changed or
        // crossed kickoff while the CPU work was running.
        val current = db.matchDao().byId(matchId)
            ?: return Outcome.Failure(AppError(ErrorCode.NOT_FOUND))
        PredictionPolicy.error(current, clock.nowEpochMs())?.let { return Outcome.Failure(AppError(it)) }
        if (current.status != match.status || current.minute != match.minute ||
            current.homeGoals != match.homeGoals || current.awayGoals != match.awayGoals) {
            return Outcome.Failure(AppError(ErrorCode.STALE_DATA))
        }
        // First pre-match snapshot per model version is immutable; repeated page visits
        // must not multiply a match's weight or overwrite an earlier forecast.
        if (persist && (isLive || db.predictionDao().firstPreMatch(matchId, MODEL_VERSION) == null)) {
            save(view, matchId)
        }
        return Outcome.Success(view)
    }

    private suspend fun save(view: PredictionView, matchId: String) {
        val entity = PredictionEntity(
            id = view.id,
            matchId = matchId,
            kind = view.kind.name,
            createdAtEpochMs = view.createdAtEpochMs,
            asOfEpochMs = view.createdAtEpochMs,
            modelName = view.modelName,
            modelVersion = view.modelVersion,
            expectedHomeGoals = view.expectedHomeGoals,
            expectedAwayGoals = view.expectedAwayGoals,
            homeWin = view.homeWin,
            draw = view.draw,
            awayWin = view.awayWin,
            over25 = view.markets.firstOrNull { it.type == MarketType.OVER_UNDER && it.selectionKey == "OVER" && it.line == 2.5 }?.probability ?: Double.NaN,
            bttsYes = view.markets.firstOrNull { it.type == MarketType.BOTH_TEAMS_TO_SCORE && it.selectionKey == "YES" }?.probability ?: Double.NaN,
            fittedMatches = view.fittedMatches,
            rho = view.rho,
            qualityLevel = view.quality.level.name,
            qualityCompleteness = view.quality.completeness,
            qualityJson = Serialized.encodeQuality(view.quality),
            explanationsJson = Serialized.encodeExplanations(view.explanations),
            liveSnapshotJson = Serialized.encodeLiveSnapshot(view.live),
            minute = view.live?.minute,
            homeGoals = view.live?.homeGoals,
            awayGoals = view.live?.awayGoals,
            oddsHome = view.odds?.home,
            oddsDraw = view.odds?.draw,
            oddsAway = view.odds?.away,
            resolvedAtEpochMs = null,
            actualHomeGoals = null,
            actualAwayGoals = null,
            outcomeCorrect = null,
            brier = null,
            logLoss = null,
        )
        val markets = view.markets.mapIndexed { index, row ->
            PredictionMarketEntity(
                predictionId = view.id,
                marketType = row.type.name,
                selectionKey = row.selectionKey,
                line = row.line,
                probability = row.probability,
                sortOrder = index,
            )
        }
        db.predictionDao().save(entity, markets)
    }

    suspend fun latestView(matchId: String): PredictionView? {
        val entity = db.predictionDao().latestForMatch(matchId) ?: return null
        return toView(entity)
    }

    fun observeRecent(limit: Int = 50): Flow<List<PredictionWithMatch>> =
        db.predictionDao().observeRecent(limit).map { entities ->
            val views = ArrayList<PredictionView>(entities.size)
            for (entity in entities) {
                toView(entity)?.let { views.add(it) }
            }
            val matches = matchRepository.decorate(
                db.matchDao().byIds(views.map { it.matchId }.distinct()),
            ).associateBy { it.id }
            views.mapNotNull { view ->
                matches[view.matchId]?.let { PredictionWithMatch(view, it) }
            }
        }

    suspend fun resolvedViews(limit: Int = 100): List<PredictionView> {
        val result = ArrayList<PredictionView>(limit)
        for (entity in db.predictionDao().resolved(limit)) {
            toView(entity)?.let { result.add(it) }
        }
        return result
    }

    /**
     * Resolves every stored prediction whose match has finished. Brier score and log
     * loss are stored per prediction so the performance screen can aggregate them.
     */
    suspend fun resolveFinishedPredictions(): Int {
        val unresolved = db.predictionDao().unresolved()
        if (unresolved.isEmpty()) return 0
        var resolved = 0
        for (prediction in unresolved) {
            val match = db.matchDao().byId(prediction.matchId) ?: continue
            val status = parseStatus(match.status)
            if (!status.isFinished || match.scoreScope != "REGULATION") continue
            if (prediction.modelVersion != MODEL_VERSION) continue
            if (prediction.kind == PredictionKind.PRE_MATCH.name && prediction.createdAtEpochMs >= match.kickoffEpochMs) continue
            val homeGoals = match.homeGoals ?: continue
            val awayGoals = match.awayGoals ?: continue
            val actual = when {
                homeGoals > awayGoals -> MatchOutcome.HOME_WIN
                homeGoals == awayGoals -> MatchOutcome.DRAW
                else -> MatchOutcome.AWAY_WIN
            }
            val probabilities = listOf(prediction.homeWin, prediction.draw, prediction.awayWin)
            val actualIndex = when (actual) {
                MatchOutcome.HOME_WIN -> 0
                MatchOutcome.DRAW -> 1
                MatchOutcome.AWAY_WIN -> 2
            }
            var brier = 0.0
            for (index in probabilities.indices) {
                val target = if (index == actualIndex) 1.0 else 0.0
                brier += (probabilities[index] - target) * (probabilities[index] - target)
            }
            val actualProbability = probabilities[actualIndex].coerceIn(1e-9, 1.0)
            val logLoss = -ln(actualProbability)
            val best = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
            db.predictionDao().resolve(
                id = prediction.id,
                resolvedAt = clock.nowEpochMs(),
                homeGoals = homeGoals,
                awayGoals = awayGoals,
                correct = best == actualIndex,
                brier = brier,
                logLoss = logLoss,
            )
            resolved++
        }
        return resolved
    }

    suspend fun countAll(): Int = db.predictionDao().count()

    // ------------------------------------------------------------------ mapping

    private fun PredictionResult.toView(
        matchId: String,
        kind: PredictionKind,
        createdAtEpochMs: Long,
        odds: MatchOdds?,
    ): PredictionView {
        val marketRows = buildMarketRows(this)
        return PredictionView(
            id = "$matchId:${kind.name}:$createdAtEpochMs",
            matchId = matchId,
            kind = kind,
            createdAtEpochMs = createdAtEpochMs,
            expectedHomeGoals = expectedHomeGoals,
            expectedAwayGoals = expectedAwayGoals,
            homeWin = markets.homeWin,
            draw = markets.draw,
            awayWin = markets.awayWin,
            markets = marketRows,
            topScores = markets.topScores.map { ScoreLine(it.homeGoals, it.awayGoals, it.probability) },
            modelName = model.name,
            modelVersion = model.version,
            fittedMatches = model.fittedMatches,
            rho = model.rho,
            quality = QualityView(
                level = quality.level.toDomain(),
                completeness = quality.completeness,
                missing = quality.missing.map { it.name },
                notes = quality.notes.map { it.code.name },
                historicalMatchesUsed = quality.historicalMatchesUsed,
            ),
            explanations = explanations.map { ExplanationView(it.code.name, it.weight, it.params) },
            live = live?.let { snapshot ->
                com.footballai.app.domain.model.LiveSnapshot(
                    minute = snapshot.minute,
                    homeGoals = snapshot.homeGoals,
                    awayGoals = snapshot.awayGoals,
                    remainingMinutes = snapshot.remainingMinutes,
                    remainingHomeExpectedGoals = snapshot.remainingHomeExpectedGoals,
                    remainingAwayExpectedGoals = snapshot.remainingAwayExpectedGoals,
                    nextGoalHome = snapshot.nextGoalHome,
                    nextGoalAway = snapshot.nextGoalAway,
                    noMoreGoals = snapshot.noMoreGoals,
                    remainingOver = snapshot.remainingOver,
                    adjustments = snapshot.adjustmentsApplied,
                )
            },
            odds = odds,
            isStale = false,
            resolution = null,
        )
    }

    private fun buildMarketRows(result: PredictionResult): List<MarketRow> {
        val rows = ArrayList<MarketRow>(32)
        val markets = result.markets
        rows.add(MarketRow(MarketType.ONE_X_TWO, "HOME", null, markets.homeWin))
        rows.add(MarketRow(MarketType.ONE_X_TWO, "DRAW", null, markets.draw))
        rows.add(MarketRow(MarketType.ONE_X_TWO, "AWAY", null, markets.awayWin))
        for ((doubleChance, probability) in markets.doubleChance) {
            val key = when (doubleChance) {
                com.footballai.engine.model.DoubleChance.HOME_OR_DRAW -> "HOME_OR_DRAW"
                com.footballai.engine.model.DoubleChance.HOME_OR_AWAY -> "HOME_OR_AWAY"
                com.footballai.engine.model.DoubleChance.DRAW_OR_AWAY -> "DRAW_OR_AWAY"
            }
            rows.add(MarketRow(MarketType.DOUBLE_CHANCE, key, null, probability))
        }
        for (line in MarketCalculator.GOAL_LINES) {
            markets.over[line]?.let { rows.add(MarketRow(MarketType.OVER_UNDER, "OVER", line, it)) }
        }
        for (line in MarketCalculator.GOAL_LINES) {
            markets.under[line]?.let { rows.add(MarketRow(MarketType.OVER_UNDER, "UNDER", line, it)) }
        }
        rows.add(MarketRow(MarketType.BOTH_TEAMS_TO_SCORE, "YES", null, markets.bttsYes))
        rows.add(MarketRow(MarketType.BOTH_TEAMS_TO_SCORE, "NO", null, markets.bttsNo))
        markets.topScores.take(6).forEach { score ->
            rows.add(
                MarketRow(
                    type = MarketType.EXACT_SCORE,
                    selectionKey = "${score.homeGoals}-${score.awayGoals}",
                    line = null,
                    probability = score.probability,
                ),
            )
        }
        markets.firstHalf?.let { firstHalf ->
            rows.add(MarketRow(MarketType.FIRST_HALF_RESULT, "HOME", null, firstHalf.homeWin))
            rows.add(MarketRow(MarketType.FIRST_HALF_RESULT, "DRAW", null, firstHalf.draw))
            rows.add(MarketRow(MarketType.FIRST_HALF_RESULT, "AWAY", null, firstHalf.awayWin))
            rows.add(MarketRow(MarketType.FIRST_HALF_OVER, "OVER", 0.5, firstHalf.over05))
            rows.add(MarketRow(MarketType.FIRST_HALF_OVER, "OVER", 1.5, firstHalf.over15))
        }
        result.live?.let { live ->
            rows.add(MarketRow(MarketType.NEXT_GOAL, "HOME", null, live.nextGoalHome))
            rows.add(MarketRow(MarketType.NEXT_GOAL, "AWAY", null, live.nextGoalAway))
            rows.add(MarketRow(MarketType.NEXT_GOAL, "NONE", null, live.noMoreGoals))
        }
        return rows
    }

    private suspend fun toView(entity: PredictionEntity): PredictionView? {
        // Retain legacy snapshots for audit, but never present insufficient-data probabilities.
        if (entity.qualityLevel == QualityLevel.INSUFFICIENT.name) return null
        val quality = Serialized.decodeQuality(entity.qualityJson)
        val markets = db.predictionDao().markets(entity.id)
        val marketRows = markets.mapNotNull { row ->
            val type = runCatching { MarketType.valueOf(row.marketType) }.getOrNull() ?: return@mapNotNull null
            MarketRow(type, row.selectionKey, row.line, row.probability)
        }
        val topScores = marketRows
            .filter { it.type == MarketType.EXACT_SCORE }
            .mapNotNull { row ->
                val parts = row.selectionKey.split("-")
                if (parts.size != 2) return@mapNotNull null
                val home = parts[0].toIntOrNull() ?: return@mapNotNull null
                val away = parts[1].toIntOrNull() ?: return@mapNotNull null
                ScoreLine(home, away, row.probability)
            }
        val resolution = if (entity.resolvedAtEpochMs != null && entity.actualHomeGoals != null && entity.actualAwayGoals != null) {
            PredictionResolution(
                actualHomeGoals = entity.actualHomeGoals,
                actualAwayGoals = entity.actualAwayGoals,
                outcomeCorrect = entity.outcomeCorrect ?: false,
                brier = entity.brier ?: Double.NaN,
                logLoss = entity.logLoss ?: Double.NaN,
                resolvedAtEpochMs = entity.resolvedAtEpochMs,
            )
        } else {
            null
        }
        return PredictionView(
            id = entity.id,
            matchId = entity.matchId,
            kind = runCatching { PredictionKind.valueOf(entity.kind) }.getOrDefault(PredictionKind.PRE_MATCH),
            createdAtEpochMs = entity.createdAtEpochMs,
            expectedHomeGoals = entity.expectedHomeGoals,
            expectedAwayGoals = entity.expectedAwayGoals,
            homeWin = entity.homeWin,
            draw = entity.draw,
            awayWin = entity.awayWin,
            markets = marketRows,
            topScores = topScores,
            modelName = entity.modelName,
            modelVersion = entity.modelVersion,
            fittedMatches = entity.fittedMatches,
            rho = entity.rho,
            quality = QualityView(
                level = runCatching { QualityLevel.valueOf(entity.qualityLevel) }.getOrDefault(QualityLevel.INSUFFICIENT),
                completeness = entity.qualityCompleteness,
                missing = quality.missing,
                notes = quality.notes,
                historicalMatchesUsed = entity.fittedMatches,
            ),
            explanations = Serialized.decodeExplanations(entity.explanationsJson),
            live = Serialized.decodeLiveSnapshot(entity.liveSnapshotJson),
            odds = if (entity.oddsHome != null && entity.oddsDraw != null && entity.oddsAway != null) {
                MatchOdds(entity.oddsHome, entity.oddsDraw, entity.oddsAway)
            } else {
                null
            },
            isStale = abs(clock.nowEpochMs() - entity.createdAtEpochMs) > (if (entity.kind == PredictionKind.LIVE.name) LIVE_TTL_MS else STALE_AFTER_MS),
            resolution = resolution,
        )
    }

    private fun DataQualityLevel.toDomain(): QualityLevel = when (this) {
        DataQualityLevel.HIGH -> QualityLevel.HIGH
        DataQualityLevel.MEDIUM -> QualityLevel.MEDIUM
        DataQualityLevel.LOW -> QualityLevel.LOW
        DataQualityLevel.INSUFFICIENT -> QualityLevel.INSUFFICIENT
    }

    companion object {
        const val MODEL_VERSION = "football-ai/engine-1.2.1"
        const val ELO_BLEND_WEIGHT = 0.15
        const val LIVE_TTL_MS = PredictionPolicy.LIVE_TTL_MS
        private const val STALE_AFTER_MS = 6 * TimeConstants.HOUR_MS
    }
}
