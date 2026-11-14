package com.footballai.app.domain.catalog

import com.footballai.engine.model.CompetitionProfile
import com.footballai.engine.model.CompetitionType
import com.footballai.engine.model.Gender
import com.footballai.engine.model.TeamKind
import java.text.Normalizer
import java.util.Locale

/**
 * Where a classification came from. The UI shows this so the user can tell a
 * catalogue fact from a name-based guess, and an unclassified competition is never
 * presented as if it were known.
 */
enum class ClassificationSource { CATALOG, PROVIDER_DEFAULT, NAME_RULE, UNKNOWN }

data class CompetitionClassification(
    val type: CompetitionType,
    val gender: Gender,
    val source: ClassificationSource,
    /** Human readable country/region label from the provider, when it published one. */
    val region: String? = null,
    /** Persian name when the catalogue knows one; null otherwise (never invented). */
    val persianName: String? = null,
) {
    val teamKind: TeamKind get() = if (isInternational) TeamKind.NATIONAL else TeamKind.CLUB

    val isInternational: Boolean
        get() = when (type) {
            CompetitionType.INTERNATIONAL_TOURNAMENT,
            CompetitionType.INTERNATIONAL_QUALIFIER,
            CompetitionType.INTERNATIONAL_FRIENDLY,
            CompetitionType.WOMEN_INTERNATIONAL,
            -> true
            else -> false
        }

    val isKnown: Boolean get() = source != ClassificationSource.UNKNOWN

    fun profile(neutralVenue: Boolean = false, secondLeg: Boolean = false): CompetitionProfile =
        CompetitionProfile(
            type = type,
            gender = gender,
            neutralVenue = neutralVenue,
            secondLeg = secondLeg,
        )

    fun label(): String = when (type) {
        CompetitionType.CLUB_LEAGUE -> "CLUB_LEAGUE"
        CompetitionType.CLUB_KNOCKOUT -> "CLUB_KNOCKOUT"
        CompetitionType.INTERNATIONAL_TOURNAMENT -> "INTERNATIONAL_TOURNAMENT"
        CompetitionType.INTERNATIONAL_QUALIFIER -> "INTERNATIONAL_QUALIFIER"
        CompetitionType.INTERNATIONAL_FRIENDLY -> "INTERNATIONAL_FRIENDLY"
        CompetitionType.WOMEN_CLUB_LEAGUE -> "WOMEN_CLUB_LEAGUE"
        CompetitionType.WOMEN_INTERNATIONAL -> "WOMEN_INTERNATIONAL"
    }
}

/**
 * Unified competition discovery.
 *
 * Three sources, in decreasing order of trust:
 *
 * 1. **Catalogue** — competitions whose provider key (or exact published name) is
 *    known, including their type, gender, Persian name and aliases.
 * 2. **Provider default** — a provider whose whole feed is a men's club-league
 *    catalogue (football-data.co.uk and Open Matchday). Every row from them is a
 *    club league by construction, which is a fact about the feed, not a guess.
 * 3. **Name rules** — explicit patterns for competitions that a provider returns
 *    dynamically (API-Football publishes hundreds). A name that matches no rule is
 *    reported as `UNKNOWN` instead of being silently treated as a domestic league.
 *
 * Nothing here creates a competition: the list the user sees always comes from the
 * provider. The catalogue only classifies what already exists.
 */
object CompetitionCatalog {

    private data class Entry(
        val providerId: String?,
        val providerKey: String?,
        val name: String,
        val type: CompetitionType,
        val gender: Gender,
        val region: String?,
        val persian: String?,
        val aliases: List<String> = emptyList(),
        val kindLabel: String? = null,
    )

