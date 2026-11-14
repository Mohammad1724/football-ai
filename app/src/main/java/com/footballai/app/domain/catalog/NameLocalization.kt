package com.footballai.app.domain.catalog

import java.text.Normalizer
import java.util.Locale

/**
 * Localised naming layer for teams, national teams and competitions.
 *
 * The canonical name always stays the one the provider published: this object only
 * answers "what should the UI show" and "does this row match the user's query". It
 * never renames a team in the database, and an unknown name is returned unchanged
 * instead of being transliterated into something that might be wrong.
 *
 * Persian names are translations of well-known entities; the dictionary is
 * deliberately limited to teams and competitions whose identity is unambiguous.
 */
object NameLocalization {

    data class NationalTeam(val english: String, val persian: String, val iso2: String, val aliases: List<String> = emptyList())

    /**
     * National teams with their Persian name and ISO-3166 alpha-2 code, which is also
     * used to render a flag without downloading anything.
     */
    val nationalTeams: List<NationalTeam> = listOf(
        NationalTeam("Iran", "ایران", "IR", listOf("ir iran", "islamic republic of iran")),
        NationalTeam("Japan", "ژاپن", "JP"),
        NationalTeam("South Korea", "کره جنوبی", "KR", listOf("korea republic", "korea")),
        NationalTeam("North Korea", "کره شمالی", "KP", listOf("korea dpr")),
        NationalTeam("Australia", "استرالیا", "AU"),
        NationalTeam("Saudi Arabia", "عربستان سعودی", "SA"),
        NationalTeam("Qatar", "قطر", "QA"),
        NationalTeam("United Arab Emirates", "امارات", "AE", listOf("uae")),
        NationalTeam("Iraq", "عراق", "IQ"),
        NationalTeam("Syria", "سوریه", "SY"),
        NationalTeam("Jordan", "اردن", "JO"),
        NationalTeam("Lebanon", "لبنان", "LB"),
        NationalTeam("Oman", "عمان", "OM"),
        NationalTeam("Bahrain", "بحرین", "BH"),
        NationalTeam("Kuwait", "کویت", "KW"),
        NationalTeam("Uzbekistan", "ازبکستان", "UZ"),
        NationalTeam("Turkmenistan", "ترکمنستان", "TM"),
        NationalTeam("Kyrgyzstan", "قرقیزستان", "KG"),
        NationalTeam("Tajikistan", "تاجیکستان", "TJ"),
        NationalTeam("India", "هند", "IN"),
        NationalTeam("China", "چین", "CN", listOf("china pr")),
        NationalTeam("Thailand", "تایلند", "TH"),
        NationalTeam("Vietnam", "ویتنام", "VN"),
        NationalTeam("Indonesia", "اندونزی", "ID"),
        NationalTeam("Malaysia", "مالزی", "MY"),
        NationalTeam("England", "انگلستان", "GB-ENG"),
        NationalTeam("Scotland", "اسکاتلند", "GB-SCT"),
        NationalTeam("Wales", "ولز", "GB-WLS"),
        NationalTeam("Northern Ireland", "ایرلند شمالی", "GB-NIR"),
        NationalTeam("Ireland", "ایرلند", "IE", listOf("republic of ireland")),
        NationalTeam("France", "فرانسه", "FR"),
        NationalTeam("Germany", "آلمان", "DE"),
        NationalTeam("Spain", "اسپانیا", "ES"),
        NationalTeam("Italy", "ایتالیا", "IT"),
        NationalTeam("Portugal", "پرتغال", "PT"),
        NationalTeam("Netherlands", "هلند", "NL", listOf("holland")),
        NationalTeam("Belgium", "بلژیک", "BE"),
        NationalTeam("Croatia", "کرواسی", "HR"),
        NationalTeam("Serbia", "صربستان", "RS"),
        NationalTeam("Switzerland", "سوئیس", "CH"),
        NationalTeam("Austria", "اتریش", "AT"),
        NationalTeam("Denmark", "دانمارک", "DK"),
        NationalTeam("Sweden", "سوئد", "SE"),
        NationalTeam("Norway", "نروژ", "NO"),
        NationalTeam("Finland", "فنلاند", "FI"),
        NationalTeam("Poland", "لهستان", "PL"),
        NationalTeam("Czechia", "جمهوری چک", "CZ", listOf("czech republic")),
        NationalTeam("Slovakia", "اسلواکی", "SK"),
        NationalTeam("Slovenia", "اسلوونی", "SI"),
        NationalTeam("Hungary", "مجارستان", "HU"),
        NationalTeam("Romania", "رومانی", "RO"),
        NationalTeam("Bulgaria", "بلغارستان", "BG"),
        NationalTeam("Greece", "یونان", "GR"),
        NationalTeam("Turkey", "ترکیه", "TR", listOf("türkiye", "turkiye")),
        NationalTeam("Ukraine", "اوکراین", "UA"),
        NationalTeam("Russia", "روسیه", "RU"),
        NationalTeam("Georgia", "گرجستان", "GE"),
        NationalTeam("Albania", "آلبانی", "AL"),
        NationalTeam("Iceland", "ایسلند", "IS"),
        NationalTeam("Bosnia and Herzegovina", "بوسنی و هرزگوین", "BA"),
        NationalTeam("Morocco", "مراکش", "MA"),
        NationalTeam("Algeria", "الجزایر", "DZ"),
        NationalTeam("Tunisia", "تونس", "TN"),
        NationalTeam("Egypt", "مصر", "EG"),
        NationalTeam("Senegal", "سنگال", "SN"),
        NationalTeam("Nigeria", "نیجریه", "NG"),
        NationalTeam("Ghana", "غنا", "GH"),
        NationalTeam("Cameroon", "کامرون", "CM"),
        NationalTeam("Ivory Coast", "ساحل عاج", "CI", listOf("cote d ivoire", "côte d'ivoire")),
        NationalTeam("South Africa", "آفریقای جنوبی", "ZA"),
        NationalTeam("Brazil", "برزیل", "BR"),
        NationalTeam("Argentina", "آرژانتین", "AR"),
        NationalTeam("Uruguay", "اروگوئه", "UY"),
        NationalTeam("Colombia", "کلمبیا", "CO"),
        NationalTeam("Chile", "شیلی", "CL"),
        NationalTeam("Peru", "پرو", "PE"),
        NationalTeam("Ecuador", "اکوادور", "EC"),
        NationalTeam("Paraguay", "پاراگوئه", "PY"),
        NationalTeam("Bolivia", "بولیوی", "BO"),
        NationalTeam("Venezuela", "ونزوئلا", "VE"),
        NationalTeam("Mexico", "مکزیک", "MX"),
        NationalTeam("United States", "آمریکا", "US", listOf("usa", "united states of america")),
        NationalTeam("Canada", "کانادا", "CA"),
        NationalTeam("Costa Rica", "کاستاریکا", "CR"),
        NationalTeam("Panama", "پاناما", "PA"),
        NationalTeam("Honduras", "هندوراس", "HN"),
        NationalTeam("Jamaica", "جامائیکا", "JM"),
        NationalTeam("New Zealand", "نیوزیلند", "NZ"),
    )

