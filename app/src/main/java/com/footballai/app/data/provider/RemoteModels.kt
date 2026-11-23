package com.footballai.app.data.provider

import com.footballai.app.domain.model.LineupPlayer
import com.footballai.app.domain.model.MatchEventType
import com.footballai.app.domain.model.MatchOdds
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.ProviderInfo
import com.footballai.app.domain.model.StatisticType
import com.footballai.app.core.Outcome

/**
 * Provider-neutral payloads. Providers translate their own JSON into these types so
 * that the repository, prediction and UI layers never depend on a vendor format.
 */

data class RemoteCompetition(
    val providerKey: String,
    val name: String,
    val country: String?,
    val emblemUrl: String?,
    val currentSeason: Int?,
)

data class RemoteTeam(
    val providerKey: String,
    val name: String,
    val shortName: String? = null,
    val crestUrl: String? = null,
    /** ISO-3166 alpha-2 only when the provider publishes it. */
    val countryCode: String? = null,
)

data class RemoteMatch(
    val providerKey: String,
    val competitionKey: String,
    val competitionName: String,
    val season: Int?,
    val matchday: Int?,
    val kickoffEpochMs: Long,
    val status: MatchStatus,
    val minute: Int?,
    val home: RemoteTeam,
    val away: RemoteTeam,
    val homeGoals: Int?,
    val awayGoals: Int?,
    val homeHalfTimeGoals: Int?,
    val awayHalfTimeGoals: Int?,
    val venue: String?,
    val odds: MatchOdds?,
    /** Only REGULATION scores may train or settle the 90-minute markets. */
    val scoreScope: String = "REGULATION",
    /** True only when the provider states the fixture is played at a neutral venue. */
    val isNeutralVenue: Boolean = false,
)

data class RemoteTeamStatistics(
    val teamKey: String,
    val values: Map<StatisticType, Double>,
)

data class RemoteEvent(
    val providerKey: String?,
    val minute: Int,
    val type: MatchEventType,
    val detail: String?,
    val teamKey: String?,
    val playerName: String?,
    val assistName: String?,
)

data class RemoteLineup(
    val teamKey: String,
    val formation: String?,
    val players: List<LineupPlayer>,
)

data class RemoteStandingRow(
    val rank: Int,
    val teamKey: String,
    val teamName: String,
    val crestUrl: String?,
    val points: Int,
    val played: Int,
    val goalDifference: Int,
    val form: String?,
    /** Wins, draws, losses and goals. Null means the source did not publish the column. */
    val wins: Int? = null,
    val draws: Int? = null,
    val losses: Int? = null,
    val goalsFor: Int? = null,
    val goalsAgainst: Int? = null,
)

data class RemoteHistoryMatch(
    val competitionKey: String,
    val competitionName: String,
    val season: Int?,
    val homeTeamKey: String,
    val homeTeamName: String,
    val awayTeamKey: String,
    val awayTeamName: String,
    val homeGoals: Int,
    val awayGoals: Int,
    val kickoffEpochMs: Long,
    val homeOdds: Double?,
    val drawOdds: Double?,
    val awayOdds: Double?,
)

/**
 * One row of the football-ai-data xG mirror: post-match expected goals for a
 * played match, keyed by calendar date and football-data.co.uk team names so
 * it can be joined onto [RemoteHistoryMatch]-derived rows.
 */
data class RemoteMatchXg(
    val date: java.time.LocalDate,
    val homeTeamName: String,
    val awayTeamName: String,
    val homeXg: Double,
    val awayXg: Double,
)

/**
 * Pre-match availability for one fixture: the share of each team's season
 * minutes belonging to players listed unavailable for it (injured/suspended).
 * Shares are computed by the publisher from data available before kickoff;
 * a fixture absent from the feed has no row, never a zero.
 */
data class RemoteMatchAvailability(
    val date: java.time.LocalDate,
    val homeTeamName: String,
    val awayTeamName: String,
    val homeMissingShare: Double,
    val awayMissingShare: Double,
)

/** Contract every data provider implements; new sources only need this interface. */
interface FootballDataProvider {
    val info: ProviderInfo

    /**
     * Competition keys to sync when the user follows none of *this* provider's own
     * competitions (for example after switching sources). Empty means "use whatever the
     * user follows".
     */
    val defaultCompetitionKeys: List<String>
        get() = emptyList()

    suspend fun competitions(): Outcome<List<RemoteCompetition>>

    suspend fun matches(
        competitionKey: String,
        season: Int?,
        fromEpochMs: Long? = null,
        toEpochMs: Long? = null,
    ): Outcome<List<RemoteMatch>>

    suspend fun liveMatches(): Outcome<List<RemoteMatch>>

    /** Refreshes a single fixture by its provider key; used by live tracking. */
    suspend fun match(matchKey: String): Outcome<RemoteMatch?>

    suspend fun matchStatistics(matchKey: String): Outcome<List<RemoteTeamStatistics>>

    suspend fun matchEvents(matchKey: String): Outcome<List<RemoteEvent>>

    suspend fun lineups(matchKey: String): Outcome<List<RemoteLineup>>

    suspend fun standings(competitionKey: String, season: Int): Outcome<List<RemoteStandingRow>>
}

/** Providers that can supply bulk historical results for model fitting. */
interface HistoricalDataProvider {
    val info: ProviderInfo

    /** Keys usable with [history], e.g. "E0" for the English Premier League. */
    fun availableCompetitions(): List<CsvCompetition>

    /**
     * Downloads and parses one league-season.
     * @param seasonStartYear the year the season started (2023 for 2023/24).
     */
    suspend fun history(competitionKey: String, seasonStartYear: Int): Outcome<List<RemoteHistoryMatch>>
}

data class CsvCompetition(
    val key: String,
    val name: String,
    val country: String,
)
