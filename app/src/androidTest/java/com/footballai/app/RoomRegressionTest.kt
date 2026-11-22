package com.footballai.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.local.entity.PredictionEntity
import com.footballai.app.data.local.entity.PredictionMarketEntity
import com.footballai.app.data.local.mapper.toEntity
import com.footballai.app.data.provider.RemoteMatch
import com.footballai.app.data.provider.RemoteTeam
import com.footballai.app.domain.model.MatchStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Run with :app:connectedDebugAndroidTest on an emulator/device. */
@RunWith(AndroidJUnit4::class)
class RoomRegressionTest {
    private lateinit var db: FootballDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FootballDatabase::class.java).build()
    }
    @After fun close() = db.close()

    private fun match() = RemoteMatch("m", "E0", "League", 2026, 1, 1000,
        MatchStatus.SCHEDULED, null, RemoteTeam("h", "Home"), RemoteTeam("a", "Away"),
        null, null, null, null, null, null).toEntity("csv", "h", "a", "league", 100)

    @Test fun fixtureRefreshPreservesDetailMetadata() = runBlocking {
        val m = match()
        db.matchDao().upsertAll(listOf(m.copy(hasLineups = true, hasStatistics = true, hasEvents = true, statisticsSyncedAt = 100)))
        db.matchDao().mergeFixtures(listOf(m.copy(minute = 30, status = "LIVE", homeGoals = 1, awayGoals = 0)))
        val fresh = requireNotNull(db.matchDao().byId(m.id))
        assertTrue(fresh.hasLineups && fresh.hasStatistics && fresh.hasEvents)
        assertEquals(100L, fresh.statisticsSyncedAt)
        assertEquals(1, fresh.homeGoals)
    }

    @Test fun multipleGoalLinesSurvivePersistence() = runBlocking {
        val p = prediction("p", 100)
        val markets = listOf(0.5, 1.5, 2.5, 3.5).mapIndexed { index, line ->
            PredictionMarketEntity(p.id, "OVER_UNDER", "OVER", line, .5, index)
        }
        db.predictionDao().save(p, markets)
        assertEquals(listOf(0.5, 1.5, 2.5, 3.5), db.predictionDao().markets("p").map { it.line })
    }

    @Test fun firstPreMatchSnapshotIsImmutable() = runBlocking {
        db.predictionDao().save(prediction("first", 100), emptyList())
        db.predictionDao().save(prediction("second", 200), emptyList())
        assertEquals(1, db.predictionDao().count())
        assertEquals("first", db.predictionDao().firstPreMatch("m", "v2")?.id)
    }

    private fun prediction(id: String, time: Long) = PredictionEntity(
        id = id, matchId = "m", kind = "PRE_MATCH", createdAtEpochMs = time, asOfEpochMs = time,
        modelName = "test", modelVersion = "v2", expectedHomeGoals = 1.0, expectedAwayGoals = 1.0,
        homeWin = .4, draw = .3, awayWin = .3, over25 = .5, bttsYes = .5,
        fittedMatches = 100, rho = 0.0, qualityLevel = "LOW", qualityCompleteness = .3,
        qualityJson = "{}", explanationsJson = "[]", liveSnapshotJson = null,
        minute = null, homeGoals = null, awayGoals = null,
        oddsHome = null, oddsDraw = null, oddsAway = null,
        resolvedAtEpochMs = null, actualHomeGoals = null, actualAwayGoals = null,
        outcomeCorrect = null, brier = null, logLoss = null,
    )
}
