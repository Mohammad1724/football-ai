package com.footballai.engine.model

/**
 * What kind of football a fixture belongs to.
 *
 * The type is an explicit fact taken from the competition the fixture belongs to;
 * it is never inferred from a team name. The engine uses it to switch off home
 * advantage on neutral grounds and to expose the correct market meaning (a 1X2
 * probability always refers to the 90-minute regulation-time result).
 */
enum class CompetitionType {
    CLUB_LEAGUE,
    CLUB_KNOCKOUT,
    INTERNATIONAL_TOURNAMENT,
    INTERNATIONAL_QUALIFIER,
    INTERNATIONAL_FRIENDLY,
    WOMEN_CLUB_LEAGUE,
    WOMEN_INTERNATIONAL,
}

enum class Gender { MEN, WOMEN }

/** Distinguishes a club from a national team so their ratings can never be mixed. */
enum class TeamKind { CLUB, NATIONAL }

/**
 * Competition-level modelling context.
 *
 * Every field is either structural (a neutral venue really does remove home
 * advantage) or a documented prior that the back-tester can override. Nothing here
 * is fitted to make the numbers look better. The default instance reproduces the
 * previous club-league behaviour exactly, so existing predictions and stored
 * snapshots keep their meaning.
 */
data class CompetitionProfile(
    val type: CompetitionType = CompetitionType.CLUB_LEAGUE,
    val gender: Gender = Gender.MEN,
    /** True when the fixture is played at a ground that belongs to neither team. */
    val neutralVenue: Boolean = false,
    /** Second leg of a two-leg tie; only affects wording, never the 1X2 target. */
    val secondLeg: Boolean = false,
) {
    val isInternational: Boolean
        get() = when (type) {
            CompetitionType.INTERNATIONAL_TOURNAMENT,
            CompetitionType.INTERNATIONAL_QUALIFIER,
            CompetitionType.INTERNATIONAL_FRIENDLY,
            CompetitionType.WOMEN_INTERNATIONAL,
            -> true
            CompetitionType.CLUB_LEAGUE,
            CompetitionType.CLUB_KNOCKOUT,
            CompetitionType.WOMEN_CLUB_LEAGUE,
            -> false
        }

    val teamKind: TeamKind get() = if (isInternational) TeamKind.NATIONAL else TeamKind.CLUB

    val isKnockout: Boolean
        get() = type == CompetitionType.CLUB_KNOCKOUT ||
            type == CompetitionType.INTERNATIONAL_TOURNAMENT ||
            secondLeg

    /**
     * How much of the competition's own home/away scoring split is applied.
     *
     * A neutral venue removes it completely. International friendlies are played
     * with rotated squads and frequently at neutral or semi-neutral venues, so a
     * reduced prior of 0.6 is used instead of the full league split. The value is a
     * documented prior, not a fitted parameter; the back-tester can override it.
     */
    val homeAdvantageScale: Double
        get() = when {
            neutralVenue -> 0.0
            type == CompetitionType.INTERNATIONAL_FRIENDLY -> FRIENDLY_HOME_ADVANTAGE_SCALE
            else -> 1.0
        }

    /** Elo evidence is thinner in friendlies, so its tilt is damped there. */
    val eloBlendScale: Double
        get() = if (type == CompetitionType.INTERNATIONAL_FRIENDLY) FRIENDLY_ELO_BLEND_SCALE else 1.0

    /**
     * Rebuilds a competition baseline with the home advantage partially removed.
     * The total scoring level is preserved, only the home/away asymmetry changes.
     */
    fun adjustBaseline(baseline: LeagueBaseline): LeagueBaseline {
        val scale = homeAdvantageScale
        if (scale >= 1.0) return baseline
        val mean = baseline.averageTotalGoals / 2.0
        val home = mean + (baseline.averageHomeGoals - mean) * scale
        val away = mean + (baseline.averageAwayGoals - mean) * scale
        return LeagueBaseline(
            averageHomeGoals = home.coerceIn(0.2, 5.0),
            averageAwayGoals = away.coerceIn(0.2, 5.0),
            sampleSize = baseline.sampleSize,
        )
    }

    companion object {
        const val FRIENDLY_HOME_ADVANTAGE_SCALE = 0.6
        const val FRIENDLY_ELO_BLEND_SCALE = 0.8

        /** The previous behaviour: an ordinary club league match. */
        val DEFAULT = CompetitionProfile()

        fun of(type: CompetitionType, neutralVenue: Boolean = false, secondLeg: Boolean = false): CompetitionProfile =
            CompetitionProfile(
                type = type,
                gender = if (type == CompetitionType.WOMEN_CLUB_LEAGUE || type == CompetitionType.WOMEN_INTERNATIONAL) {
                    Gender.WOMEN
                } else {
                    Gender.MEN
                },
                neutralVenue = neutralVenue,
                secondLeg = secondLeg,
            )
    }
}
