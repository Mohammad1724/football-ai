package com.footballai.app.data.repository

import kotlin.math.abs

enum class ForecastProbabilityBand { BELOW_50, FROM_50_TO_70, AT_LEAST_70 }
enum class ForecastLeadTime { UNDER_DAY, ONE_TO_THREE_DAYS, OVER_THREE_DAYS }

data class StoredForecastObservation(
    val home: Double,
    val draw: Double,
    val away: Double,
    val homeGoals: Int,
    val awayGoals: Int,
    val savedCorrect: Boolean?,
    val createdAt: Long,
    val kickoff: Long,
    val competitionId: String,
    val competitionName: String,
) {
    /** Same deterministic 1X2 ordering as the grader. Reject broken/inconsistent records. */
    fun valid(): Boolean {
        val p = listOf(home, draw, away)
        if (p.any { !it.isFinite() || it !in 0.0..1.0 } || abs(p.sum() - 1.0) > 1e-6 ||
            homeGoals < 0 || awayGoals < 0 || createdAt >= kickoff || savedCorrect == null
        ) return false
        val best = p.indices.maxByOrNull { p[it] } ?: return false
        val actual = if (homeGoals > awayGoals) 0 else if (homeGoals == awayGoals) 1 else 2
        return savedCorrect == (best == actual)
    }
    val top: Double get() = maxOf(home, draw, away)
    val probabilityBand: ForecastProbabilityBand get() = when {
        top < 0.5 -> ForecastProbabilityBand.BELOW_50
        top < 0.7 -> ForecastProbabilityBand.FROM_50_TO_70
        else -> ForecastProbabilityBand.AT_LEAST_70
    }
    val leadTime: ForecastLeadTime get() = when {
        kickoff - createdAt < DAY_MS -> ForecastLeadTime.UNDER_DAY
        kickoff - createdAt <= 3 * DAY_MS -> ForecastLeadTime.ONE_TO_THREE_DAYS
        else -> ForecastLeadTime.OVER_THREE_DAYS
    }
    companion object { private const val DAY_MS = 86_400_000L }
}

data class ForecastAuditGroup(
    val count: Int,
    val correct: Int,
    val averageTopProbability: Double,
) {
    val accuracy: Double get() = correct.toDouble() / count
}

data class ForecastLeagueAudit(val id: String, val name: String, val summary: ForecastAuditGroup)

data class StoredForecastAudit(
    val bands: Map<ForecastProbabilityBand, ForecastAuditGroup> = emptyMap(),
    val leadTimes: Map<ForecastLeadTime, ForecastAuditGroup> = emptyMap(),
    val leagues: List<ForecastLeagueAudit> = emptyList(),
) {
    companion object {
        fun from(observations: List<StoredForecastObservation>): StoredForecastAudit {
            val valid = observations.filter { it.valid() }
            fun summary(rows: List<StoredForecastObservation>) = ForecastAuditGroup(
                count = rows.size, correct = rows.count { it.savedCorrect == true },
                averageTopProbability = rows.map { it.top }.average(),
            )
            return StoredForecastAudit(
                bands = valid.groupBy { it.probabilityBand }.mapValues { summary(it.value) },
                leadTimes = valid.groupBy { it.leadTime }.mapValues { summary(it.value) },
                leagues = valid.groupBy { it.competitionId }.map { (id, rows) ->
                    ForecastLeagueAudit(id, rows.first().competitionName, summary(rows))
                }.sortedWith(compareByDescending<ForecastLeagueAudit> { it.summary.count }.thenBy { it.name }).take(5),
            )
        }
    }
}
