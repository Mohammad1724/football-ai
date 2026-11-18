package com.footballai.app.data.remote

import kotlinx.serialization.Serializable

/**
 * The public search answer at `/apis/common/v3/search`.
 *
 * It is the one place the source will name a team and hand over its crest together, which
 * is what makes a badge possible for a club whose own fixture payload carried no logo —
 * the archived leagues, for instance. Only `items` is read; everything else in the
 * document is presentation.
 */
@Serializable
data class EspnSearchResponse(
    val items: List<EspnSearchItem> = emptyList(),
)

@Serializable
data class EspnSearchItem(
    val id: String? = null,
    val sport: String? = null,
    val displayName: String? = null,
    val name: String? = null,
    val location: String? = null,
    val defaultLeagueSlug: String? = null,
    val logos: List<EspnSearchLogo> = emptyList(),
)

@Serializable
data class EspnSearchLogo(
    val href: String? = null,
    val rel: List<String> = emptyList(),
)
