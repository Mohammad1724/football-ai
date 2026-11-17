package com.footballai.app.domain.catalog

import com.footballai.engine.model.TeamKind

/**
 * Where a club's badge lives when the source did not hand us the link.
 *
 * ESPN publishes every club crest at one address per numeric club id
 * (`/i/teamlogos/soccer/500/<id>.png`), and the scoreboard payload's own `logo` field is
 * exactly that address — so a stored team whose crest link went missing can still be
 * drawn without asking the network for anything new.
 *
 * Two rules keep this honest:
 *  - only a club gets it. A national team's badge is a flag, never this path, and the
 *    countries folder has its own (three-letter) file names.
 *  - only a numeric id gets it. A provider key that is a slug or a name is not a club id,
 *    so nothing is guessed for it.
 *
 * A club whose image is genuinely absent at that address simply keeps its initials: the
 * badge draws the monogram underneath the image, so a failed load is never a blank circle.
 */
object CrestUrls {

    /** ESPN's keyless soccer crest folder, the same one its own payload points at. */
    const val ESPN_SOCCER_CRESTS = "https://a.espncdn.com/i/teamlogos/soccer/500"

    private const val ESPN_PROVIDER_ID = "espn-open"

    /**
     * The published crest address for this team, or null when the app has no honest
     * address to offer (another provider, a national team, a non-numeric key).
     */
    fun forClub(providerId: String, providerKey: String, kind: TeamKind): String? {
        if (providerId != ESPN_PROVIDER_ID) return null
        if (kind != TeamKind.CLUB) return null
        val key = providerKey.trim()
        if (key.isEmpty() || key.any { !it.isDigit() }) return null
        return "$ESPN_SOCCER_CRESTS/$key.png"
    }
}
