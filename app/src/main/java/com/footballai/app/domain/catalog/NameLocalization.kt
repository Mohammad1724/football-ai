package com.footballai.app.domain.catalog

import java.text.Normalizer
import java.util.Locale

/**
 * Localised naming layer for teams, national teams and competitions.
 *
 * The canonical name always stays the one the provider published: this object only
 * answers "what should the UI show" and "does this row match the user's query". It
 * never renames a team in the database, and a name the dictionary does not know is
 * returned unchanged instead of being transliterated into something that might be
 * wrong.
 *
 * The names themselves live in [TeamNames]; this object only turns a published name
 * into a dictionary key. Sources spell the same team differently ("IR Iran", "Korea
 * Republic", "FC Barcelona", "1. FC Union Berlin"), so a lookup goes through the
 * canonical key of [NationalTeamKeys] and, for clubs, through the small set of words
 * that sources put in front of or behind a club name without changing who it is.
 */
object NameLocalization {

    /** Canonical, comparable form of a name: lower case, no accents, no punctuation. */
    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        val stripped = decomposed.replace(Regex("\\p{Mn}+"), "")
        return stripped.lowercase(Locale.ROOT)
            .replace("ي", "ی")
            .replace("ك", "ک")
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Words a source may put around a club name without changing which club it is.
     * Dropping them is a rewrite of spelling, not of identity: "FC Barcelona" and
     * "Barcelona CF" are the same club, "Barcelona" and "Barcelona B" are not, and the
     * second one therefore keeps its own name.
     */
    private val clubNoise: Set<String> = setOf(
        "fc", "afc", "cf", "sc", "ac", "cd", "as", "ss", "ssc", "cs", "ca", "cp",
        "fk", "sk", "bk", "if", "club", "de", "atletico", "atlético", "1", "2",
    )

    /** Suffixes that mean "the women's team of the same nation", not a different nation. */
    private val womenWords: Set<String> = setOf("women", "womens", "women's", "w", "ladies")

    /** Age-group suffixes, with the Persian wording used on screen. */
    private val ageSuffixes: Map<String, String> = mapOf(
        "u23" to "زیر ۲۳ سال",
        "u21" to "زیر ۲۱ سال",
        "u20" to "زیر ۲۰ سال",
        "u19" to "زیر ۱۹ سال",
        "u17" to "زیر ۱۷ سال",
    )

    private data class Entry(val persian: String, val iso2: String?, val national: Boolean)

    private val nationalByKey: Map<String, TeamNames.NationalTeam> = buildMap {
        for (team in TeamNames.nationalTeams) {
            for (name in listOf(team.english) + team.aliases) {
                val key = NationalTeamKeys.key(name)
                if (key.isNotEmpty()) put(key, team)
            }
        }
    }

    private val clubByKey: Map<String, TeamNames.Club> = buildMap {
        for (club in TeamNames.clubs) {
            for (name in listOf(club.english) + club.aliases) {
                for (variant in variants(name)) {
                    putIfAbsent(variant, club)
                }
            }
        }
    }

    /** Every spelling of a name that can be compared: the name itself, then the same
     *  name with the noise words a source may add stripped off one end at a time. */
    private fun variants(value: String): List<String> {
        val tokens = normalize(value).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        var start = 0
        var end = tokens.size
        out.add(tokens.subList(start, end).joinToString(" "))
        while (start < end - 1 && tokens[start] in clubNoise) {
            start++
            out.add(tokens.subList(start, end).joinToString(" "))
        }
        while (end - 1 > start && tokens[end - 1] in clubNoise) {
            end--
            out.add(tokens.subList(start, end).joinToString(" "))
        }
        return out.toList()
    }

    private fun candidates(name: String, shortName: String?): List<String> =
        listOfNotNull(name, shortName).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun lookupNational(value: String): TeamNames.NationalTeam? =
        nationalByKey[NationalTeamKeys.key(value)]

