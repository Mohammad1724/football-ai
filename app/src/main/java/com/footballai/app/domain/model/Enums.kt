package com.footballai.app.domain.model

/** Provider feature matrix, used to decide which UI affordances are honest to show. */
data class ProviderCapabilities(
    val liveScores: Boolean,
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
