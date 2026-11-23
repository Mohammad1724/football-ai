import com.footballai.engine.eval.*
import java.io.File

/**
 * Scratch harness (not part of the app): like ForecastAudit, but records carry closing
 * 1X2 odds, so each emitted sample includes the engine's final (market-aligned) triple,
 * the unaligned model-only triple, the market-implied triple, and the raw closing odds.
 *
 * Input TSV columns:
 *   id, league, home, away, homeGoals, awayGoals, kickoffEpochMs, oddsHome, oddsDraw, oddsAway
 * Odds fields may be empty (= no price). Output TSV columns:
 *   id, league, kickoffEpochMs, finalH, finalD, finalA, modelH, modelD, modelA,
 *   marketH, marketD, marketA, oddsH, oddsD, oddsA, actual
 */
fun main(args: Array<String>) {
    val records = File(args[0]).readLines().filter { it.isNotBlank() }.map { line ->
        val v = line.split('\t')
        fun odd(i: Int): Double? = v.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDouble()
        HistoricalRecord(
            v[0], v[1], v[2], v[3], v[4].toInt(), v[5].toInt(), v[6].toLong(),
            odd(7), odd(8), odd(9),
        )
    }
    val samples = Backtester(
        BacktestConfig(resultAvailabilityDelayMinutes = 1440, contextFeaturesEnabled = true),
    ).samples(records)
    println(
        listOf(
            "id", "league", "time", "finalHome", "finalDraw", "finalAway",
            "modelHome", "modelDraw", "modelAway", "marketHome", "marketDraw", "marketAway",
            "oddsHome", "oddsDraw", "oddsAway", "actual",
        ).joinToString("\t"),
    )
    val oddsById = records.associateBy { it.matchId }
    for (s in samples) {
        val rec = oddsById[s.matchId]
        fun f(x: Double?): String = x?.toString() ?: ""
        println(
            listOf(
                s.matchId, s.competitionId, s.kickoffEpochMs.toString(),
                s.homeWin.toString(), s.draw.toString(), s.awayWin.toString(),
                s.unalignedHome.toString(), s.unalignedDraw.toString(), s.unalignedAway.toString(),
                f(s.marketHome), f(s.marketDraw), f(s.marketAway),
                f(rec?.oddsHome), f(rec?.oddsDraw), f(rec?.oddsAway),
                s.actual.name,
            ).joinToString("\t"),
        )
    }
    System.err.println("Historical records ${records.size}; matched forecasts ${samples.size}")
}
