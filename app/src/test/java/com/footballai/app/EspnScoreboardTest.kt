package com.footballai.app

import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.remote.ApiClient
import com.footballai.app.data.remote.EspnCompetitionInfo
import com.footballai.app.data.remote.EspnOpenProvider
import com.footballai.app.data.remote.QuotaGuard
import com.footballai.app.data.remote.QuotaStore
import com.footballai.app.domain.model.MatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The worldwide source is an undocumented public endpoint, so its mapping is pinned by
 * tests against a captured payload. Anything the app cannot read must stay null or be
 * rejected, never become zero or a guessed result.
 */
class EspnScoreboardTest {

    private val provider = EspnOpenProvider(
        client = ApiClient(
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
            // 2026-11-14T00:00Z, the day the reported bug was reproduced.
            override fun nowEpochMs(): Long = 1_794_614_400_000L
        },
    )

    private val nations = EspnCompetitionInfo(
        key = "uefa.nations",
        name = "UEFA Nations League",
        country = "Europe",
        emblemUrl = null,
        knockout = false,
    )

    private val payload = """
        {
          "events": [
            {
              "id": "401861172",
              "date": "2026-11-15T14:00Z",
              "season": {"year": 2026, "slug": "league-phase"},
              "competitions": [{
                "date": "2026-11-15T14:00Z",
                "status": {"clock": 0.0, "type": {"name": "STATUS_SCHEDULED", "state": "pre", "completed": false}},
                "venue": {"fullName": "Alphamega Stadium"},
                "competitors": [
                  {"homeAway": "home", "score": "0", "team": {"id": "445", "displayName": "Cyprus", "abbreviation": "CYP", "logo": "https://a.espncdn.com/i/teamlogos/countries/500/cyp.png"}},
                  {"homeAway": "away", "score": "0", "team": {"id": "6775", "displayName": "Montenegro", "abbreviation": "MNE"}}
                ]
              }]
            },
            {
              "id": "401861162",
              "date": "2026-11-13T17:00Z",
              "season": {"year": 2026, "slug": "league-phase"},
              "competitions": [{
                "date": "2026-11-13T17:00Z",
                "status": {"clock": 5400.0, "type": {"name": "STATUS_FULL_TIME", "state": "post", "completed": true}},
                "competitors": [
                  {"homeAway": "home", "score": "2", "team": {"id": "483", "displayName": "Moldova"}, "linescores": [{"value": 1.0}]},
                  {"homeAway": "away", "score": "1", "team": {"id": "2619", "displayName": "Kazakhstan"}, "linescores": [{"value": 1.0}]}
                ]
              }]
            },
            {
              "id": "401999999",
              "date": "2026-11-16T19:00Z",
              "season": {"year": 2026, "slug": "knockout-phase"},
              "competitions": [{
                "date": "2026-11-16T19:00Z",
                "status": {"clock": 7200.0, "type": {"name": "STATUS_FINAL_AET", "state": "post", "completed": true}},
                "competitors": [
                  {"homeAway": "home", "score": "2", "team": {"id": "1", "displayName": "Team A"}},
                  {"homeAway": "away", "score": "2", "team": {"id": "2", "displayName": "Team B"}}
                ]
              }]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a scheduled fixture has no score at all`() {
        val result = provider.parse(payload, nations)
        val matches = assertIs<Outcome.Success<List<com.footballai.app.data.provider.RemoteMatch>>>(result).value
        val scheduled = matches.first { it.providerKey == "401861172" }
        assertEquals(MatchStatus.SCHEDULED, scheduled.status)
        assertNull(scheduled.homeGoals)
        assertNull(scheduled.awayGoals)
        assertEquals("Cyprus", scheduled.home.name)
        assertEquals("Montenegro", scheduled.away.name)
        assertEquals("Alphamega Stadium", scheduled.venue)
        assertEquals("uefa.nations", scheduled.competitionKey)
        assertEquals("REGULATION", scheduled.scoreScope)
        // 2026-11-15T14:00Z
        assertEquals(1_794_751_200_000L, scheduled.kickoffEpochMs)
    }

    @Test
    fun `a finished group fixture carries the regulation score and half-time line`() {
        val result = provider.parse(payload, nations)
        val matches = assertIs<Outcome.Success<List<com.footballai.app.data.provider.RemoteMatch>>>(result).value
        val finished = matches.first { it.providerKey == "401861162" }
        assertEquals(MatchStatus.FINISHED, finished.status)
        assertEquals(2, finished.homeGoals)
        assertEquals(1, finished.awayGoals)
        assertEquals(1, finished.homeHalfTimeGoals)
        assertEquals("REGULATION", finished.scoreScope)
    }

    @Test
    fun `an extra-time score is marked unverified so it cannot train a 90-minute market`() {
        val result = provider.parse(payload, nations)
        val matches = assertIs<Outcome.Success<List<com.footballai.app.data.provider.RemoteMatch>>>(result).value
        val extraTime = matches.first { it.providerKey == "401999999" }
        assertEquals("UNVERIFIED", extraTime.scoreScope)
        assertNull(extraTime.homeHalfTimeGoals)
    }

    @Test
    fun `an event without a date or without both teams is dropped instead of guessed`() {
        val partial = """
            {"events":[
              {"id":"1","competitions":[{"competitors":[]}]},
              {"id":"2","date":"2026-11-15T14:00Z","competitions":[{"date":"2026-11-15T14:00Z",
                "status":{"type":{"name":"STATUS_SCHEDULED","state":"pre"}},
                "competitors":[{"homeAway":"home","team":{"id":"9","displayName":"Only Home"}}]}]}
            ]}
        """.trimIndent()
        val result = provider.parse(partial, nations)
        val matches = assertIs<Outcome.Success<List<com.footballai.app.data.provider.RemoteMatch>>>(result).value
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `a timestamp without seconds still parses`() {
        // The live endpoint publishes "2026-11-15T14:00Z"; Instant.parse rejects it, and
        // dropping the event for that reason would have emptied the whole screen.
        val withSeconds = payload.replace("2026-11-15T14:00Z", "2026-11-15T14:00:00Z")
        val result = provider.parse(withSeconds, nations)
        val matches = assertIs<Outcome.Success<List<com.footballai.app.data.provider.RemoteMatch>>>(result).value
        assertEquals(1_794_751_200_000L, matches.first { it.providerKey == "401861172" }.kickoffEpochMs)
    }

    @Test
    fun `malformed json is a parse error, not an empty success`() {
        val result = provider.parse("{not json", nations)
        assertIs<Outcome.Failure>(result)
    }
}
