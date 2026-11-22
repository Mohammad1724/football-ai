package com.footballai.app

import com.footballai.app.core.ErrorCode
import com.footballai.app.data.local.mapper.Ids
import com.footballai.app.data.local.mapper.toEntity
import com.footballai.app.data.provider.RemoteMatch
import com.footballai.app.data.provider.RemoteTeam
import com.footballai.app.data.repository.PredictionPolicy
import com.footballai.app.domain.model.MatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PredictionPolicyTest {
    private val now = 1_800_000_000_000L
    private fun fixture() = RemoteMatch(
        "fixture", "E0", "Premier League", 2026, 1, now + 60000,
        MatchStatus.SCHEDULED, null, RemoteTeam("arsenal", "Arsenal"), RemoteTeam("chelsea", "Chelsea"),
        null, null, null, null, null, null,
    ).toEntity("football-data-co-uk", Ids.team("football-data-co-uk", "arsenal"),
        Ids.team("football-data-co-uk", "chelsea"), Ids.competition("football-data-co-uk", "E0"), now)

    @Test fun `future scheduled fixture is allowed`() { assertNull(PredictionPolicy.error(fixture(), now)) }
    @Test fun `scheduled fixture at kickoff cannot become a new prematch prediction`() {
        assertEquals(ErrorCode.INVALID_MATCH_STATE, PredictionPolicy.error(fixture().copy(kickoffEpochMs = now), now))
    }
    @Test fun `finished cancelled postponed and unknown states are rejected`() {
        for (status in listOf("FINISHED", "CANCELLED", "POSTPONED", "UNKNOWN", "SUSPENDED")) {
            assertEquals(ErrorCode.INVALID_MATCH_STATE, PredictionPolicy.error(fixture().copy(status = status), now))
        }
    }
    @Test fun `unknown or extra time score scope is rejected`() {
        for (scope in listOf("UNKNOWN", "EXTRA_TIME_OR_PENALTIES", "ADMINISTRATIVE", "LEGACY_UNVERIFIED")) {
            assertEquals(ErrorCode.INVALID_MATCH_STATE, PredictionPolicy.error(fixture().copy(scoreScope = scope), now))
        }
    }
    @Test fun `stale live score is rejected`() {
        val live = fixture().copy(status = "LIVE", minute = 60, homeGoals = 0, awayGoals = 1)
        assertNull(PredictionPolicy.error(live, now))
        assertEquals(ErrorCode.STALE_DATA, PredictionPolicy.error(live.copy(lastSyncedAt = now - 120001), now))
        assertEquals(ErrorCode.STALE_DATA, PredictionPolicy.error(live.copy(homeGoals = null), now))
    }
    @Test fun `missing minute and unknown stoppage horizon are rejected`() {
        val live = fixture().copy(status = "LIVE", homeGoals = 0, awayGoals = 0)
        assertEquals(ErrorCode.UNKNOWN_MATCH_TIME, PredictionPolicy.error(live, now))
        assertEquals(ErrorCode.UNKNOWN_MATCH_TIME, PredictionPolicy.error(live.copy(minute = 90), now))
    }
    @Test fun `half time is exactly minute 45 independent of wall clock`() {
        val live = fixture().copy(status = "HALF_TIME", homeGoals = 0, awayGoals = 0)
        assertNull(PredictionPolicy.error(live, now))
        assertEquals(45, PredictionPolicy.minute(live))
    }
    @Test fun `live snapshot interval is measured from last persisted timestamp`() {
        assertTrue(PredictionPolicy.shouldStoreLive(now, 0))
        for (minutes in 1..14) assertFalse(PredictionPolicy.shouldStoreLive(now + minutes * 60000, now))
        assertTrue(PredictionPolicy.shouldStoreLive(now + 15 * 60000, now))
        assertTrue(PredictionPolicy.shouldStoreLive(now + 60000, now, force = true))
    }
}
