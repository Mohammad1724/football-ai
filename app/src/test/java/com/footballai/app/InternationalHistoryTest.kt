package com.footballai.app

import com.footballai.app.data.remote.InternationalResultsCsv
import com.footballai.app.domain.catalog.NationalTeamKeys
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The international archive is the only evidence a national-team forecast has, so both
 * halves of getting it right are tested here: reading the file, and agreeing on who a
 * team is.
 */
class InternationalHistoryTest {

    private val header = "date,home_team,away_team,home_score,away_score,tournament,city,country,neutral"

    private fun epochOf(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val cutoff = epochOf("2000-01-01")

    @Test
    fun `rows are read with their score, tournament and neutral flag`() {
        val csv = """
            $header
            2026-11-15,Israel,Kosovo,2,1,UEFA Nations League,Haifa,Israel,FALSE
            2026-11-16,Spain,Argentina,0,0,FIFA Series,Madrid,Spain,TRUE
        """.trimIndent()

        val result = InternationalResultsCsv.parse(csv, cutoff)
        assertEquals(2, result.rows.size)
        assertEquals(0, result.skipped)

        val first = result.rows[0]
        assertEquals(epochOf("2026-11-15"), first.kickoffEpochMs)
        assertEquals("Israel", first.homeName)
        assertEquals(2, first.homeGoals)
        assertEquals(1, first.awayGoals)
        assertEquals("UEFA Nations League", first.tournament)
        assertTrue(!first.neutralVenue)
        assertEquals(NationalTeamKeys.teamId("Israel"), first.homeTeamId)

        assertTrue(result.rows[1].neutralVenue)
    }

    @Test
    fun `a quoted city containing a comma does not shift the columns`() {
        val csv = """
            $header
            2026-06-01,United States,Canada,1,0,Friendly,"St. Louis, Missouri",United States,FALSE
        """.trimIndent()

        val result = InternationalResultsCsv.parse(csv, cutoff)
        val row = assertNotNull(result.rows.firstOrNull())
        assertEquals("United States", row.homeName)
        assertEquals("Canada", row.awayName)
        assertEquals(1, row.homeGoals)
        assertEquals(0, row.awayGoals)
        assertEquals("Friendly", row.tournament)
        assertTrue(!row.neutralVenue)
    }

    @Test
    fun `matches before the cutoff are dropped rather than stored`() {
        val csv = """
            $header
            1999-12-31,Brazil,Chile,3,1,Friendly,Rio,Brazil,FALSE
            2000-01-01,Brazil,Chile,3,1,Friendly,Rio,Brazil,FALSE
        """.trimIndent()

        val result = InternationalResultsCsv.parse(csv, cutoff)
        assertEquals(1, result.rows.size)
        assertEquals(epochOf("2000-01-01"), result.rows[0].kickoffEpochMs)
    }

    @Test
    fun `a row without a usable score or date is skipped, never guessed`() {
        val csv = """
            $header
            2026-13-45,Nowhere,Somewhere,1,0,Friendly,X,Y,FALSE
            2026-05-01,Alpha,Beta,,,Friendly,X,Y,FALSE
            2026-05-02,Gamma,Delta,-1,0,Friendly,X,Y,FALSE
            2026-05-03,Epsilon,Zeta,2,2,Friendly,X,Y,FALSE
            broken line with too few columns
        """.trimIndent()

        val result = InternationalResultsCsv.parse(csv, cutoff)
        assertEquals(1, result.rows.size)
        assertEquals("Epsilon", result.rows[0].homeName)
        assertEquals(4, result.skipped)
    }

    @Test
    fun `a match of a team against itself is rejected as corrupt`() {
        val csv = """
            $header
            2026-05-03,Israel,Israel,1,0,Friendly,X,Y,FALSE
        """.trimIndent()

        val result = InternationalResultsCsv.parse(csv, cutoff)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `one team keeps one key whatever the feed calls it`() {
        // Fixture feeds and the results archive spell some teams differently.
        assertEquals(NationalTeamKeys.key("Czech Republic"), NationalTeamKeys.key("Czechia"))
        assertEquals(NationalTeamKeys.key("Turkey"), NationalTeamKeys.key("Türkiye"))
        assertEquals(NationalTeamKeys.key("Iran"), NationalTeamKeys.key("IR Iran"))
        assertEquals(NationalTeamKeys.key("South Korea"), NationalTeamKeys.key("Korea Republic"))
        assertEquals(NationalTeamKeys.key("North Korea"), NationalTeamKeys.key("Korea DPR"))
        assertEquals(NationalTeamKeys.key("United States"), NationalTeamKeys.key("USA"))
        assertEquals(NationalTeamKeys.key("Curaçao"), NationalTeamKeys.key("Curacao"))
        assertEquals(NationalTeamKeys.key("Ivory Coast"), NationalTeamKeys.key("Côte d'Ivoire"))
        assertEquals(NationalTeamKeys.key("DR Congo"), NationalTeamKeys.key("Congo DR"))
        assertEquals(NationalTeamKeys.key("Bosnia and Herzegovina"), NationalTeamKeys.key("Bosnia-Herzegovina"))
        assertEquals(
            NationalTeamKeys.key("Saint Kitts and Nevis"),
            NationalTeamKeys.key("St. Kitts and Nevis"),
        )
    }

    @Test
    fun `two different teams never collapse into one key`() {
        // The alias table must not merge neighbours.
        assertTrue(NationalTeamKeys.key("Ireland") != NationalTeamKeys.key("Northern Ireland"))
        assertTrue(NationalTeamKeys.key("Guinea") != NationalTeamKeys.key("Equatorial Guinea"))
        assertTrue(NationalTeamKeys.key("Guinea") != NationalTeamKeys.key("Guinea-Bissau"))
        assertTrue(NationalTeamKeys.key("Sudan") != NationalTeamKeys.key("South Sudan"))
        assertTrue(NationalTeamKeys.key("Korea Republic" ) != NationalTeamKeys.key("Korea DPR"))
        assertEquals("national:israel", NationalTeamKeys.teamId("Israel"))
    }
}