    /** Football-data.org competition codes that exist on the free plan. */
    private val providerKeys = listOf(
        Entry(null, "PL", "Premier League", CompetitionType.CLUB_LEAGUE, Gender.MEN, "England", "لیگ برتر انگلیس"),
        Entry(null, "ELC", "Championship", CompetitionType.CLUB_LEAGUE, Gender.MEN, "England", "چمپیونشیپ انگلیس"),
        Entry(null, "BL1", "Bundesliga", CompetitionType.CLUB_LEAGUE, Gender.MEN, "Germany", "بوندس‌لیگا"),
        Entry(null, "SA", "Serie A", CompetitionType.CLUB_LEAGUE, Gender.MEN, "Italy", "سری آ"),
        Entry(null, "PD", "La Liga", CompetitionType.CLUB_LEAGUE, Gender.MEN, "Spain", "لالیگا"),
        Entry(null, "FL1", "Ligue 1", CompetitionType.CLUB_LEAGUE, Gender.MEN, "France", "لیگ ۱ فرانسه"),
        Entry(null, "DED", "Eredivisie", CompetitionType.CLUB_LEAGUE, Gender.MEN, "Netherlands", "ارِدیویزی"),
        Entry(null, "PPL", "Primeira Liga", CompetitionType.CLUB_LEAGUE, Gender.MEN, "Portugal", "لیگ برتر پرتغال"),
        Entry(null, "BSA", "Brasileirao", CompetitionType.CLUB_LEAGUE, Gender.MEN, "Brazil", "سری آ برزیل"),
        Entry(null, "CL", "UEFA Champions League", CompetitionType.CLUB_KNOCKOUT, Gender.MEN, "Europe", "لیگ قهرمانان اروپا"),
        Entry(null, "EC", "European Championship", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "Europe", "یورو"),
        Entry(null, "WC", "FIFA World Cup", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "World", "جام جهانی"),
    )

    /**
     * ESPN's public scoreboard, keyed by its own slugs. Every slug below was checked
     * against the live endpoint before it was written here; the endpoint answers 400 for
     * a slug that does not exist, so an invented one would be visible immediately.
     * `espn-open` also publishes women's competitions, which are catalogued explicitly
     * rather than left to a name rule.
     */
    private val espnOpenKeys = listOf(
        Entry("espn-open", "uefa.nations", "UEFA Nations League", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "Europe", "لیگ ملت‌های اروپا"),
        Entry("espn-open", "uefa.w.nations", "UEFA Women's Nations League", CompetitionType.WOMEN_INTERNATIONAL, Gender.WOMEN, "Europe", "لیگ ملت‌های اروپا (زنان)"),
        Entry("espn-open", "fifa.friendly", "International Friendlies", CompetitionType.INTERNATIONAL_FRIENDLY, Gender.MEN, "World", "بازی‌های دوستانه ملی"),
        Entry("espn-open", "fifa.worldq.uefa", "World Cup Qualifiers (UEFA)", CompetitionType.INTERNATIONAL_QUALIFIER, Gender.MEN, "Europe", "مقدماتی جام جهانی (اروپا)"),
        Entry("espn-open", "fifa.world", "FIFA World Cup", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "World", "جام جهانی"),
        Entry("espn-open", "uefa.euro", "UEFA European Championship", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "Europe", "یورو"),
        Entry("espn-open", "concacaf.nations.league", "CONCACAF Nations League", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "North America", "لیگ ملت‌های کونکاکاف"),
        Entry("espn-open", "fifa.wwc", "FIFA Women's World Cup", CompetitionType.WOMEN_INTERNATIONAL, Gender.WOMEN, "World", "جام جهانی زنان"),
        Entry("espn-open", "uefa.weuro", "UEFA Women's Euro", CompetitionType.WOMEN_INTERNATIONAL, Gender.WOMEN, "Europe", "یورو زنان"),
        Entry("espn-open", "uefa.champions", "UEFA Champions League", CompetitionType.CLUB_KNOCKOUT, Gender.MEN, "Europe", "لیگ قهرمانان اروپا"),
        Entry("espn-open", "uefa.europa", "UEFA Europa League", CompetitionType.CLUB_KNOCKOUT, Gender.MEN, "Europe", "لیگ اروپا"),
        Entry("espn-open", "uefa.wchampions", "UEFA Women's Champions League", CompetitionType.WOMEN_CLUB_LEAGUE, Gender.WOMEN, "Europe", "لیگ قهرمانان زنان"),
        Entry("espn-open", "eng.1", "Premier League", CompetitionType.CLUB_LEAGUE, Gender.MEN, "England", "لیگ برتر انگلیس"),
        Entry("espn-open", "esp.1", "LaLiga", CompetitionType.CLUB_LEAGUE, Gender.MEN, "Spain", "لالیگا"),
        Entry("espn-open", "ita.1", "Serie A", CompetitionType.CLUB_LEAGUE, Gender.MEN, "Italy", "سری آ"),
        Entry("espn-open", "ger.1", "Bundesliga", CompetitionType.CLUB_LEAGUE, Gender.MEN, "Germany", "بوندس‌لیگا"),
        Entry("espn-open", "fra.1", "Ligue 1", CompetitionType.CLUB_LEAGUE, Gender.MEN, "France", "لیگ ۱ فرانسه"),
        Entry("espn-open", "usa.1", "Major League Soccer", CompetitionType.CLUB_LEAGUE, Gender.MEN, "USA", "لیگ حرفه‌ای آمریکا (MLS)"),
        Entry("espn-open", "eng.w.1", "Women's Super League", CompetitionType.WOMEN_CLUB_LEAGUE, Gender.WOMEN, "England", "سوپرلیگ زنان انگلیس"),
    )

