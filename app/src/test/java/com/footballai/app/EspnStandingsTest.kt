package com.footballai.app

import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.remote.EspnOpenProvider
import com.footballai.app.data.remote.QuotaGuard
import com.footballai.app.data.remote.QuotaStore
import com.footballai.app.data.provider.RemoteStandingRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The table comes from the same undocumented endpoint as the fixtures, so its mapping is
 * pinned by a captured document. A row without a rank is dropped rather than placed, and
 * the goal and points columns are read by name because ESPN returns them in a list.
 */
class EspnStandingsTest {

    private val provider = EspnOpenProvider(
        client = com.footballai.app.data.remote.ApiClient(
            QuotaGuard(
                store = object : QuotaStore {
                    override suspend fun readCounter(providerId: String): Pair<Long, Int> = 0L to 0
                    override suspend fun writeCounter(providerId: String, dayEpochMs: Long, count: Int) = Unit
                },
                clock = TimeProvider.System,
            ),
            maxRetries = 0,
            sleeper = {},
        ),
        clock = object : TimeProvider {
            override fun nowEpochMs(): Long = 1_794_614_400_000L
        },
    )

    private val payload = """
        {"children":[{"standings":{"season":2026,"entries":[
          {"team":{"id":"132","displayName":"Bayern Munich"},"stats":[
            {"name":"gamesPlayed","value":9.0},
            {"name":"losses","value":0.0},
            {"name":"pointDifferential","value":20.0},
            {"name":"points","value":21.0},
            {"name":"pointsAgainst","value":9.0},
            {"name":"pointsFor","value":29.0},
            {"name":"ties","value":3.0},
            {"name":"wins","value":6.0},
            {"name":"rank","value":1.0}
          ]},
          {"team":{"id":"124","displayName":"Borussia Dortmund"},"stats":[
            {"name":"gamesPlayed","value":9.0},
            {"name":"pointDifferential","value":9.0},
            {"name":"points","value":21.0},
            {"name":"rank","value":2.0}
          ]},
          {"team":{"id":"126","displayName":"SC Freiburg"},"stats":[
            {"name":"points","value":19.0}
          ]}
        ]}}]}
    """.trimIndent()

    @Test
    fun `the table is read by name and keeps the published order`() {
        val result = provider.parseStandings(payload)
        assertIs<Outcome.Success<List<RemoteStandingRow>>>(result)
        val rows = result.value
        assertEquals(2, rows.size)
        assertEquals(1, rows[0].rank)
        assertEquals("132", rows[0].teamKey)
        assertEquals(21, rows[0].points)
        assertEquals(9, rows[0].played)
        assertEquals(20, rows[0].goalDifference)
        assertEquals(2, rows[1].rank)
    }

    @Test
    fun `a row without a rank is not placed by guesswork`() {
        val result = provider.parseStandings(payload)
        assertIs<Outcome.Success<List<RemoteStandingRow>>>(result)
        // Freiburg carries points but no rank, so it cannot be placed in the table.
        assertEquals(listOf("132", "124"), result.value.map { it.teamKey })
    }

    @Test
    fun `an unreadable document is an error, not an empty table`() {
        val result = provider.parseStandings("{\"children\":\"not a list\"}")
        assertIs<Outcome.Failure>(result)
    }
}
