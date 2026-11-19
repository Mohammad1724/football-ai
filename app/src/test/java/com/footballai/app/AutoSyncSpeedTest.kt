package com.footballai.app

import com.footballai.app.core.NetworkMonitor
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.repository.*
import com.footballai.app.data.settings.AppSettings
import com.footballai.app.data.settings.SettingsRepository
import com.footballai.app.data.sync.AutoSyncCoordinator
import com.footballai.app.data.sync.SyncTrigger
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncSpeedTest {
    @Test fun `manual refresh finishes core work without waiting for optional tables or badges`() = runTest {
        val settings = mockk<SettingsRepository>()
        val sync = mockk<SyncRepository>()
        val daily = mockk<DailyAnalysisRepository>()
        val predictions = mockk<PredictionRepository>()
        val providers = mockk<ProviderRegistry>()
        val matches = mockk<MatchRepository>()
        val network = mockk<NetworkMonitor>()
        val hold = CompletableDeferred<Unit>()
        val clock = object : TimeProvider { override fun nowEpochMs() = 1_000_000L }
        every { network.isOnlineNow() } returns true
        coEvery { settings.current() } returns AppSettings(dailyAnalysisEnabled = false)
        coEvery { providers.activeProviderId() } returns "example"
        every { providers.info(any()) } returns null
        every { providers.activeInfo(any()) } returns null
        coEvery { sync.syncFixtures(any(), any(), any(), any(), any(), any()) } returns Outcome.Success(SyncSummary(fixtures = 4))
        coEvery { predictions.resolveFinishedPredictions() } returns 0
        coEvery { sync.refreshStandings(any(), any(), any()) } coAnswers { hold.await(); 0 }
        coEvery { sync.backfillCrests(any(), any(), any(), any()) } returns 0
        val coordinator = AutoSyncCoordinator(settings, sync, daily, predictions, providers, matches, network, clock, backgroundScope)

        val result = coordinator.syncNow(SyncTrigger.MANUAL)
        assertTrue(result is Outcome.Success)
        assertEquals(4, (result as Outcome.Success).value.added)
        assertFalse(coordinator.state.value.isSyncing)
        runCurrent()
        coVerify(exactly = 1) { sync.refreshStandings(any(), any(), any()) }
        coVerify(exactly = 0) { sync.backfillCrests(any(), any(), any(), any()) }
        hold.complete(Unit)
        runCurrent()
        coVerify(exactly = 1) { sync.backfillCrests(any(), any(), any(), any()) }
    }
}