    /**
     * International and continental competitions identified by the name a provider
     * publishes. The list is intentionally explicit: a fuzzy "contains 'cup'" rule
     * would mislabel domestic cups and leagues.
     */
    private val international = listOf(
        Entry(null, null, "FIFA World Cup", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "World", "جام جهانی",
            listOf("world cup")),
        Entry(null, null, "World Cup Qualifiers", CompetitionType.INTERNATIONAL_QUALIFIER, Gender.MEN, "World", "مقدماتی جام جهانی",
            listOf("world cup qualification", "world cup qualifiers", "wc qualification")),
        Entry(null, null, "European Championship", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "Europe", "یورو",
            listOf("euro championship", "uefa euro")),
        Entry(null, null, "European Championship Qualifiers", CompetitionType.INTERNATIONAL_QUALIFIER, Gender.MEN, "Europe", "مقدماتی یورو",
            listOf("euro qualification", "euro qualifiers", "european championship qualification")),
        Entry(null, null, "UEFA Nations League", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "Europe", "لیگ ملت‌های اروپا",
            listOf("nations league")),
        Entry(null, null, "Africa Cup of Nations", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "Africa", "جام ملت‌های آفریقا",
            listOf("africa cup of nations", "afcon", "african nations championship")),
        Entry(null, null, "AFC Asian Cup", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "Asia", "جام ملت‌های آسیا",
            listOf("asian cup", "afc asian cup qualification")),
        Entry(null, null, "Copa America", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "South America", "کوپا آمه‌ریکا",
            listOf("copa américa", "copa america")),
        Entry(null, null, "CONCACAF Gold Cup", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "North America", "جام طلایی کونکاکاف",
            listOf("gold cup", "concacaf championship")),
        Entry(null, null, "CONCACAF Nations League", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "North America", "لیگ ملت‌های کونکاکاف",
            listOf("concacaf nations league")),
        Entry(null, null, "International Friendlies", CompetitionType.INTERNATIONAL_FRIENDLY, Gender.MEN, "World", "بازی‌های دوستانه ملی",
            listOf("friendlies", "friendlies clubs", "international friendlies")),
        Entry(null, null, "Olympic Football", CompetitionType.INTERNATIONAL_TOURNAMENT, Gender.MEN, "World", "فوتبال المپیک",
            listOf("olympics", "olympic games")),
        Entry(null, null, "FIFA Women's World Cup", CompetitionType.WOMEN_INTERNATIONAL, Gender.WOMEN, "World", "جام جهانی زنان",
            listOf("women's world cup", "womens world cup")),
        Entry(null, null, "UEFA Women's Euro", CompetitionType.WOMEN_INTERNATIONAL, Gender.WOMEN, "Europe", "یورو زنان",
            listOf("women's euro", "uefa women's championship")),
        Entry(null, null, "Women's International Friendlies", CompetitionType.WOMEN_INTERNATIONAL, Gender.WOMEN, "World", "دوستانه‌های ملی زنان",
            listOf("women's friendlies", "womens friendlies")),
        Entry(null, null, "UEFA Champions League", CompetitionType.CLUB_KNOCKOUT, Gender.MEN, "Europe", "لیگ قهرمانان اروپا"),
        Entry(null, null, "UEFA Europa League", CompetitionType.CLUB_KNOCKOUT, Gender.MEN, "Europe", "لیگ اروپا"),
        Entry(null, null, "UEFA Conference League", CompetitionType.CLUB_KNOCKOUT, Gender.MEN, "Europe", "لیگ کنفرانس اروپا"),
        Entry(null, null, "AFC Champions League", CompetitionType.CLUB_KNOCKOUT, Gender.MEN, "Asia", "لیگ قهرمانان آسیا",
            listOf("afc champions league", "afc champions league elite", "afc champions league two")),
        Entry(null, null, "CAF Champions League", CompetitionType.CLUB_KNOCKOUT, Gender.MEN, "Africa", "لیگ قهرمانان آفریقا",
            listOf("caf champions league", "caf confederation cup")),
        Entry(null, null, "CONCACAF Champions Cup", CompetitionType.CLUB_KNOCKOUT, Gender.MEN, "North America", "لیگ قهرمانان کونکاکاف",
            listOf("concacaf champions cup", "concacaf champions league")),
        Entry(null, null, "Copa Libertadores", CompetitionType.CLUB_KNOCKOUT, Gender.MEN, "South America", "کوپا لیبرتادورس",
            listOf("libertadores")),
        Entry(null, null, "Copa Sudamericana", CompetitionType.CLUB_KNOCKOUT, Gender.MEN, "South America", "کوپا سودآمریکانا",
            listOf("sudamericana")),
        Entry(null, null, "Saudi Pro League", CompetitionType.CLUB_LEAGUE, Gender.MEN, "Saudi Arabia", "لیگ حرفه‌ای عربستان",
            listOf("saudi professional league")),
        Entry(null, null, "Iran Pro League", CompetitionType.CLUB_LEAGUE, Gender.MEN, "Iran", "لیگ برتر ایران",
            listOf("persian gulf pro league", "persian gulf pro league")),
        Entry(null, null, "Women's Champions League", CompetitionType.WOMEN_CLUB_LEAGUE, Gender.WOMEN, "Europe", "لیگ قهرمانان زنان",
            listOf("uefa women's champions league")),
    )

