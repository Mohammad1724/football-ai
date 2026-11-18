package com.footballai.app.data.remote

import com.footballai.app.domain.catalog.TeamNames

/**
 * Explicit Open Matchday abbreviations, scoped to their competition, used only when
 * searching for artwork. Never alters team or fixture identities. Names on the right
 * are published by ESPN or TheSportsDB; unknown abbreviations are not guessed.
 */
internal object CrestSearchAliases {
    private val names = mapOf(
        ("D1" to "bayer 04 leverkusen") to "Bayer Leverkusen",
        ("WAL1" to "airbus") to "Airbus UK Broughton",
        ("WAL1" to "holywell") to "Holywell Town",
        ("WAL1" to "briton ferry") to "Briton Ferry Llansawel",
        ("WAL1" to "caernarfon") to "Caernarfon Town",
        ("WAL1" to "connahs q") to "Connah's Quay Nomads",
        ("WAL1" to "cardiff metropolitan") to "Cardiff Metropolitan University",
        ("WAL1" to "trefelin") to "Trefelin BGC",
        ("WAL1" to "tns") to "The New Saints",
        ("WAL3" to "llanelli") to "Llanelli Town",
        ("WAL3" to "caerphilly") to "Caerphilly Athletic",
        ("WAL3" to "pontypridd") to "Pontypridd Town",
        ("POL2" to "puszcza") to "Puszcza Niepolomice",
        ("POL2" to "legnica") to "Miedz Legnica",
    )

    fun lookup(providerId: String, competitionKeys: List<String>, name: String): String? {
        if (providerId != FootballDataCoUkProvider.PROVIDER_ID) return null
        val key = TeamNames.normalizeNameKey(name)
        return competitionKeys.mapNotNull { names[it to key] }.distinct().singleOrNull()
    }
}
