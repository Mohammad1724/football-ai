package com.footballai.app.data.local

import com.footballai.app.domain.model.CalibrationView
import com.footballai.app.domain.model.ConfidenceView
import com.footballai.app.domain.model.ExplanationView
import com.footballai.app.domain.model.LiveSnapshot
import com.footballai.app.domain.model.QualityView
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Compact JSON encoding for the few structured fields stored on a prediction.
 *
 * Engine explanations, quality notes and the live snapshot are variable-length
 * structures; storing them as JSON keeps the schema stable while staying readable
 * for debugging.
 *
 * ## Why every number here is nullable
 *
 * "Unknown" is represented by `Double.NaN` throughout the engine (no calibration
 * samples yet, no measured calibration error, no expected-goals data). Kotlin's default
 * JSON encoder *rejects* NaN and infinity, so a stored prediction used to throw while
 * being saved — which on Android kills the process, because it happened inside the
 * screen's coroutine. Two independent guards prevent that now:
 *
 * 1. a non-finite value is written as `null` instead of NaN,
 * 2. `allowSpecialFloatingPointValues` is on as a last line of defence, so even a value
 *    this file did not anticipate can never crash a save.
 *
 * Decoding maps `null` back to `Double.NaN`, so "unknown" survives a round trip.
 */
