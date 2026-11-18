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
import com.footballai.app.data.remote.TeamAliases
import com.footballai.app.domain.model.Competition
import com.footballai.app.domain.model.MatchDetail
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.ResultOutcome
import com.footballai.app.domain.model.TeamFormSummary
import com.footballai.app.domain.model.TeamProfile
import com.footballai.app.domain.model.TeamRating
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.domain.model.TeamResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Read side of the match data: the UI only ever talks to this class, never to Room. */
class MatchRepository(
    private val db: FootballDatabase,
    private val clock: TimeProvider,
    private val ratingsRepository: RatingsRepository,
    private val quotaGuard: com.footballai.app.data.remote.QuotaGuard? = null,
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

    /**
     * Past meetings between the two sides.
     *
     * The fixtures table only ever holds the days around today, so a head-to-head built
     * from it alone was empty for most matches. The archive holds years of the same
     * fixture; both are read and merged, and a match present in both appears once.
     */
    suspend fun headToHead(matchId: String, home: TeamRef, away: TeamRef, limit: Int = 6): List<MatchSummary> {
        val now = clock.nowEpochMs()
        val stored = decorate(db.matchDao().headToHead(matchId, home.id, away.id, limit))
        val archivedRows = db.historicalDao().between(aliasIds(home), aliasIds(away), now, limit)
        val archivedRefs = teamRefs(archivedRows.flatMap { row -> listOf(row.homeTeamId, row.awayTeamId) })
        val archived = archivedRows
            .map { row -> row.toSummaryRow() }
            .map { summary ->
                summary.copy(
                    home = archivedRefs[summary.home.id] ?: summary.home,
                    away = archivedRefs[summary.away.id] ?: summary.away,
                )
            }
        return (stored + archived)
            .distinctBy { it.kickoffEpochMs }
            .sortedByDescending { it.kickoffEpochMs }
            .take(limit)
    }

    /**
     * Every id a team answers to: the one on the fixture, plus the one the archive uses
     * for national teams. A club is only ever matched by its own id.
     */
    /**
     * Stored identities for a set of ids, crest and kind included.
     *
     * Archive rows carry only an id and a name, so a badge built from them could never be
     * a crest — and a national team's entry lost its kind, which is what tells the badge to
     * draw a flag rather than initials. Where the team is stored, its own row is used.
     */
    private suspend fun teamRefs(ids: List<String>): Map<String, TeamRef> {
        val distinct = ids.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return db.teamDao().byIds(distinct).associate { team -> team.id to team.toRef() }
    }

    private fun aliasIds(team: TeamRef): List<String> = buildList {
        add(team.id)
        if (team.isNational) {
            add(com.footballai.app.domain.catalog.NationalTeamKeys.teamId(team.name))
        } else {
            // The archive stored this club under a different published name. Adding those
            // ids means the form panel and the head-to-head list see the same results the
            // rating book already sees; without them a Bundesliga club had no past at all.
            val prefix = team.id.substringBeforeLast(':', missingDelimiterValue = "")
            val key = team.id.substringAfterLast(':')
            if (prefix.isNotEmpty() && key.isNotEmpty()) {
                for (archived in TeamAliases.archivedKeys(key)) add("$prefix:$archived")
            }
        }
    }.distinct()

    private fun com.footballai.app.data.local.entity.HistoricalMatchEntity.toSummaryRow(): MatchSummary =
        MatchSummary(
            id = id,
            competitionId = competitionId,
            competitionName = competitionName.ifBlank { competitionId },
            competitionEmblemUrl = null,
            home = TeamRef(id = homeTeamId, name = homeTeamName),
            away = TeamRef(id = awayTeamId, name = awayTeamName),
            kickoffEpochMs = kickoffEpochMs,
            status = MatchStatus.FINISHED,
            minute = null,
            homeGoals = homeGoals,
            awayGoals = awayGoals,
            lastSyncedAt = importedAt,
            hasStatistics = false,
            hasLineups = false,
            hasEvents = false,
            hasOdds = homeOdds != null,
        )

    /**
     * A team's own results, from its point of view.
     *
     * Only finished matches: a fixture that has not been played is not evidence, and a
     * form panel that counted it would show a result that does not exist yet.
     */
    private fun finishedResults(summaries: List<MatchSummary>, teamId: String): List<TeamResult> =
        summaries.filter { it.status.isFinished }.map { summary ->
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
                halfTimeGoalsFor = (if (wasHome) summary.homeHalfTimeGoals else summary.awayHalfTimeGoals),
                halfTimeGoalsAgainst = (if (wasHome) summary.awayHalfTimeGoals else summary.homeHalfTimeGoals),
                outcome = when {
                    goalsFor > goalsAgainst -> ResultOutcome.WIN
                    goalsFor == goalsAgainst -> ResultOutcome.DRAW
                    else -> ResultOutcome.LOSS
                },
                competitionId = summary.competitionId,
            )
        }

    /**
     * The last [limit] finished matches for a team, newest first.
     *
     * The fixture query returns whatever is nearest, played or not, so a wider window is
     * read and then filtered: asking for ten rows and getting seven played ones would
     * silently shrink the panel.
     */
    suspend fun recentResults(team: TeamRef, limit: Int = 10): List<TeamResult> {
        val rows = db.matchDao().recentForTeam(team.id, limit * 3)
        val fromFixtures = finishedResults(decorate(rows), team.id)
        // A club league ships its results as an archive, not as fixtures: the fixtures
        // table only holds the days around today, which is why a Bundesliga side used to
        // have no form at all.
        val ids = aliasIds(team)
        val archiveRows = db.historicalDao().forTeamIds(ids, clock.nowEpochMs(), limit * 2)
        val archiveRefs = teamRefs(archiveRows.flatMap { row -> listOf(row.homeTeamId, row.awayTeamId) })
        val fromArchive = archiveRows
            .map { row ->
                val wasHome = row.homeTeamId in ids
                val goalsFor = if (wasHome) row.homeGoals else row.awayGoals
                val goalsAgainst = if (wasHome) row.awayGoals else row.homeGoals
                TeamResult(
                    matchId = row.id,
                    opponent = if (wasHome) {
                        archiveRefs[row.awayTeamId] ?: TeamRef(id = row.awayTeamId, name = row.awayTeamName)
                    } else {
                        archiveRefs[row.homeTeamId] ?: TeamRef(id = row.homeTeamId, name = row.homeTeamName)
                    },
                    kickoffEpochMs = row.kickoffEpochMs,
                    goalsFor = goalsFor,
                    goalsAgainst = goalsAgainst,
                    wasHome = wasHome,
                    outcome = when {
                        goalsFor > goalsAgainst -> ResultOutcome.WIN
                        goalsFor == goalsAgainst -> ResultOutcome.DRAW
                        else -> ResultOutcome.LOSS
                    },
                    competitionId = row.competitionId,
                )
            }
        return (fromFixtures + fromArchive)
            // One kickoff cannot be two matches for the same team; the archive and the
            // fixture table disagree about the opponent id, not about when the game was.
            .distinctBy { it.kickoffEpochMs }
            .sortedByDescending { it.kickoffEpochMs }
            .take(limit)
    }

    /**
     * Where these teams stand in a competition table, with the size of that table.
     *
     * Empty when the source has published no table for the season: the screen then says
     * nothing rather than showing a position it cannot support.
     */
    suspend fun standingRanks(competitionId: String, teamIds: List<String>): Map<String, Pair<Int, Int>> {
        if (competitionId.isBlank() || teamIds.isEmpty()) return emptyMap()
        // The season on the competition row is what the fixture sync last wrote, and the
        // table may have been stored under a different one (a league that rolls over
        // mid-week, a season number the source changed). Asking only for that one number
        // was why a stored table could exist and still print a dash, so the newest table
        // this competition has is used when the exact season has no rows.
        val stored = db.competitionDao().byId(competitionId)?.season
            ?.let { season -> db.standingDao().byCompetition(competitionId, season) }
            .orEmpty()
        val rows = stored.ifEmpty {
            val newest = db.standingDao().latestSeason(competitionId) ?: return emptyMap()
            db.standingDao().byCompetition(competitionId, newest)
        }
        if (rows.isEmpty()) return emptyMap()
        val byTeam = rows.associateBy { it.teamId }
        return teamIds.distinct().mapNotNull { id ->
            byTeam[id]?.let { id to (it.rank to rows.size) }
        }.toMap()
    }

    suspend fun teamProfile(teamId: String): TeamProfile? {
        val team = db.teamDao().byId(teamId) ?: return null
        val recent = db.matchDao().recentForTeam(teamId, 10)
        val now = clock.nowEpochMs()
        val competitionId = recent.firstOrNull()?.competitionId.orEmpty()
        val source = team.providerId
        val competition = if (competitionId.isNotEmpty()) db.competitionDao().byId(competitionId) else null

        // The team page reads the same merged history as the match page, so a club whose
        // results live in the archive is not blank on one screen and full on the other.
        val results = recentResults(team.toRef(), 10)

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

    /**
     * What the badge is looking at, in one line, for the diagnostics row.
     *
     * "The picture arrives but no badge shows" has exactly two causes — the address is
     * missing on the stored team, or the screen does not draw it — and they are told apart
     * by counting addresses instead of by guessing: zero with a sample row is a data fault,
     * a healthy count is a display fault.
     */
    suspend fun crestDiagnostics(): String {
        val total = db.teamDao().count()
        val withCrest = db.teamDao().countWithCrest()
        val sample = db.teamDao().mostRecentlyUpdated()
        val sampleText = if (sample == null) {
            "sample: none"
        } else {
            val url = sample.crestUrl?.takeIf { it.isNotBlank() }
                ?: com.footballai.app.domain.catalog.CrestUrls.forClub(
                    providerId = sample.providerId,
                    providerKey = sample.providerKey,
                    kind = runCatching { com.footballai.engine.model.TeamKind.valueOf(sample.kind) }
                        .getOrDefault(com.footballai.engine.model.TeamKind.CLUB),
                )
            "sample: ${sample.providerId}/${sample.kind}/${sample.providerKey} -> ${url ?: "no address"}"
        }
        val bySource = db.teamDao().crestCountsByProvider()
            .joinToString(" · ") { count -> "${count.providerId} ${count.withCrest}/${count.total}" }
        // A count says how many are missing; a name says which club the report is about. The
        // window matches what the fixture screens show, so the list is what the eye sees.
        val now = clock.nowEpochMs()
        val from = now - 2L * TimeConstants.DAY_MS
        val to = now + 12L * TimeConstants.DAY_MS
        val lanes = db.teamDao().crestCompetitionsForDay(from, to).mapNotNull { competition ->
            val teams = db.teamDao().crestlessTeamsForCompetitionDay(competition.id, from, to)
            if (teams.isEmpty()) null else competition.providerKey to teams
        }.sortedByDescending { it.second.size }
        val missing = lanes.take(6).map { (key, teams) ->
            "$key ${teams.size}: " + teams.take(3).joinToString(", ") { it.name }
        }
        // A count says how many are missing; this says why one of them still is. A club that
        // was asked and answered nothing cools down for a week; "never tried" means the sweep
        // has not reached it, which is a different problem and a different fix.
        val wanted = lanes.firstOrNull()?.second?.take(3).orEmpty()
        // `map` is inline and may read the database; `joinToString`'s transform may not.
        val why = wanted.map { team ->
            val gender = db.teamDao().fixtureGenders(team.id).singleOrNull() ?: "?"
            val last = db.syncMetaDao()
                .entity(com.footballai.app.data.local.CrestAttempts.key(team.id))?.updatedAt
            val age = last?.let { written -> ((now - written) / TimeConstants.DAY_MS).coerceAtLeast(0) }
            val tried = if (age == null) "never asked" else "asked ${age}d ago"
            "${team.name} (" + listOf(team.kind, gender, tried).joinToString(", ") + ")"
        }.joinToString(" · ")
        // How much of the queue is left, so a report that says "never asked" is not a dead
        // end: the sweep only runs while the app is open, and this is its remaining work.
        val prefix = com.footballai.app.data.local.CrestAttempts.prefix()
        val waiting = db.teamDao().waitingForCrestAttempt(prefix)
        val asked = db.teamDao().askedWithoutCrest(prefix)
        // The queue number says nothing about why it is not moving: a source whose daily
        // budget is spent refuses every lookup until midnight, and that has to be visible.
        val quota = quotaGuard?.let { guard ->
            val state = guard.state(
                "sportsdb-crest", com.footballai.app.data.remote.SportsDbCrests.POLICY,
            )
            " · quota sportsdb-crest ${state.usedToday}/${state.dailyLimit}"
        }.orEmpty()
        val lastError = db.syncMetaDao()
            .entity(SyncRepository.CREST_LAST_ERROR_MARKER)?.let { row ->
                val age = ((now - row.updatedAt) / TimeConstants.HOUR_MS).coerceAtLeast(0)
                " · last error ${row.value} ${age}h ago"
            }.orEmpty()
        return "teams: $withCrest of $total with a crest · $sampleText" +
            "\nqueue: $waiting waiting · $asked asked and still without a crest" +
            quota + lastError +
            (if (bySource.isEmpty()) "" else "\nby source: $bySource") +
            (if (missing.isEmpty()) "" else "\nwithout a crest: " + missing.joinToString(" · ")) +
            (if (why.isEmpty()) "" else
                "\ncooldown ${com.footballai.app.data.local.CrestAttempts.version()}: $why")
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

    /** True when any fixture is stored in the window a user would look at. */
    suspend fun hasFixturesBetween(fromEpochMs: Long, toEpochMs: Long): Boolean =
        db.matchDao().countBetween(fromEpochMs, toEpochMs) > 0

    suspend fun countsTriple(): Triple<Int, Int, Int> = Triple(
        db.matchDao().count(),
        db.historicalDao().count(),
        db.predictionDao().count(),
    )

    suspend fun pruneOldData(nowEpochMs: Long): Int =
        db.matchDao().pruneOldMatches(nowEpochMs - 400L * TimeConstants.DAY_MS)
}
