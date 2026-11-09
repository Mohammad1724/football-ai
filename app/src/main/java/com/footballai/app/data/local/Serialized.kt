package com.footballai.app.data.local

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
 */
object Serialized {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Serializable
    data class ExplanationDto(
        val code: String,
        val weight: Double = 0.0,
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
        val remainingHomeExpectedGoals: Double,
        val remainingAwayExpectedGoals: Double,
        val nextGoalHome: Double,
        val nextGoalAway: Double,
        val noMoreGoals: Double,
        val remainingOver: Map<String, Double> = emptyMap(),
        val adjustments: List<String> = emptyList(),
    )

    fun encodeExplanations(explanations: List<ExplanationView>): String = json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(ExplanationDto.serializer()),
        explanations.map { ExplanationDto(it.code, it.weight, it.params) },
    )

    fun decodeExplanations(value: String?): List<ExplanationView> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ExplanationDto.serializer()),
                value,
            ).map { ExplanationView(it.code, it.weight, it.params) }
        }.getOrDefault(emptyList())
    }

    fun encodeQuality(quality: QualityView): String = json.encodeToString(
        QualityDto.serializer(),
        QualityDto(missing = quality.missing, notes = quality.notes),
    )

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
            remainingHomeExpectedGoals = snapshot.remainingHomeExpectedGoals,
            remainingAwayExpectedGoals = snapshot.remainingAwayExpectedGoals,
            nextGoalHome = snapshot.nextGoalHome,
            nextGoalAway = snapshot.nextGoalAway,
            noMoreGoals = snapshot.noMoreGoals,
            remainingOver = snapshot.remainingOver.mapKeys { it.key.toString() },
            adjustments = snapshot.adjustments,
        )
        return json.encodeToString(LiveSnapshotDto.serializer(), dto)
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
                remainingHomeExpectedGoals = dto.remainingHomeExpectedGoals,
                remainingAwayExpectedGoals = dto.remainingAwayExpectedGoals,
                nextGoalHome = dto.nextGoalHome,
                nextGoalAway = dto.nextGoalAway,
                noMoreGoals = dto.noMoreGoals,
                remainingOver = dto.remainingOver.mapKeys { it.key.toDoubleOrNull() ?: 0.0 },
                adjustments = dto.adjustments,
            )
        }.getOrNull()
    }
}
