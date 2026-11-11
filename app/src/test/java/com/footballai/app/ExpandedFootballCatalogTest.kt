package com.footballai.app

import com.footballai.app.data.remote.ExpandedFootballCatalog
import com.footballai.app.data.remote.ExpandedTeamIdentity
import com.footballai.app.data.remote.ExtraCsvHistoryParser
import com.footballai.app.data.remote.FootballDataCoUkProvider
import com.footballai.app.data.remote.OpenMatchdayParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExpandedFootballCatalogTest {

    @Test
    fun `expanded catalogue contains only mapped league competitions`() {
        assertEquals(61, FootballDataCoUkProvider.COMPETITIONS.size)
        assertEquals(61, ExpandedFootballCatalog.OPEN_MATCHDAY_LEAGUES.size)
        assertTrue(ExpandedFootballCatalog.OPEN_MATCHDAY_LEAGUES.values.none { "_CUP_" in it })
        assertEquals(FootballDataCoUkProvider.COMPETITIONS.map { it.key }.toSet(),
            ExpandedFootballCatalog.OPEN_MATCHDAY_LEAGUES.keys)
    }

    @Test
    fun `extra archive filters season parses scores and closing odds`() {
        val csv = "\uFEFF" + """
            Country,League,Season,Date,Time,Home,Away,HG,AG,Res,AvgCH,AvgCD,AvgCA
            Brazil,Serie A,2025,10/05/2025,20:00,Botafogo RJ,Flamengo RJ,2,1,H,2.10,3.20,3.60
            Brazil,Serie A,2026,10/05/2026,20:00,Botafogo RJ,Flamengo RJ,1,1,D,2.30,3.10,3.20
            Brazil,Serie A,2026,11/05/2026,20:00,Palmeiras,Santos,,,H,,,
        """.trimIndent()
        val competition = FootballDataCoUkProvider.COMPETITIONS.single { it.key == "BRA1" }
        val rows = ExtraCsvHistoryParser.parse(csv, competition, 2026)
        assertEquals(1, rows.size)
        assertEquals(1, rows.single().homeGoals)
        assertEquals(1, rows.single().awayGoals)
        assertEquals(2.30, rows.single().homeOdds)
        assertEquals(3.10, rows.single().drawOdds)
        assertEquals(3.20, rows.single().awayOdds)
    }

    @Test
    fun `slash season format is supported`() {
        val csv = """
            Country,League,Season,Date,Home,Away,HG,AG
            Denmark,Superliga,2025/2026,10/08/2025,Aarhus,Brondby,1,0
            Denmark,Superliga,2026/2027,10/08/2026,Aarhus,Brondby,0,1
        """.trimIndent()
        val competition = FootballDataCoUkProvider.COMPETITIONS.single { it.key == "DEN1" }
        assertEquals(1, ExtraCsvHistoryParser.parse(csv, competition, 2026).size)
        assertEquals(0, ExtraCsvHistoryParser.parse(csv, competition, 2026).single().homeGoals)
    }

    @Test
    fun `current and historical Brazilian team names share identity`() {
        val competition = FootballDataCoUkProvider.COMPETITIONS.single { it.key == "BRA1" }
        val current = OpenMatchdayParser.fixtures(
            "gw,date,time,home,away\n1,2026-11-20,20:00,Botafogo,Flamengo",
            competition,
            2026,
        ).single()
        val historical = ExtraCsvHistoryParser.parse(
            "Country,League,Season,Date,Home,Away,HG,AG\n" +
                "Brazil,Serie A,2025,20/11/2025,Botafogo RJ,Flamengo RJ,2,0",
            competition,
            2025,
        ).single()
        assertEquals(historical.homeTeamKey, current.home.providerKey)
        assertEquals(historical.awayTeamKey, current.away.providerKey)
    }

    @Test
    fun `MLS aliases merge without changing other competition identities`() {
        assertEquals(ExpandedTeamIdentity.key("USA1", "LAFC"),
            ExpandedTeamIdentity.key("USA1", "Los Angeles FC"))
        assertEquals(ExpandedTeamIdentity.key("USA1", "New York City FC"),
            ExpandedTeamIdentity.key("USA1", "New York City Football Club"))
        assertEquals(ExpandedTeamIdentity.key("JPN1", "Urawa Reds"),
            ExpandedTeamIdentity.key("JPN1", "Urawa Red Diamonds"))
    }
}
