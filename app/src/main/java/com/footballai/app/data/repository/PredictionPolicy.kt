package com.footballai.app.data.repository

import com.footballai.app.core.ErrorCode
import com.footballai.app.core.TimeConstants
import com.footballai.app.data.local.entity.MatchEntity
import com.footballai.app.data.local.mapper.parseStatus
import com.footballai.app.domain.model.MatchStatus

/** Pure, testable admission policy. Unknown time/score must never become invented input. */
object PredictionPolicy {
    const val LIVE_TTL_MS = 2 * TimeConstants.MINUTE_MS

    fun minute(match: MatchEntity): Int? =
        if (parseStatus(match.status) == MatchStatus.HALF_TIME) 45 else match.minute

    fun error(match: MatchEntity, now: Long): ErrorCode? {
        val status = parseStatus(match.status)
        if (match.scoreScope != "REGULATION" ||
            (!status.isLive && (status != MatchStatus.SCHEDULED || now >= match.kickoffEpochMs))) {
            return ErrorCode.INVALID_MATCH_STATE
        }
        if (status.isLive) {
            if (now - match.lastSyncedAt !in 0..LIVE_TTL_MS ||
                match.homeGoals == null || match.awayGoals == null ||
                match.homeGoals < 0 || match.awayGoals < 0) return ErrorCode.STALE_DATA
            val minute = minute(match)
            // The provider does not expose the remaining stoppage time. Do not model
            // a still-live 90+ minute fixture as having a known zero horizon.
            if (minute == null || minute !in 0..89) return ErrorCode.UNKNOWN_MATCH_TIME
        }
        return null
    }

    fun shouldStoreLive(now: Long, lastStoredAt: Long, force: Boolean = false): Boolean =
        force || lastStoredAt == 0L || now - lastStoredAt >= 15 * TimeConstants.MINUTE_MS
}
