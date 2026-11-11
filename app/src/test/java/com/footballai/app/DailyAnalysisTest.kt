package com.footballai.app

import com.footballai.app.core.*
import com.footballai.app.data.local.FootballDatabase
import com.footballai.app.data.local.dao.*
import com.footballai.app.data.local.mapper.toEntity
import com.footballai.app.data.provider.*
import com.footballai.app.data.remote.FootballDataCoUkProvider
import com.footballai.app.data.repository.*
import com.footballai.app.domain.model.*
import com.footballai.engine.model.TeamStrength
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class DailyAnalysisTest {
    private class Env {
        val now = Instant.parse("2026-11-10T10:00:00Z").toEpochMilli()
        val db = mockk<FootballDatabase>()
        val matches = mockk<MatchDao>()
        val stored = mockk<PredictionDao>()
        val meta = mockk<SyncMetaDao>()
        val registry = mockk<ProviderRegistry>()
        val history = mockk<HistoricalDataProvider>()
        val sync = mockk<SyncRepository>()
        val ratings = mockk<RatingsRepository>()
        val predictions = mockk<PredictionRepository>()
        val source = FootballDataCoUkProvider.PROVIDER_ID
        val fixture = RemoteMatch("m", "E0", "Premier League", 2026, 1, now + 3600000,
            MatchStatus.SCHEDULED, null, RemoteTeam("h", "Home"), RemoteTeam("a", "Away"),
            null, null, null, null, null, null).toEntity(source, "$source:team:h", "$source:team:a", "$source:league:E0", now)
        init {
            every { db.matchDao() } returns matches
            every { db.predictionDao() } returns stored
            every { db.syncMetaDao() } returns meta
            every { meta.observeAll() } returns flowOf(emptyList())
            coEvery { meta.get(any()) } returns null
            coEvery { meta.put(any()) } just Runs
            coEvery { registry.activeProviderId() } returns source
            every { registry.historical } returns history
            every { history.availableCompetitions() } returns listOf(CsvCompetition("E0", "Premier League", "England"))
            coEvery { sync.syncCompetitions(source) } returns Outcome.Success(1)
            coEvery { sync.syncFixtures(any(), any(), any(), any(), any(), any()) } returns Outcome.Success(SyncSummary(fixtures = 1))
            coEvery { sync.importCsvHistory(any(), any()) } returns Outcome.Success(SyncSummary(historical = 100))
            coEvery { matches.scheduledBetween(any(), any()) } returns listOf(fixture)
            coEvery { matches.pendingResults(any(), any()) } returns emptyList()
            coEvery { stored.firstPreMatch(any(), any()) } returns null
            coEvery { ratings.matchCount(any(), any()) } returns 100
            coEvery { ratings.strengths(any(), any(), any(), any(), any()) } returns
                (TeamStrength.neutral("h", 25) to TeamStrength.neutral("a", 25))
            coEvery { predictions.predict(any(), any()) } returns Outcome.Success(mockk<PredictionView>())
            coEvery { predictions.resolveFinishedPredictions() } returns 2
        }
        val repo by lazy { DailyAnalysisRepository(db, registry, sync, ratings, predictions,
            object : TimeProvider { override fun nowEpochMs() = now }) }
    }

    @Test fun `daily pipeline is keyless bootstraps history and saves pre match forecasts`() = runTest {
        val e = Env()
        val report = e.repo.run()
        assertEquals(1, report.predicted)
        assertEquals(2, report.resolved)
        coVerify { e.sync.syncFixtures(e.source, listOf("E0"), 0, 10, false, any()) }
        coVerify { e.sync.importCsvHistory(listOf("E0"), listOf(2026)) }
        coVerify(exactly = 0) { e.sync.syncLive(any()) }
    }

    @Test fun `insufficient team history never generates a neutral fallback forecast`() = runTest {
        val e = Env()
        coEvery { e.ratings.strengths(any(), any(), any(), any(), any()) } returns
            (TeamStrength.neutral("h", 2) to TeamStrength.neutral("a", 20))
        assertEquals(1, e.repo.run().insufficientHistory)
        coVerify(exactly = 0) { e.predictions.predict(any(), any()) }
    }

    @Test fun `existing snapshots are not regenerated`() = runTest {
        val e = Env()
        coEvery { e.stored.firstPreMatch(any(), any()) } returns mockk()
        assertEquals(1, e.repo.run().alreadySaved)
        coVerify(exactly = 0) { e.predictions.predict(any(), any()) }
    }

    @Test fun `stale fixtures absent from this sync are not forecast`() = runTest {
        val e = Env()
        coEvery { e.matches.scheduledBetween(any(), any()) } returns listOf(e.fixture.copy(lastSyncedAt = e.now - 1))
        assertEquals(0, e.repo.run().candidates)
        coVerify(exactly = 0) { e.predictions.predict(any(), any()) }
    }

    @Test fun `published final results are imported on days without upcoming matches`() = runTest {
        val e = Env()
        coEvery { e.matches.scheduledBetween(any(), any()) } returns emptyList()
        coEvery { e.matches.pendingResults(any(), any()) } returns listOf(e.fixture.copy(kickoffEpochMs = e.now - 86400000))
        val report = e.repo.run()
        assertEquals(0, report.candidates)
        assertEquals(2, report.resolved)
        coVerify { e.sync.importCsvHistory(listOf("E0"), any()) }
    }

    @Test fun `completed bootstrap downloads only the current season on later runs`() = runTest {
        val e = Env()
        coEvery { e.meta.get("daily-history-v1:E0:2026") } returns "ready"
        e.repo.run()
        coVerify { e.sync.importCsvHistory(listOf("E0"), listOf(2026)) }
    }

    @Test fun `cancellation propagates and does not mark the run completed`() = runTest {
        val e = Env()
        coEvery { e.sync.syncCompetitions(any()) } throws CancellationException()
        assertFailsWith<CancellationException> { e.repo.run() }
        coVerify(exactly = 0) { e.meta.put(any()) }
    }

    @Test fun `pick labels are descriptive and refuse ambiguous or missing data`() {
        assertEquals("HOME", DailyAnalysisPolicy.pick(.6, .2, .2, QualityLevel.MEDIUM))
        assertEquals("DRAW", DailyAnalysisPolicy.pick(.2, .6, .2, QualityLevel.MEDIUM))
        assertEquals("AWAY", DailyAnalysisPolicy.pick(.2, .2, .6, QualityLevel.MEDIUM))
        assertEquals("UNCLEAR", DailyAnalysisPolicy.pick(.4, .38, .22, QualityLevel.MEDIUM))
        assertEquals("UNCLEAR", DailyAnalysisPolicy.pick(.46, .39, .15, QualityLevel.MEDIUM))
        assertEquals("UNCLEAR", DailyAnalysisPolicy.pick(.8, .1, .1, QualityLevel.LOW))
        assertEquals("INSUFFICIENT", DailyAnalysisPolicy.pick(.8, .1, .1, QualityLevel.INSUFFICIENT))
        assertEquals("INSUFFICIENT", DailyAnalysisPolicy.pick(Double.NaN, .1, .1, QualityLevel.HIGH))
        assertFalse(DailyAnalysisPolicy.hasEnoughHistory(39, 20, 20))
        assertFalse(DailyAnalysisPolicy.hasEnoughHistory(100, 4, 20))
        assertTrue(DailyAnalysisPolicy.hasEnoughHistory(40, 5, 5))
    }

    @Test fun `daily window ends tomorrow in the device timezone`() {
        val now = Instant.parse("2026-11-10T22:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-11-12T20:30:00Z").toEpochMilli() - 1,
            DailyAnalysisPolicy.endOfTomorrow(now, ZoneId.of("Asia/Tehran")))
    }
}
