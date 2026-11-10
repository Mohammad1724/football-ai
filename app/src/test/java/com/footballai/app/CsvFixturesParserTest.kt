package com.footballai.app

import com.footballai.app.data.provider.CsvCompetition
import com.footballai.app.data.remote.CsvFixturesParser
import com.footballai.app.domain.model.MatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `fixtures.csv` is the only fixture source that needs no API key, so its parser is the
 * one place where a silent mis-parse would leave the whole app looking empty.
 */
class CsvFixturesParserTest {

    private val competitions = listOf(
        CsvCompetition("E0", "Premier League", "England"),
        CsvCompetition("D1", "Bundesliga", "Germany"),
    )

    private val csv = """
        Div,Date,Time,HomeTeam,AwayTeam,B365H,B365D,B365A
        E0,21/11/2026,15:00,Arsenal,Chelsea,1.80,3.60,4.20
        E0,22/11/2026,16:30,Manchester United,Newcastle,2.10,3.40,3.30
        D1,21/11/2026,14:30,Bayern Munich,Freiburg,,, 
        XX,21/11/2026,14:30,Unknown,Team,2.00,3.00,3.00
        E0,,,,,,,
    """.trimIndent()

    @Test
    fun `parses upcoming fixtures of supported divisions`() {
        val matches = CsvFixturesParser.parse(csv, competitions)
        assertEquals(3, matches.size)
        assertTrue(matches.all { it.status == MatchStatus.SCHEDULED })
        assertEquals("Premier League", matches.first().competitionName)
        assertEquals("arsenal", matches.first().home.providerKey)
        assertEquals("chelsea", matches.first().away.providerKey)
    }

    @Test
    fun `fixtures are sorted by kickoff`() {
        val matches = CsvFixturesParser.parse(csv, competitions)
        assertTrue(matches.zipWithNext().all { (a, b) -> a.kickoffEpochMs <= b.kickoffEpochMs })
    }

    @Test
    fun `kickoff is resolved in league local time`() {
        val matches = CsvFixturesParser.parse(csv, competitions)
        val arsenal = matches.first { it.home.name == "Arsenal" }
        // 21 Nov 2026 15:00 Europe/London (GMT) is 15:00 UTC.
        assertEquals(1_795_273_200_000L, arsenal.kickoffEpochMs)
    }

    @Test
    fun `odds are parsed and missing odds stay null`() {
        val matches = CsvFixturesParser.parse(csv, competitions)
        val arsenal = matches.first { it.home.name == "Arsenal" }
        val odds = assertNotNull(arsenal.odds)
        assertEquals(1.80, odds.home)
        assertEquals(3.60, odds.draw)
        assertEquals(4.20, odds.away)

        val bayern = matches.first { it.home.name == "Bayern Munich" }
        assertNull(bayern.odds)
    }

    @Test
    fun `unsupported divisions and empty rows are skipped`() {
        val matches = CsvFixturesParser.parse(csv, competitions)
        assertTrue(matches.none { it.home.name == "Unknown" })
        assertTrue(matches.none { it.home.name.isBlank() })
    }

    @Test
    fun `provider keys are unique and stable`() {
        val duplicated = """
            Div,Date,Time,HomeTeam,AwayTeam
            E0,21/11/2026,15:00,Arsenal,Chelsea
            E0,21/11/2026,15:00,Arsenal,Chelsea
        """.trimIndent()
        val matches = CsvFixturesParser.parse(duplicated, competitions)
        assertEquals(1, matches.size)
        assertEquals("E0:arsenal:chelsea:1795273200000", matches.single().providerKey)
    }

    @Test
    fun `season follows the kickoff month`() {
        val winter = """
            Div,Date,Time,HomeTeam,AwayTeam
            E0,14/02/2027,15:00,Leeds,Everton
        """.trimIndent()
        val match = CsvFixturesParser.parse(winter, competitions).single()
        assertEquals(1_802_617_200_000L, match.kickoffEpochMs)
        // A February fixture belongs to the season that started in 2026.
        assertEquals(2026, match.season)
    }

    @Test
    fun `an unknown header returns an empty list instead of throwing`() {
        assertTrue(CsvFixturesParser.parse("a,b,c\n1,2,3", competitions).isEmpty())
        assertTrue(CsvFixturesParser.parse("", competitions).isEmpty())
    }
}
