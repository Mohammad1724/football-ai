package com.footballai.app

import com.footballai.app.core.TimeProvider
import com.footballai.app.data.remote.ApiClient
import com.footballai.app.data.remote.QuotaGuard
import com.footballai.app.data.remote.QuotaStore
import com.footballai.app.data.remote.SportsDbCrests
import com.footballai.app.domain.catalog.TeamNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SportsDbCrestsTest {
    private val client = SportsDbCrests(ApiClient(QuotaGuard(object : QuotaStore {
        override suspend fun readCounter(providerId: String): Pair<Long, Int> = 0L to 0
        override suspend fun writeCounter(providerId: String, dayEpochMs: Long, count: Int) = Unit
    }, TimeProvider.System)))

    private val colwyn = """{"teams":[{"strTeam":"Colwyn Bay","strTeamAlternate":"Colwyn Bay Football Club, CBFC","strSport":"Soccer","strGender":"Male","strBadge":"https://r2.thesportsdb.com/images/media/team/badge/a.png"}]}"""

    @Test fun `published exact name and alias produce badge`() {
        val url = "https://r2.thesportsdb.com/images/media/team/badge/a.png"
        assertEquals(url, client.parse(colwyn, "Colwyn Bay", "MEN"))
        assertEquals(url, client.parse(colwyn, "Colwyn Bay Football Club", "MEN"))
    }

    @Test fun `a search answer is the club only when one club can be meant`() {
        val url = "https://r2.thesportsdb.com/images/media/team/badge/a.png"
        // The endpoint is a search: "Billericay" answers "Billericay Town" and that is the
        // club the feed means. A word is compared word by word, never as a substring.
        val billericay = """{"teams":[{"strTeam":"Billericay Town","strSport":"Soccer","strGender":"Male","strBadge":"https://r2.thesportsdb.com/b.png"}]}"""
        assertEquals("https://r2.thesportsdb.com/b.png", client.parse(billericay, "Billericay", "MEN"))
        assertEquals(url, client.parse(colwyn, "Colwyn", "MEN"))
        // Two different clubs that both start with the query are not a decision.
        val twins = """{"teams":[
            {"strTeam":"Billericay Town","strSport":"Soccer","strGender":"Male","strBadge":"https://r2.thesportsdb.com/b1.png"},
            {"strTeam":"Billericay Wanderers","strSport":"Soccer","strGender":"Male","strBadge":"https://r2.thesportsdb.com/b2.png"}]}"""
        assertNull(client.parse(twins, "Billericay", "MEN"))
        // A club that merely contains the query's letters is still not the club.
        assertNull(client.parse(colwyn, "Colwy", "MEN"))
    }

    @Test fun `substring reserve other sports wrong gender and untrusted URLs are rejected`() {
        assertNull(client.parse(colwyn, "Colwyn Bay", "WOMEN"))
        assertNull(client.parse(colwyn.replace("Soccer", "Rugby"), "Colwyn Bay", "MEN"))
        assertNull(client.parse(colwyn.replace("https://r2.thesportsdb.com", "https://other.example"), "Colwyn Bay", "MEN"))
        assertNull(client.parse(colwyn.replace("Colwyn Bay\"", "Colwyn Bay B\""), "Colwyn Bay", "MEN"))
        assertNull(client.parse("{\"teams\":null}", "Colwyn Bay", "MEN"))
        assertNull(client.parse("garbage", "Colwyn Bay", "MEN"))
    }

    @Test fun `a record without a gender is a men's record and nothing else`() {
        val url = "https://r2.thesportsdb.com/images/media/team/badge/a.png"
        // TheSportsDB leaves strGender out on older records; the fixture itself says men.
        val unclassified = """{"teams":[{"strTeam":"Saarbrücken","strSport":"Soccer","strBadge":"$url"}]}"""
        assertEquals(url, client.parse(unclassified, "1. FC Saarbrücken", "MEN"))
        assertNull(client.parse(unclassified, "1. FC Saarbrücken", "WOMEN"))
        // An explicit gender still has to match.
        assertNull(client.parse(unclassified.replace("\"strSport\":\"Soccer\"", "\"strSport\":\"Soccer\",\"strGender\":\"Female\""), "Saarbrücken", "MEN"))
    }

    @Test fun `duplicate exact results are not selected`() {
        val entry = """{"strTeam":"Colwyn Bay","strSport":"Soccer","strGender":"Male","strBadge":"https://r2.thesportsdb.com/a.png"}"""
        assertNull(client.parse("{\"teams\":[$entry,$entry]}", "Colwyn Bay", "MEN"))
    }

    @Test fun `verified Open Matchday aliases reach canonical query`() {
        assertEquals("Bayern Munich", TeamNames.canonicalName("FC Bayern München"))
        assertEquals("Espanyol", TeamNames.canonicalName("RCD Espanyol de Barcelona"))
    }
    @Test fun `Polish exact name keeps stroked L when matching`() {
        val payload = """{"teams":[{"strTeam":"ŁKS Łódź","strSport":"Soccer","strGender":"Male","strBadge":"https://r2.thesportsdb.com/a.png"}]}"""
        assertEquals("https://r2.thesportsdb.com/a.png", client.parse(payload, "LKS Lodz", "MEN"))
        assertNull(client.parse(payload, "KS Lodz", "MEN"))
    }
}