object Serialized {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        // Defence in depth: prefers `null` below, never throws on a stray NaN/Inf.
        allowSpecialFloatingPointValues = true
    }

    /** NaN and the infinities mean "not known" here, and JSON has no way to say that. */
    private fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }

    private fun Double?.orNaN(): Double = this ?: Double.NaN

    @Serializable
    data class ExplanationDto(
        val code: String,
        val weight: Double? = null,
        val params: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class QualityDto(
        val missing: List<String> = emptyList(),
        val notes: List<String> = emptyList(),
    )

    @Serializable
    data class LiveSnapshotDto(
        val minute: Int,
        val homeGoals: Int,
        val awayGoals: Int,
        val remainingMinutes: Int,
        val remainingHomeExpectedGoals: Double? = null,
        val remainingAwayExpectedGoals: Double? = null,
        val nextGoalHome: Double? = null,
        val nextGoalAway: Double? = null,
        val noMoreGoals: Double? = null,
        val remainingOver: Map<String, Double> = emptyMap(),
        val adjustments: List<String> = emptyList(),
    )

    @Serializable
    data class CalibrationDto(
        val method: String,
        val temperature: Double? = null,
        val sampleCount: Int = 0,
        val holdoutCount: Int = 0,
        val logLossBefore: Double? = null,
        val logLossAfter: Double? = null,
        val rawHomeWin: Double? = null,
        val rawDraw: Double? = null,
        val rawAwayWin: Double? = null,
    )

    @Serializable
    data class ConfidenceDto(
        val level: String = "INSUFFICIENT",
        val gaps: List<String> = emptyList(),
        val uncertainty: Double? = null,
        val topGap: Double? = null,
        val effectiveSample: Int = 0,
        val calibrationSamples: Int = 0,
        val calibrationError: Double? = null,
        val dataCompleteness: Double? = null,
        val freshnessMinutes: Long? = null,
        val calibration: CalibrationDto? = null,
    )

    fun encodeConfidence(confidence: ConfidenceView?, calibration: CalibrationView?): String? {
        if (confidence == null) return null
        val dto = ConfidenceDto(
            level = confidence.level,
            gaps = confidence.gaps,
            uncertainty = confidence.uncertainty.finiteOrNull(),
            topGap = confidence.topGap.finiteOrNull(),
            effectiveSample = confidence.effectiveSample,
            calibrationSamples = confidence.calibrationSamples,
            calibrationError = confidence.calibrationError.finiteOrNull(),
            dataCompleteness = confidence.dataCompleteness.finiteOrNull(),
            freshnessMinutes = confidence.freshnessMinutes,
            calibration = calibration?.let {
                CalibrationDto(
                    method = it.method,
                    temperature = it.temperature.finiteOrNull(),
                    sampleCount = it.sampleCount,
                    holdoutCount = it.holdoutCount,
                    logLossBefore = it.logLossBefore.finiteOrNull(),
                    logLossAfter = it.logLossAfter.finiteOrNull(),
                    rawHomeWin = it.rawHomeWin.finiteOrNull(),
                    rawDraw = it.rawDraw.finiteOrNull(),
                    rawAwayWin = it.rawAwayWin.finiteOrNull(),
                )
            },
        )
        return encode { json.encodeToString(ConfidenceDto.serializer(), dto) }
    }

    fun decodeConfidence(value: String?): Pair<ConfidenceView?, CalibrationView?> {
        if (value.isNullOrBlank()) return null to null
        val dto = runCatching { json.decodeFromString(ConfidenceDto.serializer(), value) }.getOrNull()
            ?: return null to null
        val confidence = ConfidenceView(
            level = dto.level,
            gaps = dto.gaps,
            uncertainty = dto.uncertainty.orNaN(),
            topGap = dto.topGap.orNaN(),
            effectiveSample = dto.effectiveSample,
            calibrationSamples = dto.calibrationSamples,
            calibrationError = dto.calibrationError.orNaN(),
            dataCompleteness = dto.dataCompleteness.orNaN(),
            freshnessMinutes = dto.freshnessMinutes,
        )
        val calibration = dto.calibration?.let {
            CalibrationView(
                method = it.method,
                temperature = it.temperature ?: 1.0,
                sampleCount = it.sampleCount,
                holdoutCount = it.holdoutCount,
                logLossBefore = it.logLossBefore.orNaN(),
                logLossAfter = it.logLossAfter.orNaN(),
                rawHomeWin = it.rawHomeWin.orNaN(),
                rawDraw = it.rawDraw.orNaN(),
                rawAwayWin = it.rawAwayWin.orNaN(),
            )
        }
        return confidence to calibration
    }

    fun encodeExplanations(explanations: List<ExplanationView>): String = encode {
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ExplanationDto.serializer()),
            explanations.map { ExplanationDto(it.code, it.weight.finiteOrNull(), it.params) },
        )
    } ?: "[]"

    fun decodeExplanations(value: String?): List<ExplanationView> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ExplanationDto.serializer()),
                value,
            ).map { ExplanationView(it.code, it.weight.orNaN(), it.params) }
        }.getOrDefault(emptyList())
    }

    fun encodeQuality(quality: QualityView): String = encode {
        json.encodeToString(
            QualityDto.serializer(),
            QualityDto(missing = quality.missing, notes = quality.notes),
        )
    } ?: "{}"

    fun decodeQuality(value: String?): QualityDto {
        if (value.isNullOrBlank()) return QualityDto()
        return runCatching { json.decodeFromString(QualityDto.serializer(), value) }.getOrDefault(QualityDto())
    }

    fun encodeLiveSnapshot(snapshot: LiveSnapshot?): String? {
        if (snapshot == null) return null
        val dto = LiveSnapshotDto(
            minute = snapshot.minute,
            homeGoals = snapshot.homeGoals,
            awayGoals = snapshot.awayGoals,
            remainingMinutes = snapshot.remainingMinutes,
            remainingHomeExpectedGoals = snapshot.remainingHomeExpectedGoals.finiteOrNull(),
            remainingAwayExpectedGoals = snapshot.remainingAwayExpectedGoals.finiteOrNull(),
            nextGoalHome = snapshot.nextGoalHome.finiteOrNull(),
            nextGoalAway = snapshot.nextGoalAway.finiteOrNull(),
            noMoreGoals = snapshot.noMoreGoals.finiteOrNull(),
            remainingOver = snapshot.remainingOver
                .filterKeys { it.isFinite() }
                .mapKeys { it.key.toString() }
                .filterValues { it.isFinite() },
            adjustments = snapshot.adjustments,
        )
        return encode { json.encodeToString(LiveSnapshotDto.serializer(), dto) }
    }

    fun decodeLiveSnapshot(value: String?): LiveSnapshot? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val dto = json.decodeFromString(LiveSnapshotDto.serializer(), value)
            LiveSnapshot(
                minute = dto.minute,
                homeGoals = dto.homeGoals,
                awayGoals = dto.awayGoals,
                remainingMinutes = dto.remainingMinutes,
                remainingHomeExpectedGoals = dto.remainingHomeExpectedGoals.orNaN(),
                remainingAwayExpectedGoals = dto.remainingAwayExpectedGoals.orNaN(),
                nextGoalHome = dto.nextGoalHome.orNaN(),
                nextGoalAway = dto.nextGoalAway.orNaN(),
                noMoreGoals = dto.noMoreGoals.orNaN(),
                remainingOver = dto.remainingOver.mapKeys { it.key.toDoubleOrNull() ?: 0.0 },
                adjustments = dto.adjustments,
            )
        }.getOrNull()
    }

    /**
     * A serialization failure must never travel up as an exception: these values are
     * convenience fields, and a screen that shows a forecast is worth more than an
     * exact copy of its explanation list. Returning `null` keeps that decision local.
     */
    private inline fun encode(block: () -> String): String? = runCatching(block).getOrNull()
}
