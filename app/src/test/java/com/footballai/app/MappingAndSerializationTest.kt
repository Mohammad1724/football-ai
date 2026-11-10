package com.footballai.app

import com.footballai.app.data.local.Serialized
import com.footballai.app.data.local.mapper.Ids
import com.footballai.app.data.local.mapper.parseStatus
import com.footballai.app.data.provider.RemoteMatch
import com.footballai.app.data.provider.RemoteTeam
import com.footballai.app.data.local.mapper.toEntity
import com.footballai.app.domain.model.ExplanationView
import com.footballai.app.domain.model.LiveSnapshot
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.QualityView
import com.footballai.app.domain.model.QualityLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MappingAndSerializationTest {

    @Test
    fun `identifiers are stable and namespaced per provider`() {
        assertEquals("api-football:match:1234", Ids.match("api-football", "1234"))
        assertEquals("football-data-org:league:PL", Ids.competition("football-data-org", "PL"))
        assertEquals(Ids.team("provider", "7"), Ids.team("provider", "7"))
        assertTrue(Ids.team("a", "7") != Ids.team("b", "7"))
    }

    @Test
    fun `unknown status strings do not crash the mapper`() {
        assertEquals(MatchStatus.FINISHED, parseStatus("FINISHED"))
        assertEquals(MatchStatus.UNKNOWN, parseStatus("SOMETHING_NEW"))
        assertEquals(MatchStatus.UNKNOWN, parseStatus(""))
    }

    @Test
    fun `remote match mapping keeps the provider identity`() {
        val remote = RemoteMatch(
            providerKey = "42",
            competitionKey = "39",
            competitionName = "Premier League",
            season = 2024,
            matchday = 5,
            kickoffEpochMs = 1_700_000_000_000L,
            status = MatchStatus.SCHEDULED,
            minute = null,
            home = RemoteTeam("10", "Arsenal", "ARS"),
            away = RemoteTeam("20", "Chelsea", "CHE"),
            homeGoals = null,
            awayGoals = null,
            homeHalfTimeGoals = null,
            awayHalfTimeGoals = null,
            venue = "Emirates Stadium",
            odds = null,
        )
        val entity = remote.toEntity(
            providerId = "api-football",
            homeTeamId = Ids.team("api-football", "10"),
            awayTeamId = Ids.team("api-football", "20"),
            competitionId = Ids.competition("api-football", "39"),
            now = 1_700_000_500_000L,
        )
        assertEquals("api-football:match:42", entity.id)
        assertEquals("SCHEDULED", entity.status)
        assertEquals(1_700_000_000_000L, entity.kickoffEpochMs)
        assertEquals(null, entity.homeGoals)
        assertTrue(!entity.hasStatistics)
    }

    @Test
    fun `explanations survive a json round trip`() {
        val explanations = listOf(
            ExplanationView("FAVOURITE_HOME", 0.8, mapOf("attack" to "1.42")),
            ExplanationView("HOME_ADVANTAGE", 0.35, emptyMap()),
        )
        val encoded = Serialized.encodeExplanations(explanations)
        val decoded = Serialized.decodeExplanations(encoded)
        assertEquals(2, decoded.size)
        assertEquals("FAVOURITE_HOME", decoded[0].code)
        assertEquals("1.42", decoded[0].params["attack"])
    }

    @Test
    fun `quality notes round trip`() {
        val quality = QualityView(
            level = QualityLevel.MEDIUM,
            completeness = 0.62,
            missing = listOf("LINEUPS", "INJURIES"),
            notes = listOf("MODEL_NOT_CALIBRATED"),
            historicalMatchesUsed = 210,
        )
        val decoded = Serialized.decodeQuality(Serialized.encodeQuality(quality))
        assertEquals(listOf("LINEUPS", "INJURIES"), decoded.missing)
        assertEquals(listOf("MODEL_NOT_CALIBRATED"), decoded.notes)
    }

    @Test
    fun `live snapshot round trip preserves goal expectations`() {
        val snapshot = LiveSnapshot(
            minute = 63,
            homeGoals = 1,
            awayGoals = 1,
            remainingMinutes = 27,
            remainingHomeExpectedGoals = 0.72,
            remainingAwayExpectedGoals = 0.51,
            nextGoalHome = 0.31,
            nextGoalAway = 0.22,
            noMoreGoals = 0.47,
            remainingOver = mapOf(0.5 to 0.55, 1.5 to 0.18),
            adjustments = listOf("shot_intensity"),
        )
        val decoded = assertNotNull(Serialized.decodeLiveSnapshot(Serialized.encodeLiveSnapshot(snapshot)))
        assertEquals(63, decoded.minute)
        assertEquals(0.72, decoded.remainingHomeExpectedGoals, 1e-9)
        assertEquals(2, decoded.remainingOver.size)
        assertEquals(listOf("shot_intensity"), decoded.adjustments)
    }

    @Test
    fun `decoding corrupt json never throws`() {
        assertTrue(Serialized.decodeExplanations("not json").isEmpty())
        assertTrue(Serialized.decodeExplanations(null).isEmpty())
        assertNull(Serialized.decodeLiveSnapshot("{}"))
        assertEquals(emptyList(), Serialized.decodeQuality("{oops}").missing)
    }
}
