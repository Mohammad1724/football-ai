package com.footballai.app.domain.model

import java.time.Instant
import java.time.ZoneOffset

/** Merge results for one explicitly identified season, preferring fixtures over archive copies. */
internal object TableResults {
    data class DatedResult(val kickoff: Long, val result: ComputedTable.Result)

    // Only calendars we can identify safely for legacy archive rows (which have no season).
    // Other providers/leagues are deliberately excluded rather than mixing seasons.
    private val calendarYear = setOf("BRA1", "USA1", "FIN1", "NOR1", "SWE1")
    private val julyToJune = setOf(
        "E0", "E1", "E2", "E3", "EC", "SC0", "SC1", "SC2", "SC3",
        "D1", "D2", "I1", "I2", "SP1", "SP2", "F1", "F2", "N1", "B1", "P1", "T1", "G1", "DEN1",
    )

    fun archiveSeason(league: String, kickoff: Long): Int? {
        val date = Instant.ofEpochMilli(kickoff).atZone(ZoneOffset.UTC)
        return when (league) {
            in calendarYear -> date.year
            in julyToJune -> if (date.monthValue >= 7) date.year else date.year - 1
            else -> null
        }
    }

    fun merge(fixtures: List<DatedResult>, archive: List<DatedResult>): List<ComputedTable.Result> {
        // CSV sources can disagree about kickoff time. A pair can play again on another date,
        // while many unrelated matches can kick off at the same instant.
        fun key(row: DatedResult): Triple<String, String, java.time.LocalDate> = Triple(
            row.result.homeTeamId, row.result.awayTeamId,
            Instant.ofEpochMilli(row.kickoff).atZone(java.time.ZoneId.of("Europe/London")).toLocalDate(),
        )
        return (fixtures + archive).distinctBy(::key).map { it.result }
    }
}
