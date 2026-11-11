package com.footballai.engine.quality

import com.footballai.engine.model.DataQuality
import com.footballai.engine.model.DataQualityLevel
import com.footballai.engine.model.MissingDataItem
import com.footballai.engine.model.QualityNote
import com.footballai.engine.model.QualityNoteCode

/**
 * Inputs used to decide how much the engine's output can be trusted.
 * Every field maps to a fact the data layer actually knows; nothing is guessed.
 */
data class QualityInputs(
    val competitionHistoricalMatches: Int = 0,
    val homeMatchesUsed: Int = 0,
    val awayMatchesUsed: Int = 0,
    val hasLineups: Boolean = false,
    val hasInjuries: Boolean = false,
    val hasOdds: Boolean = false,
    val hasLiveStatistics: Boolean = false,
    val hasExpectedGoalsData: Boolean = false,
    val hasStandings: Boolean = false,
    val leagueBaselineIsFallback: Boolean = false,
    val isLive: Boolean = false,
)

/**
 * Turns data availability into an explicit quality grade.
 *
 * The grade is a weighted completeness score, never a marketing label: a match
 * without historical results can never be rated above LOW.
 */
object DataQualityAssessor {

    private const val WEIGHT_HISTORY = 0.45
    private const val WEIGHT_TEAM_SAMPLE = 0.20
    private const val WEIGHT_LINEUPS = 0.10
    private const val WEIGHT_INJURIES = 0.10
    private const val WEIGHT_ODDS = 0.05
    private const val WEIGHT_LIVE_STATS = 0.10

    fun assess(inputs: QualityInputs): DataQuality {
        val missing = mutableListOf<MissingDataItem>()
        val notes = mutableListOf<QualityNote>()
        var score = 0.0

        // Historical depth for the competition.
        score += WEIGHT_HISTORY * when {
            inputs.competitionHistoricalMatches >= 300 -> 1.0
            inputs.competitionHistoricalMatches >= 120 -> 0.8
            inputs.competitionHistoricalMatches >= 40 -> 0.55
            inputs.competitionHistoricalMatches > 0 -> 0.3
            else -> 0.0
        }
        if (inputs.competitionHistoricalMatches < 40) missing.add(MissingDataItem.HISTORICAL_MATCHES)

        // Per-team sample size.
        val minTeamSample = minOf(inputs.homeMatchesUsed, inputs.awayMatchesUsed)
        score += WEIGHT_TEAM_SAMPLE * when {
            minTeamSample >= 20 -> 1.0
            minTeamSample >= 10 -> 0.75
            minTeamSample >= 5 -> 0.5
            minTeamSample > 0 -> 0.25
            else -> 0.0
        }
        if (minTeamSample < 5) missing.add(MissingDataItem.RECENT_FORM)

        if (inputs.hasLineups) score += WEIGHT_LINEUPS else missing.add(MissingDataItem.LINEUPS)
        if (inputs.hasInjuries) score += WEIGHT_INJURIES else missing.add(MissingDataItem.INJURIES)
        if (inputs.hasOdds) score += WEIGHT_ODDS else {
            missing.add(MissingDataItem.BOOKMAKER_ODDS)
            notes.add(QualityNote(QualityNoteCode.ODDS_UNAVAILABLE))
        }
        if (inputs.isLive) {
            if (inputs.hasLiveStatistics) score += WEIGHT_LIVE_STATS else {
                missing.add(MissingDataItem.LIVE_STATISTICS)
                notes.add(QualityNote(QualityNoteCode.LIVE_STATS_PARTIAL))
            }
        }
        if (!inputs.hasExpectedGoalsData) missing.add(MissingDataItem.XG_BASED_FEATURES)
        if (!inputs.hasStandings) missing.add(MissingDataItem.STANDINGS)

        if (inputs.leagueBaselineIsFallback) {
            notes.add(QualityNote(QualityNoteCode.FALLBACK_LEAGUE_BASELINE))
        }
        if (inputs.competitionHistoricalMatches in 1..119) {
            notes.add(
                QualityNote(
                    QualityNoteCode.SMALL_SAMPLE,
                    mapOf("matches" to inputs.competitionHistoricalMatches.toString()),
                ),
            )
        }
        if (inputs.competitionHistoricalMatches == 0) {
            notes.add(QualityNote(QualityNoteCode.NO_HISTORY_FOR_COMPETITION))
        }
        if (!inputs.hasLineups) notes.add(QualityNote(QualityNoteCode.LINEUPS_UNAVAILABLE))
        if (!inputs.hasInjuries) notes.add(QualityNote(QualityNoteCode.INJURIES_UNAVAILABLE))
        notes.add(QualityNote(QualityNoteCode.MODEL_NOT_CALIBRATED))

        // Live-only evidence must not make HIGH unreachable before kickoff. Normalize
        // against the evidence categories applicable to this prediction context.
        val applicableWeight = WEIGHT_HISTORY + WEIGHT_TEAM_SAMPLE + WEIGHT_LINEUPS +
            WEIGHT_INJURIES + WEIGHT_ODDS + if (inputs.isLive) WEIGHT_LIVE_STATS else 0.0
        val completeness = (score / applicableWeight).coerceIn(0.0, 1.0)
        val level = when {
            inputs.competitionHistoricalMatches == 0 && minTeamSample == 0 -> DataQualityLevel.INSUFFICIENT
            completeness >= 0.80 -> DataQualityLevel.HIGH
            completeness >= 0.55 -> DataQualityLevel.MEDIUM
            completeness >= 0.30 -> DataQualityLevel.LOW
            else -> DataQualityLevel.INSUFFICIENT
        }

        return DataQuality(
            level = level,
            completeness = completeness,
            missing = missing.distinct(),
            historicalMatchesUsed = inputs.competitionHistoricalMatches,
            notes = notes,
        )
    }
}