    private val womenClues = listOf("women", "women's", "womens", "ladies", "feminine", "frauen", "femminile")

    /**
     * Provider keys are only meaningful inside the provider that publishes them:
     * `EC` is football-data.org's European Championship but an English fifth-tier
     * league in the football-data.co.uk CSV catalogue. Keys are therefore scoped by
     * provider id, and an unscoped entry belongs to football-data.org.
     */
    private val byProviderKey: Map<String, Entry> = (providerKeys + espnOpenKeys)
        .filter { it.providerKey != null }
        .associateBy { (it.providerId ?: DEFAULT_KEY_PROVIDER) + "|" + it.providerKey!! }

    private val byName: Map<String, Entry> = international.flatMap { entry ->
        (listOf(entry.name) + entry.aliases).map { normalize(it) to entry }
    }.toMap()

    /**
     * Classifies one competition. [providerId] and [providerKey] take precedence over
     * the published [name] because a provider key is stable while a name is not.
     */
    fun classify(
        providerId: String,
        providerKey: String,
        name: String,
        country: String? = null,
    ): CompetitionClassification {
        // A whole provider feed that is, by construction, a men's club-league catalogue.
        if (providerId in clubLeagueOnlyProviders) {
            return CompetitionClassification(
                type = CompetitionType.CLUB_LEAGUE,
                gender = Gender.MEN,
                source = ClassificationSource.PROVIDER_DEFAULT,
                region = country,
            )
        }
        byProviderKey[providerId + "|" + providerKey]?.let { entry ->
            return CompetitionClassification(
                type = entry.type,
                gender = entry.gender,
                source = ClassificationSource.CATALOG,
                region = entry.region ?: country,
                persianName = entry.persian,
            )
        }
        val normalized = normalize(name)
        byName[normalized]?.let { entry ->
            return CompetitionClassification(
                type = entry.type,
                gender = entry.gender,
                source = ClassificationSource.CATALOG,
                region = entry.region ?: country,
                persianName = entry.persian,
            )
        }
        // A women's competition is decided before any men's name containment runs, so
        // "Women's World Cup 2027" can never inherit the men's tournament entry.
        val women = womenClues.any { normalized.contains(it) }
        if (women) return womenClassification(normalized, country)
        // Partial name containment is a weaker signal than an exact catalogue hit, and
        // is reported as such. Only men's entries participate here.
        international.firstOrNull { entry ->
            entry.gender == Gender.MEN && entry.name.length >= 6 && normalized.contains(normalize(entry.name))
        }?.let { entry ->
            return CompetitionClassification(
                type = entry.type,
                gender = entry.gender,
                source = ClassificationSource.NAME_RULE,
                region = entry.region ?: country,
                persianName = entry.persian,
            )
        }
        // Qualifiers and friendlies are international by definition; testing them before
        // the generic "cup" heuristic is what stops "World Cup - Qualification" from
        // being classified as a club knockout competition.
        if (qualifierClues.any { normalized.contains(it) }) {
            return CompetitionClassification(
                type = CompetitionType.INTERNATIONAL_QUALIFIER,
                gender = Gender.MEN,
                source = ClassificationSource.NAME_RULE,
                region = country,
                persianName = "مقدماتی مسابقات ملی",
            )
        }
        if (friendlyClues.any { normalized.contains(it) }) {
            return CompetitionClassification(
                type = CompetitionType.INTERNATIONAL_FRIENDLY,
                gender = Gender.MEN,
                source = ClassificationSource.NAME_RULE,
                region = country,
                persianName = "بازی دوستانه ملی",
            )
        }
        val knockoutHint = knockoutClues.any { normalized.contains(it) }
        if (knockoutHint) {
            return CompetitionClassification(
                type = CompetitionType.CLUB_KNOCKOUT,
                gender = Gender.MEN,
                source = ClassificationSource.NAME_RULE,
                region = country,
            )
        }
        return CompetitionClassification(
            type = CompetitionType.CLUB_LEAGUE,
            gender = Gender.MEN,
            source = ClassificationSource.UNKNOWN,
            region = country,
        )
    }


