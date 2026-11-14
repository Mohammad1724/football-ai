package com.footballai.app.data.repository

import com.footballai.app.core.TimeConstants
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.local.entity.CompetitionEntity
import com.footballai.app.data.local.entity.MatchEntity
import com.footballai.app.data.local.entity.TeamEntity
import com.footballai.app.data.local.mapper.toDetail
import com.footballai.app.data.local.mapper.toDomain
import com.footballai.app.data.local.mapper.toRef
import com.footballai.app.data.local.mapper.toSummary
import com.footballai.app.domain.model.Competition
import com.footballai.app.domain.model.MatchDetail
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.ResultOutcome
import com.footballai.app.domain.model.TeamFormSummary
import com.footballai.app.domain.model.TeamProfile
import com.footballai.app.domain.model.TeamRating
import com.footballai.app.domain.model.TeamResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Read side of the match data: the UI only ever talks to this class, never to Room. */
class MatchRepository(
    private val db: FootballDatabase,
    private val clock: TimeProvider,
    private val ratingsRepository: RatingsRepository,
) {

    fun observeLive(): Flow<List<MatchSummary>> = db.matchDao().observeLive().map { decorate(it) }

    fun observeAll(): Flow<List<MatchSummary>> = db.matchDao().observeAll().map { decorate(it) }

    fun observeBetween(fromEpochMs: Long, toEpochMs: Long): Flow<List<MatchSummary>> =
        db.matchDao().observeBetween(fromEpochMs, toEpochMs).map { decorate(it) }

    fun observeUpcoming(limit: Int = 20): Flow<List<MatchSummary>> {
        val now = clock.nowEpochMs()
        return db.matchDao().observeUpcoming(now, limit).map { decorate(it) }
    }

    fun observeCompetitions(): Flow<List<Competition>> =
        db.competitionDao().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun competitions(): List<Competition> =
        db.competitionDao().allOnce().map { it.toDomain() }

    suspend fun competition(competitionId: String): Competition? =
        db.competitionDao().byId(competitionId)?.toDomain()

    suspend fun matchDetail(matchId: String): MatchDetail? {
        val match = db.matchDao().byId(matchId) ?: return null
        val summary = decorate(listOf(match)).firstOrNull() ?: return null
        val statistics = db.statisticsDao().byMatch(matchId)
        val events = db.eventDao().forMatchNow(matchId)
        val lineups = db.lineupDao().forMatchNow(matchId)
        return match.toDetail(summary, statistics, events, lineups)
    }

    fun observeMatchDetail(matchId: String): Flow<MatchDetail?> =
        db.matchDao().byIdFlow(matchId).map { entity ->
            if (entity == null) {
                null
            } else {
                val summary = decorate(listOf(entity)).firstOrNull()
                if (summary == null) {
                    null
                } else {
                    entity.toDetail(
                        summary = summary,
                        statistics = db.statisticsDao().byMatch(entity.id),
                        events = db.eventDao().forMatchNow(entity.id),
                        lineups = db.lineupDao().forMatchNow(entity.id),
                    )
                }
            }
        }

    suspend fun recentMatchesForTeam(teamId: String, limit: Int = 10): List<MatchSummary> {
        val matches = db.matchDao().recentForTeam(teamId, limit)
        return decorate(matches)
    }

    suspend fun fixturesForCompetition(competitionId: String, limit: Int = 30): List<MatchSummary> =
        decorate(db.matchDao().recentForCompetition(competitionId, limit))

    suspend fun headToHead(matchId: String, homeTeamId: String, awayTeamId: String, limit: Int = 6): List<MatchSummary> =
        decorate(db.matchDao().headToHead(matchId, homeTeamId, awayTeamId, limit))

    suspend fun teamProfile(teamId: String): TeamProfile? {
        val team = db.teamDao().byId(teamId) ?: return null
        val recent = db.matchDao().recentForTeam(teamId, 10)
        val summaries = decorate(recent)
        val now = clock.nowEpochMs()
        val competitionId = recent.firstOrNull()?.competitionId.orEmpty()
        val source = team.providerId
        val competition = if (competitionId.isNotEmpty()) db.competitionDao().byId(competitionId) else null

        val results = summaries.filter { it.status.isFinished }.map { summary ->
            val wasHome = summary.home.id == teamId
            val goalsFor = (if (wasHome) summary.homeGoals else summary.awayGoals) ?: 0
            val goalsAgainst = (if (wasHome) summary.awayGoals else summary.homeGoals) ?: 0
            TeamResult(
                matchId = summary.id,
                opponent = if (wasHome) summary.away else summary.home,
                kickoffEpochMs = summary.kickoffEpochMs,
                goalsFor = goalsFor,
                goalsAgainst = goalsAgainst,
                wasHome = wasHome,
                outcome = when {
                    goalsFor > goalsAgainst -> ResultOutcome.WIN
                    goalsFor == goalsAgainst -> ResultOutcome.DRAW
                    else -> ResultOutcome.LOSS
                },
                competitionId = summary.competitionId,
            )
        }

        val form = TeamFormSummary(
            wins = results.count { it.outcome == ResultOutcome.WIN },
            draws = results.count { it.outcome == ResultOutcome.DRAW },
            losses = results.count { it.outcome == ResultOutcome.LOSS },
            goalsFor = results.sumOf { it.goalsFor },
            goalsAgainst = results.sumOf { it.goalsAgainst },
            sequence = results.sortedByDescending { it.kickoffEpochMs }.map { it.outcome },
        )

        val rating = if (competitionId.isNotEmpty()) {
            val strengths = ratingsRepository.strengths(source, competitionId, teamId, teamId, now)
            TeamRating(
                attack = strengths.first.attack,
                defence = strengths.first.defence,
                elo = strengths.first.elo,
                matchesUsed = strengths.first.matchesUsed,
            )
        } else {
            TeamRating(1.0, 1.0, 1500.0, 0)
        }

        return TeamProfile(
            team = team.toRef(),
            competitionId = competitionId,
            competitionName = competition?.name.orEmpty(),
            rating = rating,
            form = form,
            recentMatches = results.sortedByDescending { it.kickoffEpochMs },
            ratingAsOfEpochMs = now,
        )
    }

    suspend fun matchEntity(matchId: String): MatchEntity? = db.matchDao().byId(matchId)

    suspend fun teamEntity(teamId: String): TeamEntity? = db.teamDao().byId(teamId)

    suspend fun competitionEntity(competitionId: String): CompetitionEntity? =
        db.competitionDao().byId(competitionId)

    suspend fun decorate(matches: List<MatchEntity>): List<MatchSummary> {
        if (matches.isEmpty()) return emptyList()
        val competitionIds = matches.map { it.competitionId }.distinct()
        val teamIds = matches.flatMap { listOf(it.homeTeamId, it.awayTeamId) }.distinct()
        val competitions = db.competitionDao().byIds(competitionIds).associateBy { it.id }
        val teams = db.teamDao().byIds(teamIds).associateBy { it.id }
        // One extra read for the whole list: the stored pre-match forecast per match, so
        // a card can show 1X2 probabilities without generating anything.
        val predictions = db.predictionDao()
            .preMatchForMatches(matches.map { it.id }, PredictionRepository.MODEL_VERSION)
            .groupBy { it.matchId }
            .mapValues { (_, rows) -> rows.minByOrNull { it.createdAtEpochMs }!! }

        return matches.mapNotNull { match ->
            val competition = competitions[match.competitionId]
            val home = teams[match.homeTeamId]
            val away = teams[match.awayTeamId]
            if (home == null || away == null) {
                // Teams are written together with matches; skip instead of inventing names.
                null
            } else {
                match.toSummary(
                    competitionName = competition?.name ?: "",
                    competitionEmblemUrl = competition?.emblemUrl,
                    home = home.toRef(),
                    away = away.toRef(),
                    competitionClass = competition?.competitionClass ?: "CLUB_LEAGUE",
                    gender = competition?.gender ?: "MEN",
                ).copy(
                    prediction = predictions[match.id]?.let { entity ->
                        com.footballai.app.domain.model.MatchProbability(
                            homeWin = entity.homeWin,
                            draw = entity.draw,
                            awayWin = entity.awayWin,
                            qualityLevel = runCatching {
                                com.footballai.app.domain.model.QualityLevel.valueOf(entity.qualityLevel)
                            }.getOrDefault(com.footballai.app.domain.model.QualityLevel.INSUFFICIENT),
                            confidenceLevel = entity.confidenceLevel,
                            modelVersion = entity.modelVersion,
                        )
                    },
                )
            }
        }
    }

    fun dayWindow(nowEpochMs: Long): Pair<Long, Long> {
        val zone = java.time.ZoneId.systemDefault()
        val date = java.time.Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        return date.atStartOfDay(zone).toInstant().toEpochMilli() to
            (date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
    }

    suspend fun countsTriple(): Triple<Int, Int, Int> = Triple(
        db.matchDao().count(),
        db.historicalDao().count(),
        db.predictionDao().count(),
    )

    suspend fun pruneOldData(nowEpochMs: Long): Int =
        db.matchDao().pruneOldMatches(nowEpochMs - 400L * TimeConstants.DAY_MS)
}
