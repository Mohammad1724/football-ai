package com.footballai.app.data.remote

import com.footballai.app.data.provider.CsvCompetition
import com.footballai.app.data.provider.RemoteHistoryMatch
import java.text.Normalizer
import java.util.Locale

/** Extra football-data.co.uk files and their daily GitHub transport mirrors. */
internal data class ExtraLeagueSource(
    val fileCode: String,
    val mirrorDirectory: String,
)

/**
 * League-only open-matchday catalogue. Knockout competitions are deliberately
 * excluded until their regulation/extra-time score scope is explicit upstream.
 */
internal object ExpandedFootballCatalog {
    val OPEN_MATCHDAY_LEAGUES: Map<String, String> = linkedMapOf(
        "E0" to "ENG_1_premier-league",
        "E1" to "ENG_2_championship",
        "E2" to "ENG_3_league-one",
        "E3" to "ENG_4_league-two",
        "EC" to "ENG_5_national-league",
        "ENG6N" to "ENG_6_national-league-north",
        "ENG6S" to "ENG_6_national-league-south",
        "ENG7N" to "ENG_7_northern-premier-league",
        "SC0" to "SCO_1_premiership",
        "SC1" to "SCO_2_championship",
        "SC2" to "SCO_3_league-one",
        "SC3" to "SCO_4_league-two",
        "D1" to "GER_1_bundesliga",
        "D2" to "GER_2_2-bundesliga",
        "D3" to "GER_3_3-liga",
        "I1" to "ITA_1_serie-a",
        "I2" to "ITA_2_serie-b",
        "SP1" to "ESP_1_la-liga",
        "SP2" to "ESP_2_segunda-division",
        "F1" to "FRA_1_ligue-1",
        "F2" to "FRA_2_ligue-2",
        "N1" to "NED_1_eredivisie",
        "N2" to "NED_2_eerste-divisie",
        "B1" to "BEL_1_pro-league",
        "BELU21" to "BEL_U21_pro-league",
        "P1" to "POR_1_primeira-liga",
        "T1" to "TUR_1_super-lig",
        "G1" to "GRE_1_super-league",
        "SWZ1" to "SUI_1_super-league",
        "AUT1" to "AUT_1_bundesliga",
        "DEN1" to "DEN_1_superliga",
        "SWE1" to "SWE_1_allsvenskan",
        "NOR1" to "NOR_1_eliteserien",
        "ROU1" to "ROU_1_liga-1",
        "RUS1" to "RUS_1_premier-liga",
        "IRE2" to "IRE_2_first-division",
        "IRE3" to "IRE_3_national-league",
        "POL1" to "POL_1_ekstraklasa",
        "POL2" to "POL_2_division-1",
        "POL3" to "POL_3_division-2",
        "WAL1" to "WAL_1_cymru-premier",
        "WAL2" to "WAL_2_cymru-north",
        "WAL3" to "WAL_3_cymru-south",
        "CRO1" to "CRO_1_hnl",
        "CZE1" to "CZE_1_chance-liga",
        "UKR1" to "UKR_1_premier-league",
        "CYP1" to "CYP_1_first-division",
        "SVK1" to "SVK_1_nike-liga",
        "SVN1" to "SVN_1_prva-liga",
        "FIN1" to "FIN_1_veikkausliiga",
        "KAZ1" to "KAZ_1_premier-league",
        "BRA1" to "BRA_1_serie-a",
        "BRA2" to "BRA_2_serie-b",
        "ARG1" to "ARG_1_liga-profesional",
        "ARG2" to "ARG_2_primera-nacional",
        "MEX1" to "MEX_1_liga-mx",
        "MEX2" to "MEX_2_liga-expansion",
        "USA1" to "USA_1_mls",
        "JPN1" to "JPN_1_j1-league",
        "AUS1" to "AUS_1_a-league",
        "CHN1" to "CHN_1_super-league",
    )

