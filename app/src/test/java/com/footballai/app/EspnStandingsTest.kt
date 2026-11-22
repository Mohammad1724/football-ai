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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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

    @Test
    fun `mapped CSV leagues are allowed for standings without expanding fixture polling`() {
        assertTrue(EspnOpenProvider.supportsStandingsKey("aus.1"))
        assertTrue(EspnOpenProvider.supportsStandingsKey("sco.2"))
        assertTrue(EspnOpenProvider.supportsStandingsKey("eng.1"))
        assertFalse(EspnOpenProvider.supportsStandingsKey("unknown.league"))
        assertFalse(EspnOpenProvider.COMPETITIONS.any { it.key == "aus.1" })
    }

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
        // The columns a table is read by, taken from the same named list. "pointsFor" and
        // "pointsAgainst" are goals in this sport, which the measured document confirms:
        // 29 for and 9 against sit on the row whose difference is 20.
        assertEquals(6, rows[0].wins)
        assertEquals(3, rows[0].draws)
        assertEquals(0, rows[0].losses)
        assertEquals(29, rows[0].goalsFor)
        assertEquals(9, rows[0].goalsAgainst)
        // Dortmund's row publishes none of them: absent, not zero.
        assertNull(rows[1].wins)
        assertNull(rows[1].goalsFor)
    }

    @Test
    fun `a row without a rank is not placed by guesswork`() {
        val result = provider.parseStandings(payload)
        assertIs<Outcome.Success<List<RemoteStandingRow>>>(result)
        // Freiburg carries points but no rank, so it cannot be placed in the table.
        assertEquals(listOf("132", "124"), result.value.map { it.teamKey })
    }

    @Test
    fun `a table row carries the crest the document publishes`() {
        val withLogos = """
            {"children":[{"standings":{"season":2026,"entries":[
              {"team":{"id":"132","displayName":"Bayern Munich","logos":[
                {"href":"https://a.espncdn.com/i/teamlogos/soccer/500-dark/132.png","rel":["dark"]},
                {"href":"https://a.espncdn.com/i/teamlogos/soccer/500/132.png","rel":["full","default"]}
              ]},"stats":[{"name":"rank","value":1.0}]}
            ]}}]}
        """.trimIndent()
        val result = provider.parseStandings(withLogos)
        assertIs<Outcome.Success<List<RemoteStandingRow>>>(result)
        // The default crest, not the dark variant: the badge is drawn on a light circle.
        assertEquals("https://a.espncdn.com/i/teamlogos/soccer/500/132.png", result.value.single().crestUrl)
    }

    @Test
    fun `a row without a published crest keeps no crest rather than a guessed one`() {
        val result = provider.parseStandings(payload)
        assertIs<Outcome.Success<List<RemoteStandingRow>>>(result)
        assertEquals(listOf(null, null), result.value.map { it.crestUrl })
    }

    @Test
    fun `an unreadable document is an error, not an empty table`() {
        val result = provider.parseStandings("{\"children\":\"not a list\"}")
        assertIs<Outcome.Failure>(result)
    }
}
