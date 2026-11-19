package com.footballai.app

import com.footballai.app.domain.model.Competition
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.TeamRef
import com.footballai.engine.model.TeamKind
import com.footballai.app.ui.viewmodel.MatchFilters
import com.footballai.app.ui.viewmodel.MatchKindFilter
import com.footballai.app.ui.viewmodel.MatchGenderFilter
import com.footballai.app.ui.viewmodel.MatchEmptyReason
import com.footballai.app.ui.viewmodel.MatchesUiState
import com.footballai.app.ui.viewmodel.MatchesViewModel
import com.footballai.app.ui.screens.matchSections
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class MatchesViewModelTest {

    @Test
    fun `recent match windows use local calendar days`() {
        val zone = ZoneId.of("Asia/Tehran")
        val now = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()

        val (start, end) = MatchesViewModel.localDayWindow(now, 0, zone)

        assertEquals(Instant.parse("2026-11-10T20:30:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-11-11T20:30:00Z").toEpochMilli(), end)
    }

    @Test
    fun `two day offset selects the complete earlier day`() {
        val zone = ZoneId.of("Asia/Tehran")
        val now = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()

        val (start, end) = MatchesViewModel.localDayWindow(now, -2, zone)

        assertEquals(Instant.parse("2026-11-08T20:30:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-11-09T20:30:00Z").toEpochMilli(), end)
    }

    @Test
    fun `future offset selects the complete following day`() {
        val zone = ZoneId.of("Asia/Tehran")
        val now = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()

        val (start, end) = MatchesViewModel.localDayWindow(now, 1, zone)

        assertEquals(Instant.parse("2026-11-11T20:30:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-11-12T20:30:00Z").toEpochMilli(), end)
    }

    @Test
    fun `calendar date outside shortcut strip gets a local day offset`() {
        val zone = ZoneId.of("Asia/Tehran")
        val now = Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()
        val selected = Instant.parse("2026-12-19T20:30:00Z").toEpochMilli()

        assertEquals(39, MatchesViewModel.dayOffsetFor(now, selected, zone))
        val (start, end) = MatchesViewModel.localDayWindow(now, 39, zone)
        assertTrue(selected in start until end)
    }

    @Test
    fun `local search finds competitions teams and matches case insensitively`() {
        val arsenal = TeamRef("arsenal", "Arsenal")
        val chelsea = TeamRef("chelsea", "Chelsea")
        val match = MatchSummary(
            id = "football-data-co-uk:1",
            competitionId = "epl",
            competitionName = "Premier League",
            competitionEmblemUrl = null,
            home = arsenal,
            away = chelsea,
            kickoffEpochMs = 1L,
            status = MatchStatus.SCHEDULED,
            minute = null,
            homeGoals = null,
            awayGoals = null,
            lastSyncedAt = 1L,
            hasStatistics = false,
            hasLineups = false,
            hasEvents = false,
            hasOdds = false,
        )
        val competition = Competition("epl", "Premier League", "England", null, 2026, "football-data-co-uk", true)

        val teamResults = MatchesViewModel.buildSearchResults("ArSeNaL", listOf(match), listOf(competition), true)
        assertEquals(listOf(arsenal), teamResults.teams)
        assertEquals(listOf(match), teamResults.matches)
        val leagueResults = MatchesViewModel.buildSearchResults("england", listOf(match), listOf(competition), true)
        assertEquals(listOf(competition), leagueResults.competitions)
        assertTrue(MatchesViewModel.buildSearchResults(" ", listOf(match), listOf(competition), true).isEmpty)
    }

    @Test
    fun `search finds Persian league names in competitions and fixtures respecting local scope`() {
        val competition = Competition("espn-open:league:esp.1", "LaLiga", "Spain", null, 2026, "espn-open", true)
        val match = MatchSummary(
            id = "espn-open:1", competitionId = competition.id, competitionName = competition.name,
            competitionEmblemUrl = null, home = TeamRef("home", "Barcelona"), away = TeamRef("away", "Real Madrid"),
            kickoffEpochMs = 1L, status = MatchStatus.SCHEDULED, minute = null, homeGoals = null, awayGoals = null,
            lastSyncedAt = 1L, hasStatistics = false, hasLineups = false, hasEvents = false, hasOdds = false,
        )
        val persian = MatchesViewModel.buildSearchResults("لالیگا", listOf(match), listOf(competition), false)
        assertEquals(listOf(competition), persian.competitions)
        assertEquals(listOf(match), persian.matches)
        val arabicSpelling = MatchesViewModel.buildSearchResults("لاليگا", listOf(match), listOf(competition), false)
        assertEquals(listOf(competition), arabicSpelling.competitions)
        assertEquals(listOf(match), arabicSpelling.matches)
        assertTrue(MatchesViewModel.buildSearchResults("لالیگا", listOf(match), listOf(competition), true).isEmpty)
        assertTrue(MatchesViewModel.buildSearchResults("---", listOf(match), listOf(competition), false).isEmpty)
    }

    @Test
    fun `day windows respect daylight saving transitions`() {
        val zone = ZoneId.of("Europe/London")
        val now = Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()

        val (start, end) = MatchesViewModel.localDayWindow(now, -1, zone)

        assertEquals(Instant.parse("2026-10-24T23:00:00Z").toEpochMilli(), start)
        assertEquals(Instant.parse("2026-10-26T00:00:00Z").toEpochMilli(), end)
        assertEquals(25 * 60 * 60 * 1000L, end - start)
    }
    @Test
    fun `filter count and empty reasons reflect active filters and stored matches`() {
        assertEquals(0, MatchFilters().activeCount)
        val filters = MatchFilters("league", true, true, MatchKindFilter.NATIONAL, MatchGenderFilter.WOMEN)
        assertEquals(5, filters.activeCount)
        assertEquals(3, filters.copy(competitionId = null, liveOnly = false).activeCount)
        assertEquals(MatchEmptyReason.NO_DATA, MatchesUiState().emptyReason)
        assertEquals(MatchEmptyReason.FILTERED_NO_DATA, MatchesUiState(liveOnly = true).emptyReason)
        assertEquals(MatchEmptyReason.FILTERED, MatchesUiState(liveOnly = true, availableMatchesCount = 1).emptyReason)
    }

    @Test
    fun `all filter choices are applied together without changing their meaning`() {
        val nationalTeam = TeamRef("a", "Team A", kind = TeamKind.NATIONAL)
        val match = MatchSummary(
            id = "match", competitionId = "league", competitionName = "League", competitionEmblemUrl = null,
            home = nationalTeam, away = TeamRef("b", "Team B"), kickoffEpochMs = 1,
            status = MatchStatus.LIVE, minute = null, homeGoals = 1, awayGoals = 0, lastSyncedAt = 1,
            hasStatistics = false, hasLineups = false, hasEvents = false, hasOdds = false, gender = "WOMEN",
        )
        val filters = MatchFilters("league", true, true, MatchKindFilter.NATIONAL, MatchGenderFilter.WOMEN)
        assertTrue(filters.accepts(match, setOf("league")))
        assertFalse(filters.accepts(match, emptySet()))
        assertFalse(filters.accepts(match.copy(status = MatchStatus.SCHEDULED), setOf("league")))
        assertFalse(filters.accepts(match.copy(gender = "MEN"), setOf("league")))
        assertFalse(filters.accepts(match.copy(home = TeamRef("a", "Club")), setOf("league")))
        assertFalse(filters.accepts(match.copy(competitionId = "other"), setOf("other")))
        assertTrue(MatchFilters().accepts(match, emptySet()))
    }

    @Test
    fun `favourite matches remain both featured and in the complete league group`() {
        val match = MatchSummary(
            id = "match", competitionId = "league", competitionName = "League", competitionEmblemUrl = null,
            home = TeamRef("a", "Team A"), away = TeamRef("b", "Team B"), kickoffEpochMs = 1,
            status = MatchStatus.SCHEDULED, minute = null, homeGoals = null, awayGoals = null,
            lastSyncedAt = 1, hasStatistics = false, hasLineups = false, hasEvents = false, hasOdds = false,
        )
        val league = Competition("league", "League", null, null, 2026, "provider", true)
        val sections = matchSections(MatchesUiState(matches = listOf(match), favourites = listOf(match), competitions = listOf(league)))
        assertEquals(listOf(match), sections.favourites)
        assertEquals(listOf(listOf(match)), sections.groups)
    }

}
