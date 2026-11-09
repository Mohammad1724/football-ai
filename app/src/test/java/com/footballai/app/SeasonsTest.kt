package com.footballai.app

import com.footballai.app.core.Seasons
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeasonsTest {

    private fun at(year: Int, month: Int): ZonedDateTime =
        ZonedDateTime.of(year, month, 15, 12, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `season start year rolls over in july`() {
        assertEquals(listOf(2024, 2023), Seasons.recentStartYears(at(2024, 7), 2))
        assertEquals(listOf(2023, 2022), Seasons.recentStartYears(at(2024, 6), 2))
    }

    @Test
    fun `count is clamped to a sane range`() {
        assertEquals(1, Seasons.recentStartYears(at(2025, 1), 0).size)
        assertEquals(Seasons.MAX_SEASONS, Seasons.recentStartYears(at(2025, 1), 99).size)
    }

    @Test
    fun `years descend without gaps`() {
        val years = Seasons.recentStartYears(at(2024, 9), 5)
        assertEquals(listOf(2024, 2023, 2022, 2021, 2020), years)
        assertTrue(years.zipWithNext().all { (newer, older) -> newer == older + 1 })
    }

    @Test
    fun `epoch based input is resolved in utc`() {
        // A device in Tehran is already in July while UTC is still in June; the season
        // must not depend on the phone's time zone.
        val june = ZonedDateTime.of(2024, 6, 30, 23, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val july = ZonedDateTime.of(2024, 7, 1, 0, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(2023, Seasons.recentStartYears(june, 1).single())
        assertEquals(2024, Seasons.recentStartYears(july, 1).single())
    }
}
