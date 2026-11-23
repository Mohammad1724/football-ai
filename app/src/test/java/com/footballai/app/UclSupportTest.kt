package com.footballai.app

import com.footballai.app.data.remote.AvailabilityHistoryProvider
import com.footballai.app.data.remote.CsvHistoryParser
import com.footballai.app.data.remote.FootballDataCoUkProvider
import com.footballai.app.data.remote.XgHistoryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Champions League reaches the app through the football-ai-data mirror:
 * registered as a CSV competition, parsed by the same history parser as the
 * football-data.co.uk files, and covered by both enrichment mirrors.
 */
class UclSupportTest {

    @Test
    fun `UCL is a registered CSV competition served from the mirror`() {
        val competition = FootballDataCoUkProvider.COMPETITIONS.single { it.key == "UCL" }
        assertEquals("UEFA Champions League", competition.name)
        assertTrue("UCL" in XgHistoryProvider.COVERED_COMPETITIONS)
        assertTrue("UCL" in AvailabilityHistoryProvider.COVERED_COMPETITIONS)
        assertEquals("UCL-2024-25.csv", XgHistoryProvider.fileName("UCL", 2024))
    }

    @Test
    fun `mirror history layout parses into regulation results`() {
        val csv = """
            Div,Date,Time,HomeTeam,AwayTeam,FTHG,FTAG
            UCL,04/05/2022,20:00,Real Madrid,Man City,2,1
            UCL,26/04/2022,20:00,Man City,Real Madrid,4,3
        """.trimIndent()
        val rows = CsvHistoryParser.parse(csv, "UCL", "UEFA Champions League", 2021)
        assertEquals(2, rows.size)
        val secondLeg = rows.single { it.homeTeamName == "Real Madrid" }
        // The collector publishes the 90-minute score: this tie finished 3-1
        // after extra time, and 2-1 is the only score the model may train on.
        assertEquals(2, secondLeg.homeGoals)
        assertEquals(1, secondLeg.awayGoals)
        assertEquals("real-madrid", secondLeg.homeTeamKey)
        assertEquals("man-city", secondLeg.awayTeamKey)
        assertEquals(2021, secondLeg.season)
    }
}
