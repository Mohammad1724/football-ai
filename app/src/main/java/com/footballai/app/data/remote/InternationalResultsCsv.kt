package com.footballai.app.data.remote

import com.footballai.app.domain.catalog.NationalTeamKeys
import java.time.LocalDate
import java.time.ZoneOffset

/** One finished international, as published by the archive. */
data class InternationalResult(
    val kickoffEpochMs: Long,
    val homeName: String,
    val awayName: String,
    val homeTeamId: String,
    val awayTeamId: String,
    val homeGoals: Int,
    val awayGoals: Int,
    val tournament: String,
    val neutralVenue: Boolean,
)

/**
 * Parser for the CC0 international-results archive
 * (`martj42/international_results`, columns
 * `date,home_team,away_team,home_score,away_score,tournament,city,country,neutral`).
 *
 * The file is large and written by hand over the years, so this is tolerant by design:
 * a row that cannot be understood is skipped and counted, never guessed at. Scores must
 * be integers — a missing score means the result is unknown, and an unknown result must
 * not enter a rating.
 *
 * A date carries no kickoff time in the archive. Matches are therefore placed at
 * 00:00 UTC of their day, which is the *earliest* possible instant for that fixture. A
 * rating computed "as of" some moment therefore never sees a result before the match
 * could have finished, which is what a walk-forward evaluation needs.
 */
object InternationalResultsCsv {

    data class Result(val rows: List<InternationalResult>, val skipped: Int, val total: Int)

    private const val COLUMNS = 9

    fun parse(text: String, sinceEpochMs: Long): Result {
        val rows = ArrayList<InternationalResult>(4096)
        var skipped = 0
        var total = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val fields = splitLine(line)
            if (fields == null || fields.size < COLUMNS) {
                // The header and any malformed line land here.
                if (!line.startsWith("date,")) skipped++
                continue
            }
            if (fields[0].equals("date", ignoreCase = true)) continue
            total++
            val row = toRow(fields, sinceEpochMs)
            if (row == null) skipped++ else rows.add(row)
        }
        return Result(rows, skipped, total)
    }

    private fun toRow(fields: List<String>, sinceEpochMs: Long): InternationalResult? {
        val date = runCatching { LocalDate.parse(fields[0].trim()) }.getOrNull() ?: return null
        val kickoff = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        if (kickoff < sinceEpochMs) return null
        val homeName = fields[1].trim()
        val awayName = fields[2].trim()
        if (homeName.isEmpty() || awayName.isEmpty()) return null
        val homeGoals = fields[3].trim().toIntOrNull() ?: return null
        val awayGoals = fields[4].trim().toIntOrNull() ?: return null
        // Negative goals do not exist; a row claiming otherwise is corrupt.
        if (homeGoals < 0 || awayGoals < 0) return null
        val homeId = NationalTeamKeys.teamId(homeName)
        val awayId = NationalTeamKeys.teamId(awayName)
        if (homeId == awayId) return null
        val neutral = fields[8].trim().equals("true", ignoreCase = true)
        return InternationalResult(
            kickoffEpochMs = kickoff,
            homeName = homeName,
            awayName = awayName,
            homeTeamId = homeId,
            awayTeamId = awayId,
            homeGoals = homeGoals,
            awayGoals = awayGoals,
            tournament = fields[5].trim(),
            neutralVenue = neutral,
        )
    }

    /**
     * Splits one CSV line, honouring double quotes. The archive quotes fields that contain
     * a comma (a city such as "St. Louis, Missouri"), so a plain `split(",")` would shift
     * every following column.
     */
    internal fun splitLine(line: String): List<String>? {
        val out = ArrayList<String>(COLUMNS)
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' -> {
                    if (quoted && index + 1 < line.length && line[index + 1] == '"') {
                        current.append('"')
                        index++
                    } else {
                        quoted = !quoted
                    }
                }
                char == ',' && !quoted -> {
                    out.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        if (quoted) return null // an unterminated quote means the line cannot be trusted
        out.add(current.toString())
        return out
    }
}