    private val names = mapOf(
        "E0" to ("Premier League" to "England"),
        "E1" to ("Championship" to "England"),
        "E2" to ("League One" to "England"),
        "E3" to ("League Two" to "England"),
        "EC" to ("National League" to "England"),
        "ENG6N" to ("National League North" to "England"),
        "ENG6S" to ("National League South" to "England"),
        "ENG7N" to ("Northern Premier League" to "England"),
        "SC0" to ("Premiership" to "Scotland"),
        "SC1" to ("Championship" to "Scotland"),
        "SC2" to ("League One" to "Scotland"),
        "SC3" to ("League Two" to "Scotland"),
        "D1" to ("Bundesliga" to "Germany"),
        "D2" to ("2. Bundesliga" to "Germany"),
        "D3" to ("3. Liga" to "Germany"),
        "I1" to ("Serie A" to "Italy"),
        "I2" to ("Serie B" to "Italy"),
        "SP1" to ("La Liga" to "Spain"),
        "SP2" to ("Segunda Division" to "Spain"),
        "F1" to ("Ligue 1" to "France"),
        "F2" to ("Ligue 2" to "France"),
        "N1" to ("Eredivisie" to "Netherlands"),
        "N2" to ("Eerste Divisie" to "Netherlands"),
        "B1" to ("Jupiler Pro League" to "Belgium"),
        "BELU21" to ("Pro League U21" to "Belgium"),
        "P1" to ("Primeira Liga" to "Portugal"),
        "T1" to ("Super Lig" to "Turkey"),
        "G1" to ("Super League" to "Greece"),
        "SWZ1" to ("Super League" to "Switzerland"),
        "AUT1" to ("Bundesliga" to "Austria"),
        "DEN1" to ("Superliga" to "Denmark"),
        "SWE1" to ("Allsvenskan" to "Sweden"),
        "NOR1" to ("Eliteserien" to "Norway"),
        "ROU1" to ("Liga I" to "Romania"),
        "RUS1" to ("Premier Liga" to "Russia"),
        "IRE2" to ("First Division" to "Ireland"),
        "IRE3" to ("National League" to "Ireland"),
        "POL1" to ("Ekstraklasa" to "Poland"),
        "POL2" to ("I Liga" to "Poland"),
        "POL3" to ("II Liga" to "Poland"),
        "WAL1" to ("Cymru Premier" to "Wales"),
        "WAL2" to ("Cymru North" to "Wales"),
        "WAL3" to ("Cymru South" to "Wales"),
        "CRO1" to ("HNL" to "Croatia"),
        "CZE1" to ("Chance Liga" to "Czech Republic"),
        "UKR1" to ("Premier League" to "Ukraine"),
        "CYP1" to ("First Division" to "Cyprus"),
        "SVK1" to ("Nike Liga" to "Slovakia"),
        "SVN1" to ("Prva Liga" to "Slovenia"),
        "FIN1" to ("Veikkausliiga" to "Finland"),
        "KAZ1" to ("Premier League" to "Kazakhstan"),
        "BRA1" to ("Serie A" to "Brazil"),
        "BRA2" to ("Serie B" to "Brazil"),
        "ARG1" to ("Liga Profesional" to "Argentina"),
        "ARG2" to ("Primera Nacional" to "Argentina"),
        "MEX1" to ("Liga MX" to "Mexico"),
        "MEX2" to ("Liga de Expansion MX" to "Mexico"),
        "USA1" to ("Major League Soccer" to "United States"),
        "JPN1" to ("J1 League" to "Japan"),
        "AUS1" to ("A-Leagues" to "Australia"),
        "CHN1" to ("Super League" to "China"),
    )

    val COMPETITIONS: List<CsvCompetition> = OPEN_MATCHDAY_LEAGUES.keys.map { key ->
        val (name, country) = names.getValue(key)
        CsvCompetition(key, name, country)
    }

    /** Leagues with the original per-season football-data.co.uk URL contract. */
    val MAIN_HISTORY_LEAGUES = setOf(
        "E0", "E1", "E2", "E3", "EC", "SC0", "SC1", "SC2", "SC3",
        "D1", "D2", "I1", "I2", "SP1", "SP2", "F1", "F2", "N1", "B1", "P1", "T1", "G1",
    )

