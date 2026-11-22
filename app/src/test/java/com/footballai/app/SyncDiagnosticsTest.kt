package com.footballai.app

import com.footballai.app.data.local.dao.SyncMetaDao
import com.footballai.app.data.local.entity.SyncMetaEntity
import com.footballai.app.data.sync.SyncDiagnosticsStore
import com.footballai.app.data.sync.SyncTimingRecorder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SyncDiagnosticsTest {
    @Test fun `durations use monotonic time and record a failed phase`() = runTest {
        var ticks = 1_000_000_000L
        val timer = SyncTimingRecorder { ticks }
        timer.measure("results") { ticks += 1_250_000_000L }
        assertFailsWith<IllegalStateException> {
            timer.measure("grading") { ticks += 500_000_000L; error("failed") }
        }
        ticks += 250_000_000L
        val report = timer.report(123, "daily", "failed")
        assertEquals(2_000L, report.totalMs)
        assertEquals(1_250L, report.phasesMs["results"])
        assertEquals(500L, report.phasesMs["grading"])
        assertNull(report.phasesMs["fixtures"])
    }

    @Test fun `last report survives storage and malformed reports are ignored`() = runTest {
        val dao = mockk<SyncMetaDao>()
        var saved: SyncMetaEntity? = null
        every { dao.observeAll() } answers { flowOf(listOfNotNull(saved)) }
        coEvery { dao.put(any()) } coAnswers { saved = firstArg() }
        val timer = SyncTimingRecorder { 0L }
        val report = timer.report(123, "daily", "success")
        SyncDiagnosticsStore(dao).save(report)
        assertEquals(report, SyncDiagnosticsStore(dao).reports.first())
        saved = saved!!.copy(value = "not json")
        assertNull(SyncDiagnosticsStore(dao).reports.first())
    }
}
