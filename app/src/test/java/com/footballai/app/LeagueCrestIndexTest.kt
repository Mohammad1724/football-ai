package com.footballai.app

import com.footballai.app.core.TimeProvider
import com.footballai.app.data.remote.ApiClient
import com.footballai.app.data.remote.LeagueCrestIndex
import com.footballai.app.data.remote.LeagueCrestTeam
import com.footballai.app.data.remote.QuotaGuard
import com.footballai.app.data.remote.QuotaStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeagueCrestIndexTest {
    private val index = LeagueCrestIndex(ApiClient(QuotaGuard(object : QuotaStore {
        override suspend fun readCounter(providerId: String) = 0L to 0
        override suspend fun writeCounter(providerId: String, dayEpochMs: Long, count: Int) = Unit
    }, TimeProvider.System)))

    private val payload = """{"sports":[{"id":"600","leagues":[{"slug":"esp.1","teams":[
      {"team":{"id":"86","name":"Real Madrid","logos":[
        {"href":"https://a.espncdn.com/i/teamlogos/soccer/500-dark/86.png","rel":["dark"]},
        {"href":"https://a.espncdn.com/i/teamlogos/soccer/500/86.png","rel":["default"]}]}},
      {"team":{"id":"94","displayName":"Valencia","logos":[{"href":"https://a.espncdn.com/i/teamlogos/soccer/500/94.png","rel":["default"]}]}},
      {"team":{"id":"95","displayName":"Valencia B"}}
    ]}]}]}"""

    @Test fun `bulk parse matches several real club names and retains reserve distinction`() {
        val teams = index.parse(payload, "esp.1")!!
        assertEquals("https://a.espncdn.com/i/teamlogos/soccer/500/86.png", LeagueCrestIndex.match(teams, "Real Madrid"))
        assertEquals("https://a.espncdn.com/i/teamlogos/soccer/500/94.png", LeagueCrestIndex.match(teams, "Valencia CF"))
        assertNull(LeagueCrestIndex.match(teams, "Valencia B"))
        assertNull(LeagueCrestIndex.match(teams, "Real"))
    }

    @Test fun `ambiguous names cannot be matched even if only one has a badge`() {
        val teams = listOf(
            LeagueCrestTeam("1", listOf("City FC"), "https://a.espncdn.com/one.png"),
            LeagueCrestTeam("2", listOf("City"), null),
        )
        assertNull(LeagueCrestIndex.match(teams, "City"))
    }

    @Test fun `only expected league documents and published safe logo links are accepted`() {
        assertNull(index.parse(payload, "ger.1"))
        assertNull(index.parse("{}", "esp.1"))
        val bad = payload.replace("https://a.espncdn.com", "https://example.org")
        assertNull(LeagueCrestIndex.match(index.parse(bad, "esp.1")!!, "Real Madrid"))
    }

    @Test fun `legal forms are not part of a club name`() {
        // The pairs that kept Belgian, Portuguese and German clubs as two initials.
        val teams = listOf(
            LeagueCrestTeam("1", listOf("Gent"), "https://a.espncdn.com/g.png"),
            LeagueCrestTeam("2", listOf("Cercle Brugge"), "https://a.espncdn.com/c.png"),
            LeagueCrestTeam("3", listOf("Club Brugge"), "https://a.espncdn.com/b.png"),
            LeagueCrestTeam("4", listOf("Anderlecht"), "https://a.espncdn.com/an.png"),
        )
        assertEquals("https://a.espncdn.com/g.png", LeagueCrestIndex.match(teams, "KAA Gent"))
        assertEquals("https://a.espncdn.com/c.png", LeagueCrestIndex.match(teams, "Cercle Brugge KSV"))
        assertEquals("https://a.espncdn.com/an.png", LeagueCrestIndex.match(teams, "RSC Anderlecht"))
        // Brugge has two clubs; "Club" is a distinctive word, not a legal form.
        assertEquals("https://a.espncdn.com/b.png", LeagueCrestIndex.match(teams, "Club Brugge"))
        assertNull(LeagueCrestIndex.match(teams, "KV Kortrijk"))
    }

    @Test fun `the roster of a club's own league names it the way the source spells it`() {
        // Names taken from the live roster document, against names the feed publishes.
        val eredivisie = listOf(
            LeagueCrestTeam("139", listOf("Ajax Amsterdam"), "https://a.espncdn.com/ajax.png"),
            LeagueCrestTeam("148", listOf("PSV Eindhoven"), "https://a.espncdn.com/psv.png"),
            LeagueCrestTeam("142", listOf("Feyenoord Rotterdam"), "https://a.espncdn.com/fey.png"),
            LeagueCrestTeam("2566", listOf("Excelsior"), "https://a.espncdn.com/exc.png"),
            LeagueCrestTeam("146", listOf("Heerenveen"), "https://a.espncdn.com/hee.png"),
            LeagueCrestTeam("3735", listOf("Telstar"), "https://a.espncdn.com/tel.png"),
        )
        assertEquals("https://a.espncdn.com/ajax.png", LeagueCrestIndex.match(eredivisie, "Ajax"))
        assertEquals("https://a.espncdn.com/psv.png", LeagueCrestIndex.match(eredivisie, "PSV"))
        assertEquals("https://a.espncdn.com/fey.png", LeagueCrestIndex.match(eredivisie, "Feyenoord"))
        assertEquals("https://a.espncdn.com/exc.png", LeagueCrestIndex.match(eredivisie, "Excelsior Rotterdam"))
        assertEquals("https://a.espncdn.com/hee.png", LeagueCrestIndex.match(eredivisie, "sc Heerenveen"))
        // A club that is not in the roster is not guessed from a neighbour.
        assertNull(LeagueCrestIndex.match(eredivisie, "Willem II"))
    }

    @Test fun `a club found in the roster without a badge is known, not unknown`() {
        val roster = listOf(
            LeagueCrestTeam("1", listOf("Barry Town"), null),
            LeagueCrestTeam("2", listOf("TNS"), "https://a.espncdn.com/tns.png"),
        )
        val known = LeagueCrestIndex.lookup(roster, "Barry")
        assertNull(known.crestUrl)
        // Recognised: the source publishes this club and publishes no badge for it, so
        // asking the same source again would only spend a request.
        assertTrue(known.matched)
        val unknown = LeagueCrestIndex.lookup(roster, "Haverfordwest")
        assertFalse(unknown.matched)
        assertEquals("https://a.espncdn.com/tns.png", LeagueCrestIndex.lookup(roster, "TNS").crestUrl)
    }

    @Test fun `every mapped league is a documented ESPN slug and no key is repeated`() {
        val slugs = LeagueCrestIndex.LEAGUES.values
        assertEquals(slugs.size, slugs.toSet().size)
        slugs.forEach { slug -> assertTrue(Regex("[a-z]+\\.[0-9a-z._]+").matches(slug)) }
        assertEquals("eng.1", LeagueCrestIndex.LEAGUES["E0"])
        assertEquals("bel.1", LeagueCrestIndex.LEAGUES["B1"])
        assertEquals("por.1", LeagueCrestIndex.LEAGUES["P1"])
        assertEquals("jpn.1", LeagueCrestIndex.LEAGUES["JPN1"])
        // Verified against the endpoint: these answer, and the four that 404 stay out.
        assertEquals("sco.3", LeagueCrestIndex.LEAGUES["SC2"])
        assertEquals("sco.4", LeagueCrestIndex.LEAGUES["SC3"])
        assertEquals("eng.5", LeagueCrestIndex.LEAGUES["EC"])
        assertEquals("wal.1", LeagueCrestIndex.LEAGUES["WAL1"])
        assertNull(LeagueCrestIndex.LEAGUES["UKR1"])
        assertNull(LeagueCrestIndex.LEAGUES["CZE1"])
        assertNull(LeagueCrestIndex.LEAGUES["FIN1"])
    }

    @Test fun `known alias works without making substring matches`() {
        val team = listOf(LeagueCrestTeam("1", listOf("Bayer Leverkusen"), "https://a.espncdn.com/a.png"))
        assertEquals("https://a.espncdn.com/a.png", LeagueCrestIndex.match(team, "Bayer 04 Leverkusen", "Bayer Leverkusen"))
        assertNull(LeagueCrestIndex.match(team, "Bayer"))
        assertNull(LeagueCrestIndex.match(team, "Bayer Leverkusen II"))
    }
}
