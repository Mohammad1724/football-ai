package com.footballai.app

import com.footballai.app.data.remote.CsvHistoryParser
import com.footballai.app.data.remote.FootballDataCoUkProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsvHistoryParserTest {

    private val csv = """
        Div,Date,Time,HomeTeam,AwayTeam,FTHG,FTAG,FTR,B365H,B365D,B365A
        E0,19/08/2023,15:00,Arsenal,Nottingham Forest,2,1,H,1.30,5.50,10.00
        E0,20/08/2023,14:00,Aston Villa,Everton,4,0,H,1.75,3.80,4.60
        E0,21/08/2023,20:00,Manchester United,Wolverhampton,1,0,H,,,,,,
    """.trimIndent()

    @Test
    fun `parses every finished match`() {
        val matches = CsvHistoryParser.parse(csv, "E0", "Premier League", 2023)
        assertEquals(3, matches.size)
        val first = matches.first()
        assertEquals("Arsenal", first.homeTeamName)
        assertEquals("Nottingham Forest", first.awayTeamName)
        assertEquals(2, first.homeGoals)
        assertEquals(1, first.awayGoals)
        assertEquals("arsenal", first.homeTeamKey)
        assertEquals("nottingham-forest", first.awayTeamKey)
        assertEquals(1.30, first.homeOdds)
        assertEquals(5.50, first.drawOdds)
        assertEquals(10.00, first.awayOdds)
    }

    @Test
    fun `missing odds stay null instead of zero`() {
        val matches = CsvHistoryParser.parse(csv, "E0", "Premier League", 2023)
        val last = matches.last()
        assertNull(last.homeOdds)
        assertNull(last.drawOdds)
        assertNull(last.awayOdds)
    }

    @Test
    fun `kickoff timestamps are parsed in league local time`() {
        val matches = CsvHistoryParser.parse(csv, "E0", "Premier League", 2023)
        assertTrue(matches.first().kickoffEpochMs > 1_600_000_000_000L)
        assertTrue(matches.zipWithNext().all { (a, b) -> a.kickoffEpochMs <= b.kickoffEpochMs })
    }

    @Test
    fun `rows without a score are skipped`() {
        val partial = """
            Div,Date,HomeTeam,AwayTeam,FTHG,FTAG
            E0,19/08/2023,Arsenal,Chelsea,,,
            E0,20/08/2023,Leeds,Watford,1,1
        """.trimIndent()
        val matches = CsvHistoryParser.parse(partial, "E0", "Premier League", 2023)
        assertEquals(1, matches.size)
        assertEquals("Leeds", matches.first().homeTeamName)
    }

    @Test
    fun `unknown header returns an empty list instead of throwing`() {
        assertTrue(CsvHistoryParser.parse("a,b,c\n1,2,3", "E0", "Test", 2023).isEmpty())
        assertTrue(CsvHistoryParser.parse("", "E0", "Test", 2023).isEmpty())
    }

    @Test
    fun `quoted fields are supported`() {
        val fields = CsvHistoryParser.splitLine(""""a,b",c,"d""e"""")
        assertEquals(3, fields.size)
        assertEquals("a,b", fields[0])
        assertEquals("c", fields[1])
        assertEquals("d\"e", fields[2])
    }

    @Test
    fun `season codes follow the provider convention`() {
        assertEquals("2324", FootballDataCoUkProvider.seasonCode(2023))
        assertEquals("9900", FootballDataCoUkProvider.seasonCode(1999))
    }

    @Test
    fun `slugs remove punctuation and accents of separators`() {
        assertEquals("man-city", CsvHistoryParser.slug("Man City"))
        assertEquals("brighton-and-hove-albion", CsvHistoryParser.slug("Brighton & Hove Albion"))
        assertNotNull(CsvHistoryParser.slug("PSG"))
    }
}
