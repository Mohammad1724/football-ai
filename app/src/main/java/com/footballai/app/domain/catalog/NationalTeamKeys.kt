package com.footballai.app.domain.catalog

import java.text.Normalizer
import java.util.Locale

/**
 * Stable identity for a national team, built from its published name.
 *
 * National-team fixtures arrive from one source (ESPN publishes them with the name it
 * likes) while historical results arrive from another, with its own spelling. If the two
 * spellings are treated as different teams, every lookup finds nothing and the model
 * silently claims it has no evidence. A canonical key fixes that without inventing a
 * mapping: a name is folded to lower case, stripped of accents and punctuation, and the
 * short list of genuinely different spellings is written down explicitly below.
 *
 * A name that no alias covers keeps its own key. That is deliberate: an unknown team must
 * end up with an empty history and be reported as such, not merged into a similar one.
 */
object NationalTeamKeys {

    /** The one history source this app owns for national teams. */
    const val SOURCE = "international-history"

    /** All international results live in one bucket; a national rating needs every match. */
    const val COMPETITION_ID = "international:all"

    fun teamId(name: String): String = "national:" + key(name)

    /** Canonical, comparable form of a team name. Empty when nothing usable is left. */
    fun key(name: String): String {
        val folded = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace("'", "")
            .replace(".", " ")
            .replace("-", " ")
        val cleaned = folded.split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return aliases[cleaned] ?: cleaned
    }

    /**
     * Spellings that differ between the fixture feeds and the results archive.
     *
     * Only differences that would otherwise produce two keys for one team are listed.
     * Anything else stays untouched so a mismatch is visible instead of guessed.
     */
    private val aliases: Map<String, String> = mapOf(
        "czechia" to "czech republic",
        "turkiye" to "turkey",
        "ir iran" to "iran",
        "korea republic" to "south korea",
        "republic of korea" to "south korea",
        "korea dpr" to "north korea",
        "dpr korea" to "north korea",
        "usa" to "united states",
        "united states of america" to "united states",
        "cape verde islands" to "cape verde",
        "congo dr" to "dr congo",
        "congo democratic republic" to "dr congo",
        "democratic republic of congo" to "dr congo",
        "bosnia herzegovina" to "bosnia and herzegovina",
        "macedonia" to "north macedonia",
        "cote divoire" to "ivory coast",
        "st kitts and nevis" to "saint kitts and nevis",
        "saint kitts nevis" to "saint kitts and nevis",
        "swaziland" to "eswatini",
        "burma" to "myanmar",
        "east timor" to "timor leste",
        "china pr" to "china",
        "hong kong china" to "hong kong",
        "chinese taipei" to "taiwan",
        "st vincent grenadines" to "saint vincent and the grenadines",
        "st vincent and the grenadines" to "saint vincent and the grenadines",
        "uae" to "united arab emirates",
        "cabo verde" to "cape verde",
        "congo republic" to "congo",
        "republic of congo" to "congo",
        "st lucia" to "saint lucia",
        "st vincent and grenadines" to "saint vincent and the grenadines",
    )
}
