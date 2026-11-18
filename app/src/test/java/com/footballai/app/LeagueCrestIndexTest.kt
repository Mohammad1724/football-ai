package com.footballai.app

import com.footballai.app.core.TimeProvider
import com.footballai.app.data.remote.ApiClient
import com.footballai.app.data.remote.LeagueCrestIndex
import com.footballai.app.data.remote.LeagueCrestTeam
import com.footballai.app.data.remote.QuotaGuard
import com.footballai.app.data.remote.QuotaStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test fun `known alias works without making substring matches`() {
        val team = listOf(LeagueCrestTeam("1", listOf("Bayer Leverkusen"), "https://a.espncdn.com/a.png"))
        assertEquals("https://a.espncdn.com/a.png", LeagueCrestIndex.match(team, "Bayer 04 Leverkusen", "Bayer Leverkusen"))
        assertNull(LeagueCrestIndex.match(team, "Bayer"))
        assertNull(LeagueCrestIndex.match(team, "Bayer Leverkusen II"))
    }
}
