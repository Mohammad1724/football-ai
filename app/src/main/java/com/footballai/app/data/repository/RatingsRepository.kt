package com.footballai.app.data.repository

import com.footballai.app.core.TimeProvider
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.local.entity.HistoricalMatchEntity
import com.footballai.app.data.remote.TeamAliases
import com.footballai.app.data.remote.TeamIds
import com.footballai.app.domain.catalog.NationalTeamKeys
import com.footballai.engine.model.LambdaAdjustment
import com.footballai.engine.model.LeagueBaseline
import com.footballai.engine.model.TeamStrength
import com.footballai.engine.ratings.DixonColesFit
import com.footballai.engine.ratings.NationalMatch
import com.footballai.engine.ratings.NationalPrepared
import com.footballai.engine.ratings.NationalTeamRatingBook
import com.footballai.engine.ratings.RatingBook
import com.footballai.engine.ratings.RatingBookConfig
import com.footballai.engine.ratings.RatingMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Builds and caches the [RatingBook] for a competition.
 *
 * The book is rebuilt only when the underlying history changes, which keeps
 * predictions instant while the model always uses every result that happened
 * before the prediction timestamp.
 */
class RatingsRepository(
    private val db: FootballDatabase,
    private val clock: TimeProvider,
    private val config: RatingBookConfig = RatingBookConfig(
        dixonColes = com.footballai.engine.ratings.DixonColesConfig(
            xgBlendWeight = PRODUCTION_XG_BLEND_WEIGHT,
        ),
    ),
) {

    companion object {
        /**
         * Production blend of xG into the Dixon–Coles strength fit.
         *
         * Originally validated at 0.5 by walk-forward backtest on the big-5
         * (6,566 forecasts, 2021-22…2024-25): log loss 0.9939 → 0.9871 and
         * top-pick accuracy 52.2% → 52.8% versus goals-only. Retuned to 0.7
         * in the 2026-11-23 hyperparameter study (FotMob xG, ~100% coverage,
         * 8,289 forecasts): selected on 2021-22…2023-24 only, where the
         * response rises monotonically to a flat plateau at 0.7–1.0, and
         * confirmed on the untouched 2024-25 + 2025-26 validation window —
         * log loss 0.9855 → 0.9843 (paired z −2.7), accuracy 52.93% → 53.11%,
         * ECE 0.0155 → 0.0130, gains in both seasons. The weight only bites
         * on matches that carry xG (the big-5, via the football-ai-data
         * mirror); every other match — and every other competition —
         * contributes goals-only evidence, so for uncovered competitions
         * this is bit-for-bit the old behaviour.
         */
        const val PRODUCTION_XG_BLEND_WEIGHT = 0.7
    }

    private data class Key(val source: String, val competitionId: String)

    private class Entry(val historyCount: Int, val latestKickoff: Long, val book: RatingBook)

    /** Same idea as [Entry], but a national book is a different type, never mixed with it. */
    private class NationalEntry(
        val historyCount: Int,
        val latestKickoff: Long,
        val book: NationalTeamRatingBook,
    )

    private val cache = HashMap<Key, Entry>()

    /** The national archive is a single book, so it needs a single cache slot. */
    private var nationalCache: NationalEntry? = null

    private val mutex = Mutex()

    private suspend fun history(source: String, competitionId: String, asOf: Long): List<HistoricalMatchEntity> {
        val native = db.historicalDao().forCompetition(source, competitionId, asOf)
        val providerKey = competitionId.substringAfterLast(':')
        // Two bridges onto the CSV archive, both name-exact: football-data.org's
        // league codes ([HistoryBridge.leagues]) and the competitions a provider
        // publishes without usable history of its own ([HistoryBridge.mirrorLeagues]).
        val code = if (source == "football-data-org") {
            HistoryBridge.leagues[providerKey]
        } else {
            HistoryBridge.mirrorLeagues["$source:$providerKey"]
        } ?: return native.canonicalIdentities(competitionId)
        val csv = db.historicalDao().forCompetition("football-data-co-uk", "football-data-co-uk:league:$code", asOf)
        val mapped = HistoryBridge.map(
            csv,
            db.teamDao().forCompetition(competitionId),
            HistoryBridge.nameAliases[code].orEmpty(),
        )
        // Use one complete source, never double-count the same results across providers.
        val chosen = if (mapped.size > native.size) mapped else native
        return chosen.canonicalIdentities(competitionId)
    }

    /**
     * Rewrites archive ids onto the id the live feed gives the same club.
     *
     * The archive (football-data.co.uk) and the live feed (open-matchday) publish the
     * same club under different names, so the stored rows carry two ids for one team
     * and the rating book — which joins on the id — used to ignore the archive
     * completely for those clubs. [TeamAliases] holds the pairs that are backed by
     * evidence (same date, same score in both publishers); a club that is not in the
     * table keeps its own id, exactly as before.
     *
     * A row's id is *not* recomputed here: it stays the primary key of the stored row.
     * Only the two participating ids are rewritten, so nothing in the database moves.
     */
    private fun List<HistoricalMatchEntity>.canonicalIdentities(
        competitionId: String,
    ): List<HistoricalMatchEntity> = map { row ->
        val home = canonicalTeamId(competitionId, row.homeTeamId)
        val away = canonicalTeamId(competitionId, row.awayTeamId)
        if (home == row.homeTeamId && away == row.awayTeamId) row
        else row.copy(homeTeamId = home, awayTeamId = away)
    }

    /** `provider:team:<key>` with the key replaced by the live key, when one is known. */
    internal fun canonicalTeamId(competitionId: String, teamId: String): String =
        TeamIds.canonical(TeamIds.leagueOf(competitionId), teamId)

    suspend fun book(source: String, competitionId: String, asOfEpochMs: Long = clock.nowEpochMs()): RatingBook? =
        mutex.withLock {
            val rows = history(source, competitionId, asOfEpochMs)
            if (rows.isEmpty()) return null
            val key = Key(source, competitionId)
            val latest = rows.last().kickoffEpochMs
            val cached = cache[key]
            if (source != "football-data-org" && cached != null && cached.historyCount == rows.size && cached.latestKickoff == latest) {
                return cached.book
            }
            val book = RatingBook(config)
            book.addAll(competitionId, rows.map { it.toRatingMatch() })
            cache[key] = Entry(rows.size, latest, book)
            return book
        }

    suspend fun strengths(
        source: String,
        competitionId: String,
        homeTeamId: String,
        awayTeamId: String,
        asOfEpochMs: Long,
    ): Pair<TeamStrength, TeamStrength> = withContext(Dispatchers.Default) {
        val book = book(source, competitionId, asOfEpochMs)
            ?: return@withContext Pair(TeamStrength.neutral(homeTeamId), TeamStrength.neutral(awayTeamId))
        // The fixture's own id is looked up under the live naming; the history rows were
        // rewritten the same way, so both ends of the join agree.
        val (home, away) = book.strengths(
            competitionId,
            canonicalTeamId(competitionId, homeTeamId),
            canonicalTeamId(competitionId, awayTeamId),
            asOfEpochMs,
        )
        // The caller's ids are returned unchanged: they identify the fixture, not the rating.
        Pair(home.copy(teamId = homeTeamId), away.copy(teamId = awayTeamId))
    }

    /**
     * National-team strengths for an international fixture.
     *
     * The ratings come from the imported international archive, not from the fixture
     * provider: no feed in this app publishes past national results, and a national
     * team's strength is built from every match it played, not from one competition.
     * Team identifiers are the canonical keys from [NationalTeamKeys], which the caller
     * derives from the published team names.
     *
     * The book is cached by (history size, latest kickoff) so repeated screen visits do
     * not replay thousands of matches.
     */
    suspend fun nationalPrepared(
        homeTeamId: String,
        awayTeamId: String,
        asOfEpochMs: Long,
        neutralVenue: Boolean,
    ): NationalPrepared? = withContext(Dispatchers.Default) {
        val book = nationalBook(asOfEpochMs) ?: return@withContext null
        // Each past result carries the weight it deserves, assigned when the archive was
        // imported; the fixture's own importance only matters for future results.
        book.prepare(
            homeTeamId = homeTeamId,
            awayTeamId = awayTeamId,
            asOfEpochMs = asOfEpochMs,
            neutralVenue = neutralVenue,
        )
    }

    /** How many international results are available at all (0 = archive not imported). */
    suspend fun nationalHistoryCount(): Int =
        withContext(Dispatchers.Default) { db.historicalDao().countForSource(NationalTeamKeys.SOURCE) }

    private suspend fun nationalBook(asOfEpochMs: Long): NationalTeamRatingBook? = mutex.withLock {
        val rows = db.historicalDao().forSource(NationalTeamKeys.SOURCE, asOfEpochMs)
        if (rows.isEmpty()) return null
        val latest = rows.last().kickoffEpochMs
        val cached = nationalCache
        if (cached != null && cached.historyCount == rows.size && cached.latestKickoff == latest) {
            return cached.book
        }
        val book = NationalTeamRatingBook()
        book.addAll(
            rows.map { row ->
                NationalMatch(
                    homeTeamId = row.homeTeamId,
                    awayTeamId = row.awayTeamId,
                    homeGoals = row.homeGoals,
                    awayGoals = row.awayGoals,
                    kickoffEpochMs = row.kickoffEpochMs,
                    importance = nationalImportance(row.competitionName),
                    neutralVenue = row.neutralVenue,
                )
            },
        )
        nationalCache = NationalEntry(rows.size, latest, book)
        return book
    }

    /**
     * Weight of a past international, taken from the tournament name the archive prints.
     * The scale matches the one used for fixtures: a friendly counts least, a qualifier
     * normally, a finals tournament a little more.
     */
    private fun nationalImportance(tournament: String): Double {
        val name = tournament.lowercase()
        return when {
            name.contains("friendly") -> 0.5
            name.contains("qualification") || name.contains("qualifier") -> 1.0
            name.contains("world cup") || name.contains("euro") || name.contains("copa") ||
                name.contains("cup of nations") || name.contains("gold cup") ||
                name.contains("nations league") || name.contains("confederations") -> 1.25
            else -> 1.0
        }
    }

    suspend fun adjustments(
        source: String,
        competitionId: String,
        homeTeamId: String,
        awayTeamId: String,
        asOfEpochMs: Long,
    ): List<LambdaAdjustment> = withContext(Dispatchers.Default) {
        book(source, competitionId, asOfEpochMs)?.adjustments(
            competitionId,
            canonicalTeamId(competitionId, homeTeamId),
            canonicalTeamId(competitionId, awayTeamId),
            asOfEpochMs,
        ).orEmpty()
    }

    suspend fun baseline(source: String, competitionId: String, asOfEpochMs: Long): LeagueBaseline {
        val book = book(source, competitionId, asOfEpochMs) ?: return LeagueBaseline.FALLBACK
        return book.baseline(competitionId, asOfEpochMs)
    }

    suspend fun fit(source: String, competitionId: String, asOfEpochMs: Long): DixonColesFit? =
        withContext(Dispatchers.Default) { book(source, competitionId, asOfEpochMs)?.fit(competitionId, asOfEpochMs) }

    suspend fun eloRating(source: String, competitionId: String, teamId: String, asOfEpochMs: Long): Double =
        withContext(Dispatchers.Default) {
            book(source, competitionId, asOfEpochMs)
                ?.eloRating(canonicalTeamId(competitionId, teamId), asOfEpochMs)
                ?: TeamStrength.AVERAGE_ELO
        }

    suspend fun matchCount(source: String, competitionId: String): Int =
        history(source, competitionId, clock.nowEpochMs()).size

    /** Invalidates the cache, e.g. after a historical import or a fixture sync. */
    suspend fun invalidate() = mutex.withLock {
        cache.clear()
        nationalCache = null
    }
}

fun HistoricalMatchEntity.toRatingMatch(): RatingMatch = RatingMatch(
    homeTeamId = homeTeamId,
    awayTeamId = awayTeamId,
    homeGoals = homeGoals,
    awayGoals = awayGoals,
    kickoffEpochMs = kickoffEpochMs,
    weight = 1.0,
    homeXg = homeXg,
    awayXg = awayXg,
)
