package com.footballai.app.ui.viewmodel

import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.PredictionKind
import com.footballai.app.domain.model.PredictionWithMatch

internal enum class PredictionRecordStatus { UPCOMING, LIVE, WAITING, RESOLVED }

/** Classification is per record, not per team name. A result can be unavailable or ungradable. */
internal fun predictionRecordStatus(item: PredictionWithMatch, now: Long): PredictionRecordStatus = when {
    item.prediction.resolution != null -> PredictionRecordStatus.RESOLVED
    item.match.status.isLive -> PredictionRecordStatus.LIVE
    item.match.status == MatchStatus.SCHEDULED && item.match.kickoffEpochMs > now -> PredictionRecordStatus.UPCOMING
    else -> PredictionRecordStatus.WAITING
}

internal fun isLatePreMatch(item: PredictionWithMatch): Boolean =
    item.prediction.kind == PredictionKind.PRE_MATCH && item.prediction.createdAtEpochMs >= item.match.kickoffEpochMs

data class PredictionGroup(val matchId: String, val versions: List<PredictionWithMatch>) {
    val latest: PredictionWithMatch get() = versions.first()
}

internal fun predictionGroups(items: List<PredictionWithMatch>): List<PredictionGroup> =
    items.groupBy { it.match.id }.map { (id, versions) ->
        PredictionGroup(id, versions.sortedWith(compareByDescending<PredictionWithMatch> { it.prediction.createdAtEpochMs }
            .thenByDescending { it.prediction.id }))
    }

internal fun PredictionListFilter.includes(item: PredictionWithMatch, now: Long): Boolean = when (this) {
    PredictionListFilter.ALL -> true
    PredictionListFilter.UPCOMING -> predictionRecordStatus(item, now) == PredictionRecordStatus.UPCOMING
    PredictionListFilter.LIVE -> predictionRecordStatus(item, now) == PredictionRecordStatus.LIVE
    PredictionListFilter.WAITING -> predictionRecordStatus(item, now) == PredictionRecordStatus.WAITING
    PredictionListFilter.RESOLVED -> predictionRecordStatus(item, now) == PredictionRecordStatus.RESOLVED
}

internal fun filterPredictionGroups(groups: List<PredictionGroup>, filter: PredictionListFilter, now: Long): List<PredictionGroup> {
    val found = groups.filter { group -> group.versions.any { filter.includes(it, now) } }
    return if (filter == PredictionListFilter.UPCOMING || filter == PredictionListFilter.LIVE)
        found.sortedBy { it.latest.match.kickoffEpochMs }
    else found.sortedByDescending { it.latest.match.kickoffEpochMs }
}
