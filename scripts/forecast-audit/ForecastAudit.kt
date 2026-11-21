import com.footballai.engine.eval.*
import java.io.File

fun main(args: Array<String>) {
    val records = File(args[0]).readLines().map { line ->
        val v = line.split('\t')
        HistoricalRecord(v[0], v[1], v[2], v[3], v[4].toInt(), v[5].toInt(), v[6].toLong())
    }
    val a = Backtester(BacktestConfig(resultAvailabilityDelayMinutes = 1440, contextFeaturesEnabled = true)).samples(records)
    val b = Backtester(BacktestConfig(resultAvailabilityDelayMinutes = 1440, contextFeaturesEnabled = false)).samples(records).associateBy { it.matchId }
    println("id\tleague\ttime\thome\tdraw\taway\tactual\tcontextHome\tcontextDraw\tcontextAway")
    for (s in a) {
        val other = b[s.matchId] ?: error("unpaired")
        check(s.actual == other.actual)
        println(listOf(s.matchId,s.competitionId,s.kickoffEpochMs,s.homeWin,s.draw,s.awayWin,s.actual,other.homeWin,other.draw,other.awayWin).joinToString("\t"))
    }
    System.err.println("Historical records ${records.size}; matched forecasts ${a.size}; no-context ${b.size}")
}
