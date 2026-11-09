package com.footballai.app.data.local.mapper

import com.footballai.app.data.local.entity.CompetitionEntity
import com.footballai.app.data.local.entity.MatchEntity
import com.footballai.app.data.local.entity.MatchEventEntity
import com.footballai.app.data.local.entity.MatchStatisticsEntity
import com.footballai.app.data.local.entity.TeamEntity
import com.footballai.app.data.provider.RemoteMatch
import com.footballai.app.data.provider.RemoteTeam
import com.footballai.app.domain.model.Competition
import com.footballai.app.domain.model.MatchDetail
import com.footballai.app.domain.model.MatchEvent
import com.footballai.app.domain.model.MatchEventType
import com.footballai.app.domain.model.MatchOdds
import com.footballai.app.domain.model.MatchStatisticPair
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.StatisticType
import com.footballai.app.domain.model.TeamRef

/** Stable identifiers keep the same match identifiable across syncs. */
object Ids {
    fun competition(providerId: String, providerKey: String): String = "$providerId:league:$providerKey"

    fun team(providerId: String, providerKey: String): String = "$providerId:team:$providerKey"

    fun match(providerId: String, providerKey: String): String = "$providerId:match:$providerKey"

    fun historical(source: String, competitionId: String, homeId: String, awayId: String, kickoff: Long): String =
        "hist:$source:$competitionId:$homeId:$awayId:$kickoff"

    fun event(matchId: String, minute: Int, type: String, player: String?): String =
        "$matchId:event:$minute:$type:${player.orEmpty()}"

    fun lineup(matchId: String, teamId: String, playerName: String): String =
        "$matchId:lineup:$teamId:$playerName"
}

fun RemoteTeam.toEntity(providerId: String, now: Long): TeamEntity = TeamEntity(
    id = Ids.team(providerId, providerKey),
    providerId = providerId,
    providerKey = providerKey,
    name = name,
    shortName = shortName,
    crestUrl = crestUrl,
    updatedAt = now,
)

fun RemoteMatch.toEntity(providerId: String, homeTeamId: String, awayTeamId: String, competitionId: String, now: Long): MatchEntity =
    MatchEntity(
        id = Ids.match(providerId, providerKey),
        providerId = providerId,
        providerKey = providerKey,
        competitionId = competitionId,
        season = season,
        matchday = matchday,
        homeTeamId = homeTeamId,
        awayTeamId = awayTeamId,
        kickoffEpochMs = kickoffEpochMs,
        status = status.name,
        minute = minute,
        homeGoals = homeGoals,
        awayGoals = awayGoals,
        homeHalfTimeGoals = homeHalfTimeGoals,
        awayHalfTimeGoals = awayHalfTimeGoals,
        venue = venue,
        oddsHome = odds?.home,
        oddsDraw = odds?.draw,
        oddsAway = odds?.away,
        hasStatistics = false,
        hasEvents = false,
        hasLineups = false,
        statisticsSyncedAt = null,
        lastSyncedAt = now,
    )

fun CompetitionEntity.toDomain(): Competition = Competition(
    id = id,
    name = name,
    country = country,
    emblemUrl = emblemUrl,
    season = season,
    providerId = providerId,
    hasFullCoverage = hasFullCoverage,
    isFollowed = isFollowed,
    providerKey = providerKey,
)

fun TeamEntity.toRef(): TeamRef = TeamRef(
    id = id,
    name = name,
    shortName = shortName,
    crestUrl = crestUrl,
)

fun MatchEntity.toSummary(
    competitionName: String,
    competitionEmblemUrl: String?,
    home: TeamRef,
    away: TeamRef,
): MatchSummary = MatchSummary(
    id = id,
    competitionId = competitionId,
    competitionName = competitionName,
    competitionEmblemUrl = competitionEmblemUrl,
    home = home,
    away = away,
    kickoffEpochMs = kickoffEpochMs,
    status = parseStatus(status),
    minute = minute,
    homeGoals = homeGoals,
    awayGoals = awayGoals,
    lastSyncedAt = lastSyncedAt,
    hasStatistics = hasStatistics,
    hasLineups = hasLineups,
    hasEvents = hasEvents,
    hasOdds = oddsHome != null && oddsDraw != null && oddsAway != null,
)

fun MatchEntity.toDetail(
    summary: MatchSummary,
    statistics: MatchStatisticsEntity?,
    events: List<MatchEventEntity>,
    lineups: List<com.footballai.app.data.local.entity.LineupPlayerEntity>,
): MatchDetail = MatchDetail(
    summary = summary,
    venue = venue,
    matchday = matchday,
    statistics = statistics?.toPairs().orEmpty(),
    events = events.map { it.toDomain() },
    lineups = lineups.groupBy { it.teamId }.map { (teamId, players) ->
        com.footballai.app.domain.model.TeamLineup(
            teamId = teamId,
            formation = players.firstOrNull()?.formation,
            players = players.map {
                com.footballai.app.domain.model.LineupPlayer(
                    name = it.name,
                    number = it.number,
                    position = it.position,
                    isStarter = it.isStarter,
                )
            },
        )
    },
    odds = if (oddsHome != null && oddsDraw != null && oddsAway != null) {
        MatchOdds(oddsHome, oddsDraw, oddsAway)
    } else {
        null
    },
    homeHalfTimeGoals = homeHalfTimeGoals,
    awayHalfTimeGoals = awayHalfTimeGoals,
    statisticsSyncedAt = statisticsSyncedAt,
)

fun MatchStatisticsEntity.toPairs(): List<MatchStatisticPair> = listOf(
    MatchStatisticPair(StatisticType.POSSESSION, homePossession, awayPossession),
    MatchStatisticPair(StatisticType.SHOTS, homeShots?.toDouble(), awayShots?.toDouble()),
    MatchStatisticPair(StatisticType.SHOTS_ON_TARGET, homeShotsOnTarget?.toDouble(), awayShotsOnTarget?.toDouble()),
    MatchStatisticPair(StatisticType.CORNERS, homeCorners?.toDouble(), awayCorners?.toDouble()),
    MatchStatisticPair(StatisticType.EXPECTED_GOALS, homeExpectedGoals, awayExpectedGoals),
    MatchStatisticPair(StatisticType.YELLOW_CARDS, homeYellowCards?.toDouble(), awayYellowCards?.toDouble()),
    MatchStatisticPair(StatisticType.RED_CARDS, homeRedCards?.toDouble(), awayRedCards?.toDouble()),
).filter { it.home != null || it.away != null }

fun MatchEventEntity.toDomain(): MatchEvent = MatchEvent(
    id = id,
    minute = minute,
    type = runCatching { MatchEventType.valueOf(type) }.getOrDefault(MatchEventType.OTHER),
    detail = detail,
    teamId = teamId,
    playerName = playerName,
    assistName = assistName,
)

fun parseStatus(value: String): MatchStatus =
    runCatching { MatchStatus.valueOf(value) }.getOrDefault(MatchStatus.UNKNOWN)