    /** Well-known clubs whose Persian name is stable and widely used. */
    private val clubPersian: Map<String, String> = mapOf(
        "persepolis" to "پرسپولیس",
        "esteghlal" to "استقلال",
        "sepahan" to "سپاهان",
        "tractor" to "تراکتور",
        "tractor sazi" to "تراکتور",
        "foolad" to "فولاد",
        "real madrid" to "رئال مادرید",
        "barcelona" to "بارسلونا",
        "atletico madrid" to "اتلتیکو مادرید",
        "manchester united" to "منچستریونایتد",
        "manchester city" to "منچسترسیتی",
        "liverpool" to "لیورپول",
        "arsenal" to "آرسنال",
        "chelsea" to "چلسی",
        "tottenham hotspur" to "تاتنهام",
        "newcastle united" to "نیوکاسل",
        "aston villa" to "استون ویلا",
        "everton" to "اورتون",
        "bayern munich" to "بایرن مونیخ",
        "borussia dortmund" to "دورتموند",
        "rb leipzig" to "لایپزیش",
        "bayer leverkusen" to "بایر لورکوزن",
        "juventus" to "یوونتوس",
        "ac milan" to "میلان",
        "inter" to "اینتر",
        "internazionale" to "اینتر میلان",
        "napoli" to "ناپولی",
        "as roma" to "رم",
        "lazio" to "لاتزیو",
        "paris saint germain" to "پاری سن ژرمن",
        "marseille" to "مارسی",
        "ajax" to "آژاکس",
        "psv" to "پی‌اس‌وی",
        "feyenoord" to "فاینورد",
        "benfica" to "بنفیکا",
        "porto" to "پورتو",
        "sporting cp" to "اسپورتینگ",
        "galatasaray" to "گالاتاسرای",
        "fenerbahce" to "فنرباغچه",
        "besiktas" to "بشیکتاش",
        "olympiacos" to "المپیاکوس",
        "panathinaikos" to "پاناتینایکوس",
        "flamengo" to "فلامنگو",
        "palmeiras" to "پالمیراس",
        "boca juniors" to "بوکا جونیورز",
        "river plate" to "ریور پلاته",
        "inter miami" to "اینتر میامی",
        "al hilal" to "الهلال",
        "al nassr" to "النصر",
        "al ittihad" to "الاتحاد",
    )

