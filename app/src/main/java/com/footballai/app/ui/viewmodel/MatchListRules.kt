package com.footballai.app.ui.viewmodel

import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.MatchStatus
import java.time.Instant
import java.time.ZoneId

enum class MatchListFilter { TODAY, TOMORROW, WEEK, FINISHED }

object MatchListRules {
    /** Inclusive query bounds, using local calendar days (including DST transitions). */
    fun range(filter: MatchListFilter, now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun start(days: Long) = date.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
        return when (filter) {
            MatchListFilter.TODAY -> start(0) to start(1) - 1
            MatchListFilter.TOMORROW -> start(1) to start(2) - 1
            MatchListFilter.WEEK -> now to start(7) - 1
            MatchListFilter.FINISHED -> start(-14) to now
        }
    }

    fun select(matches: List<MatchSummary>, filter: MatchListFilter, competitionId: String?): List<MatchSummary> {
        val filtered = matches.filter { match ->
            (competitionId == null || match.competitionId == competitionId) && when (filter) {
                MatchListFilter.FINISHED -> match.status.isFinished
                MatchListFilter.WEEK, MatchListFilter.TOMORROW ->
                    match.status == MatchStatus.SCHEDULED || match.status.isLive
                MatchListFilter.TODAY -> true
            }
        }
        return if (filter == MatchListFilter.FINISHED) {
            filtered.sortedWith(compareByDescending<MatchSummary> { it.kickoffEpochMs }.thenBy { it.id })
        } else filtered.sortedWith(compareBy<MatchSummary> { it.kickoffEpochMs }.thenBy { it.id })
    }
}
