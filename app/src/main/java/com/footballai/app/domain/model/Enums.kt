package com.footballai.app.domain.model

/** Provider feature matrix, used to decide which UI affordances are honest to show. */
data class ProviderCapabilities(
    val liveScores: Boolean,
    /**
     * Whether the source can refresh one fixture on its own.
     *
     * The open sources publish whole matchdays, so a stored fixture cannot be re-read
     * without fetching its day again. A screen must know that before it offers live
     * tracking, and a refresh attempt must not be reported as a failure.
     */
    val singleMatchRefresh: Boolean = true,
    val matchStatistics: Boolean,
    val events: Boolean,
    val lineups: Boolean,
    val injuries: Boolean,
    val odds: Boolean,
    val standings: Boolean,
    val historicalSeasons: Int,
)

/** Request budget of a provider, enforced by the quota guard before every call. */
data class QuotaPolicy(
    val requestsPerMinute: Int,
    val requestsPerDay: Int,
)

data class ProviderInfo(
    val id: String,
    val displayName: String,
    val requiresApiKey: Boolean,
    val capabilities: ProviderCapabilities,
    val quota: QuotaPolicy,
    val attribution: String,
)