    private val persistedNationalByKey: Map<String, NationalTeam> = nationalTeams.flatMap { team ->
        (listOf(team.english) + team.aliases).map { normalize(it) to team }
    }.toMap()

    /** Persian name for a team, or null when the identity is not certain. */
    fun persianTeamName(name: String, shortName: String? = null): String? {
        val candidates = listOfNotNull(name, shortName).map { normalize(it) }
        for (key in candidates) {
            persistedNationalByKey[key]?.let { return it.persian }
            clubPersian[key]?.let { return it }
        }
        // "FC Barcelona" / "Barcelona CF": try the longest dictionary key contained.
        for (key in candidates) {
            clubPersian.keys.filter { key.contains(it) && it.length >= 5 }
                .maxByOrNull { it.length }
                ?.let { return clubPersian[it] }
        }
        return null
    }

    /** Persian competition name, delegated to the competition catalogue. */
    fun persianCompetitionName(providerId: String, providerKey: String, name: String): String? =
        CompetitionCatalog.classify(providerId, providerKey, name).persianName

    /** Flag emoji for a national team, or null when the team is not a known nation. */
    fun flagEmoji(name: String, shortName: String? = null): String? {
        val candidates = listOfNotNull(name, shortName).map { normalize(it) }
        val team = candidates.firstNotNullOfOrNull { persistedNationalByKey[it] } ?: return null
        return emojiFor(team.iso2)
    }

    fun isNationalTeamName(name: String, shortName: String? = null): Boolean {
        val candidates = listOfNotNull(name, shortName).map { normalize(it) }
        return candidates.any { persistedNationalByKey.containsKey(it) }
    }

    /**
     * Search match across the published English name, the short name and the Persian
     * name. English is always searchable, so a Persian user can still type "Real".
     */
    fun matches(query: String, name: String, shortName: String? = null): Boolean {
        val needle = normalize(query)
        if (needle.isEmpty()) return true
        val haystack = buildList {
            add(normalize(name))
            shortName?.let { add(normalize(it)) }
            persianTeamName(name, shortName)?.let { add(it) }
        }
        return haystack.any { it.contains(needle) }
    }

    /** What the UI should render for a team name in the active language. */
    fun displayName(name: String, languageTag: String, shortName: String? = null): String {
        if (!languageTag.lowercase(Locale.ROOT).startsWith("fa")) return name
        return persianTeamName(name, shortName) ?: name
    }

    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        val stripped = decomposed.replace(Regex("\\p{Mn}+"), "")
        return stripped.lowercase(Locale.ROOT)
            .replace("ي", "ی")
            .replace("ك", "ک")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Builds a two-letter regional indicator pair from an ISO-3166 alpha-2 code. */
    private fun emojiFor(iso2: String): String? {
        val code = iso2.substringBefore('-')
        if (code.length != 2 || code.any { !it.isLetter() }) return null
        val base = 0x1F1E6 - 'A'.code
        val first = Character.toChars(base + code[0].uppercaseChar().code)
        val second = Character.toChars(base + code[1].uppercaseChar().code)
        return String(first) + String(second)
    }
}
