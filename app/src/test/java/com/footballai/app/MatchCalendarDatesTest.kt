package com.footballai.app

import com.footballai.app.core.MatchCalendarDates
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchCalendarDatesTest {
    @Test
    fun `month grid starts on the configured weekday`() {
        val date = LocalDate.of(2026, 3, 21) // Saturday
        assertEquals(0, MatchCalendarDates.leadingDays(date, DayOfWeek.SATURDAY))
        assertEquals(5, MatchCalendarDates.leadingDays(date, DayOfWeek.MONDAY))
    }

    @Test
    fun `gregorian months and bounds handle leap day and year transitions`() {
        val zone = ZoneId.of("Europe/Helsinki")
        val february = MatchCalendarDates.month(LocalDate.of(2024, 2, 29), false, zone)
        assertEquals(29, february.length)
        assertEquals(LocalDate.of(2024, 2, 1), february.first)
        assertEquals(LocalDate.of(2023, 2, 1), MatchCalendarDates.shift(february, -12, false, zone).first)
        assertTrue(february.intersects(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 10)))
        assertFalse(february.intersects(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 4, 1)))
    }
}
