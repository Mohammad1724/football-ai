import com.footballai.engine.eval.*
import com.footballai.engine.markets.MarketCalculator
import java.io.File

/**
 * Scratch harness (not part of the app): extends PricedAudit with per-match xG and
 * BOTH Pinnacle opening and closing odds.
 *
 * Usage: XgAudit <input.tsv> <closing|opening> <xgBlendWeight>
 * The chosen odds set is the one fed to the Backtester (so the market arm and the
 * production market alignment use it); the samples also carry both implied triples
 * and both raw odds sets for analysis.
 *
 * Input TSV columns:
 *   id, league, home, away, homeGoals, awayGoals, kickoffEpochMs, xgHome, xgAway,
 *   openH, openD, openA, closeH, closeD, closeA
 * Empty field = absent. Output TSV columns:
 *   id, league, time, finalH/D/A, modelH/D/A, marketH/D/A (fed odds, implied),
 *   openImpH/D/A, closeImpH/D/A, openH/D/A, closeH/D/A, actual
 */
fun main(args: Array<String>) {
    val mode = args[1]
    require(mode == "closing" || mode == "opening") { "mode must be closing|opening" }
    val xgWeight = args[2].toDouble()
    data class Row(
        val id: String, val league: String, val home: String, val away: String,
        val hg: Int, val ag: Int, val time: Long,
        val xgH: Double?, val xgA: Double?,
        val open: Triple<Double?, Double?, Double?>,
        val close: Triple<Double?, Double?, Double?>,
    )
    val rows = File(args[0]).readLines().filter { it.isNotBlank() }.map { line ->
        val v = line.split('\t')
        fun d(i: Int): Double? = v.getOrNull(i)?.takeIf { it.isNotEmpty() }?.toDouble()
        Row(
            v[0], v[1], v[2], v[3], v[4].toInt(), v[5].toInt(), v[6].toLong(),
            d(7), d(8),
            Triple(d(9), d(10), d(11)),
            Triple(d(12), d(13), d(14)),
        )
    }
    val records = rows.map { r ->
        val fed = if (mode == "closing") r.close else r.open
        HistoricalRecord(
            r.id, r.league, r.home, r.away, r.hg, r.ag, r.time,
            fed.first, fed.second, fed.third,
            homeXg = r.xgH, awayXg = r.xgA,
        )
    }
    val samples = Backtester(
        BacktestConfig(
            resultAvailabilityDelayMinutes = 1440,
            contextFeaturesEnabled = true,
            xgBlendWeight = xgWeight,
        ),
    ).samples(records)
    val byId = rows.associateBy { it.id }
    fun imp(o: Triple<Double?, Double?, Double?>): Triple<Double?, Double?, Double?> {
        val p = MarketCalculator.impliedProbabilities(o.first, o.second, o.third)
        return Triple(p?.first, p?.second, p?.third)
    }
    fun f(x: Double?): String = x?.toString() ?: ""
    println(
        listOf(
            "id", "league", "time", "finalHome", "finalDraw", "finalAway",
            "modelHome", "modelDraw", "modelAway", "marketHome", "marketDraw", "marketAway",
            "openImpHome", "openImpDraw", "openImpAway", "closeImpHome", "closeImpDraw", "closeImpAway",
            "openHome", "openDraw", "openAway", "closeHome", "closeDraw", "closeAway", "actual",
        ).joinToString("\t"),
    )
    for (s in samples) {
        val r = byId.getValue(s.matchId)
        val oi = imp(r.open)
        val ci = imp(r.close)
        println(
            listOf(
                s.matchId, s.competitionId, s.kickoffEpochMs.toString(),
                s.homeWin.toString(), s.draw.toString(), s.awayWin.toString(),
                s.unalignedHome.toString(), s.unalignedDraw.toString(), s.unalignedAway.toString(),
                f(s.marketHome), f(s.marketDraw), f(s.marketAway),
                f(oi.first), f(oi.second), f(oi.third),
                f(ci.first), f(ci.second), f(ci.third),
                f(r.open.first), f(r.open.second), f(r.open.third),
                f(r.close.first), f(r.close.second), f(r.close.third),
                s.actual.name,
            ).joinToString("\t"),
        )
    }
    System.err.println("Historical records ${records.size}; matched forecasts ${samples.size}; mode=$mode xgWeight=$xgWeight")
}
