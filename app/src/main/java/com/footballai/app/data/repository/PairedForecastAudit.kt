package com.footballai.app.data.repository

import kotlin.math.ln

/** Both observations must describe the same match and verified regulation score. */
data class PairedForecastAudit(
    val count: Int = 0,
    val earlyCorrect: Int = 0,
    val lateCorrect: Int = 0,
    val earlyBrier: Double = Double.NaN,
    val lateBrier: Double = Double.NaN,
    val earlyLogLoss: Double = Double.NaN,
    val lateLogLoss: Double = Double.NaN,
) {
    companion object {
        fun from(pairs: List<Pair<StoredForecastObservation, StoredForecastObservation>>): PairedForecastAudit {
            val valid = pairs.filter { (early, late) ->
                early.valid() && late.valid() && early.kickoff == late.kickoff &&
                    early.homeGoals == late.homeGoals && early.awayGoals == late.awayGoals &&
                    early.createdAt < early.kickoff - 86_400_000L &&
                    late.createdAt >= late.kickoff - 86_400_000L && late.createdAt > early.createdAt
            }
            if (valid.isEmpty()) return PairedForecastAudit()
            fun brier(o: StoredForecastObservation): Double {
                val actual = if (o.homeGoals > o.awayGoals) 0 else if (o.homeGoals == o.awayGoals) 1 else 2
                return listOf(o.home, o.draw, o.away).mapIndexed { i, p ->
                    val d = p - if (i == actual) 1.0 else 0.0
                    d * d
                }.sum()
            }
            fun loss(o: StoredForecastObservation): Double = -ln((if (o.homeGoals > o.awayGoals) o.home
                else if (o.homeGoals == o.awayGoals) o.draw else o.away).coerceIn(1e-9, 1.0))
            val early = valid.map { it.first }
            val late = valid.map { it.second }
            return PairedForecastAudit(valid.size, early.count { it.savedCorrect == true },
                late.count { it.savedCorrect == true }, early.map(::brier).average(), late.map(::brier).average(),
                early.map(::loss).average(), late.map(::loss).average())
        }
    }
}
