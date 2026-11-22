package com.footballai.app

import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.remote.ApiClient
import com.footballai.app.data.remote.QuotaGuard
import com.footballai.app.data.remote.QuotaStore
import com.footballai.app.data.remote.Varzesh3Provider
import com.footballai.app.domain.model.MatchStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class Varzesh3ProviderTest {
    private fun provider(server: MockWebServer): Varzesh3Provider {
        val store = object : QuotaStore {
            override suspend fun readCounter(providerId: String) = 0L to 0
            override suspend fun writeCounter(providerId: String, dayEpochMs: Long, count: Int) = Unit
        }
        val api = ApiClient(QuotaGuard(store, TimeProvider.System), maxRetries = 0)
        return Varzesh3Provider(api, TimeProvider.System, server.url("/").toString().trimEnd('/')) { date, time ->
            if (date == "1405/09/02" && time == "14:30") 1_700_000_000_000L else null
        }
    }

    @Test
    fun `extracts ids from links and does not invent results or kickoff times`() {
        MockWebServer().use { server ->
            val p = provider(server)
            val page = p.parsePage("""{"items":[{"round":"هفته 11","dates":[{"date":"1405/09/02","matches":[
                {"id":487004,"time":"14:30","status":0,"host":{"id":0,"link":"/football/team/7/مس","name":"مس کرمان"},"guest":{"id":0,"link":"/football/team/901725/بعثت","name":"بعثت کرمانشاه"}},
                {"id":487005,"time":"نامشخص","status":0,"host":{"id":7,"name":"مس"},"guest":{"id":13,"name":"سایپا"}}
            ]}]}],"_links":[]}""", "24", 2026) { date, time ->
                if (date == "1405/09/02" && time == "14:30") 1234L else null
            }
            assertEquals(1, page.matches.size)
            val match = page.matches.single()
            assertEquals("487004", match.providerKey)
            assertEquals("7", match.home.providerKey)
            assertEquals("901725", match.away.providerKey)
            assertEquals(11, match.matchday)
            assertEquals(MatchStatus.SCHEDULED, match.status)
            assertNull(match.homeGoals)
        }
    }

    @Test
    fun `discovers current season by league link and follows previous results`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when (request.path) {
                        "/v2.0/football/leagues/6" -> """{"tabs":[{"_links":[{"href":"https://web-api.varzesh3.ir/v2.0/football/leagues/6/seasons/903038/matches"}]}]}"""
                        "/v2.0/football/leagues/6/archive" -> """{"seasons":[{"id":903038,"title":"فصل 1405-1406","status":2}]}"""
                        "/v2.0/football/leagues/6/seasons/903038/matches" -> """{"hasMore":false,"items":[],"_links":[]}"""
                        "/v2.0/football/leagues/6/seasons/903038/results?skip=0" -> """{"hasPrev":true,"items":[],"_links":[{"rel":"prev","href":"https://web-api.varzesh3.ir/v2.0/football/leagues/6/seasons/903038/results?skip=24"}]}"""
                        "/v2.0/football/leagues/6/seasons/903038/results?skip=24" -> """{"hasPrev":false,"_links":[],"items":[{"round":"هفته 1","dates":[{"date":"1405/09/02","matches":[{"id":123,"time":"14:30","status":7,"goals":{"host":0,"guest":1},"host":{"id":6,"name":"پرسپولیس"},"guest":{"id":4,"name":"استقلال"}}]}]}]}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(body)
                }
            }
            val result = provider(server).matches("6", null, null, null)
            val matches = assertIs<Outcome.Success<List<com.footballai.app.data.provider.RemoteMatch>>>(result).value
            assertEquals(1, matches.size)
            assertEquals(MatchStatus.FINISHED, matches.single().status)
            assertEquals(0, matches.single().homeGoals)
            assertEquals(1, matches.single().awayGoals)
            assertEquals(2026, matches.single().season)
        }
    }
}
