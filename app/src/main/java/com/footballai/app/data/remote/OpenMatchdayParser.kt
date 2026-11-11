package com.footballai.app.data.remote

import com.footballai.app.data.provider.CsvCompetition
import com.footballai.app.data.provider.RemoteHistoryMatch
import com.footballai.app.data.provider.RemoteMatch
import com.footballai.app.data.provider.RemoteTeam
import com.footballai.app.domain.model.MatchStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Parser for open-matchday's keyless, daily refreshed current-season CSV files. */
object OpenMatchdayParser {

    // Upstream documents a fixed CET-1 (UTC+1) convention, including summer rows.
    private val SOURCE_ZONE: ZoneOffset = ZoneOffset.ofHours(1)
    private val TIME_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("H:mm")

    fun fixtures(
        csv: String,
        competition: CsvCompetition,
        seasonStartYear: Int,
    ): List<RemoteMatch> = parse(csv, competition, seasonStartYear, finished = false)

    fun results(
        csv: String,
        competition: CsvCompetition,
        seasonStartYear: Int,
    ): List<RemoteMatch> = parse(csv, competition, seasonStartYear, finished = true)

    fun history(
        csv: String,
        competition: CsvCompetition,
        seasonStartYear: Int,
    ): List<RemoteHistoryMatch> = results(csv, competition, seasonStartYear).map { match ->
        RemoteHistoryMatch(
            competitionKey = competition.key,
            competitionName = competition.name,
            season = seasonStartYear,
            homeTeamKey = match.home.providerKey,
            homeTeamName = match.home.name,
            awayTeamKey = match.away.providerKey,
            awayTeamName = match.away.name,
            homeGoals = match.homeGoals ?: 0,
            awayGoals = match.awayGoals ?: 0,
            kickoffEpochMs = match.kickoffEpochMs,
            homeOdds = null,
            drawOdds = null,
            awayOdds = null,
        )
    }

    private fun parse(
        csv: String,
        competition: CsvCompetition,
        seasonStartYear: Int,
        finished: Boolean,
    ): List<RemoteMatch> {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return emptyList()
        val header = CsvHistoryParser.splitLine(lines.first())
            .mapIndexed { index, name -> name.trim() to index }
            .toMap()
        val dateIndex = header["date"] ?: return emptyList()
        val homeIndex = header["home"] ?: return emptyList()
        val awayIndex = header["away"] ?: return emptyList()
        val timeIndex = header["time"]
        val roundIndex = header["gw"]
        val homeGoalsIndex = if (finished) header["ft_home"] else null
        val awayGoalsIndex = if (finished) header["ft_away"] else null
        if (finished && (homeGoalsIndex == null || awayGoalsIndex == null)) return emptyList()

        val matches = ArrayList<RemoteMatch>(lines.size - 1)
        val seen = HashSet<String>()
        for (lineIndex in 1 until lines.size) {
            val columns = CsvHistoryParser.splitLine(lines[lineIndex])
            val requiredLast = listOfNotNull(dateIndex, homeIndex, awayIndex, homeGoalsIndex, awayGoalsIndex).max()
            if (columns.size <= requiredLast) continue
            val homeName = columns[homeIndex].trim()
            val awayName = columns[awayIndex].trim()
            if (homeName.isEmpty() || awayName.isEmpty()) continue
            val kickoff = kickoff(columns[dateIndex].trim(), timeIndex?.let { columns.getOrNull(it) })
                ?: continue
            val homeGoals = homeGoalsIndex?.let { columns[it].trim().toIntOrNull() }
            val awayGoals = awayGoalsIndex?.let { columns[it].trim().toIntOrNull() }
            if (finished && (homeGoals == null || awayGoals == null)) continue

            val homeKey = if (competition.key in ExpandedFootballCatalog.CROSS_SOURCE_LEAGUES) {
                ExpandedTeamIdentity.key(competition.key, homeName)
            } else {
                CsvHistoryParser.slug(homeName)
            }
            val awayKey = if (competition.key in ExpandedFootballCatalog.CROSS_SOURCE_LEAGUES) {
                ExpandedTeamIdentity.key(competition.key, awayName)
            } else {
                CsvHistoryParser.slug(awayName)
            }
            val providerKey = "${competition.key}:$homeKey:$awayKey:$kickoff"
            if (!seen.add(providerKey)) continue
            matches += RemoteMatch(
                providerKey = providerKey,
                competitionKey = competition.key,
                competitionName = competition.name,
                season = seasonStartYear,
                matchday = roundIndex?.let { columns.getOrNull(it)?.trim()?.toIntOrNull() },
                kickoffEpochMs = kickoff,
                status = if (finished) MatchStatus.FINISHED else MatchStatus.SCHEDULED,
                minute = null,
                home = RemoteTeam(providerKey = homeKey, name = homeName),
                away = RemoteTeam(providerKey = awayKey, name = awayName),
                homeGoals = homeGoals,
                awayGoals = awayGoals,
                homeHalfTimeGoals = columns.optionalInt(header, "ht_home"),
                awayHalfTimeGoals = columns.optionalInt(header, "ht_away"),
                venue = null,
                odds = null,
            )
        }
        return matches.sortedBy { it.kickoffEpochMs }
    }

    private fun kickoff(date: String, time: String?): Long? = try {
        val localDate = LocalDate.parse(date)
        val localTime = time?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { LocalTime.parse(it, TIME_FORMATTER) }
            ?: LocalTime.NOON
        localDate.atTime(localTime).toInstant(SOURCE_ZONE).toEpochMilli()
    } catch (_: Exception) {
        null
    }

    private fun List<String>.optionalInt(header: Map<String, Int>, name: String): Int? =
        header[name]?.let { index -> getOrNull(index)?.trim()?.toIntOrNull() }
}
