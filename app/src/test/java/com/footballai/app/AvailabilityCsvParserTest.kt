package com.footballai.app

import com.footballai.app.data.remote.AvailabilityCsvParser
import com.footballai.app.data.remote.AvailabilityHistoryProvider
import java.time.LocalDate
import java.time.Instant
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityCsvParserTest {

    private val csv = """
        date,league,home_team,away_team,missing_home,missing_away
        2021-08-13,D1,M'gladbach,Bayern Munich,0.0210,0.1050
        2021-08-14,D1,Augsburg,Hoffenheim,0.0000,0.0000
        2021-08-14,D1,Bielefeld,Freiburg,,
    """.trimIndent()

    @Test
    fun `parses rows with both shares present`() {
        val rows = AvailabilityCsvParser.parse(csv)
        assertEquals(2, rows.size)
        val first = rows.first()
        assertEquals(LocalDate.of(2021, 8, 13), first.date)
        assertEquals("M'gladbach", first.homeTeamName)
        assertEquals("Bayern Munich", first.awayTeamName)
        assertEquals(0.0210, first.homeMissingShare)
        assertEquals(0.1050, first.awayMissingShare)
    }

    @Test
    fun `rows without published shares are skipped, never read as zero`() {
        val rows = AvailabilityCsvParser.parse(csv)
        assertTrue(rows.none { it.homeTeamName == "Bielefeld" })
    }

    @Test
    fun `zero shares are real data and are kept`() {
        val rows = AvailabilityCsvParser.parse(csv)
        val augsburg = rows.single { it.homeTeamName == "Augsburg" }
        assertEquals(0.0, augsburg.homeMissingShare)
        assertEquals(0.0, augsburg.awayMissingShare)
    }

    @Test
    fun `out of range shares are rejected`() {
        val bad = """
            date,league,home_team,away_team,missing_home,missing_away
            2021-08-13,D1,M'gladbach,Bayern Munich,1.4000,0.1000
        """.trimIndent()
        assertTrue(AvailabilityCsvParser.parse(bad).isEmpty())
    }

    @Test
    fun `a file without the expected header yields nothing`() {
        assertTrue(AvailabilityCsvParser.parse("a,b,c\n1,2,3").isEmpty())
        assertTrue(AvailabilityCsvParser.parse("").isEmpty())
    }

    @Test
    fun `legacy rows lack provenance and source observation times are parsed explicitly`() {
        assertNull(AvailabilityCsvParser.parse(csv).first().observedAtEpochMs)
        val withTimes = """
            date,home_team,away_team,missing_home,missing_away,observed_at
            2026-08-14,Home,Away,0.1,0.2,2026-08-13T12:00:00Z
            2026-08-15,Other,Away,0.2,0.1,not-a-timestamp
        """.trimIndent()
        val rows = AvailabilityCsvParser.parse(withTimes)
        assertEquals(Instant.parse("2026-08-13T12:00:00Z").toEpochMilli(), rows.first().observedAtEpochMs)
        assertNull(rows.last().observedAtEpochMs)
    }

    @Test
    fun `file names mirror the data repo layout`() {
        assertEquals("E0-2021-22.csv", AvailabilityHistoryProvider.fileName("E0", 2021))
        assertEquals("SP1-2025-26.csv", AvailabilityHistoryProvider.fileName("SP1", 2025))
    }
}