    /**
     * Women's competitions, resolved without ever borrowing a men's entry.
     *
     * The order mirrors the men's path: an explicit women's international clue wins,
     * then a knock-out/qualifier clue, and anything else is a women's club competition.
     * The gender is never inferred from a club or country name.
     */
    private fun womenClassification(normalized: String, country: String?): CompetitionClassification {
        val international = womenInternationalClues.any { normalized.contains(it) }
        val type = when {
            international -> CompetitionType.WOMEN_INTERNATIONAL
            qualifierClues.any { normalized.contains(it) } -> CompetitionType.WOMEN_INTERNATIONAL
            friendlyClues.any { normalized.contains(it) } -> CompetitionType.WOMEN_INTERNATIONAL
            else -> CompetitionType.WOMEN_CLUB_LEAGUE
        }
        return CompetitionClassification(
            type = type,
            gender = Gender.WOMEN,
            source = ClassificationSource.NAME_RULE,
            region = country,
        )
    }

    /** Every catalogue entry, used by the competition picker and the documentation. */
    fun cataloguedCompetitions(): List<Pair<String, CompetitionClassification>> =
        international.map { it.name to classify("catalog", "", it.name) } +
            providerKeys.mapNotNull { entry -> entry.providerKey?.let { it to classify("football-data-org", it, entry.name) } } +
            espnOpenKeys.mapNotNull { entry -> entry.providerKey?.let { it to classify("espn-open", it, entry.name) } }

    fun persianName(providerId: String, providerKey: String, name: String): String? =
        classify(providerId, providerKey, name).persianName

    private const val DEFAULT_KEY_PROVIDER = "football-data-org"

    /** Feeds whose every row is a men's club league. */
    private val clubLeagueOnlyProviders = setOf("football-data-co-uk", "open-matchday")

    private val knockoutClues = listOf("champions league", "europa league", "conference league", "cup", "copa", "coupe", "trophy", "playoff", "play-off")

    private val qualifierClues = listOf("qualification", "qualifier", "qualifiers", "qualifying")

    private val friendlyClues = listOf("friendlies", "friendly")

    private val womenInternationalClues = listOf(
        "world cup", "euro", "nations", "olympic", "cup of nations", "asian cup",
        "gold cup", "copa america", "shebelieves", "algarve cup",
    )

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        val stripped = decomposed.replace(Regex("\\p{Mn}+"), "")
        return stripped.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