    /** Resolves a published team name to its Persian name, flag code and identity. */
    private fun resolve(name: String, shortName: String? = null): Entry? {
        val all = candidates(name, shortName)
        for (candidate in all) {
            lookupNational(candidate)?.let {
                return Entry(it.persian, it.iso2.ifBlank { null }, true)
            }
            for (variant in variants(candidate)) {
                clubByKey[variant]?.let { return Entry(it.persian, null, false) }
            }
        }
        // "Spain Women", "Japan U21": the same nation, written with a suffix.
        for (candidate in all) {
            val tokens = normalize(candidate).split(' ').filter { it.isNotBlank() }
            if (tokens.isEmpty()) continue
            var rest = tokens
            val labels = mutableListOf<String>()
            while (rest.isNotEmpty() && rest.last() in womenWords) {
                if ("بانوان" !in labels) labels.add("بانوان")
                rest = rest.dropLast(1)
            }
            while (rest.isNotEmpty()) {
                val ageLabel = ageSuffixes[rest.last()]
                if (ageLabel == null) break
                if (ageLabel !in labels) labels.add(ageLabel)
                rest = rest.dropLast(1)
            }
            if (rest.isEmpty() || labels.isEmpty() || rest.size == tokens.size) continue
            lookupNational(rest.joinToString(" "))?.let { team ->
                val suffix = labels.joinToString("، ")
                return Entry("${team.persian} ($suffix)", team.iso2.ifBlank { null }, true)
            }
        }
        return null
    }

    /** Persian name for a team, or null when the identity is not certain. */
    fun persianTeamName(name: String, shortName: String? = null): String? =
        resolve(name, shortName)?.persian

    /**
     * The two names a Persian screen shows for one team: the Persian one when it is
     * known, plus the published Latin one underneath. A team the dictionary does not
     * know gets null as its second name instead of a guess.
     */
    fun namePair(name: String, languageTag: String, shortName: String? = null): Pair<String, String?> {
        val persian = persianTeamName(name, shortName)
        val persianScreen = languageTag.lowercase(Locale.ROOT).startsWith("fa")
        return when {
            persian == null -> name to null
            persianScreen -> persian to name.takeIf { it != persian }
            else -> name to persian
        }
    }

    /** Persian competition name, delegated to the competition catalogue. */
    fun persianCompetitionName(providerId: String, providerKey: String, name: String): String? =
        CompetitionCatalog.classify(providerId, providerKey, name).persianName

    /** Flag emoji for a national team, or null when the team has no flag. */
    fun flagEmoji(name: String, shortName: String? = null): String? =
        resolve(name, shortName)?.iso2?.let { emojiFor(it) }

    fun isNationalTeamName(name: String, shortName: String? = null): Boolean =
        resolve(name, shortName)?.national == true

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
            persianTeamName(name, shortName)?.let { add(normalize(it)) }
        }
        return haystack.any { it.contains(needle) }
    }

    /** What the UI should render for a team name in the active language. */
    fun displayName(name: String, languageTag: String, shortName: String? = null): String =
        namePair(name, languageTag, shortName).first

    /** Builds a regional-indicator pair, or the tag sequence used by the home nations. */
    private fun emojiFor(iso2: String): String? {
        homeNationFlags[iso2]?.let { return it }
        val code = iso2.substringBefore('-')
        if (code.length != 2 || code.any { !it.isLetter() }) return null
        val base = 0x1F1E6 - 'A'.code
        val first = Character.toChars(base + code[0].uppercaseChar().code)
        val second = Character.toChars(base + code[1].uppercaseChar().code)
        return String(first) + String(second)
    }

    /**
     * England, Scotland and Wales are not countries in ISO-3166, so they have no
     * two-letter flag; they use the emoji tag sequences instead. Northern Ireland has
     * none at all, and falls back to the United Kingdom flag rather than nothing.
     */
    private val homeNationFlags: Map<String, String> = mapOf(
        "GB-ENG" to "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F",
        "GB-SCT" to "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F",
        "GB-WLS" to "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC77\uDB40\uDC6C\uDB40\uDC73\uDB40\uDC7F",
        "GB-NIR" to "\uD83C\uDDEC\uD83C\uDDE7",
    )
}
