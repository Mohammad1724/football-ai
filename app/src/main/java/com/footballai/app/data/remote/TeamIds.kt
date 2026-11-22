package com.footballai.app.data.remote

/**
 * Turns a stored team id into the id the live feed uses for the same club.
 *
 * Ids are `provider:team:<key>`, where the key is derived from the published name. The
 * archive and the live feed name clubs differently, so [TeamAliases] holds the pairs
 * that are backed by evidence. This object is the only place that rewrites an id, so
 * the prediction path, the Elo replay and the form panel cannot drift apart.
 *
 * Nothing is rewritten when the league is unknown or the club is not in the table: an
 * id that is already correct must stay byte-for-byte identical, otherwise stored rows
 * would stop matching themselves.
 */
internal object TeamIds {

    fun canonical(league: String, teamId: String): String {
        if (league.isEmpty() || teamId.isEmpty()) return teamId
        val key = teamId.substringAfterLast(':')
        if (key.isEmpty()) return teamId
        val live = TeamAliases.canonical(league, key) ?: return teamId
        val prefix = teamId.substringBeforeLast(':', missingDelimiterValue = "")
        return if (prefix.isEmpty()) live else "$prefix:$live"
    }

    /** The league key inside a competition id (`provider:league:E0` -> `E0`). */
    fun leagueOf(competitionId: String): String = competitionId.substringAfterLast(':')
}
