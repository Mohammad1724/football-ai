package com.footballai.app

import com.footballai.app.domain.model.ComputedTable
import com.footballai.app.domain.model.TableResults
import com.footballai.app.domain.model.TableVenue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TableResultsTest {
    private fun dated(date: String, home: String, away: String, hg: Int = 1, ag: Int = 0) =
        TableResults.DatedResult(Instant.parse(date).toEpochMilli(), ComputedTable.Result(home, away, hg, ag))

    @Test fun archiveSeasonsAreScopedToKnownLeagueCalendars() {
        val spring = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()
        val autumn = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
        assertEquals(2026, TableResults.archiveSeason("BRA1", spring))
        assertEquals(2026, TableResults.archiveSeason("BRA1", autumn))
        assertEquals(2025, TableResults.archiveSeason("D1", spring))
        assertEquals(2026, TableResults.archiveSeason("D1", autumn))
        assertNull(TableResults.archiveSeason("UNKNOWN", spring))
    }

    @Test fun mergesCopiesWithoutDroppingSimultaneousOrRepeatFixtures() {
        val fixture = dated("2026-11-04T15:00:00Z", "a", "b", 2, 0)
        val copy = dated("2026-11-04T12:00:00Z", "a", "b", 1, 0)
        val simultaneous = dated("2026-11-04T15:00:00Z", "c", "d")
        val rematch = dated("2026-11-11T15:00:00Z", "a", "b")
        val results = TableResults.merge(listOf(fixture), listOf(copy, simultaneous, rematch))
        assertEquals(3, results.size)
        assertEquals(2, results.first().homeGoals)
        val all = ComputedTable.rows(results)
        assertEquals(setOf("a", "b", "c", "d"), all.map { it.teamId }.toSet())
        assertEquals(2, all.single { it.teamId == "a" }.played)
        assertEquals(2, ComputedTable.rows(results, TableVenue.HOME).single { it.teamId == "a" }.played)
    }
}
