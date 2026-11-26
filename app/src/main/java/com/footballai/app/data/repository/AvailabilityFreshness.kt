package com.footballai.app.data.repository

/** Fetch time is only a freshness bound, not proof of the publisher's update time. */
object AvailabilityFreshness {
    // The current collector caches fixture details without source-observation times.
    // A timestamp field alone is insufficient until that producer is repaired and audited.
    const val SOURCE_PROVENANCE_VERIFIED = false
    const val MAX_AGE_MS = 24 * 60 * 60 * 1_000L

    fun usable(updatedAt: Long, observedAt: Long?, recordedKickoff: Long?, currentKickoff: Long, now: Long,
               homeShare: Double, awayShare: Double): Boolean =
        observedAt != null && observedAt > 0 && updatedAt >= observedAt && now >= updatedAt &&
            now - observedAt <= MAX_AGE_MS && recordedKickoff != null && recordedKickoff == currentKickoff &&
            updatedAt < currentKickoff && observedAt < currentKickoff &&
            homeShare.isFinite() && homeShare in 0.0..1.0 &&
            awayShare.isFinite() && awayShare in 0.0..1.0
}
