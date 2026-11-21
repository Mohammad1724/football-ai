package com.footballai.app.data.local

/** Eligibility is rechecked inside the insert transaction, including a changed kickoff. */
object LateForecastPolicy {
    private const val DAY_MS = 86_400_000L
    fun eligible(firstId: String, expectedFirstId: String, status: String,
                 kickoff: Long, snapshotKickoff: Long, firstAt: Long, snapshotAt: Long, finishedAt: Long): Boolean =
        firstId == expectedFirstId && status == "SCHEDULED" && kickoff == snapshotKickoff &&
            firstAt < kickoff - DAY_MS && snapshotAt >= kickoff - DAY_MS &&
            snapshotAt > firstAt && snapshotAt < kickoff && finishedAt >= snapshotAt && finishedAt < kickoff
}