    val MIRROR_HISTORY_LEAGUES = setOf(
        "T1", "E0", "E1", "E2", "E3", "SP1", "SP2", "D1", "D2", "I1", "I2",
        "F1", "F2", "N1", "P1", "B1", "G1", "SC0", "SC1", "SC2", "SC3",
    )

    val EXTRA_HISTORY_LEAGUES = mapOf(
        "BRA1" to ExtraLeagueSource("BRA", "brazil"),
        "USA1" to ExtraLeagueSource("USA", "usa"),
        "DEN1" to ExtraLeagueSource("DNK", "denmark"),
        "FIN1" to ExtraLeagueSource("FIN", "finland"),
        "JPN1" to ExtraLeagueSource("JPN", "japan"),
        "NOR1" to ExtraLeagueSource("NOR", "norway"),
        "SWE1" to ExtraLeagueSource("SWE", "sweden"),
    )

    /** Newly added keys where historical and current provider names need reconciliation. */
    val CROSS_SOURCE_LEAGUES: Set<String> = EXTRA_HISTORY_LEAGUES.keys + setOf("E3", "EC", "SC1", "SC2", "SC3")
}

/** Resolves naming differences only for newly added cross-source leagues. */
internal object ExpandedTeamIdentity {
    private val aliases: Map<String, Map<String, String>> = mapOf(
        "BRA1" to aliases(
            "Botafogo RJ" to "Botafogo",
            "Bragantino" to "Red Bull Bragantino",
            "Chapecoense-SC" to "Chapecoense",
            "Flamengo RJ" to "Flamengo",
            "Vasco" to "Vasco da Gama",
        ),
        "USA1" to aliases(
            "Atlanta Utd" to "Atlanta United",
            "Charlotte" to "Charlotte FC",
            "Chicago Fire" to "Chicago Fire FC",
            "Houston Dynamo" to "Houston Dynamo FC",
            "Inter Miami" to "Inter Miami CF",
            "Los Angeles Galaxy" to "LA Galaxy",
            "Los Angeles FC" to "Los Angeles Football Club",
            "LAFC" to "Los Angeles Football Club",
            "Minnesota United" to "Minnesota United FC",
            "New York City" to "New York City Football Club",
            "New York City FC" to "New York City Football Club",
            "New York Red Bulls" to "Red Bull New York",
            "Orlando City" to "Orlando City SC",
            "Seattle Sounders" to "Seattle Sounders FC",
            "St. Louis City" to "St. Louis CITY SC",
            "Vancouver Whitecaps" to "Vancouver Whitecaps FC",
        ),
        "JPN1" to aliases(
            "Okayama" to "Fagiano Okayama",
            "Chiba" to "JEF United Ichihara-Chiba",
            "Kyoto" to "Kyoto Sanga",
            "Machida" to "Machida Zelvia",
            "Mito" to "Mito Hollyhock",
            "Verdy" to "Tokyo Verdy 1969",
            "Urawa Reds" to "Urawa Red Diamonds",
        ),
        "E3" to aliases(
            "Accrington" to "Accrington Stanley",
            "Bristol Rvs" to "Bristol Rovers",
            "Cheltenham" to "Cheltenham Town",
            "Colchester" to "Colchester United",
            "Crewe" to "Crewe Alexandra",
            "Exeter" to "Exeter City",
            "Grimsby" to "Grimsby Town",
            "Northampton" to "Northampton Town",
            "Oldham" to "Oldham Athletic",
            "Rotherham" to "Rotherham United",
            "Salford" to "Salford City",
            "Shrewsbury" to "Shrewsbury Town",
            "Swindon" to "Swindon Town",
            "Tranmere" to "Tranmere Rovers",
            "York" to "York City",
        ),
        "EC" to aliases(
            "Aldershot" to "Aldershot Town",
            "Boston Utd" to "Boston United",
            "Carlisle" to "Carlisle United",
            "FC Halifax" to "FC Halifax Town",
            "Forest Green" to "Forest Green Rovers",
            "Harrogate" to "Harrogate Town",
            "Hartlepool" to "Hartlepool United",
            "Scunthorpe" to "Scunthorpe United",
            "Southend" to "Southend United",
            "Sutton Utd" to "Sutton United",
            "Yeovil" to "Yeovil Town",
        ),
        "SC1" to aliases(
            "Ayr" to "Ayr United",
            "Dunfermline" to "Dunfermline Athletic",
            "Morton" to "Greenock Morton",
            "Inverness C" to "Inverness Caledonian Thistle",
            "Partick" to "Partick Thistle",
            "Raith Rvs" to "Raith Rovers",
        ),
    )

