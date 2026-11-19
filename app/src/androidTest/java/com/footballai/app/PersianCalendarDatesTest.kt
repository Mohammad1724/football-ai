package com.footballai.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.footballai.app.core.MatchCalendarDates
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersianCalendarDatesTest {
    @Test
    fun nowruzAndLeapYearsFollowPlatformPersianCalendarInDifferentZones() {
        for (zone in listOf(ZoneId.of("Asia/Tehran"), ZoneId.of("Europe/Helsinki"), ZoneId.of("Pacific/Kiritimati"), ZoneId.of("America/Los_Angeles"))) {
            val nowruz = MatchCalendarDates.month(LocalDate.of(2025, 3, 21), true, zone)
            assertEquals(1404, nowruz.year)
            assertEquals(1, nowruz.month)
            assertEquals(LocalDate.of(2025, 3, 21), nowruz.first)
            val leapEsfand = MatchCalendarDates.shift(nowruz, -1, true, zone)
            assertEquals(1403, leapEsfand.year)
            assertEquals(12, leapEsfand.month)
            assertEquals(30, leapEsfand.length)
            assertEquals(LocalDate.of(2025, 3, 20), leapEsfand.last)
            assertEquals(nowruz, MatchCalendarDates.shift(leapEsfand, 1, true, zone))
            val ordinaryEsfand = MatchCalendarDates.month(LocalDate.of(2022, 3, 20), true, zone)
            assertEquals(1400, ordinaryEsfand.year)
            assertEquals(12, ordinaryEsfand.month)
            assertEquals(29, ordinaryEsfand.length)
        }
    }
}
