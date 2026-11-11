package com.footballai.app

import com.footballai.app.data.provider.CsvCompetition
import com.footballai.app.data.remote.OpenMatchdayParser
import com.footballai.app.domain.model.MatchStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenMatchdayParserTest {
    private val league = CsvCompetition("E0", "Premier League", "England")

    @Test
    fun `fixtures retain teams round and fixed UTC plus one kickoff`() {
        val csv = """
            gw,date,time,home,away
            11,2026-11-21,13:30,Man City,Fulham
            11,2026-11-21,16:00,Aston Villa,Sunderland
        """.trimIndent()

        val matches = OpenMatchdayParser.fixtures(csv, league, 2026)

        assertEquals(2, matches.size)
        assertEquals(MatchStatus.SCHEDULED, matches[0].status)
        assertEquals(11, matches[0].matchday)
        assertEquals("man-city", matches[0].home.providerKey)
        assertEquals(Instant.parse("2026-11-21T12:30:00Z").toEpochMilli(), matches[0].kickoffEpochMs)
        assertNull(matches[0].homeGoals)
    }

    @Test
    fun `results feed both finished matches and model history`() {
        val csv = """
            gw,date,time,home,away,ft_home,ft_away,ft_result,ht_home,ht_away,ht_result
            10,2026-11-09,20:00,Arsenal,Coventry,3,0,H,2,0,H
            10,2026-11-09,20:00,No Score,Fulham,,,D,,,D
        """.trimIndent()

        val matches = OpenMatchdayParser.results(csv, league, 2026)
        val history = OpenMatchdayParser.history(csv, league, 2026)

        assertEquals(1, matches.size)
        assertEquals(MatchStatus.FINISHED, matches[0].status)
        assertEquals(3, matches[0].homeGoals)
        assertEquals(0, matches[0].awayGoals)
        assertEquals(2, matches[0].homeHalfTimeGoals)
        assertEquals(1, history.size)
        assertEquals("arsenal", history[0].homeTeamKey)
        assertEquals(3, history[0].homeGoals)
    }

    @Test
    fun `missing kickoff time uses source noon instead of failing the row`() {
        val csv = "gw,date,time,home,away\n1,2026-11-21,,Man City,Fulham"

        val match = OpenMatchdayParser.fixtures(csv, league, 2026).single()

        assertEquals(Instant.parse("2026-11-21T11:00:00Z").toEpochMilli(), match.kickoffEpochMs)
    }
}