    fun key(competitionKey: String, teamName: String): String {
        val normalized = normalize(teamName)
        return aliases[competitionKey]?.get(normalized) ?: normalized
    }

    private fun aliases(vararg pairs: Pair<String, String>): Map<String, String> =
        pairs.associate { (source, target) -> normalize(source) to normalize(target) }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "")
}

/** Parser for football-data.co.uk/new/*.csv cumulative league archives. */
internal object ExtraCsvHistoryParser {
    private val HOME_ODDS = listOf("AvgCH", "PSCH", "B365CH", "AvgH", "PH", "B365H")
    private val DRAW_ODDS = listOf("AvgCD", "PSCD", "B365CD", "AvgD", "PD", "B365D")
    private val AWAY_ODDS = listOf("AvgCA", "PSCA", "B365CA", "AvgA", "PA", "B365A")

    fun parse(
        csv: String,
        competition: CsvCompetition,
        seasonStartYear: Int,
    ): List<RemoteHistoryMatch> {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return emptyList()
        val header = CsvHistoryParser.splitLine(lines.first()).mapIndexed { index, name ->
            name.trim().removePrefix("\uFEFF") to index
        }.toMap()
        val homeIndex = header["Home"] ?: return emptyList()
        val awayIndex = header["Away"] ?: return emptyList()
        val homeGoalsIndex = header["HG"] ?: return emptyList()
        val awayGoalsIndex = header["AG"] ?: return emptyList()
        val dateIndex = header["Date"] ?: return emptyList()
        val seasonIndex = header["Season"] ?: return emptyList()
        val timeIndex = header["Time"]

        return lines.drop(1).mapNotNull { line ->
            val columns = CsvHistoryParser.splitLine(line)
            val rowSeason = columns.getOrNull(seasonIndex)?.trim().orEmpty()
            if (rowSeason != seasonStartYear.toString() &&
                rowSeason != "$seasonStartYear/${seasonStartYear + 1}"
            ) return@mapNotNull null
            val home = columns.getOrNull(homeIndex)?.trim().orEmpty()
            val away = columns.getOrNull(awayIndex)?.trim().orEmpty()
            if (home.isEmpty() || away.isEmpty()) return@mapNotNull null
            val homeGoals = columns.getOrNull(homeGoalsIndex)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val awayGoals = columns.getOrNull(awayGoalsIndex)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val kickoff = CsvHistoryParser.parseKickoff(
                columns.getOrNull(dateIndex)?.trim().orEmpty(),
                timeIndex?.let { columns.getOrNull(it)?.trim() },
                seasonStartYear,
            ) ?: return@mapNotNull null
            RemoteHistoryMatch(
                competitionKey = competition.key,
                competitionName = competition.name,
                season = seasonStartYear,
                homeTeamKey = ExpandedTeamIdentity.key(competition.key, home),
                homeTeamName = home,
                awayTeamKey = ExpandedTeamIdentity.key(competition.key, away),
                awayTeamName = away,
                homeGoals = homeGoals,
                awayGoals = awayGoals,
                kickoffEpochMs = kickoff,
                homeOdds = CsvHistoryParser.firstDouble(columns, header, HOME_ODDS),
                drawOdds = CsvHistoryParser.firstDouble(columns, header, DRAW_ODDS),
                awayOdds = CsvHistoryParser.firstDouble(columns, header, AWAY_ODDS),
            )
        }
    }
}
