package com.footballai.app

import com.footballai.app.ui.viewmodel.MatchesViewModel
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class MatchesViewModelTest {

    @Test
    fun `recent match windows use local calendar days`() {
        val zone = ZoneId.of("Asia/Tehran")
        val now = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()

        val (start, end) = MatchesViewModel.localDayWindow(now, 0, zone)

        assertEquals(Instant.parse("2026-11-10T20:30:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-11-11T20:30:00Z").toEpochMilli(), end)
    }

    @Test
    fun `two day offset selects the complete earlier day`() {
        val zone = ZoneId.of("Asia/Tehran")
        val now = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()

        val (start, end) = MatchesViewModel.localDayWindow(now, -2, zone)

        assertEquals(Instant.parse("2026-11-08T20:30:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-11-09T20:30:00Z").toEpochMilli(), end)
    }

    @Test
    fun `future offset selects the complete following day`() {
        val zone = ZoneId.of("Asia/Tehran")
        val now = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()

        val (start, end) = MatchesViewModel.localDayWindow(now, 1, zone)

        assertEquals(Instant.parse("2026-11-11T20:30:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-11-12T20:30:00Z").toEpochMilli(), end)
    }

    @Test
    fun `day windows respect daylight saving transitions`() {
        val zone = ZoneId.of("Europe/London")
        val now = Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()

        val (start, end) = MatchesViewModel.localDayWindow(now, -1, zone)

        assertEquals(Instant.parse("2026-10-24T23:00:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-10-26T00:00:00Z").toEpochMilli(), end)
        assertEquals(25 * 60 * 60 * 1000L, end - start)
    }
}
